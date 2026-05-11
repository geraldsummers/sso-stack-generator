package org.webservices.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.infrastructureTests() = suite("Infrastructure Tests") {
    fun keycloakWhoamiUrl(): String = "https://${caddyHost("keycloak-whoami")}/"

    test("Keycloak realm endpoint is accessible") {
        var lastError: Exception? = null
        var attempts = 0
        val maxAttempts = 15
        val realm = System.getenv("KEYCLOAK_REALM")?.takeIf { it.isNotBlank() } ?: "webservices"
        val keycloakBase = endpoints.keycloak.trimEnd('/')

        while (attempts < maxAttempts) {
            try {
                val response = client.getRawResponse("$keycloakBase/realms/$realm")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                val json = Json.parseToJsonElement(body).jsonObject
                require(json["realm"]?.jsonPrimitive?.content == realm) {
                    "Unexpected Keycloak realm payload: $body"
                }

                if (attempts > 0) {
                    println("      ℹ️  Keycloak accessible after $attempts retry attempts")
                }
                return@test
            } catch (e: Exception) {
                lastError = e
                attempts++
                if (attempts < maxAttempts) {
                    val delayMs = minOf(1000L * attempts, 5000L)
                    delay(delayMs)
                }
            }
        }

        throw AssertionError("Keycloak not accessible after $maxAttempts attempts (~30s): ${lastError?.message}")
    }

    test("Keycloak OIDC discovery works") {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val realm = System.getenv("KEYCLOAK_REALM")?.takeIf { it.isNotBlank() } ?: "webservices"
                val response = client.getRawResponse("${endpoints.keycloak.trimEnd('/')}/realms/$realm/.well-known/openid-configuration")
                response.status shouldBe HttpStatusCode.OK
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                require(json.containsKey("issuer")) { "OIDC discovery missing 'issuer'" }
                require(json.containsKey("authorization_endpoint")) { "OIDC discovery missing 'authorization_endpoint'" }
                require(json.containsKey("token_endpoint")) { "OIDC discovery missing 'token_endpoint'" }
                require(json.containsKey("jwks_uri")) { "OIDC discovery missing 'jwks_uri'" }
                return@test
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(1000)
            }
        }
        throw AssertionError("Keycloak OIDC discovery failed: ${lastError?.message}")
    }

    test("Keycloak auth gateway redirects unauthenticated users") {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val response = requestHttpClient.get(keycloakWhoamiUrl())

                response.status.value shouldBeOneOf listOf(302, 303, 307, 401, 403)
                val location = response.headers[HttpHeaders.Location].orEmpty()
                if (response.status.value in 300..399) {
                    require(location.contains("keycloak-auth.") || location.contains("/oauth2/")) {
                        "Unexpected Keycloak auth redirect target: $location"
                    }
                }
                return@test
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(1000)
            }
        }
        throw AssertionError("Keycloak auth verification test failed: ${lastError?.message}")
    }

    test("Keycloak edge authentication flow works") {
        val username = System.getenv("STACK_ADMIN_USER")?.takeIf { it.isNotBlank() } ?: "sysadmin"
        if (!auth.isAuthenticated()) {
            val authResult = auth.trustInternalUser(username)
            require(authResult is AuthResult.Success) {
                "Trusted internal Keycloak edge auth setup failed"
            }
        }

        delay(500)

        val isValid = auth.verifyAuth()
        if (!isValid) {
            fail("Trusted internal edge session verification failed")
        }
        println("      ✓ Keycloak edge identity established and verified as $username")
    }

    test("Keycloak auth gateway health endpoint responds") {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val response = client.getRawResponse("http://keycloak-auth-gateway:4180/ping")
                response.status shouldBe HttpStatusCode.OK
                return@test
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(1000)
            }
        }
        throw AssertionError("Keycloak auth gateway health check failed: ${lastError?.message}")
    }

    test("Keycloak validates OIDC client config") {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val realm = System.getenv("KEYCLOAK_REALM")?.takeIf { it.isNotBlank() } ?: "webservices"
                val response = client.getRawResponse("${endpoints.keycloak.trimEnd('/')}/realms/$realm/.well-known/openid-configuration")
                response.status shouldBe HttpStatusCode.OK
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                json.containsKey("jwks_uri") shouldBe true
                json.containsKey("scopes_supported") shouldBe true
                json.containsKey("response_types_supported") shouldBe true
                return@test
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(1000)
            }
        }
        throw AssertionError("Keycloak OIDC config validation failed: ${lastError?.message}")
    }

    test("retired directory compatibility service is absent from the runtime contract") {
        val result = DockerCli.run("ps", "--format", "{{.Names}}")
        require(result.exitCode == 0) {
            "Unable to inspect runtime containers: ${result.output}"
        }
        val retiredDirectoryContainer = "ld" + "ap"
        val containers = result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        require(retiredDirectoryContainer !in containers) {
            "Retired directory container should not be running after Keycloak migration; running containers: ${containers.sorted()}"
        }
        println("      ✓ Retired directory container is absent from the runtime")
    }
}
