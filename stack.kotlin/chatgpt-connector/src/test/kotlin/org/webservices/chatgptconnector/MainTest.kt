package org.webservices.chatgptconnector

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `root page serves usable account and token management ui`() = testApplication {
        val tempDir = createTempDirectory("chatgpt-connector-ui")
        val store = ConnectorStore(tempDir.resolve("db.sqlite"))
        val httpClient = searchMockClient()
        val config = testConfig(tempDir)

        application {
            configureServer(
                config = config,
                store = store,
                auth = Authenticator(config, httpClient),
                keycloakAdmin = stubKeycloakAdmin(),
                httpClient = httpClient
            )
        }

        val response = client.get("/")
        val html = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(html.contains("Create Agent Account"))
        assertTrue(html.contains("Mint Token"))
        assertTrue(html.contains("Accounts and Tokens"))
        assertTrue(html.contains("Audit Events"))
        assertTrue(html.contains("api('/api/agent-accounts'"))
        assertTrue(html.contains("api(`/api/agent-accounts/"))

        httpClient.close()
        store.close()
    }

    @Test
    fun `api routes trust only forwarded identity and enforce token ownership`() = testApplication {
        val tempDir = createTempDirectory("chatgpt-connector-api")
        val store = ConnectorStore(tempDir.resolve("db.sqlite"))
        val httpClient = searchMockClient()
        val config = testConfig(tempDir)

        application {
            configureServer(
                config = config,
                store = store,
                auth = Authenticator(config, httpClient),
                keycloakAdmin = stubKeycloakAdmin(),
                httpClient = httpClient
            )
        }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val createdAccount = client.post("/api/agent-accounts") {
            asUser("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateAccountRequest(displayName = "Alice Agent", scopes = listOf("search", "fetch")))
        }
        assertEquals(HttpStatusCode.Created, createdAccount.status)
        val account = json.decodeFromString(AgentAccountDto.serializer(), createdAccount.bodyAsText())
        assertEquals("alice", account.ownerUsername)
        assertEquals("agent-${account.id}", account.keycloakUserId)

        val mintedToken = client.post("/api/agent-accounts/${account.id}/tokens") {
            asUser("alice")
            contentType(ContentType.Application.Json)
            setBody(MintTokenRequest(scopes = listOf("search"), ttlSeconds = 3600))
        }
        assertEquals(HttpStatusCode.Created, mintedToken.status)
        val minted = json.decodeFromString(MintedTokenResponse.serializer(), mintedToken.bodyAsText())

        val bobRead = client.get("/api/tokens/${minted.tokenId}") {
            asUser("bob")
        }
        assertEquals(HttpStatusCode.Forbidden, bobRead.status)

        val bobRevoke = client.post("/api/tokens/${minted.tokenId}/revoke") {
            asUser("bob")
        }
        assertEquals(HttpStatusCode.Forbidden, bobRevoke.status)
        assertNull(store.tokenById(minted.tokenId)?.revokedAt)

        val aliceRevoke = client.post("/api/tokens/${minted.tokenId}/revoke") {
            asUser("alice")
        }
        assertEquals(HttpStatusCode.OK, aliceRevoke.status)
        assertNotNull(store.tokenById(minted.tokenId)?.revokedAt)

        val untrustedForwardHeaders = client.get("/api/me") {
            header("Remote-User", "mallory")
            header("Remote-Groups", "users")
        }
        assertEquals(HttpStatusCode.Unauthorized, untrustedForwardHeaders.status)

        httpClient.close()
        store.close()
    }

    @Test
    fun `mcp endpoint requires connector bearer token and applies token scopes`() = testApplication {
        val tempDir = createTempDirectory("chatgpt-connector-mcp")
        val store = ConnectorStore(tempDir.resolve("db.sqlite"))
        val httpClient = searchMockClient()
        val config = testConfig(tempDir)
        val account = store.createAccount("alice", "Alice Agent", listOf("search", "fetch"))
        val searchToken = store.mintToken(account.id, listOf("search"), 3600, "alice")

        application {
            configureServer(
                config = config,
                store = store,
                auth = Authenticator(config, httpClient),
                keycloakAdmin = stubKeycloakAdmin(),
                httpClient = httpClient
            )
        }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val missingBearer = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, missingBearer.status)

        val toolsList = client.post("/mcp") {
            header(HttpHeaders.Authorization, "Bearer ${searchToken.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
        }
        assertEquals(HttpStatusCode.OK, toolsList.status)
        val toolsPayload = json.parseToJsonElement(toolsList.bodyAsText()).jsonObject
        assertNotNull(toolsPayload["result"])
        assertNotNull(store.tokenById(searchToken.tokenId)?.lastUsedAt)

        val deniedFetch = client.post("/mcp") {
            header(HttpHeaders.Authorization, "Bearer ${searchToken.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"fetch","arguments":{"id":"doc-1"}}}""")
        }
        assertEquals(HttpStatusCode.OK, deniedFetch.status)
        assertTrue(deniedFetch.bodyAsText().contains("scope denied for tool fetch"))

        val allowedSearch = client.post("/mcp") {
            header(HttpHeaders.Authorization, "Bearer ${searchToken.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search","arguments":{"query":"rbac"}}}""")
        }
        assertEquals(HttpStatusCode.OK, allowedSearch.status)
        assertTrue(allowedSearch.bodyAsText().contains("agent-doc"))

        httpClient.close()
        store.close()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.asUser(username: String) {
        header("Remote-User", username)
        header("Remote-Email", "$username@example.test")
        header("Remote-Groups", "users")
        header("X-Trusted-Proxy-Secret", "test-secret")
    }

    private fun testConfig(dataDir: java.nio.file.Path): ConnectorConfig = ConnectorConfig(
        port = 8130,
        dataDir = dataDir,
        databasePath = dataDir.resolve("db.sqlite"),
        oidcBaseUrl = "http://keycloak.test/realms/webservices",
        trustedProxySecret = "test-secret",
        searchServiceBaseUrl = "https://opensearch.test/knowledge",
        searchServiceUsername = "admin",
        searchServicePassword = "opensearch-password",
        keycloakRealm = "webservices",
        keycloakAdminClientId = "admin-cli",
        keycloakAdminClientSecret = "",
        keycloakAdminUsername = "admin",
        keycloakAdminPassword = "password",
        keycloakBaseUrl = "http://keycloak.test",
        keycloakAgentUserPrefix = "gpt_agent"
    )

    private fun searchMockClient(): HttpClient {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/knowledge/_search" -> respond(
                    """{"hits":{"total":{"value":1},"hits":[{"_id":"agent-doc","_score":1.0,"_source":{"id":"agent-doc","title":"Agent Docs","text":"RBAC search","metadata":{}}}]}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "/protocol/openid-connect/userinfo" -> respond(
                    """{"sub":"oidc-user","preferred_username":"oidc-user","email":"oidc@example.test","groups":["users"]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> respond(
                    """{"status":"ok"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
    }

    private fun stubKeycloakAdmin(): KeycloakAdmin = object : KeycloakAdmin {
        override suspend fun createAgentUser(ownerUsername: String, accountId: String, scopes: List<String>): Pair<String, String> =
            "agent-$accountId" to "${ownerUsername}_gpt_agent_${accountId.take(8)}"

        override suspend fun disableUser(userId: String) = Unit
    }
}
