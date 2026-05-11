package org.webservices.testrunner.framework

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Validates username and password shape before tests use them in provisioning
 * or browser-driven Keycloak flows.
 */
fun validateCredentials(username: String, password: String) {
    require(username.isNotBlank()) { "Username cannot be blank" }
    require(username.length in 3..64) { "Username must be 3-64 characters" }
    require(password.isNotBlank()) { "Password cannot be blank" }
    require(password.length >= 8) { "Password must be at least 8 characters" }

    val weakPasswords = setOf("password", "12345678", "admin123", "test1234")
    require(password.lowercase() !in weakPasswords) {
        "Password is too weak (common password detected)"
    }
}

/**
 * Test-runner auth helper for the Keycloak-era edge contract.
 *
 * Stack-contract tests do not perform password login against the browser IdP.
 * They either validate anonymous redirects to the Keycloak auth gateway or use
 * an explicit trusted internal edge identity header set accepted only by Caddy.
 */
class AuthHelper(
    private val client: HttpClient,
    private val cookieStorage: ResettableCookiesStorage? = null,
    private val requestClient: HttpClient = client,
    private val protectedServiceUrl: String? = null,
    private val protectedVirtualHost: String? = null
) {
    private var trustedUsername: String? = null

    private fun HttpRequestBuilder.applyTrustedIdentity() {
        val username = trustedUsername?.takeIf { it.isNotBlank() } ?: return
        val trustedProxySecret = System.getenv("WORKSPACE_PROXY_AUTH_SECRET")?.takeIf { it.isNotBlank() }
            ?: System.getenv("MODEL_CONTEXT_PROXY_AUTH_SECRET")?.takeIf { it.isNotBlank() }
        if (!trustedProxySecret.isNullOrBlank()) {
            header("X-Trusted-Proxy-Secret", trustedProxySecret)
            header("X-Remote-User", username)
            header("X-Remote-Groups", "users")
            header("X-Remote-Name", username)
            header(
                "X-Remote-Email",
                System.getenv("STACK_ADMIN_EMAIL")?.takeIf { it.isNotBlank() }
                    ?: "$username@${System.getenv("DOMAIN") ?: "localhost"}"
            )
        }
    }

    suspend fun login(username: String = "admin", password: String, totpSecret: String? = null): AuthResult {
        return try {
            validateCredentials(username, password)
            AuthResult.Error("Direct API login is not supported; Keycloak login is browser-driven and tested by Playwright.")
        } catch (e: IllegalArgumentException) {
            AuthResult.Error("Credential validation failed: ${e.message}")
        }
    }

    suspend fun authenticatedGet(url: String): HttpResponse {
        return requestClient.get(url) {
            applyTrustedIdentity()
        }
    }

    suspend fun authenticatedGet(url: String, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        return requestClient.get(url) {
            applyTrustedIdentity()
            block()
        }
    }

    suspend fun authenticatedPost(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        return requestClient.post(url) {
            applyTrustedIdentity()
            block()
        }
    }

    fun logout() {
        trustedUsername = null
        cookieStorage?.clear()
    }

    fun trustInternalUser(
        username: String = System.getenv("STACK_ADMIN_USER")?.takeIf { it.isNotBlank() } ?: "sysadmin",
        passwordMarker: String = "__trusted_internal_edge_auth__"
    ): AuthResult {
        trustedUsername = username
        return AuthResult.Success(Cookie(name = "trusted_internal_edge_auth", value = username, httpOnly = true, secure = true))
    }

    fun isAuthenticated(): Boolean = trustedUsername != null

    fun getSessionCookie(): Cookie? = trustedUsername?.let {
        Cookie(name = "trusted_internal_edge_auth", value = it, httpOnly = true, secure = true)
    }

    suspend fun verifyAuth(): Boolean {
        val username = trustedUsername ?: return false
        val response = requestClient.get(protectedServiceUrl ?: return true) {
            applyTrustedIdentity()
            protectedVirtualHost?.let { applyCaddyVirtualHost(it) }
        }
        return response.status.value in 200..399 && response.bodyAsText().contains(username)
    }

    suspend fun loginWithEphemeralUser(groups: List<String> = listOf("users")): AuthResult {
        return AuthResult.Error("Ephemeral password login is not supported; use Keycloak-managed browser users.")
    }

    fun cleanupEphemeralUser() {
        logout()
    }

    fun getEphemeralUser(): TestUser? = null
}

sealed class AuthResult {
    data class Success(val sessionCookie: Cookie) : AuthResult()
    data class Error(val message: String) : AuthResult()
}
