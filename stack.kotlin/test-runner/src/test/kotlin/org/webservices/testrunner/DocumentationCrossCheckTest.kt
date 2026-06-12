package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentationCrossCheckTest {
    @Test
    fun `identity cutover doc matches restored keycloak backed services`() {
        val doc = repoFileText("docs/keycloak-identity-cutover.md")
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val keycloakConfigure = repoFileText("stack.config/keycloak/configure-runtime.sh")

        assertFalse(doc.contains("SOGo | Retired"), "SOGo is restored in stack.compose/sogo.yml and must not be documented as retired")
        assertTrue(doc.contains("SOGo | Restored as Keycloak-backed groupware"))
        assertTrue(doc.contains("Jellyfin | App SSO with Keycloak group authorization"))
        assertTrue(doc.contains("Donetick | Keycloak edge-auth and app OAuth2 client wiring"))
        assertTrue(doc.contains("Vaultwarden | Keycloak SSO entry path derives email"))
        assertTrue(doc.contains("ERPNext | Keycloak edge-auth and app OAuth2 client wiring"))
        assertTrue(doc.contains("Disposable Workspaces | Keycloak edge-auth to the dispatcher"))

        assertTrue(caddyfile.contains("sogo.{\$DOMAIN}"))
        assertTrue(caddyfile.contains("Jellyfin password login is disabled; use Keycloak SSO"))
        assertTrue(caddyfile.contains("import keycloak_group_allow donetick users|operators|admins"))
        assertTrue(caddyfile.contains("import keycloak_auth erpnext"))
        assertTrue(keycloakConfigure.contains("ensure_confidential_client \"sogo\""))
        assertTrue(keycloakConfigure.contains("ensure_confidential_client \"jellyfin\""))
        assertTrue(keycloakConfigure.contains("ensure_confidential_client \"donetick\""))
        assertTrue(keycloakConfigure.contains("ensure_confidential_client \"erpnext\""))
        assertTrue(keycloakConfigure.contains("ensure_confidential_client \"vaultwarden\""))
    }

    @Test
    fun `test command docs match granular runner surface`() {
        val readme = repoFileText("README.md")
        val buildDoc = repoFileText("docs/build-system.md")
        val testingDoc = repoFileText("docs/testing.md")
        val runner = repoFileText("stack.containers/test-runner/run-tests.sh")

        listOf("list", "plan [target]", "changed", "kt-tests [suite]", "kt-plan [suite]", "kt-one <id> [suite]", "source-unit", "all").forEach { token ->
            assertTrue(readme.contains(token), "README should document $token")
            assertTrue(buildDoc.contains(token), "build-system doc should document $token")
            assertTrue(testingDoc.contains(token), "testing doc should document $token")
        }
        assertTrue(testingDoc.contains("./run-tests.sh all"))
        assertTrue(testingDoc.contains("./run-tests.sh changed"))
        assertTrue(runner.contains("kt-tests [suite]"))
        assertTrue(runner.contains("kt-plan [suite]"))
        assertTrue(runner.contains("kt-one <id> [suite]"))
        assertTrue(runner.contains("source-unit"))
        assertTrue(runner.contains("all               Run every registered test/check"))
    }

    @Test
    fun `workspace docs match dispatcher notebook ttyd ssh and codex token endpoints`() {
        val workspaceDoc = repoFileText("stack.containers/workspace-provisioner/README.md")
        val codexDoc = repoFileText("stack.containers/agent-workspace/codex/README.md")
        val main = repoFileText("stack.kotlin/workspace-provisioner/src/main/kotlin/org/webservices/workspaceprovisioner/Main.kt")
        val runtime = repoFileText("stack.kotlin/workspace-provisioner/src/main/kotlin/org/webservices/workspaceprovisioner/DockerWorkspaceRuntime.kt")
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")

        listOf("/shell/auth", "/notebook/auth", "/ssh-cert", "/codex-token").forEach { endpoint ->
            assertTrue(workspaceDoc.contains(endpoint), "workspace README should document $endpoint")
            assertTrue(main.contains(endpoint.trimStart('/').substringBefore('/')), "workspace API should include $endpoint")
        }
        assertTrue(workspaceDoc.contains("X-Workspace-Ttyd-Port"))
        assertTrue(workspaceDoc.contains("X-Workspace-Notebook-Port"))
        assertTrue(workspaceDoc.contains("write-only runtime secrets"))
        assertTrue(caddyfile.contains("copy_headers X-Workspace-Ttyd-Port X-Workspace-Ttyd-Base-Path X-Workspace-Health"))
        assertTrue(caddyfile.contains("copy_headers X-Workspace-Notebook-Port X-Workspace-Notebook-Base-Path"))
        assertTrue(runtime.contains(".config/webservices/codex.env"))
        assertTrue(runtime.contains("chmod 0600"))
        assertTrue(codexDoc.contains("/workspace-home/.config/webservices/codex.env"))
        assertTrue(codexDoc.contains("OPENAI_API_KEY"))
    }

    @Test
    fun `architecture and operations docs match procedural docs and purge implementation`() {
        val readme = repoFileText("README.md")
        val architecture = repoFileText("stack.config/stack-knowledge/02-webservices-stack-architecture.md")
        val minimalDeploy = repoFileText("docs/minimal-build-deploy-system.md")
        val troubleshooting = repoFileText("stack.config/stack-knowledge/06-troubleshooting-guide.md")
        val purge = repoFileText("ops/host-admin/purge-webservices-stack.sh")
        val proceduralPublisher = repoFileText("stack.config/bookstack/publish-procedural-docs.php")

        assertTrue(architecture.contains("Procedural documentation is generated at runtime"))
        assertTrue(architecture.contains("Disposable Workspaces use a dispatcher model"))
        assertTrue(readme.contains("Start with [docs/service-standard.md]"))
        assertTrue(proceduralPublisher.contains("URL Index"))
        assertTrue(proceduralPublisher.contains("API Index"))

        assertTrue(minimalDeploy.contains("ops/host-admin/"))
        assertTrue(minimalDeploy.contains("--skip-labware-runtime"))
        assertTrue(troubleshooting.contains("ops/host-admin/purge-webservices-stack.sh --print-only"))
        assertTrue(purge.contains("PURGE_LABWARE_RUNTIME=1"))
        assertTrue(purge.contains("webservices.workspace.id"))
        assertTrue(purge.contains("webservices.test.tenant.id"))
    }

    private fun repoFileText(relativePath: String): String =
        Files.readString(repoRoot().resolve(relativePath))

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        while (!Files.exists(current.resolve("BUILD.bazel"))) {
            current = current.parent ?: error("repo root not found")
        }
        return current
    }
}
