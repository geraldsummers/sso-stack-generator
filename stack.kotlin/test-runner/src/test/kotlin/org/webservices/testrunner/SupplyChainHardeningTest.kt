package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SupplyChainHardeningTest {

    @Test
    fun `high risk image references are immutable and watchtower cannot update by default`() {
        val vaultwarden = repoFileText("stack.compose/vaultwarden.yml")
        val volumeInit = repoFileText("global.settings/volume-init.yml")
        val watchtower = repoFileText("stack.compose/watchtower.yml")
        val caddy = repoFileText("stack.compose/caddy.yml")
        val jellyfin = repoFileText("stack.config/jellyfin/Dockerfile")
        val donetick = repoFileText("stack.compose/donetick.yml")
        val workspaceProvisioner = repoFileText("stack.containers/workspace-provisioner/Dockerfile")
        val keycloak = repoFileText("stack.containers/keycloak/Dockerfile")
        val keycloakAuthGateway = repoFileText("stack.compose/keycloak-auth-gateway.yml")
        val keycloakListener = repoFileText("stack.kotlin/keycloak-onboarding-listener/build.gradle.kts")
        val combined = listOf(vaultwarden, volumeInit, watchtower, caddy, jellyfin, donetick, workspaceProvisioner, keycloak, keycloakAuthGateway).joinToString("\n")

        assertFalse(combined.contains(":latest"))
        assertTrue(vaultwarden.contains("image: vaultwarden/server@sha256:"))
        assertTrue(volumeInit.contains("image: alpine@sha256:"))
        assertTrue(watchtower.contains("image: containrrr/watchtower@sha256:"))
        assertTrue(caddy.contains("image: caddy:2.11.3@sha256:"))
        assertTrue(jellyfin.contains("FROM jellyfin/jellyfin@sha256:"))
        assertTrue(donetick.contains("image: donetick/donetick:v0.1.75@sha256:"))
        assertTrue(workspaceProvisioner.contains("FROM smallstep/step-ca:0.30.2@sha256:"))
        assertTrue(workspaceProvisioner.contains("sha256sum -c -"))
        assertTrue(keycloak.contains("FROM quay.io/keycloak/keycloak:26.6.2@sha256:"))
        assertTrue(keycloakAuthGateway.contains("image: quay.io/keycloak/keycloak:26.6.2@sha256:"))
        assertTrue(keycloakListener.contains("org.keycloak:keycloak-server-spi:26.6.2"))

        assertTrue(watchtower.contains("WATCHTOWER_LABEL_ENABLE: true"))
        assertTrue(watchtower.contains("WATCHTOWER_MONITOR_ONLY: \${WATCHTOWER_MONITOR_ONLY:-true}"))
        assertTrue(watchtower.contains("WATCHTOWER_SCOPE: webservices"))
    }

    @Test
    fun `release packaging rejects symlinks outside the package source root`() {
        val script = repoRoot().resolve("scripts/deploy/package_release.sh")
        val workDir = Files.createTempDirectory("webservices-package-release-test-")
        try {
            Files.writeString(workDir.resolve("safe.txt"), "ok\n")
            Files.createSymbolicLink(workDir.resolve("safe-link"), Path.of("safe.txt"))

            val safeResult = runCommand(workDir, script.toString(), workDir.resolve("safe.tar").toString(), "safe-link")
            assertTrue(safeResult.exitCode == 0, safeResult.stderr)

            Files.createSymbolicLink(workDir.resolve("leak"), Path.of("/etc/passwd"))
            val unsafeResult = runCommand(workDir, script.toString(), workDir.resolve("unsafe.tar").toString(), "leak")

            assertNotEquals(0, unsafeResult.exitCode)
            assertTrue(unsafeResult.stderr.contains("unsafe symlink in release input"), unsafeResult.stderr)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `release packaging rejects absolute and parent traversal input paths`() {
        val script = repoRoot().resolve("scripts/deploy/package_release.sh")
        val workDir = Files.createTempDirectory("webservices-package-release-path-test-")
        try {
            Files.writeString(workDir.resolve("safe.txt"), "ok\n")
            Files.writeString(workDir.resolve("outside.txt"), "nope\n")

            val absoluteResult = runCommand(
                workDir,
                script.toString(),
                workDir.resolve("absolute.tar").toString(),
                workDir.resolve("safe.txt").toString(),
            )
            assertNotEquals(0, absoluteResult.exitCode)
            assertTrue(absoluteResult.stderr.contains("release input path must be relative"), absoluteResult.stderr)

            val parentTraversalResult = runCommand(
                workDir,
                script.toString(),
                workDir.resolve("parent.tar").toString(),
                "../outside.txt",
            )
            assertNotEquals(0, parentTraversalResult.exitCode)
            assertTrue(parentTraversalResult.stderr.contains("must not traverse parent directories"), parentTraversalResult.stderr)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `site manifest member resolution rejects symlink targets outside the manifest directory`() {
        val manifestLib = repoRoot().resolve("scripts/lib/site-manifest.sh")
        val workspace = Files.createTempDirectory("webservices-manifest-hardening-test-")
        try {
            val manifestDir = Files.createDirectories(workspace.resolve("manifest"))
            val outsideDir = Files.createDirectories(workspace.resolve("outside"))
            Files.writeString(manifestDir.resolve("stack.config.yaml"), "site: test\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.writeString(outsideDir.resolve("secret.sops.json"), "{\"secret\":\"value\"}\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.createSymbolicLink(manifestDir.resolve("secret-link.sops.json"), Path.of("../outside/secret.sops.json"))
            Files.writeString(
                manifestDir.resolve("manifest.json"),
                """
                {
                  "site": "latium",
                  "stackConfig": "stack.config.yaml",
                  "secretStore": "secret-link.sops.json"
                }
                """.trimIndent() + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )

            val result = runCommand(
                workspace,
                "bash",
                "-ceu",
                """
                source "${manifestLib.toAbsolutePath()}"
                resolve_site_manifest_secret_store_path "${manifestDir.resolve("manifest.json").toAbsolutePath()}"
                """.trimIndent(),
            )

            assertNotEquals(0, result.exitCode)
            assertTrue(result.stderr.contains("escapes the manifest directory"), result.stderr)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `systemd renderer rejects graph names that could escape output paths or inject directives`() {
        val renderer = repoRoot().resolve("scripts/deploy/render-systemd-user.py")
        val workspace = Files.createTempDirectory("webservices-render-hardening-test-")
        try {
            val composeConfig = workspace.resolve("compose.json")
            val graph = workspace.resolve("graph.json")
            val baseNetworks = workspace.resolve("base-networks.json")
            val outputDir = workspace.resolve("output")

            Files.writeString(
                composeConfig,
                """
                {
                  "services": {
                    "svc": {
                      "image": "alpine:3.20",
                      "restart": "no"
                    }
                  },
                  "networks": {},
                  "volumes": {}
                }
                """.trimIndent() + "\n",
            )
            Files.writeString(baseNetworks, "{ \"networks\": {} }\n")
            Files.writeString(
                graph,
                """
                {
                  "unitPrefix": "webservices",
                  "defaultTarget": {
                    "name": "webservices.target",
                    "description": "Platform target",
                    "install": true,
                    "includeUnitsFromNonOnDemandDomains": true
                  },
                  "lifecycleDomains": [
                    {
                      "name": "../escape",
                      "services": ["svc"]
                    }
                  ]
                }
                """.trimIndent() + "\n",
            )

            val result = runCommand(
                workspace,
                "python3",
                renderer.toString(),
                "--local-bundle-root", workspace.toString(),
                "--local-deploy-root", workspace.toString(),
                "--deploy-root-template", "/tmp/webservices",
                "--unit-root-template", "/tmp/webservices/build/systemd-user",
                "--runtime-env-file-template", "/tmp/webservices/runtime/stack.env",
                "--compose-config-json", composeConfig.toString(),
                "--graph-path", graph.toString(),
                "--output-dir", outputDir.toString(),
                "--compose-project-name", "webservices",
                "--systemd-notify-bin", "/usr/bin/systemd-notify",
                "--compose-helper", "/tmp/webservices/build/scripts/lib/systemd-compose-unit.sh",
                "--infra-helper", "/tmp/webservices/build/scripts/lib/systemd-docker-infra.sh",
                "--diagnostics-helper", "/tmp/webservices/build/scripts/lib/systemd-diagnostics.sh",
                "--base-networks-json", baseNetworks.toString(),
            )

            assertNotEquals(0, result.exitCode)
            assertTrue(result.stderr.contains("graph.lifecycleDomains[0].name"), result.stderr)
            assertTrue(result.stderr.contains("must match"), result.stderr)

            Files.writeString(
                graph,
                """
                {
                  "unitPrefix": "webservices",
                  "defaultTarget": {
                    "name": "webservices.target",
                    "description": "Platform target",
                    "install": true,
                    "includeUnitsFromNonOnDemandDomains": true
                  },
                  "auxiliaryTargets": [
                    {
                      "name": "webservices-extra.target\nWants=evil.service",
                      "description": "Injected target",
                      "services": ["svc"]
                    }
                  ]
                }
                """.trimIndent() + "\n",
            )

            val injectionResult = runCommand(
                workspace,
                "python3",
                renderer.toString(),
                "--local-bundle-root", workspace.toString(),
                "--local-deploy-root", workspace.toString(),
                "--deploy-root-template", "/tmp/webservices",
                "--unit-root-template", "/tmp/webservices/build/systemd-user",
                "--runtime-env-file-template", "/tmp/webservices/runtime/stack.env",
                "--compose-config-json", composeConfig.toString(),
                "--graph-path", graph.toString(),
                "--output-dir", outputDir.toString(),
                "--compose-project-name", "webservices",
                "--systemd-notify-bin", "/usr/bin/systemd-notify",
                "--compose-helper", "/tmp/webservices/build/scripts/lib/systemd-compose-unit.sh",
                "--infra-helper", "/tmp/webservices/build/scripts/lib/systemd-docker-infra.sh",
                "--diagnostics-helper", "/tmp/webservices/build/scripts/lib/systemd-diagnostics.sh",
                "--base-networks-json", baseNetworks.toString(),
            )
            assertNotEquals(0, injectionResult.exitCode)
            assertTrue(injectionResult.stderr.contains("graph.auxiliaryTargets[0].name"), injectionResult.stderr)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `systemd user unit install copies rendered units instead of linking bundle files`() {
        val text = repoFileText("scripts/deploy/install-systemd-user-units.sh")

        assertFalse(text.contains("ln -s"))
        assertTrue(text.contains("[ ! -L \"\$UNIT_DIR\" ]"))
        assertTrue(text.contains("[ ! -L \"\$rendered_path\" ]"))
        assertTrue(text.contains("install -m 0644 \"\$rendered_path\" \"\$tmp_unit\""))
        assertTrue(text.contains("mv -f \"\$tmp_unit\" \"\$USER_UNIT_DIR/\$rendered_name\""))
    }

    private fun repoFileText(relativePath: String): String =
        Files.readString(repoRoot().resolve(relativePath))

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

    private fun runCommand(workingDirectory: Path, vararg command: String): CommandResult {
        val process = ProcessBuilder(*command)
            .directory(workingDirectory.toFile())
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return CommandResult(exitCode, stdout, stderr)
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
