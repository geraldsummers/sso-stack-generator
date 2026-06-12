package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookStackHardeningConfigTest {

    @Test
    fun `api bookstack virtual host only proxies api paths`() {
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val apiBlock = caddyfile.substringAfter("api.bookstack.{$" + "DOMAIN} {")
            .substringBefore("# Seafile")

        assertTrue(apiBlock.contains("@bookstack_api path /api /api/*"))
        assertTrue(apiBlock.contains("handle @bookstack_api"))
        assertTrue(apiBlock.contains("reverse_proxy bookstack:80"))
        assertTrue(apiBlock.contains("respond 404"))
        assertTrue(apiBlock.indexOf("reverse_proxy bookstack:80") > apiBlock.indexOf("handle @bookstack_api"))
        assertTrue(apiBlock.indexOf("respond 404") > apiBlock.indexOf("reverse_proxy bookstack:80"))
    }

    @Test
    fun `public guest role is permissionless by default`() {
        val permissionsScript = repoFileText("stack.config/bookstack/init/50-configure-permissions.sh")
        val publicRoleBlock = permissionsScript
            .substringAfter("SET @public_role_id")
            .substringBefore("-- Promote the first OIDC user")

        assertTrue(publicRoleBlock.contains("Public guest access is intentionally disabled by default"))
        assertFalse(publicRoleBlock.contains("page-view-all"))
        assertFalse(publicRoleBlock.contains("chapter-view-all"))
        assertFalse(publicRoleBlock.contains("book-view-all"))
        assertFalse(publicRoleBlock.contains("bookshelf-view-all"))
    }

    @Test
    fun `automation token is non admin bound and time limited`() {
        val permissionsScript = repoFileText("stack.config/bookstack/init/50-configure-permissions.sh")
        val initScript = repoFileText("stack.config/bookstack/init/80-create-api-token.sh")
        val generateScript = repoFileText("stack.config/bookstack/generate-api-token.main.kts")
        val injectScript = repoFileText("stack.config/bookstack/inject-token.main.kts")
        val combinedTokenScripts = "$initScript\n$generateScript\n$injectScript"

        assertTrue(permissionsScript.contains("system_name = 'automation'"))
        assertTrue(permissionsScript.contains("webservices-automation@localhost"))
        assertTrue(permissionsScript.contains("'access-api'"))
        assertTrue(combinedTokenScripts.contains("webservices-automation@localhost"))
        assertTrue(combinedTokenScripts.contains("BOOKSTACK_API_TOKEN_TTL_DAYS"))
        assertTrue(combinedTokenScripts.contains("Hash::make"))
        assertTrue(combinedTokenScripts.contains("Token identifier loaded"))
        assertTrue(combinedTokenScripts.contains("Token identifier stored"))

        assertFalse(combinedTokenScripts.contains("admin@admin.com"))
        assertFalse(combinedTokenScripts.contains("2099-12-31"))
        assertFalse(combinedTokenScripts.contains("role_id = 1"))
        assertFalse(combinedTokenScripts.contains("Token ID:"))
        assertFalse(combinedTokenScripts.contains("Token ID: ${'$'}tokenId"))
        assertFalse(combinedTokenScripts.contains("Token ID: ${'$'}BOOKSTACK_TOKEN_ID"))
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
