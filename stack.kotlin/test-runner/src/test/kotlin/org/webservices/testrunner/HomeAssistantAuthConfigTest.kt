package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeAssistantAuthConfigTest {
    private val retiredDirectoryId = "ld" + "ap"
    private val retiredDirectoryEnvPrefix = retiredDirectoryId.uppercase() + "_"

    @Test
    fun `home assistant exposes keycloak edge auth through trusted frontend flow`() {
        val configuration = repoFileText("stack.config/homeassistant/configuration.yaml")
        val compose = repoFileText("stack.compose/homeassistant.yml")

        assertTrue(configuration.contains("- type: trusted_networks"))
        assertTrue(configuration.contains("name: Keycloak"))
        assertFalse(configuration.contains("type: homeassistant"))
        assertTrue(compose.contains("./configs/homeassistant/auth_keycloak.py:/usr/src/homeassistant/homeassistant/auth/providers/trusted_networks.py:ro"))

        assertFalse(configuration.contains("allow_bypass_login"))
        assertFalse(configuration.contains("${retiredDirectoryId}_"))
        assertFalse(compose.contains(retiredDirectoryEnvPrefix))
        assertFalse(compose.contains("$retiredDirectoryId:"))
        assertFalse(compose.contains("HOMEASSISTANT_TRUSTED_NETWORKS"))
    }

    @Test
    fun `home assistant keycloak provider canonicalizes usernames and trusts only edge headers`() {
        val provider = repoFileText("stack.config/homeassistant/auth_keycloak.py")

        assertTrue(provider.contains("unicodedata.normalize(\"NFKC\", username).strip().casefold()"))
        assertTrue(provider.contains("USERNAME_PATTERN.fullmatch(canonical_username)"))
        assertTrue(provider.contains("current_request.get(None)"))
        assertTrue(provider.contains("trusted_remote_user_header"))
        assertTrue(provider.contains("async_validate_trusted_header_login"))
        assertTrue(provider.contains("@AUTH_PROVIDERS.register(\"trusted_networks\")"))
        assertTrue(provider.contains("TRUSTED_PROXY_NETWORKS = [ip_network(\"172.16.0.0/12\")]"))
        assertTrue(provider.contains("if \"user\" in flow_result:"))
        assertTrue(provider.contains("await self.store.async_link_user(selected_user, credential)"))
        assertTrue(provider.contains("user is not None and user.is_active"))
        assertTrue(provider.contains("Ignoring inactive Home Assistant credential link"))

        assertFalse(provider.contains("@AUTH_PROVIDERS.register(\"$retiredDirectoryId\")"))
        assertFalse(provider.contains("${retiredDirectoryId}3"))
        assertFalse(provider.contains("async_validate_login"))
    }

    @Test
    fun `home assistant bootstrap relinks stack admin credentials to active user`() {
        val initScript = repoFileText("stack.config/homeassistant/init-homeassistant.sh")

        assertTrue(initScript.contains("credential[\"user_id\"] = keep.get(\"id\")"))
        assertTrue(initScript.contains("candidate_admin_users"))
        assertTrue(initScript.contains("Linked Home Assistant stack-admin credentials to active user"))
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
