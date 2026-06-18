package org.webservices.workspaceprovisioner

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory

class WorkspaceProvisionerServiceTest {
    @Test
    fun `create workspace skips host ports that are already unavailable`() {
        val tempDir = createTempDirectory("workspace-provisioner-service")
        val store = WorkspaceStore(tempDir.resolve("workspaces.sqlite"))
        val runtime = mockk<DockerWorkspaceRuntime>(relaxed = true)
        every { runtime.isHostPortAvailable(any()) } returns true
        every { runtime.isHostPortAvailable(47000) } returns false
        every { runtime.isHostPortAvailable(47001) } returns true
        every { runtime.createWorkspace(any(), any()) } answers {
            val record = firstArg<WorkspaceRecord>()
            RuntimeAccessInfo("labware.local", record.sshPort, "agent", "ssh-ed25519 AAAA")
        }
        every { runtime.readProfiles(any()) } returns emptyList()
        val service = WorkspaceProvisionerService(testConfig(tempDir), store, runtime, mockk(relaxed = true), mockk(relaxed = true))

        val created = service.createWorkspace(principal("alice"), CreateWorkspaceRequest(displayName = "build-lab"))

        assertEquals(47001, created.sshPort)
        assertEquals(49000, created.shell.port)
        assertEquals(listOf(47001), store.listAllWorkspaces().map { it.sshPort })
        verify(exactly = 1) { runtime.createWorkspace(match { it.sshPort == 47001 }, any()) }
        verify(exactly = 1) { runtime.createWorkspace(match { it.ttydPort == 49000 }, any()) }
    }

    @Test
    fun `create workspace retries with a new port when docker reports bind collision`() {
        val tempDir = createTempDirectory("workspace-provisioner-retry")
        val store = WorkspaceStore(tempDir.resolve("workspaces.sqlite"))
        val runtime = mockk<DockerWorkspaceRuntime>(relaxed = true)
        every { runtime.isHostPortAvailable(any()) } returns true
        every { runtime.createWorkspace(any(), any()) } answers {
            val record = firstArg<WorkspaceRecord>()
            if (record.sshPort == 47000) {
                throw HostPortUnavailableException(record.sshPort, "workspace SSH port ${record.sshPort} is already allocated on the host")
            }
            RuntimeAccessInfo("labware.local", record.sshPort, "agent", "ssh-ed25519 AAAA")
        }
        every { runtime.deleteWorkspace(match { it.sshPort == 47000 }) } just runs
        every { runtime.readProfiles(any()) } returns emptyList()
        val service = WorkspaceProvisionerService(testConfig(tempDir), store, runtime, mockk(relaxed = true), mockk(relaxed = true))

        val created = service.createWorkspace(principal("alice"), CreateWorkspaceRequest(displayName = "analysis"))

        assertEquals(47001, created.sshPort)
        assertEquals(49001, created.shell.port)
        assertEquals(listOf(47001), store.listAllWorkspaces().map { it.sshPort })
        assertNull(store.listAllWorkspaces().find { it.sshPort == 47000 })
        verify(exactly = 1) { runtime.deleteWorkspace(match { it.sshPort == 47000 }) }
        verify(exactly = 1) { runtime.createWorkspace(match { it.sshPort == 47000 }, any()) }
        verify(exactly = 1) { runtime.createWorkspace(match { it.sshPort == 47001 }, any()) }
        verify(exactly = 1) { runtime.createWorkspace(match { it.ttydPort == 49000 }, any()) }
        verify(exactly = 1) { runtime.createWorkspace(match { it.ttydPort == 49001 }, any()) }
    }

    @Test
    fun `workspace summaries expose ssh notebook and ttyd access links`() {
        val tempDir = createTempDirectory("workspace-provisioner-links")
        val store = WorkspaceStore(tempDir.resolve("workspaces.sqlite"))
        val runtime = mockk<DockerWorkspaceRuntime>(relaxed = true)
        every { runtime.isHostPortAvailable(any()) } returns true
        every { runtime.createWorkspace(any(), any()) } answers {
            val record = firstArg<WorkspaceRecord>()
            RuntimeAccessInfo("labware.local", record.sshPort, "agent", "ssh-ed25519 AAAA")
        }
        every { runtime.notebookUrl(any()) } answers { "https://workspaces.example.test/w/${firstArg<String>()}/notebook/lab" }
        every { runtime.notebookBasePath(any()) } answers { "/w/${firstArg<String>()}/notebook/" }
        every { runtime.ttydUrl(any()) } answers { "https://workspaces.example.test/w/${firstArg<String>()}/shell/" }
        every { runtime.ttydBasePath(any()) } answers { "/w/${firstArg<String>()}/shell" }
        every { runtime.readProfiles(any()) } returns emptyList()
        val service = WorkspaceProvisionerService(testConfig(tempDir), store, runtime, mockk(relaxed = true), mockk(relaxed = true))

        val created = service.createWorkspace(principal("alice"), CreateWorkspaceRequest(displayName = "links"))

        assertEquals("labware.local", created.sshHost)
        assertEquals(47000, created.sshPort)
        assertEquals("agent", created.sshUser)
        assertEquals("https://workspaces.example.test/w/${created.id}/shell/", created.shell.url)
        assertEquals("/w/${created.id}/shell", created.shell.basePath)
        assertEquals("https://workspaces.example.test/w/${created.id}/notebook/lab", created.notebook.url)
        assertEquals("/w/${created.id}/notebook/", created.notebook.basePath)
    }

    @Test
    fun `codex token is written only into the workspace runtime secret file`() {
        val tempDir = createTempDirectory("workspace-provisioner-codex")
        val store = WorkspaceStore(tempDir.resolve("workspaces.sqlite"))
        val runtime = mockk<DockerWorkspaceRuntime>(relaxed = true)
        every { runtime.isHostPortAvailable(any()) } returns true
        every { runtime.createWorkspace(any(), any()) } answers {
            val record = firstArg<WorkspaceRecord>()
            RuntimeAccessInfo("labware.local", record.sshPort, "agent", "ssh-ed25519 AAAA")
        }
        every { runtime.writeCodexToken(any(), any()) } just runs
        every { runtime.clearCodexToken(any()) } just runs
        every { runtime.readProfiles(any()) } returns emptyList()
        val service = WorkspaceProvisionerService(testConfig(tempDir), store, runtime, mockk(relaxed = true), mockk(relaxed = true))
        val created = service.createWorkspace(principal("alice"), CreateWorkspaceRequest(displayName = "codex"))

        val set = service.setCodexToken(principal("alice"), created.id, "sk-test-12345678901234567890")
        val cleared = service.clearCodexToken(principal("alice"), created.id)

        assertTrue(set.configured)
        assertEquals(created.id, set.workspaceId)
        assertEquals(false, cleared.configured)
        verify(exactly = 1) { runtime.writeCodexToken(match { it.id == created.id }, "sk-test-12345678901234567890") }
        verify(exactly = 1) { runtime.clearCodexToken(match { it.id == created.id }) }
    }

    @Test
    fun `ssh certificate access returns host trust material for accessible workspace`() {
        val tempDir = createTempDirectory("workspace-provisioner-ssh")
        val store = WorkspaceStore(tempDir.resolve("workspaces.sqlite"))
        val runtime = mockk<DockerWorkspaceRuntime>(relaxed = true)
        val sshCa = mockk<SmallstepSshCa>(relaxed = true)
        every { runtime.isHostPortAvailable(any()) } returns true
        every { runtime.createWorkspace(any(), any()) } answers {
            val record = firstArg<WorkspaceRecord>()
            RuntimeAccessInfo("labware.local", record.sshPort, "agent", "ssh-ed25519 AAAA")
        }
        every { runtime.fetchHostPublicKey(any()) } returns "ssh-ed25519 host-public"
        every { runtime.readProfiles(any()) } returns emptyList()
        every { sshCa.fingerprint(any()) } returns "SHA256:test"
        every { sshCa.issueUserCertificate(any(), any()) } returns IssuedUserCertificate(
            certificate = "ssh-ed25519-cert-v01@openssh.com cert\n",
            expiresAt = "2026-05-07T12:00:00Z"
        )
        every { sshCa.userCaPublicKey() } returns "ssh-ed25519 user-ca"
        val service = WorkspaceProvisionerService(testConfig(tempDir), store, runtime, sshCa, mockk(relaxed = true))
        val principal = principal("alice")
        val created = service.createWorkspace(principal, CreateWorkspaceRequest(displayName = "ssh"))
        service.addSshKey(principal, AddSshKeyRequest("laptop", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest alice@example.test"))

        val cert = service.issueSshCertificate(principal, created.id, "laptop")

        assertEquals(created.id, cert.workspaceId)
        assertEquals("labware.local", cert.sshHost)
        assertEquals(47000, cert.sshPort)
        assertEquals("agent", cert.sshUser)
        assertTrue(cert.knownHostsEntry.contains("[labware.local]:47000 ssh-ed25519 host-public"))
        assertEquals("ssh-ed25519 user-ca", cert.caPublicKey)
        assertEquals("ssh-ed25519-cert-v01@openssh.com cert\n", cert.certificate)
    }

    @Test
    fun `docker runtime rejects unsafe docker identifiers before shelling out`() {
        val tempDir = createTempDirectory("workspace-provisioner-identifiers")
        val runtime = DockerWorkspaceRuntime(testConfig(tempDir), mockk(relaxed = true))
        val now = Instant.now().toString()
        val record = WorkspaceRecord(
            id = "../bad",
            displayName = "Bad",
            ownerUsername = "alice",
            status = "running",
            containerName = "workspace-good",
            volumeName = "workspace-good-home",
            sshPort = 47000,
            sshUser = "agent",
            notebookContainerName = null,
            notebookPort = null,
            notebookStatus = "stopped",
            ttydPort = null,
            ttydStatus = "stopped",
            createdAt = now,
            updatedAt = now,
            leaseExpiresAt = now,
            notebookLastError = null,
            ttydLastError = null
        )

        assertThrows(IllegalArgumentException::class.java) {
            runtime.startWorkspace(record, emptyList())
        }
    }

    @Test
    fun `search backend base url validation rejects credentials and non-http schemes`() {
        assertEquals("https://opensearch:9200/knowledge", validateSearchServiceBaseUrl("https://opensearch:9200/knowledge/"))
        assertThrows(IllegalArgumentException::class.java) {
            validateSearchServiceBaseUrl("https://user:pass@opensearch:9200/knowledge")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateSearchServiceBaseUrl("file:///etc/passwd")
        }
    }

    private fun principal(username: String) = PrincipalIdentity(
        username = username,
        email = "$username@example.test",
        source = "test"
    )

    private fun testConfig(tempDir: Path): WorkspaceProvisionerConfig {
        tempDir.resolve("step/config").createDirectories()
        tempDir.resolve("step/secrets").createDirectories()
        tempDir.resolve("step/certs").createDirectories()
        return WorkspaceProvisionerConfig(
            port = 8120,
            dataDir = tempDir,
            databasePath = tempDir.resolve("workspaces.sqlite"),
            oidcBaseUrl = "http://keycloak:8080/realms/webservices",
            oidcPublicUrl = "https://keycloak.example.test/realms/webservices",
            publicBaseUrl = "https://workspaces.example.test",
            searchServiceBaseUrl = "https://opensearch:9200/knowledge",
            searchServiceUsername = "admin",
            searchServicePassword = "opensearch-password",
            searchServiceToken = "search-token",
            trustedProxySecret = "test-secret",
            allowedBearerGroups = setOf("admins", "operators", "agents"),
            agentTokenSecret = "test-agent-secret",
            agentTokenTtlSeconds = 86_400L,
            workspaceClientId = "workspace-cli",
            workspaceCliRedirectUri = "http://127.0.0.1:38080/callback",
            runtimePublicHost = "labware.local",
            runtimeHttpBindAddress = "127.0.0.1",
            runtimeSshPortStart = 47000,
            runtimeSshPortEnd = 47005,
            runtimeNotebookPortStart = 48000,
            runtimeNotebookPortEnd = 48005,
            runtimeTtydPortStart = 49000,
            runtimeTtydPortEnd = 49005,
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
}
