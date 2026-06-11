package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultwardenSsoEntryConfigTest {

    @Test
    fun `vaultwarden portal entry preselects internal sso organization`() {
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val contracts = repoFileText("stack.config/service-contracts.json")

        assertTrue(caddyfile.contains("@vw_sso_login path /sso-login /sso-login/"))
        assertTrue(caddyfile.contains("import keycloak_auth vaultwarden"))
        assertTrue(caddyfile.contains("redir * \"/#/sso?identifier={\$VAULTWARDEN_ORG_ID}&email={http.request.header.Remote-Email}\" 302"))
        assertTrue(contracts.contains("\"vaultwarden\""))
        assertTrue(contracts.contains("\"hrefHost\": \"vaultwarden\""))
        assertTrue(contracts.contains("\"path\": \"/sso-login\""))
    }

    @Test
    fun `vaultwarden sso derives email from keycloak verified email claim`() {
        val compose = repoFileText("stack.compose/vaultwarden.yml")
        val keycloakConfigure = repoFileText("stack.config/keycloak/configure-runtime.sh")

        assertTrue(compose.contains("SSO_SCOPES: openid email profile"))
        assertTrue(compose.contains("SSO_SIGNUPS_MATCH_EMAIL: true"))
        assertTrue(compose.contains("SSO_ALLOW_UNKNOWN_EMAIL_VERIFICATION: false"))
        assertFalse(keycloakConfigure.contains("vaultwarden-email-verified"))
        assertFalse(keycloakConfigure.contains("\"email_verified\" \"true\""))
    }

    @Test
    fun `embedding service is not exposed in portal visible config`() {
        val contracts = repoFileText("stack.config/service-contracts.json")
        val inferenceBlock = contracts.substringAfter("\"inference\"").substringBefore("\"search\"")

        assertTrue(inferenceBlock.contains("\"visible\": false"))
        assertFalse(inferenceBlock.contains("\"hrefHost\": \"models\""))
    }

    @Test
    fun `portal exposes restored keycloak backed sogo web ui`() {
        val contracts = repoFileText("stack.config/service-contracts.json")
        val sogoBlock = contracts.substringAfter("\"sogo\"").substringBefore("\"vaultwarden\"")

        assertTrue(sogoBlock.contains("\"name\": \"SOGo\""))
        assertTrue(sogoBlock.contains("\"hrefHost\": \"sogo\""))
        assertTrue(sogoBlock.contains("\"description\": \"Mail, calendar, and contacts with deterministic evidence views.\""))
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
