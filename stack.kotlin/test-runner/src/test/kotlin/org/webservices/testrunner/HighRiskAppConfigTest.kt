package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighRiskAppConfigTest {

    @Test
    fun `qbittorrent bypasses local WebUI auth only for resolved caddy proxy`() {
        val seedConfig = repoFileText("stack.config/qbittorrent/qBittorrent.conf")
        val initScript = repoFileText("stack.config/qbittorrent/init/10-configure-auth.sh")
        val combinedText = "$seedConfig\n$initScript"

        assertTrue(seedConfig.contains("WebUI\\AuthSubnetWhitelistEnabled=true"))
        assertTrue(seedConfig.contains("WebUI\\AuthSubnetWhitelist=127.0.0.1"))
        assertTrue(seedConfig.contains("WebUI\\BypassLocalAuth=true"))

        assertTrue(initScript.contains("upsert_preference 'WebUI\\AuthSubnetWhitelistEnabled' 'WebUI\\AuthSubnetWhitelistEnabled=true'"))
        assertTrue(initScript.contains("getent ahostsv4 caddy"))
        assertTrue(initScript.contains("WebUI\\\\AuthSubnetWhitelist=\${caddy_whitelist}"))
        assertTrue(initScript.contains("upsert_preference 'WebUI\\BypassLocalAuth' 'WebUI\\BypassLocalAuth=true'"))

        assertFalse(combinedText.contains("172.16.0.0/12"))
        assertFalse(combinedText.contains("10.0.0.0/8"))
        assertFalse(combinedText.contains("192.168.0.0/16"))
        assertFalse(combinedText.contains("0.0.0.0/0"))
        assertFalse(combinedText.contains("WebUI\\AuthSubnetWhitelistEnabled=false"))
        assertFalse(combinedText.contains("WebUI\\BypassLocalAuth=false"))
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
