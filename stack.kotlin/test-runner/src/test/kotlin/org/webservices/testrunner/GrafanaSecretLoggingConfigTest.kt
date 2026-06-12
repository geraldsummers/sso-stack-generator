package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class GrafanaSecretLoggingConfigTest {
    @Test
    fun `grafana moves database password out of environment before startup`() {
        val entrypoint = repoFileText("stack.config/grafana/entrypoint.sh")

        assertTrue(entrypoint.contains("runtime_config=\"/tmp/grafana-runtime.ini\""))
        assertTrue(entrypoint.contains("password = ${'$'}{GF_DATABASE_PASSWORD}"))
        assertTrue(entrypoint.contains("export GF_PATHS_CONFIG=\"${'$'}runtime_config\""))
        assertTrue(entrypoint.contains("unset GF_DATABASE_TYPE GF_DATABASE_HOST GF_DATABASE_NAME GF_DATABASE_USER GF_DATABASE_PASSWORD GF_DATABASE_SSL_MODE GF_DATABASE_CONN_MAX_LIFETIME"))
        assertTrue(entrypoint.contains("exec /run.sh"))
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
}
