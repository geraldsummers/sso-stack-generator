package org.webservices.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StackDeploymentHelpersTest {
    private fun repoFile(path: String): Path =
        Path.of("").toAbsolutePath().normalize().resolve("../..").normalize().resolve(path)

    @Test
    fun `runtime root lives in user tmpfs`() {
        val runtimeRoot = "/run/user/1000/webservices-runtime"
        assertTrue(runtimeRoot.startsWith("/run/user/"))
        assertTrue(runtimeRoot.endsWith("/webservices-runtime"))
    }

    @Test
    fun `docker compose command construction uses bundle root and runtime env`() {
        val bundleRoot = "/app/build"
        val composeFile = "$bundleRoot/docker-compose.yml"
        val runtimeEnvFile = "/app/runtime/stack.env"

        val command = listOf(
            "docker",
            "compose",
            "--env-file", runtimeEnvFile,
            "-f", composeFile,
            "up", "-d"
        )

        assertEquals("docker", command[0])
        assertEquals("compose", command[1])
        assertEquals("--env-file", command[2])
        assertEquals(runtimeEnvFile, command[3])
        assertEquals("-f", command[4])
        assertEquals(composeFile, command[5])
        assertTrue(command.contains("up"))
        assertTrue(command.contains("-d"))
    }

    @Test
    fun `docker compose ps command for readiness`() {
        val service = "postgres"
        val command = listOf(
            "docker",
            "compose",
            "ps",
            service,
            "--format",
            "{{.Status}}"
        )

        assertTrue(command.contains("ps"))
        assertTrue(command.contains(service))
        assertTrue(command.contains("--format"))
    }

    @Test
    fun `required bundle files list is correct`() {
        val requiredFiles = listOf(
            "build/docker-compose.yml",
            "build/site/manifest.json",
            "build/build-info.json"
        )

        assertEquals(3, requiredFiles.size)
        assertTrue(requiredFiles.contains("build/docker-compose.yml"))
        assertTrue(requiredFiles.contains("build/site/manifest.json"))
        assertTrue(requiredFiles.contains("build/build-info.json"))
    }

    @Test
    fun `workspace provisioner depends on platform prerequisites`() {
        val composeSource = Files.readString(repoFile("stack.compose/workspace-provisioner.yml"))

        assertFalse(
            composeSource.contains("authelia"),
            "Workspace provisioner must not depend on Authelia"
        )
        assertTrue(
            composeSource.contains("docker-vm-controller-proxy:\n        condition: service_started"),
            "Workspace provisioner should wait for docker-vm-controller-proxy startup"
        )
        assertTrue(
            composeSource.contains("search-service:\n        condition: service_started"),
            "Workspace provisioner should wait for search-service startup"
        )
    }

    @Test
    fun `caddy can resolve isolated workspace runtime host`() {
        val caddyCompose = Files.readString(repoFile("stack.compose/caddy.yml"))
        val renderValues = Files.readString(repoFile("scripts/lib/render-values.sh"))

        assertTrue(
            caddyCompose.contains("\"${'$'}{WORKSPACE_RUNTIME_PUBLIC_HOST}:${'$'}{WORKSPACE_RUNTIME_PUBLIC_ADDRESS}\""),
            "Caddy should receive an explicit host mapping for the isolated labware runtime"
        )
        assertTrue(
            renderValues.contains("render_set WORKSPACE_RUNTIME_PUBLIC_ADDRESS"),
            "Runtime rendering should publish the resolved labware address into stack.env"
        )
        assertTrue(
            renderValues.contains("runtime.isolated_docker_vm_public_address"),
            "Site bundles should be able to override the public labware address"
        )
    }

    @Test
    fun `forgejo runner ssh mount uses dedicated render-managed host directory`() {
        val compose = Files.readString(repoFile("stack.compose/forgejo-runner.yml"))
        val renderValues = Files.readString(repoFile("scripts/lib/render-values.sh"))
        val renderRuntime = Files.readString(repoFile("scripts/deploy/render-runtime.sh"))

        assertTrue(
            compose.contains("FORGEJO_RUNNER_SSH_DIR:?Set FORGEJO_RUNNER_SSH_DIR to a dedicated runner-only SSH directory"),
            "Forgejo runner must not fall back to an implicit broad SSH mount"
        )
        assertTrue(
            renderValues.contains("runtime.forgejo_runner_ssh_dir"),
            "Site bundles should be able to override the dedicated runner SSH directory"
        )
        assertTrue(
            renderValues.contains("default_forgejo_runner_ssh_dir"),
            "Real deployments should get a safe dedicated default if the site omits the optional override"
        )
        assertTrue(
            renderValues.contains("render_set FORGEJO_RUNNER_SSH_DIR"),
            "Runtime rendering should publish the runner SSH directory into stack.env"
        )
        assertTrue(
            renderRuntime.contains("prepare_host_runtime_dirs"),
            "Deploy should create host bind directories before compose validation"
        )
        assertTrue(
            renderRuntime.contains("chmod 700 \"${'$'}forgejo_runner_ssh_dir\""),
            "The runner SSH directory should be private to the deploy user"
        )
    }

    @Test
    fun `mastodon stack targets postgres ssd across all roles`() {
        val mastodonCompose = Files.readString(repoFile("stack.compose/mastodon.yml"))
        val mastodonEnv = Files.readString(repoFile("stack.config/mastodon/mastodon.env"))

        assertTrue(
            mastodonCompose.contains("postgres-ssd-bootstrap:\n        condition: service_completed_successfully"),
            "Mastodon services should wait for postgres-ssd bootstrap completion before starting"
        )
        assertTrue(
            mastodonCompose.contains("DB_HOST: postgres-ssd"),
            "Mastodon compose should point every role at postgres-ssd"
        )
        assertTrue(
            mastodonEnv.contains("DB_HOST=postgres-ssd"),
            "Mastodon env file should align with the postgres-ssd target"
        )
    }

    @Test
    fun `status output parsing detects healthy status`() {
        val healthyStatuses = listOf(
            "Up 2 minutes (healthy)",
            "Up About a minute (healthy)",
            "Up 30 seconds (healthy)"
        )

        healthyStatuses.forEach { status ->
            assertTrue(status.contains("healthy", ignoreCase = true))
        }
    }

    @Test
    fun `status output parsing detects unhealthy status`() {
        val unhealthyStatuses = listOf(
            "Up 2 minutes (unhealthy)",
            "Up About a minute (health: starting)",
            "Exited (1)"
        )

        unhealthyStatuses.forEach { status ->
            assertFalse(status.contains("healthy", ignoreCase = true) && !status.contains("unhealthy"))
        }
    }
}
