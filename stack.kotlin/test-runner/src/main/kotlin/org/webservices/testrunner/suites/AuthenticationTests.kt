package org.webservices.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webservices.testrunner.framework.*


suspend fun TestRunner.authenticationTests() = suite("Authentication & Authorization Tests") {
    fun keycloakWhoamiUrl(): String = "https://${caddyHost("keycloak-whoami")}/"


    suspend fun getBookStackResponse(path: String, attempts: Int = 12, delayMs: Long = 5000): HttpResponse? {
        val suffix = if (path.startsWith("/")) path else "/$path"
        val url = "${env.endpoints.bookstack}$suffix"

        repeat(attempts) { attempt ->
            try {
                return client.getRawResponse(url)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val retryable = msg.contains("Connection refused", ignoreCase = true) ||
                    msg.contains("ConnectException", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)
                if (!retryable || attempt == attempts - 1) {
                    println("      ℹ️  BookStack request failed at $suffix: ${e.message}")
                    return null
                }
                if (attempt == 0) {
                    println("      ℹ️  Waiting for BookStack to become reachable...")
                }
                delay(delayMs)
            }
        }

        return null
    }

    test("Keycloak realm discovery document is available") {
        val realm = System.getenv("KEYCLOAK_REALM")?.takeIf { it.isNotBlank() } ?: "webservices"
        val keycloakBase = endpoints.keycloak.trimEnd('/')
        val response = client.getRawResponse("$keycloakBase/realms/$realm/.well-known/openid-configuration")
        require(response.status == HttpStatusCode.OK) {
            "Keycloak discovery failed: ${response.status}"
        }

        val discovery = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val issuer = discovery["issuer"]?.jsonPrimitive?.content
        require(issuer == "$keycloakBase/realms/$realm") {
            "Unexpected Keycloak issuer: $issuer"
        }
        require(discovery["authorization_endpoint"]?.jsonPrimitive?.content?.contains("/protocol/openid-connect/auth") == true) {
            "Keycloak authorization endpoint missing or invalid"
        }
        require(discovery["token_endpoint"]?.jsonPrimitive?.content?.contains("/protocol/openid-connect/token") == true) {
            "Keycloak token endpoint missing or invalid"
        }
        require(discovery["jwks_uri"]?.jsonPrimitive?.content?.contains("/protocol/openid-connect/certs") == true) {
            "Keycloak JWKS endpoint missing or invalid"
        }

        println("      ✓ Keycloak realm discovery is available at issuer $issuer")
    }

    test("Keycloak auth gateway redirects unauthenticated protected routes") {
        val response = client.getRawResponse(keycloakWhoamiUrl())
        require(response.status in listOf(HttpStatusCode.Found, HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect, HttpStatusCode.Unauthorized)) {
            "Unauthenticated Keycloak-protected route should redirect or deny: ${response.status}"
        }
        val location = response.headers[HttpHeaders.Location].orEmpty()
        if (response.status.value in 300..399) {
            require(location.contains("keycloak-auth.") || location.contains("/oauth2/")) {
                "Protected route redirected outside Keycloak auth gateway: $location"
            }
        }
        println("      ✓ Keycloak auth gateway enforces unauthenticated access")
    }

    test("Trusted internal edge identity reaches protected Keycloak route") {
        val username = System.getenv("STACK_ADMIN_USER")?.takeIf { it.isNotBlank() } ?: "sysadmin"
        auth.trustInternalUser(username)
        val response = auth.authenticatedGet(keycloakWhoamiUrl())
        require(response.status == HttpStatusCode.OK) {
            "Trusted internal edge identity should reach Keycloak whoami route: ${response.status}"
        }
        val body = response.bodyAsText()
        require(body.contains(username)) {
            "Keycloak whoami route did not receive Remote-User '$username': $body"
        }
        println("      ✓ Trusted internal edge identity reached Keycloak route as $username")
    }

    
    
    

    test("Grafana requires authentication") {
        val response = client.getRawResponse("${env.endpoints.grafana}/api/dashboards/home")
        require(response.status == HttpStatusCode.Unauthorized) {
            "Grafana should require authentication: ${response.status}"
        }

        println("      ✓ Grafana correctly requires authentication")
    }

    test("Vaultwarden requires authentication for API") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/api/accounts/profile")
        require(response.status == HttpStatusCode.Unauthorized) {
            "Vaultwarden should require authentication: ${response.status}"
        }

        println("      ✓ Vaultwarden correctly requires authentication")
    }

    test("BookStack requires authentication for content") {
        val response = getBookStackResponse("/api/books")
            ?: fail("BookStack unavailable after retries while validating authentication")

        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "BookStack should require authentication or return empty: ${response.status}"
        }

        println("      ✓ BookStack API accessible")
    }

    test("Forgejo requires authentication for API") {
        val response = client.getRawResponse("${env.endpoints.forgejo}/api/v1/user")
        require(response.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Forgejo should require authentication: ${response.status}"
        }

        println("      ✓ Forgejo correctly requires authentication")
    }

    test("Mastodon requires authentication for timeline") {
        val response = client.getRawResponse("${env.endpoints.mastodon}/api/v1/timelines/home")
        require(response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            "Mastodon should require authentication: ${response.status}"
        }

        println("      ✓ Mastodon correctly requires authentication")
    }

    test("JupyterHub requires authentication") {
        val response = client.getRawResponse("${env.endpoints.jupyterhub}/hub/api/user")
        require(response.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.Found)) {
            "JupyterHub should require authentication: ${response.status}"
        }

        println("      ✓ JupyterHub correctly requires authentication")
    }

    test("Seafile requires authentication for API") {
        val response = client.getRawResponse("${env.endpoints.seafile}/api2/auth/ping/")

        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            "Seafile API not accessible: ${response.status}"
        }

        println("      ✓ Seafile authentication endpoint accessible")
    }

    test("Planka requires authentication for boards") {
        val response = client.getRawResponse("${env.endpoints.planka}/api/boards")
        
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized)) {
            "Planka should require authentication or return empty: ${response.status}"
        }

        println("      ✓ Planka API accessible")
    }
}
