package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultwardenSsoEntryConfigTest {

    @Test
    fun `vaultwarden homepage entry preselects internal sso organization`() {
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val homepageServices = repoFileText("stack.config/homepage/services.yaml")

        assertTrue(caddyfile.contains("@vw_sso_login path /sso-login /sso-login/"))
        assertTrue(caddyfile.contains("import keycloak_auth vaultwarden"))
        assertTrue(caddyfile.contains("redir * \"/#/sso?identifier={\$VAULTWARDEN_ORG_ID}&email={http.request.header.Remote-Email}\" 302"))
        assertTrue(homepageServices.contains("href: https://vaultwarden.{{DOMAIN}}/sso-login"))
    }

    @Test
    fun `vaultwarden sso derives email from keycloak verified email claim`() {
        val compose = repoFileText("stack.compose/vaultwarden.yml")
        val keycloakConfigure = repoFileText("stack.config/keycloak/configure-runtime.sh")

        assertTrue(compose.contains("SSO_SCOPES: openid email profile"))
        assertTrue(compose.contains("SSO_SIGNUPS_MATCH_EMAIL: true"))
        assertTrue(compose.contains("SSO_ALLOW_UNKNOWN_EMAIL_VERIFICATION: false"))
        assertTrue(keycloakConfigure.contains("ensure_hardcoded_claim_mapper \"vaultwarden\" \"vaultwarden-email-verified\" \"email_verified\" \"true\" \"boolean\""))
    }

    @Test
    fun `embedding service is not exposed in homepage visible config`() {
        val homepageServices = repoFileText("stack.config/homepage/services.yaml")
        val homepageBookmarks = repoFileText("stack.config/homepage/bookmarks.yaml")
        val homepageWidgets = repoFileText("stack.config/homepage/widgets.yaml")

        assertFalse(homepageServices.contains("Embedding API"))
        assertFalse(homepageServices.contains("https://models.{{DOMAIN}}"))
        assertFalse(homepageBookmarks.contains("embedding", ignoreCase = true))
        assertFalse(homepageBookmarks.contains("models.{{DOMAIN}}"))
        assertFalse(homepageWidgets.contains("embedding", ignoreCase = true))
        assertFalse(homepageWidgets.contains("models.{{DOMAIN}}"))
    }

    @Test
    fun `homepage exposes restored keycloak backed sogo web ui`() {
        val homepageServices = repoFileText("stack.config/homepage/services.yaml")

        assertTrue(homepageServices.contains("- SOGo:"))
        assertTrue(homepageServices.contains("href: https://sogo.{{DOMAIN}}"))
        assertTrue(homepageServices.contains("description: Mail, calendar, and contacts"))
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
