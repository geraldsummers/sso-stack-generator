package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeafileSynapseHardeningConfigTest {

    @Test
    fun `synapse entrypoint fails closed when privilege drop helpers are missing`() {
        val entrypoint = repoFileText("stack.config/synapse/entrypoint.sh")

        assertTrue(entrypoint.contains("command -v gosu"))
        assertTrue(entrypoint.contains("command -v su-exec"))
        assertTrue(entrypoint.contains("refusing unsafe fallback re-exec"))
        assertFalse(entrypoint.contains("exec su -s /bin/bash -c \"exec \$0 \$*\""))
    }

    @Test
    fun `seafile schema reset validates identifiers and api edge strips remote user headers`() {
        val runtimeEntrypoint = repoFileText("stack.config/seafile/runtime-entrypoint.sh")
        val seahubSettings = repoFileText("stack.config/seafile/seahub_settings.py")
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val seafileCompose = repoFileText("stack.compose/seafile.yml")
        val volumes = repoFileText("global.settings/volumes.yml")
        val deploy = repoFileText("scripts/deploy.sh")

        assertTrue(runtimeEntrypoint.contains("re.compile(r\"^[A-Za-z_][A-Za-z0-9_]*$\")"))
        assertTrue(runtimeEntrypoint.contains("Invalid Seafile schema identifier(s) for DDL"))
        assertTrue(runtimeEntrypoint.contains("reconcile_admin_user"))
        assertTrue(runtimeEntrypoint.contains("start_admin_user_reconciler"))
        assertTrue(runtimeEntrypoint.contains("INIT_SEAFILE_ADMIN_EMAIL"))
        assertTrue(runtimeEntrypoint.contains("user.set_password(admin_password)"))
        assertTrue(runtimeEntrypoint.contains("user.check_password(admin_password)"))
        assertTrue(seahubSettings.contains("ENABLE_REMOTE_USER_AUTHENTICATION = True"))
        assertTrue(caddyfile.contains("api.seafile.{\$DOMAIN}"))
        assertTrue(caddyfile.contains("header_up -Remote-User"))
        assertTrue(caddyfile.contains("header_up -X-Remote-User"))
        assertTrue(caddyfile.contains("header_up -X-Trusted-Proxy-Secret"))
        assertTrue(seafileCompose.contains("- seafile_files:/shared"))
        assertFalse(seafileCompose.contains("/shared/seafile/seafile-data/storage"))
        assertTrue(volumes.contains("seafile_files:"))
        assertTrue(volumes.contains("device: \${SEAFILE_MEDIA_ROOT}"))
        assertFalse(volumes.contains("seafile_media:"))
        assertTrue(deploy.contains("migrate_legacy_seafile_split_volume"))
        assertTrue(deploy.contains("legacy Seafile split volume"))
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
