package org.webservices.workspaceprovisioner

import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory

class MainTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `health and ready endpoints return typed json payloads`() = testApplication {
        val tempDir = createTempDirectory("workspace-provisioner-test")
        val store = WorkspaceStore(tempDir.resolve("workspaces.sqlite"))
        val service = WorkspaceProvisionerService(
            config = testConfig(tempDir),
            store = store,
            runtime = mockk(relaxed = true),
            sshCa = mockk(relaxed = true),
            knowledgeGateway = mockk(relaxed = true)
        )

        application {
            configureServer(
                config = testConfig(tempDir),
                service = service,
                authenticator = mockk(relaxed = true)
            )
        }

        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("ok", health.headers["X-Workspace-Health"])
        assertTrue(health.bodyAsText().contains("\"status\""))
        assertTrue(health.bodyAsText().contains("\"ok\""))

        val ready = client.get("/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals("ready", ready.headers["X-Workspace-Health"])
        val payload = json.decodeFromString(ReadyResponse.serializer(), ready.bodyAsText())
        assertEquals("ok", payload.status)
        assertEquals(0, payload.workspaces)

        store.close()
    }

    @Test
    fun `shell auth endpoint returns ttyd headers`() = testApplication {
        val tempDir = createTempDirectory("workspace-provisioner-test")
        val service = mockk<WorkspaceProvisionerService>(relaxed = true)
        val authenticator = mockk<Authenticator>(relaxed = true)
        coEvery { authenticator.authenticate(any()) } returns PrincipalIdentity(
            username = "gerald",
            email = "gerald@example.test",
            groups = listOf("admins"),
            source = "forward_auth"
        )
        every { service.ttydAccess(any(), "ws-123") } returns TtydSessionView(
            status = "running",
            url = "https://workspaces.example.test/w/ws-123/shell/",
            basePath = "/w/ws-123/shell",
            port = 49000
        )

        application {
            configureServer(
                config = testConfig(tempDir),
                service = service,
                authenticator = authenticator
            )
        }

        val response = client.get("/api/workspaces/ws-123/shell/auth")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("running", response.headers["X-Workspace-Health"])
        assertEquals("49000", response.headers["X-Workspace-Ttyd-Port"])
        assertEquals("/w/ws-123/shell", response.headers["X-Workspace-Ttyd-Base-Path"])
    }

    @Test
    fun `notebook auth endpoint returns notebook headers`() = testApplication {
        val tempDir = createTempDirectory("workspace-provisioner-test")
        val service = mockk<WorkspaceProvisionerService>(relaxed = true)
        val authenticator = mockk<Authenticator>(relaxed = true)
        coEvery { authenticator.authenticate(any()) } returns PrincipalIdentity(
            username = "gerald",
            email = "gerald@example.test",
            groups = listOf("users"),
            source = "forward_auth"
        )
        every { service.notebookAccess(any(), "ws-123") } returns NotebookSessionView(
            status = "running",
            url = "https://workspaces.example.test/w/ws-123/notebook/lab",
            basePath = "/w/ws-123/notebook/",
            port = 48000
        )

        application {
            configureServer(
                config = testConfig(tempDir),
                service = service,
                authenticator = authenticator
            )
        }

        val response = client.get("/api/workspaces/ws-123/notebook/auth")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("running", response.headers["X-Workspace-Health"])
        assertEquals("48000", response.headers["X-Workspace-Notebook-Port"])
        assertEquals("/w/ws-123/notebook/", response.headers["X-Workspace-Notebook-Base-Path"])
    }

    @Test
    fun `codex token endpoints are write only authenticated controls`() = testApplication {
        val tempDir = createTempDirectory("workspace-provisioner-test")
        val service = mockk<WorkspaceProvisionerService>(relaxed = true)
        val authenticator = mockk<Authenticator>(relaxed = true)
        val principal = PrincipalIdentity(
            username = "gerald",
            email = "gerald@example.test",
            groups = listOf("users"),
            source = "forward_auth"
        )
        coEvery { authenticator.authenticate(any()) } returns principal
        every { service.setCodexToken(any(), "ws-123", "sk-test-12345678901234567890") } returns WorkspaceSecretStatusResponse("ws-123", true)
        every { service.clearCodexToken(any(), "ws-123") } returns WorkspaceSecretStatusResponse("ws-123", false)

        application {
            configureServer(
                config = testConfig(tempDir),
                service = service,
                authenticator = authenticator
            )
        }

        val setResponse = client.post("/api/workspaces/ws-123/codex-token") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"sk-test-12345678901234567890"}""")
        }
        assertEquals(HttpStatusCode.OK, setResponse.status)
        assertTrue(setResponse.bodyAsText().contains("\"configured\": true"))

        val clearResponse = client.delete("/api/workspaces/ws-123/codex-token")
        assertEquals(HttpStatusCode.OK, clearResponse.status)
        assertTrue(clearResponse.bodyAsText().contains("\"configured\": false"))
        verify(exactly = 1) { service.setCodexToken(principal, "ws-123", "sk-test-12345678901234567890") }
        verify(exactly = 1) { service.clearCodexToken(principal, "ws-123") }
    }

    private fun testConfig(tempDir: java.nio.file.Path) = WorkspaceProvisionerConfig(
        port = 8120,
        dataDir = tempDir,
        databasePath = tempDir.resolve("workspaces.sqlite"),
        oidcBaseUrl = "http://keycloak:8080/realms/webservices",
        oidcPublicUrl = "https://keycloak.example.test/realms/webservices",
        publicBaseUrl = "https://workspaces.example.test",
        searchServiceBaseUrl = "http://search-service:8098",
        searchServiceToken = "search-token",
        trustedProxySecret = "test-secret",
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
        workspaceImage = "webservices/agent-workspace:workspace-build",
        workspaceContext = tempDir,
        notebookImage = "webservices/agent-workspace-notebook:workspace-build",
        notebookContext = tempDir,
        workspaceUser = "agent",
        workspaceSshUser = "agent",
        workspaceSshPortInternal = 2222,
        workspaceNotebookPortInternal = 8888,
        workspaceTtydPortInternal = 7681,
        workspaceCertTtl = "12h",
        workspaceLeaseDays = 14,
        stepPath = tempDir.resolve("step"),
        caConfigPath = tempDir.resolve("step/config/ca.json"),
        caProvisioner = "workspace-provisioner",
        caProvisionerPasswordFile = tempDir.resolve("step/secrets/provisioner-password.txt"),
        caUserPublicKeyPath = tempDir.resolve("step/certs/ssh_user_ca_key.pub")
    )
}
