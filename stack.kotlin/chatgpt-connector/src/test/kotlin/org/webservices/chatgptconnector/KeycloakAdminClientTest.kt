package org.webservices.chatgptconnector

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeycloakAdminClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `missing admin credentials fail closed`() = runTest {
        val admin = KeycloakAdminClient(
            testConfig().copy(keycloakAdminUsername = "", keycloakAdminPassword = ""),
            mockClient { error("Keycloak should not be called without credentials") }
        )

        assertFailsWith<IllegalArgumentException> {
            admin.createAgentUser("alice", "account-1", listOf("search"))
        }
    }

    @Test
    fun `create user checks keycloak responses and returns looked up id`() = runTest {
        val responses = ArrayDeque(
            listOf(
                MockResponse(HttpStatusCode.OK, """{"access_token":"admin-token"}"""),
                MockResponse(HttpStatusCode.Created, ""),
                MockResponse(HttpStatusCode.OK, """[{"id":"user-1","username":"alice_gpt_agent_account-"}]""")
            )
        )
        val client = mockClient { responses.removeFirst() }
        val admin = KeycloakAdminClient(testConfig(), client)

        val created = admin.createAgentUser("alice", "account-12345678", listOf("search"))

        assertEquals("user-1" to "alice_gpt_agent_account-", created)
        client.close()
    }

    @Test
    fun `create user surfaces keycloak create failures`() = runTest {
        val responses = ArrayDeque(
            listOf(
                MockResponse(HttpStatusCode.OK, """{"access_token":"admin-token"}"""),
                MockResponse(HttpStatusCode.InternalServerError, """{"error":"boom"}""")
            )
        )
        val admin = KeycloakAdminClient(testConfig(), mockClient { responses.removeFirst() })

        assertFailsWith<IllegalStateException> {
            admin.createAgentUser("alice", "account-12345678", listOf("search"))
        }
    }

    @Test
    fun `disable user checks keycloak response`() = runTest {
        val responses = ArrayDeque(
            listOf(
                MockResponse(HttpStatusCode.OK, """{"access_token":"admin-token"}"""),
                MockResponse(HttpStatusCode.Forbidden, """{"error":"denied"}""")
            )
        )
        val admin = KeycloakAdminClient(testConfig(), mockClient { responses.removeFirst() })

        assertFailsWith<IllegalStateException> {
            admin.disableUser("user-1")
        }
    }

    private data class MockResponse(val status: HttpStatusCode, val body: String)

    private fun mockClient(next: () -> MockResponse): HttpClient {
        val engine = MockEngine {
            val response = next()
            respond(
                response.body,
                response.status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
    }

    private fun testConfig(): ConnectorConfig = ConnectorConfig(
        port = 8130,
        dataDir = Path("/tmp/chatgpt-connector-test"),
        databasePath = Path("/tmp/chatgpt-connector-test/db.sqlite"),
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
}
