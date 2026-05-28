package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PurgeScriptConfigTest {
    @Test
    fun `webservices purge covers systemd compose and labware workspace resources`() {
        val purge = repoFileText("ops/host-admin/purge-webservices-stack.sh")

        assertTrue(purge.contains("systemctl --user"))
        assertTrue(purge.contains("webservices.target"))
        assertTrue(purge.contains("--print-only"))
        assertTrue(purge.contains("EXPECTED_HOSTNAME"))
        assertTrue(purge.contains("--yes-delete-webservices-stack"))
        assertTrue(purge.contains("print_docker_targets"))
        assertTrue(purge.contains("label=com.docker.compose.project=\$STACK_PROJECT_NAME"))
        assertTrue(purge.contains("\${STACK_PROJECT_NAME}_ name prefix"))
        assertTrue(purge.contains("list_target_volume_names"))
        assertTrue(purge.contains("index(\$0, prefix) == 1"))
        assertTrue(purge.contains("docker network rm"))
        assertTrue(purge.contains("docker volume rm"))
        assertTrue(purge.contains("LABWARE_DOCKER_HOST=\"\${LABWARE_DOCKER_HOST:-unix:///run/docker-labware/docker.sock}\""))
        assertTrue(purge.contains("label=webservices.workspace.id"))
        assertTrue(purge.contains("label=webservices.test.tenant.id"))
        assertTrue(purge.contains("purge_labware_runtime"))
    }

    @Test
    fun `site storage purge matches hardcoded external storage roots`() {
        val purge = repoFileText("ops/host-admin/purge-site-storage-dirs.sh")

        listOf(
            "/mnt/pg_ssd/postgres-ssd",
            "/mnt/raid/docker/nocow/postgres",
            "/mnt/raid/docker/nocow/mariadb",
            "/mnt/labware/vectors/qdrant",
            "/mnt/media/seafile-media",
        ).forEach { path ->
            assertTrue(purge.contains("\"$path\""), "storage purge should include $path")
            assertTrue(purge.contains(path), "storage purge validator should allow $path")
        }
        assertTrue(purge.contains("rm -rf --one-file-system --"))
        assertTrue(purge.contains("--yes-delete-site-storage"))
        assertTrue(purge.contains("EXPECTED_HOSTNAME"))
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
