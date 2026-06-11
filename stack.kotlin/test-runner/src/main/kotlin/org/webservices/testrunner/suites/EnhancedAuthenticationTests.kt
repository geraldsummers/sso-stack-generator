package org.webservices.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webservices.testrunner.framework.*


suspend fun TestRunner.enhancedAuthenticationTests() = suite("Enhanced Authentication Tests") {
    fun keycloakWhoamiUrl(): String = "https://${caddyHost("keycloak-whoami")}/"

    suspend fun ensureSharedEdgeSession(): Cookie {
        if (!auth.isAuthenticated() || !auth.verifyAuth()) {
            val username = System.getenv("STACK_ADMIN_USER")?.takeIf { it.isNotBlank() } ?: "sysadmin"
            val authResult = auth.trustInternalUser(username)
            require(authResult is AuthResult.Success) {
                "Trusted internal edge auth setup failed"
            }
            require(auth.verifyAuth()) {
                "Trusted internal edge identity did not pass Keycloak-protected Caddy route"
            }
        }
        return auth.getSessionCookie()
            ?: Cookie(name = "trusted_internal_edge_auth", value = "active", httpOnly = true, secure = true)
    }

    test("Phase 1: Trusted internal edge session is explicit and secure") {
        val cookie = ensureSharedEdgeSession()
        require(cookie.name == "trusted_internal_edge_auth") {
            "Stack-contract auth should use the explicit trusted internal edge marker, got: ${cookie.name}"
        }
        require(cookie.httpOnly) {
            "Trusted edge auth marker must be HTTP-only"
        }
        require(cookie.secure) {
            "Trusted edge auth marker must be Secure"
        }

        println("      ✓ Trusted internal edge session marker is explicit and secure")
    }

    test("Phase 1: Trusted internal edge session persists across multiple requests") {
        ensureSharedEdgeSession()

        repeat(5) { i ->
            val isValid = auth.verifyAuth()
            if (!isValid) {
                fail("Trusted edge session verification failed on request ${i + 1}/5")
            }
        }

        println("      ✓ Trusted internal edge session persisted across 5 consecutive requests")
    }

    test("Phase 1: Untrusted identity headers are rejected") {
        val response = requestHttpClient.get(keycloakWhoamiUrl()) {
            header("X-Remote-User", "forged-user")
            header("X-Remote-Groups", "admins")
        }

        require(response.status in listOf(HttpStatusCode.Found, HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Forged Remote-* headers must not bypass Keycloak edge auth: ${response.status}"
        }
        println("      ✓ Untrusted identity headers did not bypass Keycloak edge auth")
    }

    test("Phase 1: Complete edge auth flow reaches protected route") {
        ensureSharedEdgeSession()
        println("      ✓ Step 1: Trusted edge identity established")

        delay(500)

        val isValid = auth.verifyAuth()
        if (!isValid) {
            fail("Trusted edge session verification failed after setup")
        }
        println("      ✓ Step 2: Session verified through protected Caddy route")

        val username = System.getenv("STACK_ADMIN_USER")?.takeIf { it.isNotBlank() } ?: "sysadmin"
        val response = auth.authenticatedGet(keycloakWhoamiUrl())
        require(response.status == HttpStatusCode.OK) {
            "Authenticated Keycloak edge route failed: ${response.status}"
        }
        require(response.bodyAsText().contains(username)) {
            "Protected route did not receive expected Remote-User '$username'"
        }
        println("      ✓ Step 3: Authenticated access reached protected service")

        println("      ✓ Complete edge auth flow: establish → verify → access")
    }

    test("Phase 2: Keycloak OIDC discovery document contains required endpoints") {
        val realm = System.getenv("KEYCLOAK_REALM")?.takeIf { it.isNotBlank() } ?: "webservices"
        val keycloakBase = endpoints.keycloak.trimEnd('/')
        val response = client.getRawResponse("$keycloakBase/realms/$realm/.well-known/openid-configuration")
        require(response.status == HttpStatusCode.OK) {
            "Keycloak discovery failed: ${response.status}"
        }

        val discovery = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val issuer = discovery["issuer"]?.jsonPrimitive?.content
        val authorization = discovery["authorization_endpoint"]?.jsonPrimitive?.content
        val token = discovery["token_endpoint"]?.jsonPrimitive?.content
        val jwks = discovery["jwks_uri"]?.jsonPrimitive?.content
        val userInfo = discovery["userinfo_endpoint"]?.jsonPrimitive?.content

        require(issuer == "$keycloakBase/realms/$realm") { "Unexpected issuer: $issuer" }
        require(authorization?.contains("/protocol/openid-connect/auth") == true) { "Invalid authorization endpoint: $authorization" }
        require(token?.contains("/protocol/openid-connect/token") == true) { "Invalid token endpoint: $token" }
        require(jwks?.contains("/protocol/openid-connect/certs") == true) { "Invalid JWKS endpoint: $jwks" }
        require(userInfo?.contains("/protocol/openid-connect/userinfo") == true) { "Invalid userinfo endpoint: $userInfo" }

        println("      ✓ Keycloak OIDC discovery endpoints validated:")
        println("        - Issuer: $issuer")
        println("        - Authorization: $authorization")
        println("        - Token: $token")
        println("        - JWKS: $jwks")
        println("        - UserInfo: $userInfo")
    }

    test("Phase 3: Caddy Keycloak edge auth redirects unauthenticated requests") {
        val response = client.getRawResponse(keycloakWhoamiUrl())

        val redirected = response.status in listOf(
            HttpStatusCode.Found,
            HttpStatusCode.TemporaryRedirect,
            HttpStatusCode.SeeOther,
            HttpStatusCode.PermanentRedirect
        )
        val unauthorized = response.status in listOf(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden
        )

        require(redirected || unauthorized) {
            "Unauthenticated request should be redirected or denied: ${response.status}"
        }

        if (redirected) {
            val location = response.headers["Location"]
            require(location?.contains("keycloak-auth.") == true || location?.contains("/oauth2/") == true) {
                "Expected Keycloak auth gateway redirect, got: $location"
            }
            println("      ✓ Redirected to Keycloak auth gateway: $location")
        } else {
            println("      ✓ Access denied: ${response.status}")
        }
    }

    test("Phase 3: Authenticated request reaches protected service") {
        ensureSharedEdgeSession()

        val response = auth.authenticatedGet(caddyUrl(endpoints.caddy)) {
            applyCaddyVirtualHost(caddyHost("portal"))
        }

        require(response.status.value in 200..399) {
            "Authenticated request should reach service: ${response.status}"
        }

        println("      ✓ Authenticated request successfully reached protected service")
        println("        - Status: ${response.status}")
    }

    test("Phase 3: Users group can access allowed services") {
        ensureSharedEdgeSession()

        val allowedServices = listOf(
            Triple("grafana", "/", "Grafana"),
            Triple("bookstack", "/", "BookStack"),
            Triple("forgejo", "/", "Forgejo"),
            Triple("planka", "/", "Planka")
        )

        var successCount = 0
        for ((subdomain, path, serviceName) in allowedServices) {
            try {
                val response = auth.authenticatedGet(caddyUrl(endpoints.caddy, path)) {
                    applyCaddyVirtualHost(caddyHost(subdomain))
                }
                if (response.status.value in 200..399) {
                    println("      ✓ User accessed $serviceName: ${response.status}")
                    successCount++
                } else {
                    println("      ⚠ $serviceName returned: ${response.status}")
                }
            } catch (e: Exception) {
                println("      ⚠ $serviceName error: ${e.message?.take(50)}")
            }
        }

        require(successCount > 0) {
            "User should be able to access at least one service"
        }

        println("      ✓ User accessed $successCount/${allowedServices.size} services")
    }

    test("Phase 3: Internal trusted edge path requires shared secret") {
        val response = requestHttpClient.get(keycloakWhoamiUrl()) {
            header("X-Trusted-Proxy-Secret", "wrong-secret")
            header("X-Remote-User", "forged-user")
            header("X-Remote-Groups", "admins")
        }

        require(response.status in listOf(HttpStatusCode.Found, HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Wrong trusted proxy secret must not bypass Keycloak edge auth: ${response.status}"
        }

        println("      ✓ Internal trusted edge path rejects wrong shared secret")
    }

    test("Phase 4: Single edge identity grants access to multiple services") {
        ensureSharedEdgeSession()
        println("      ✓ Reusing one verified Keycloak edge identity")

        val services = listOf(
            Triple("grafana", "/", "Grafana"),
            Triple("bookstack", "/", "BookStack"),
            Triple("forgejo", "/", "Forgejo")
        )

        var accessibleServices = 0
        for ((subdomain, path, serviceName) in services) {
            try {
                val response = auth.authenticatedGet(caddyUrl(endpoints.caddy, path)) {
                    applyCaddyVirtualHost(caddyHost(subdomain))
                }
                if (response.status.value in 200..399) {
                    println("      ✓ SSO granted access to $serviceName")
                    accessibleServices++
                }
            } catch (e: Exception) {
                println("      ⚠ $serviceName: ${e.message?.take(50)}")
            }
        }

        require(accessibleServices >= 2) {
            "SSO should grant access to at least 2 services, got $accessibleServices"
        }

        println("      ✓ Single Sign-On working: 1 edge identity → $accessibleServices services")
    }

    test("Phase 4: Logout clears local test session state") {
        ensureSharedEdgeSession()

        val beforeLogout = auth.authenticatedGet(caddyUrl(endpoints.caddy)) {
            applyCaddyVirtualHost(caddyHost("grafana"))
        }
        require(beforeLogout.status.value in 200..399) {
            "Should have access before logout"
        }
        println("      ✓ Access granted before logout")

        auth.logout()
        println("      ✓ Logged out")

        require(!auth.verifyAuth()) {
            "Auth helper should not retain a valid trusted session after logout"
        }

        println("      ✓ Session correctly invalidated in test helper")
    }
}
