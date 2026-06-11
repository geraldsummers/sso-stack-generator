package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatGptConnectorConfigTest {
    @Test
    fun `connector is included in deployable app stack with explicit dependencies and secrets`() {
        val settings = repoFileText("settings.gradle.kts")
        val compose = repoFileText("stack.compose/chatgpt-connector.yml")
        val graph = repoFileText("stack.systemd/graph.json")
        val deploy = repoFileText("scripts/deploy.sh")
        val keycloakRealm = repoFileText("stack.config/keycloak/realm/webservices-realm.json.template")
        val keycloakConfigure = repoFileText("stack.config/keycloak/configure-runtime.sh")

        assertTrue(settings.contains("include(\":chatgpt-connector\")"))
        assertTrue(settings.contains("stack.kotlin/chatgpt-connector"))

        assertTrue(compose.contains("image: webservices/chatgpt-connector:local-build"))
        assertTrue(compose.contains("dockerfile: ./stack.containers/chatgpt-connector/Dockerfile"))
        assertTrue(compose.contains("chatgpt_connector_data:/data"))
        assertTrue(compose.contains("CHATGPT_CONNECTOR_TRUSTED_PROXY_SECRET: \${CHATGPT_CONNECTOR_TRUSTED_PROXY_SECRET:?CHATGPT_CONNECTOR_TRUSTED_PROXY_SECRET is required}"))
        assertTrue(compose.contains("CHATGPT_CONNECTOR_KEYCLOAK_ADMIN_PASSWORD: \${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD is required}"))
        assertTrue(compose.contains("keycloak:"))
        assertTrue(compose.contains("opensearch:"))
        assertTrue(compose.contains("workspace-provisioner:"))
        assertTrue(compose.contains("http://127.0.0.1:8130/health"))

        assertTrue(graph.contains("\"chatgpt-connector\""))
        assertTrue(appsTargetBlock(graph).contains("\"chatgpt-connector\""))
        assertFalse(Regex(""""onDemandServices":\s*\[[^\]]*"chatgpt-connector"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(graph))
        assertFalse(Regex(""""excludedServices":\s*\[[^\]]*"chatgpt-connector"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(graph))
        assertTrue(keycloakRealm.contains("{ \"name\": \"agents\" }"))
        assertTrue(keycloakConfigure.contains("ensure_group \"agents\""))
        assertTrue(
            deploy.contains("webservices-chatgpt-connector.service"),
            "Deploy should reload the connector when its image or bundled static UI changes"
        )
    }

    @Test
    fun `connector route keeps mcp bearer endpoint outside keycloak redirect while protecting ui`() {
        val caddyCompose = repoFileText("stack.compose/caddy.yml")
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val block = siteBlock(caddyfile, "chatgpt-connector.{\$DOMAIN}")

        assertTrue(caddyCompose.contains("chatgpt-connector.\${DOMAIN}"))
        assertTrue(block.contains("@mcp path /mcp"))
        assertTrue(block.contains("reverse_proxy @mcp chatgpt-connector:8130"))
        assertTrue(block.contains("import keycloak_auth chatgpt_connector"))
        assertTrue(block.contains("reverse_proxy chatgpt-connector:8130"))
        assertTrue(block.contains("header_up X-Trusted-Proxy-Secret {\$CHATGPT_CONNECTOR_TRUSTED_PROXY_SECRET}"))
        assertTrue(
            block.indexOf("reverse_proxy @mcp chatgpt-connector:8130") < block.indexOf("import keycloak_auth chatgpt_connector"),
            "/mcp must reach the connector before Caddy forward-auth redirects browser-oriented routes"
        )
    }

    @Test
    fun `connector is registered in portal route catalog and visual coverage`() {
        val contracts = repoFileText("stack.config/service-contracts.json")
        val routeCatalog = repoFileText("stack.containers/test-runner/playwright-tests/utils/route-catalog.ts")
        val caddyHosts = repoFileText("stack.containers/test-runner/fixtures/caddy-hosts.txt")
        val visualRunner = repoFileText("stack.containers/test-runner/playwright-tests/scripts/run-visual-suite.sh")

        assertTrue(contracts.contains("\"chatgpt-connector\""))
        assertTrue(contracts.contains("\"hrefHost\": \"chatgpt-connector\""))
        assertTrue(contracts.contains("\"description\": \"Managed ChatGPT connector accounts and MCP endpoint status.\""))

        assertTrue(routeCatalog.contains("host: 'chatgpt-connector'"))
        assertTrue(routeCatalog.contains("fileStem: 'chatgpt-connector-authenticated'"))
        assertTrue(routeCatalog.contains("ownership: { route: true, smoke: true, visual: true, deep: true }"))
        assertTrue(caddyHosts.lines().any { it == "chatgpt-connector" })
        assertTrue(visualRunner.contains("chatgpt-connector"))
    }

    @Test
    fun `agent docs ingestion is wired for connector searchable corpus`() {
        val pipeline = repoFileText("stack.compose/pipeline.yml")
        val dag = repoFileText("stack.config/airflow/dags/knowledge_ingestion_dags.py")
        val runner = repoFileText("stack.containers/ingestion-runner/ingestion_runner.py")

        assertTrue(pipeline.contains("AGENT_DOCS_PATH: /configs/agent-docs"))
        assertTrue(pipeline.contains("./configs/agent-docs:/configs/agent-docs:ro"))
        assertTrue(dag.contains("\"agent_docs\""))
        assertTrue(runner.contains("\"agent_docs\": \"agent_docs\""))
        assertTrue(runner.contains("local_markdown_documents(source, os.getenv(\"AGENT_DOCS_PATH\""))
    }

    private fun appsTargetBlock(graph: String): String {
        val marker = "\"name\": \"webservices-apps.target\""
        val start = graph.indexOf(marker)
        require(start >= 0) { "Missing webservices-apps.target in graph" }
        val nextTarget = graph.indexOf("\n    {", start + marker.length)
        return if (nextTarget > start) graph.substring(start, nextTarget) else graph.substring(start)
    }

    private fun siteBlock(caddyfile: String, siteLabel: String): String {
        val start = caddyfile.indexOf(siteLabel)
        require(start >= 0) { "Missing Caddy site block $siteLabel" }
        val blockOpen = caddyfile.indexOf("{", start + siteLabel.length)
        require(blockOpen >= 0) { "Missing Caddy site block open brace for $siteLabel" }
        var depth = 0
        var inBlock = false
        for (index in blockOpen until caddyfile.length) {
            when (caddyfile[index]) {
                '{' -> {
                    depth += 1
                    inBlock = true
                }
                '}' -> {
                    depth -= 1
                    if (inBlock && depth == 0) return caddyfile.substring(start, index + 1)
                }
            }
        }
        error("Unterminated Caddy site block $siteLabel")
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
