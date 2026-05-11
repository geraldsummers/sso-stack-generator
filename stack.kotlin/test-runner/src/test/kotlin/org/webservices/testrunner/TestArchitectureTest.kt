package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestArchitectureTest {

    @Test
    fun `main exposes the repo owned Kotlin suites only`() {
        val text = Files.readString(repoRoot().resolve("stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/Main.kt"))
        val catalogText = Files.readString(repoRoot().resolve("stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/SuiteCatalog.kt"))
        val suitesText = Files.readString(repoRoot().resolve("stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/WebServicesTests.kt"))

        assertTrue(text.contains("SuiteCatalog.resolve"))
        assertTrue(catalogText.contains("stackContractTests()"))
        assertTrue(catalogText.contains("agentLabTests()"))
        assertTrue(suitesText.contains("suspend fun TestRunner.stackLiveIngestionTests()"))
        assertFalse(catalogText.contains("tradingTests()"))
        assertFalse(catalogText.contains("web3WalletTests()"))
    }

    @Test
    fun `run tests script uses the static test runner service`() {
        val text = Files.readString(repoRoot().resolve("stack.containers/test-runner/run-tests.sh"))
        val composeText = Files.readString(repoRoot().resolve("stack.compose/test-runners.yml"))

        assertTrue(text.contains("TEST_RUNNER_SERVICE=\"test-runner\""))
        assertTrue(text.contains("DEFAULT_KT_SUITE=\"\${DEFAULT_KT_SUITE:-stack-contract}\""))
        assertTrue(text.contains("kt-contract"))
        assertTrue(text.contains("kt-live-ingestion"))
        assertTrue(text.contains("kt-agent-lab"))
        assertTrue(composeText.contains("TEST_RESULTS_HOST_DIR:?TEST_RESULTS_HOST_DIR must be set by run-tests.sh"))
        assertTrue(composeText.contains("TEST_RUNNER_RUNTIME_HOST_DIR:?TEST_RUNNER_RUNTIME_HOST_DIR must be set by run-tests.sh"))
        assertTrue(composeText.contains("TEST_RUNNER_HOST_XDG_RUNTIME_DIR:?TEST_RUNNER_HOST_XDG_RUNTIME_DIR must be set by run-tests.sh"))
        assertTrue(composeText.contains("TEST_RUNNER_RUNTIME_ROOT: /runtime"))
        assertTrue(composeText.contains("XDG_RUNTIME_DIR: /host-user-runtime"))
        assertTrue(composeText.contains("DBUS_SESSION_BUS_ADDRESS: unix:path=/host-user-runtime/bus"))
        assertTrue(composeText.contains("CADDY_URL: http://caddy:80"))
        assertFalse(composeText.contains("AUTHELIA_API_URL"))
        assertTrue(composeText.contains("IDENTITY_PROVIDER: \${IDENTITY_PROVIDER:-keycloak}"))
        assertTrue(composeText.contains("KEYCLOAK_INTERNAL_URL: \${KEYCLOAK_INTERNAL_URL:-http://keycloak:8080}"))
        assertTrue(composeText.contains("KEYCLOAK_ADMIN_PASSWORD: \${KEYCLOAK_ADMIN_PASSWORD}"))
        assertTrue(composeText.contains("WORKSPACE_PROXY_AUTH_SECRET: \${MODEL_CONTEXT_PROXY_AUTH_SECRET}"))
        assertTrue(composeText.contains("MODEL_CONTEXT_OIDC_REDIRECT_URI: \${MODEL_CONTEXT_OIDC_REDIRECT_URI:-http://test-runner-managed/callback}"))
        assertTrue(text.contains("resolve_test_runner_runtime_host_dir"))
        assertTrue(text.contains("resolve_test_runner_systemd_runtime_host_dir"))
        assertFalse(composeText.contains("\${TEST_RESULTS_HOST_DIR:-./test-results}"))
        assertFalse(text.contains("suite_service()"))
        assertFalse(text.contains("test-playwright-e2e"))
    }

    @Test
    fun `docker daemon access is split between read only and controller proxies`() {
        val repoRoot = repoRoot()
        val proxyText = Files.readString(repoRoot.resolve("stack.compose/docker-proxy.yml"))
        val networksText = Files.readString(repoRoot.resolve("global.settings/networks.yml"))
        val forgejoRunnerConfig = Files.readString(repoRoot.resolve("stack.config/forgejo-runner/config.yaml"))
        val forgejoRunnerCompose = Files.readString(repoRoot.resolve("stack.compose/forgejo-runner.yml"))
        val testRunnerCompose = Files.readString(repoRoot.resolve("stack.compose/test-runners.yml"))
        val agentWorkspaceSuites = Files.readString(repoRoot.resolve("stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AgentWorkspaceSuites.kt"))

        assertTrue(networksText.contains("docker-controller:\n    driver: bridge\n    internal: true"))
        assertTrue(proxyText.contains("docker-socket-proxy:\n    image: tecnativa/docker-socket-proxy"))
        assertTrue(proxyText.contains("docker-socket-controller-proxy:\n    image: tecnativa/docker-socket-proxy"))
        assertTrue(proxyText.contains("docker-vm-controller-proxy:\n    image: tecnativa/docker-socket-proxy"))
        assertTrue(proxyText.contains("POST: 0          # Disallow create/start/stop/exec"))
        assertTrue(proxyText.contains("DELETE: 0        # Disallow removals"))
        assertTrue(proxyText.contains("EXEC: 0          # Disable container exec"))
        assertTrue(forgejoRunnerCompose.contains("DOCKER_HOST: tcp://docker-vm-controller-proxy:2375"))
        assertTrue(forgejoRunnerConfig.contains("network: \"bridge\""))
        assertTrue(testRunnerCompose.contains("DOCKER_HOST: tcp://docker-socket-controller-proxy:2375"))
        assertTrue(testRunnerCompose.contains("ISOLATED_DOCKER_VM_DOCKER_HOST: tcp://docker-vm-controller-proxy:2375"))
        assertFalse(agentWorkspaceSuites.contains("/var/run/docker.sock:/var/run/docker.sock"))
    }

    @Test
    fun `shipped deploy script is bundle local and renders runtime in user tmpfs`() {
        val text = Files.readString(repoRoot().resolve("scripts/deploy.sh"))
        val buildText = Files.readString(repoRoot().resolve("build.sh"))
        val runtimeStateText = Files.readString(repoRoot().resolve("scripts/lib/runtime-state.sh"))
        val verifyText = Files.readString(repoRoot().resolve("scripts/verify.sh"))

        assertTrue(runtimeStateText.contains("webservices-runtime"))
        assertTrue(text.contains("site_manifest_path"))
        assertTrue(text.contains("runtime/stack.env"))
        assertTrue(text.contains("run_compose_from_bundle"))
        assertTrue(text.contains("run_model_prep_jobs"))
        assertTrue(buildText.contains("render-systemd-user.sh"))
        assertTrue(text.contains("install-systemd-user-units.sh"))
        assertTrue(text.contains("missing pre-rendered systemd user units"))
        assertTrue(text.contains("reconcile_target"))
        assertTrue(text.contains("wait_for_target_reconcile"))
        assertTrue(text.contains("aux_action=\"start\""))
        assertTrue(text.contains("user_systemd_list_matching_jobs_raw"))
        assertTrue(text.contains("restart_post_reconcile_units"))
        assertTrue(text.contains("webservices-keycloak-configure.service"))
        assertTrue(text.contains("webservices-keycloak-auth-gateway.service"))
        assertTrue(verifyText.contains("default_test_results_host_dir"))
        assertFalse(verifyText.contains("\$DEPLOY_ROOT/test-results"))
        assertFalse(text.contains("webservices-next"))
        assertFalse(text.contains("webservices-releases"))
        assertFalse(text.contains("ln -sfnT"))
        assertFalse(text.contains("render-systemd-user.sh"))
        assertFalse(text.contains("post-reconcile unit inventory"))
        assertFalse(text.contains("siteConfigRoot"))
    }

    @Test
    fun `scripts directory only exposes the current bundled command surface`() {
        val topLevelFiles = Files.list(repoRoot().resolve("scripts")).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }

        assertEquals(listOf("build-artifact.sh", "deploy.sh", "verify.sh"), topLevelFiles)
    }

    @Test
    fun `repo root only exposes build as public local deployment command`() {
        val repoRoot = repoRoot()

        assertTrue(Files.exists(repoRoot.resolve("build.sh")))
        assertFalse(Files.exists(repoRoot.resolve("sync.sh")))
        assertFalse(Files.exists(repoRoot.resolve("deploy.sh")))
        assertFalse(Files.exists(repoRoot.resolve("render.sh")))
        assertFalse(Files.exists(repoRoot.resolve("sync-dist.sh")))
        assertFalse(Files.exists(repoRoot.resolve("test.sh")))
        assertFalse(Files.exists(repoRoot.resolve("wait-ready.sh")))
    }

    @Test
    fun `build requires explicit site manifest and no repo site-config link exists`() {
        val repoRoot = repoRoot()
        val buildText = Files.readString(repoRoot.resolve("build.sh"))
        val manifestLib = repoRoot.resolve("scripts/lib/site-manifest.sh")

        assertTrue(buildText.contains("--manifest <path-to-manifest.json>"))
        assertTrue(buildText.contains("site_manifest_path"))
        assertTrue(buildText.contains("mkdir -p \"\$DIST_DIR/build\""))
        assertTrue(buildText.contains("validate_generated_compose"))
        assertFalse(buildText.contains("TEST_RESULTS_HOST_DIR=\"\${TEST_RESULTS_HOST_DIR:-\$SCRIPT_DIR/test-results}\""))
        assertTrue(Files.exists(manifestLib))
        assertTrue(repoRoot.resolve("scripts/lib/site-config.sh").notExists())
        assertTrue(repoRoot.resolve("site-config").notExists())
        assertFalse(buildText.contains("--site <site>"))
        assertFalse(buildText.contains("--url <public-site-url>"))
    }

    @Test
    fun `test runner image uses the stable playwright uid for host bind mounts`() {
        val dockerfileText = Files.readString(repoRoot().resolve("stack.containers/test-runner/Dockerfile"))
        val entrypointText = Files.readString(repoRoot().resolve("stack.containers/test-runner/container-entrypoint.sh"))

        assertTrue(dockerfileText.contains("usermod -aG docker pwuser"))
        assertTrue(dockerfileText.contains("USER pwuser"))
        assertTrue(dockerfileText.contains("COPY stack.containers/test-runner/fixtures/aider-runtime /app/stack.containers/test-runner/fixtures/aider-runtime"))
        assertFalse(dockerfileText.contains("COPY stack.containers/test-runner/fixtures/codex-runtime /app/stack.containers/test-runner/fixtures/codex-runtime"))
        assertFalse(dockerfileText.contains("testing_container_user"))
        assertTrue(entrypointText.contains("TEST_USER=\"pwuser\""))
    }

    @Test
    fun `managed runner auth defaults to keycloak without authelia api wiring`() {
        val testRunnerCompose = Files.readString(repoRoot().resolve("stack.compose/test-runners.yml"))
        val authGatewayCompose = Files.readString(repoRoot().resolve("stack.compose/keycloak-auth-gateway.yml"))
        val realmTemplate = Files.readString(repoRoot().resolve("stack.config/keycloak/realm/webservices-realm.json.template"))

        assertTrue(testRunnerCompose.contains("IDENTITY_PROVIDER: \${IDENTITY_PROVIDER:-keycloak}"))
        assertTrue(testRunnerCompose.contains("KEYCLOAK_INTERNAL_URL: \${KEYCLOAK_INTERNAL_URL:-http://keycloak:8080}"))
        assertTrue(authGatewayCompose.contains("OAUTH2_PROXY_OIDC_ISSUER_URL: https://keycloak.\${DOMAIN}/realms/webservices"))
        assertTrue(realmTemplate.contains("http://test-runner/callback"))
        assertTrue(realmTemplate.contains("http://test-runner-managed/callback"))
        assertFalse(testRunnerCompose.contains("AUTHELIA_API_URL"))
    }

    @Test
    fun `wait ready resolves bundled common helper and run time stays in runtime dir`() {
        val repoRoot = repoRoot()
        val waitReadyText = Files.readString(repoRoot.resolve("scripts/lib/wait-ready.sh"))
        val deployText = Files.readString(repoRoot.resolve("scripts/deploy.sh"))
        val composeLibText = Files.readString(repoRoot.resolve("scripts/lib/compose.sh"))

        assertTrue(waitReadyText.contains("LIB_DIR"))
        assertTrue(waitReadyText.contains("source \"\$LIB_DIR/common.sh\""))
        assertTrue(waitReadyText.contains("created_service_blockers"))
        assertTrue(waitReadyText.contains("service_is_completion_dependency_job"))
        assertTrue(waitReadyText.contains("service_completed_successfully"))
        assertTrue(waitReadyText.contains("awaiting "))
        assertTrue(composeLibText.contains("validate_generated_compose"))
        assertTrue(composeLibText.contains("config --quiet --no-interpolate"))
        assertFalse(waitReadyText.contains("webservices-next"))
        assertTrue(deployText.contains("ensure_runtime_links"))
        assertTrue(deployText.contains("runtime/stack.env"))
    }

    @Test
    fun `MatrixRTC services are app services and no Jitsi services are present`() {
        val graphText = Files.readString(repoRoot().resolve("stack.systemd/graph.json"))
        val appsTarget = Regex(
            """"name":\s*"webservices-apps\.target".*?"services":\s*\[(.*?)\]""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(graphText)?.groupValues?.get(1)
            ?: error("missing webservices-apps.target services")

        assertTrue(appsTarget.contains("\"livekit\""))
        assertTrue(appsTarget.contains("\"matrix-rtc-auth\""))
        assertFalse(graphText.contains("\"jitsi\""))
        assertFalse(graphText.contains("\"jicofo\""))
        assertFalse(graphText.contains("\"jvb\""))
        assertFalse(graphText.contains("\"prosody\""))
        assertFalse(Regex(""""excludedServices":\s*\[[^\]]*"livekit"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(graphText))
        assertFalse(Regex(""""excludedServices":\s*\[[^\]]*"matrix-rtc-auth"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(graphText))
        assertFalse(Regex(""""onDemandServices":\s*\[[^\]]*"livekit"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(graphText))
        assertFalse(Regex(""""onDemandServices":\s*\[[^\]]*"matrix-rtc-auth"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(graphText))
    }

    @Test
    fun `compose startup gates reserve health checks for stateful prerequisites and init jobs`() {
        val repoRoot = repoRoot()
        val embedding = Files.readString(repoRoot.resolve("stack.compose/embedding.yml"))
        val pipeline = Files.readString(repoRoot.resolve("stack.compose/pipeline.yml"))
        val jupyterhub = Files.readString(repoRoot.resolve("stack.compose/jupyterhub.yml"))
        val workspaceProvisioner = Files.readString(repoRoot.resolve("stack.compose/workspace-provisioner.yml"))
        val searchService = Files.readString(repoRoot.resolve("stack.compose/search-service.yml"))
        val bookstack = Files.readString(repoRoot.resolve("stack.compose/bookstack.yml"))
        val caddyfile = Files.readString(repoRoot.resolve("stack.config/caddy/Caddyfile"))

        assertTrue(embedding.contains("image: webservices/embedding-bge:local-build"))
        assertTrue(embedding.contains("BAAI/bge-m3"))
        assertTrue(embedding.contains("--dtype float32"))
        assertTrue(embedding.contains("start_period: 300s"))
        assertTrue(searchService.contains("embedding-gpu:\n        condition: service_started"))
        assertTrue(searchService.contains("EMBEDDING_SERVICE_URL: http://embedding-gpu:8080"))
        assertFalse(searchService.contains("inference-gateway"))
        assertTrue(jupyterhub.contains("DOCKER_NETWORK_NAME: webservices_ai"))
        assertFalse(workspaceProvisioner.contains("authelia"))
        assertTrue(workspaceProvisioner.contains("docker-vm-controller-proxy:\n        condition: service_started"))
        assertTrue(workspaceProvisioner.contains("search-service:\n        condition: service_started"))
        assertTrue(bookstack.contains("API_REQUESTS_PER_MIN: \${BOOKSTACK_API_REQUESTS_PER_MIN:-1200}"))
        assertTrue(caddyfile.contains("workspaces.{\$DOMAIN}"))
        assertTrue(caddyfile.contains("reverse_proxy workspace-provisioner:8120"))
        assertTrue(caddyfile.contains("models.{\$DOMAIN}"))
        assertTrue(caddyfile.contains("reverse_proxy embedding-gpu:8080"))
        assertFalse(caddyfile.contains("open-webui.{\$DOMAIN}"))
        assertFalse(caddyfile.contains("reverse_proxy inference-gateway:8111"))
        assertFalse(searchService.contains("embedding-gpu:\n        condition: service_healthy"))
        assertFalse(pipeline.contains("inference-gateway"))
    }

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("MODULE.bazel"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
