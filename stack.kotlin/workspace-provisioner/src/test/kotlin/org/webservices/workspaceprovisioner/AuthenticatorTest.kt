package org.webservices.workspaceprovisioner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthenticatorTest {
    private val baseConfig = WorkspaceProvisionerConfig(
        port = 8120,
        dataDir = Path("/tmp"),
        databasePath = Path("/tmp/workspaces.sqlite"),
        oidcBaseUrl = "http://keycloak:8080/realms/webservices",
        oidcPublicUrl = "https://keycloak.example.test/realms/webservices",
        publicBaseUrl = "https://workspaces.example.test",
        searchServiceBaseUrl = "https://opensearch:9200/knowledge",
        searchServiceUsername = "admin",
        searchServicePassword = "opensearch-password",
        searchServiceToken = "search-token",
        trustedProxySecret = "proxy-secret",
        agentTokenSecret = "test-agent-secret",
        agentTokenTtlSeconds = 86_400L,
        workspaceClientId = "workspace-cli",
        workspaceCliRedirectUri = "http://127.0.0.1:38080/callback",
        runtimePublicHost = "labware.local",
        runtimeHttpBindAddress = "127.0.0.1",
        runtimeSshPortStart = 47000,
        runtimeSshPortEnd = 47999,
        runtimeNotebookPortStart = 48000,
        runtimeNotebookPortEnd = 48999,
        runtimeTtydPortStart = 49000,
        runtimeTtydPortEnd = 49999,
        workspaceImage = "workspace-image",
        workspaceContext = Path("/workspace"),
        notebookImage = "notebook-image",
        notebookContext = Path("/workspace-notebook"),
        workspaceUser = "agent",
        workspaceSshUser = "agent",
        workspaceSshPortInternal = 2222,
        workspaceNotebookPortInternal = 8888,
        workspaceTtydPortInternal = 7681,
        workspaceCertTtl = "12h",
        workspaceLeaseDays = 14,
        stepPath = Path("/tmp/step"),
        caConfigPath = Path("/tmp/step/config/ca.json"),
        caProvisioner = "workspace-provisioner",
        caProvisionerPasswordFile = Path("/tmp/step/secrets/provisioner-password.txt"),
        caUserPublicKeyPath = Path("/tmp/step/certs/ssh_user_ca_key.pub")
    )

    @Test
    fun `forward auth headers require trusted proxy secret`() = runTest {
        val authenticator = Authenticator(baseConfig, noOpHttpClient())

        val withoutSecret = authenticator.authenticate(
            callWithHeaders(
                "Remote-User" to "gerald",
                "Remote-Groups" to "admins,operators",
                "Remote-Email" to "gerald@example.test"
            )
        )
        assertNull(withoutSecret)

        val withSecret = authenticator.authenticate(
            callWithHeaders(
                "Remote-User" to "gerald",
                "Remote-Groups" to "admins,operators",
                "Remote-Email" to "gerald@example.test",
                "X-Trusted-Proxy-Secret" to "proxy-secret"
            )
        )

        requireNotNull(withSecret)
        assertEquals("gerald", withSecret.username)
        assertEquals(listOf("admins", "operators"), withSecret.groups)
        assertEquals("gerald@example.test", withSecret.email)
        assertEquals("forward_auth", withSecret.source)
    }

    @Test
    fun `bearer token auth still works when spoofed remote user header is present`() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("Bearer opaque-token", request.headers[HttpHeaders.Authorization])
                    respond(
                        content = """{"sub":"gerald@example.test","preferred_username":"gerald","groups":["agents"]}""",
                        status = HttpStatusCode.OK,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val authenticator = Authenticator(baseConfig, client)

        val principal = authenticator.authenticate(
            callWithHeaders(
                "Remote-User" to "spoofed",
                HttpHeaders.Authorization to "Bearer opaque-token"
            )
        )

        requireNotNull(principal)
        assertEquals("gerald", principal.username)
        assertEquals(listOf("agents"), principal.groups)
        assertEquals("oidc_bearer", principal.source)
        client.close()
    }

    @Test
    fun `workspace agent token authenticates without OIDC roundtrip`() = runTest {
        val now = Instant.now()
        val codec = WorkspaceAgentTokenCodec(
            sharedSecret = baseConfig.agentTokenSecret,
            ttlSeconds = baseConfig.agentTokenTtlSeconds
        )
        val token = codec.issue(
            WorkspaceRecord(
                id = "ws-123",
                displayName = "test",
                ownerUsername = "gerald",
                status = "running",
                containerName = "workspace-test",
                volumeName = "workspace-volume",
                sshPort = 47000,
                sshUser = "agent",
                notebookContainerName = "workspace-notebook",
                notebookPort = 48000,
                notebookStatus = "stopped",
                ttydPort = 49000,
                ttydStatus = "stopped",
                createdAt = now.minusSeconds(3_600).toString(),
                updatedAt = now.minusSeconds(1_800).toString(),
                leaseExpiresAt = now.plusSeconds(3_600).toString(),
                ttydLastError = null
            ),
            now = now
        )
        val authenticator = Authenticator(baseConfig, noOpHttpClient())

        val principal = authenticator.authenticate(
            callWithHeaders(HttpHeaders.Authorization to "Bearer ${token.value}")
        )

        requireNotNull(principal)
        assertEquals("gerald", principal.username)
        assertEquals("workspace_agent_token", principal.source)
        assertEquals("workspace_agent", principal.subjectKind)
        assertEquals("ws-123", principal.workspaceId)
        assertEquals(listOf("knowledge:search", "knowledge:document"), principal.scopes)
    }

    private fun noOpHttpClient(): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(
                    content = "",
                    status = HttpStatusCode.Unauthorized
                )
            }
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun callWithHeaders(vararg headers: Pair<String, String>): ApplicationCall {
        val applicationRequest = mockk<ApplicationRequest>()
        every {
            applicationRequest.headers
        } returns Headers.build {
            headers.forEach { (name, value) -> append(name, value) }
        }

        return mockk {
            every { request } returns applicationRequest
        }
    }
}
