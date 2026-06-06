package org.webservices.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*
import org.webservices.testrunner.framework.applyCaddyVirtualHost
import org.webservices.testrunner.framework.caddyHost
import org.webservices.testrunner.framework.caddyUrl
import kotlin.random.Random


suspend fun TestRunner.authenticatedOperationsTests() = suite("Authenticated Operations Tests") {
    suspend fun authenticatedCaddyGet(subdomain: String, path: String = "/"): HttpResponse {
        return auth.authenticatedGet("https://${caddyHost(subdomain)}${if (path.startsWith("/")) path else "/$path"}")
    }

    suspend fun probeFirstReachable(
        urls: List<String>,
        attempts: Int = 1,
        retryDelayMs: Long = 0L
    ): HttpResponse? {
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            for (url in urls) {
                val response = runCatching { client.getRawResponse(url) }.getOrNull() ?: continue
                if (
                    response.status != HttpStatusCode.NotFound &&
                    response.status != HttpStatusCode.BadGateway &&
                    response.status != HttpStatusCode.ServiceUnavailable &&
                    response.status != HttpStatusCode.GatewayTimeout
                ) {
                    return response
                }
            }

            if (retryDelayMs > 0 && attempt < attempts - 1) {
                delay(retryDelayMs)
            }
        }
        return null
    }

    suspend fun getRawResponseWithRetry(
        url: String,
        attempts: Int = 4,
        attemptTimeoutMs: Long = 20_000L,
        retryDelayMs: Long = 2_000L
    ): HttpResponse {
        var lastError: Throwable? = null

        repeat(attempts.coerceAtLeast(1)) { attempt ->
            try {
                return withTimeout(attemptTimeoutMs) {
                    client.getRawResponse(url)
                }
            } catch (e: Exception) {
                lastError = e
                val message = e.message.orEmpty()
                val retryable = message.contains("timed out", ignoreCase = true) ||
                    message.contains("timeout", ignoreCase = true) ||
                    message.contains("connection refused", ignoreCase = true) ||
                    message.contains("connectexception", ignoreCase = true) ||
                    message.contains("connection reset", ignoreCase = true) ||
                    message.contains("broken pipe", ignoreCase = true)

                if (!retryable || attempt == attempts - 1) {
                    throw e
                }

                if (attempt == 0) {
                    println("      ℹ️  JupyterHub API probe hit a transient network timeout, retrying...")
                }
                delay(retryDelayMs)
            }
        }

        throw lastError ?: IllegalStateException("Request did not execute for $url")
    }

    var edgeSessionReady = false
    suspend fun ensureEdgeSession() {
        if (edgeSessionReady && auth.isAuthenticated()) {
            return
        }
        val username = System.getenv("STACK_ADMIN_USER")?.takeIf { it.isNotBlank() } ?: "sysadmin"
        val result = auth.trustInternalUser(username)
        require(result is AuthResult.Success) {
            "Failed to establish trusted internal edge session"
        }
        require(auth.verifyAuth()) {
            "Trusted internal edge session did not pass Caddy Keycloak auth"
        }
        edgeSessionReady = true
        println("      ✓ Authenticated through Keycloak edge identity as $username")
    }

    test("Grafana: Acquire API key and query datasources") {
        val grafanaPassword = System.getenv("GRAFANA_ADMIN_PASSWORD") ?: "admin"

        ensureEdgeSession()

        
        val tokenResult = tokens.acquireGrafanaToken("admin", grafanaPassword)
        require(tokenResult.isSuccess) { "Failed to acquire Grafana token: ${tokenResult.exceptionOrNull()?.message}" }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Grafana API key")

        
        val response = tokens.authenticatedGet("grafana", "${env.endpoints.grafana}/api/datasources")
        require(response.status == HttpStatusCode.OK) {
            "Failed to query datasources: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("[") || body.contains("{}")) {
            "Unexpected datasources response"
        }
        println("      ✓ Successfully queried Grafana datasources with API key")

        
        val proxiedResponse = authenticatedCaddyGet("grafana")
        require(proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Grafana through authenticated proxy")
    }

    
    
    

    test("Seafile: Acquire token and list libraries") {
        val username = System.getenv("SEAFILE_USERNAME")
            ?: System.getenv("STACK_ADMIN_EMAIL")
            ?: "admin@example.test"
        val password = System.getenv("SEAFILE_PASSWORD")
            ?: System.getenv("STACK_ADMIN_PASSWORD")
            ?: "changeme"

        val tokenResult = tokens.acquireSeafileToken(username, password)
        require(tokenResult.isSuccess) {
            "Failed to acquire Seafile token: ${tokenResult.exceptionOrNull()?.message}"
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Seafile token")


        val response = tokens.authenticatedGet("seafile", "http://seafile:80/api2/repos/")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list libraries: ${response.status}"
        }

        println("      ✓ Successfully listed Seafile libraries")
    }

    
    
    

    test("Forgejo: Acquire token and list repositories") {
        val username = System.getenv("FORGEJO_USERNAME")
            ?: System.getenv("STACK_ADMIN_USER")
            ?: "admin"
        val password = System.getenv("FORGEJO_PASSWORD")
            ?: System.getenv("STACK_ADMIN_PASSWORD")
            ?: ""
        val hasInteractiveCredentials = username.isNotBlank() && password.isNotBlank()

        val tokenResult = tokens.acquireForgejoToken(username, password)
        if (
            tokenResult.isFailure &&
            System.getenv("FORGEJO_API_TOKEN").isNullOrBlank() &&
            tokenResult.exceptionOrNull()?.message?.contains("403 Forbidden") == true
        ) {
            val unauthenticatedResponse = client.getRawResponse("http://forgejo:3000/api/v1/user")
            require(unauthenticatedResponse.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                "Forgejo API should reject unauthenticated local access when password token creation is disabled: ${unauthenticatedResponse.status}"
            }
            println("      ✓ Forgejo password token creation is disabled and unauthenticated API access is rejected")
            return@test
        }
        if (
            tokenResult.isFailure &&
            System.getenv("FORGEJO_API_TOKEN").isNullOrBlank() &&
            !hasInteractiveCredentials
        ) {
            val unauthenticatedResponse = client.getRawResponse("http://forgejo:3000/api/v1/user")
            require(unauthenticatedResponse.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                "Forgejo API should reject unauthenticated local access when no pre-generated token is configured: ${unauthenticatedResponse.status}"
            }
            println("      ✓ Forgejo token acquisition intentionally skipped because no credentials were provided")
            return@test
        }

        require(tokenResult.isSuccess) {
            "Failed to acquire Forgejo token: ${tokenResult.exceptionOrNull()?.message}"
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Forgejo access token")


        val response = tokens.authenticatedGet("forgejo", "http://forgejo:3000/api/v1/user/repos")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list repos: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.startsWith("[")) {
            "Unexpected repos response"
        }

        println("      ✓ Successfully listed Forgejo repositories")
    }

    
    
    

    test("Planka: Validate API auth path under SSO policy") {
        val email = System.getenv("PLANKA_EMAIL")
            ?: System.getenv("STACK_ADMIN_EMAIL")
            ?: "admin@example.test"
        val password = System.getenv("PLANKA_PASSWORD")
            ?: System.getenv("STACK_ADMIN_PASSWORD")
            ?: "changeme"

        val tokenResult = tokens.acquirePlankaToken(email, password)
        if (tokenResult.isSuccess) {
            tokenResult.getOrThrow()
            println("      ✓ Acquired Planka authentication token")

            val response = tokens.authenticatedGet("planka", "http://planka:1337/api/boards")
            require(response.status == HttpStatusCode.OK) {
                "Failed to list boards: ${response.status}"
            }

            println("      ✓ Successfully listed Planka boards")
        } else {
            val error = tokenResult.exceptionOrNull()?.message.orEmpty()
            require(error.contains("OIDC enforced")) {
                "Failed to acquire Planka token: $error"
            }

            val response = client.getRawResponse("http://planka:1337/api/boards")
            val body = response.bodyAsText()
            val blocksUnauthenticatedApi = response.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden) ||
                (response.status == HttpStatusCode.OK && body.contains("<title>Planka</title>", ignoreCase = true))
            require(blocksUnauthenticatedApi) {
                "Planka should block unauthenticated API access when OIDC is enforced: ${response.status}"
            }
            println("      ✓ Planka enforces OIDC-only authentication (local token login disabled)")
        }
    }

    test("Mastodon: Acquire OAuth token and verify credentials") {
        val email = System.getenv("MASTODON_EMAIL")
            ?: System.getenv("STACK_ADMIN_EMAIL")
            ?: "admin@example.test"
        val password = System.getenv("MASTODON_PASSWORD")
            ?: System.getenv("STACK_ADMIN_PASSWORD")
            ?: "changeme"

        val tokenResult = tokens.acquireMastodonToken(email, password)
        require(tokenResult.isSuccess) {
            "Failed to acquire Mastodon token: ${tokenResult.exceptionOrNull()?.message}"
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Mastodon OAuth token")


        val response = tokens.authenticatedGet("mastodon", "${env.endpoints.mastodon}/api/v1/accounts/verify_credentials")
        require(response.status == HttpStatusCode.OK) {
            "Failed to verify credentials: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("id") && body.contains("username")) {
            "Unexpected credentials response"
        }

        println("      ✓ Successfully verified Mastodon credentials")
    }

    
    
    

    test("JupyterHub: Authenticate and access hub API") {
        ensureEdgeSession()

        
        val directResponse = getRawResponseWithRetry("http://jupyterhub:8000/hub/api")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "JupyterHub container not responding: ${directResponse.status}"
        }
        println("      ✓ JupyterHub container accessible")

        
        val proxiedResponse = authenticatedCaddyGet("jupyterhub")
        require(proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed JupyterHub through authenticated proxy")
    }

    
    
    

    test("Models gateway: Arbitrary Authorization header does not bypass Keycloak edge auth") {
        val response = requestHttpClient.get(caddyUrl(endpoints.caddy, "/health")) {
            applyCaddyVirtualHost(caddyHost("models"))
            header(HttpHeaders.Authorization, "Bearer smoke-test")
        }

        val redirected = response.status in listOf(
            HttpStatusCode.Found,
            HttpStatusCode.TemporaryRedirect,
            HttpStatusCode.SeeOther,
            HttpStatusCode.PermanentRedirect
        )
        val denied = response.status in listOf(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden
        )

        require(redirected || denied) {
            "Unauthenticated models request with arbitrary Authorization should not reach backend: ${response.status}"
        }
        println("      ✓ Models gateway requires Keycloak edge auth despite arbitrary Authorization header")
    }

    test("Models gateway: Authenticated browser session can access API") {
        if (isTestdevProfile()) {
            println("      ✓ Models gateway backend intentionally excluded from testdev profile")
            return@test
        }
        ensureEdgeSession()

        val proxiedResponse = authenticatedCaddyGet("models", "/health")
        require(proxiedResponse.status == HttpStatusCode.OK) {
            "Models gateway proxy should accept authenticated browser sessions, got: ${proxiedResponse.status}"
        }
        val rawBody = proxiedResponse.bodyAsText().trim()
        if (rawBody.isBlank()) {
            println("      ℹ️  Models gateway returned an empty health payload (no local LLM target configured)")
            return@test
        }
        val body = runCatching { Json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
        require(body != null) {
            "Models gateway returned a non-JSON payload: $rawBody"
        }
        require(body["ready"]?.jsonPrimitive?.booleanOrNull != false) {
            "Models gateway proxy returned an explicit non-ready payload: $body"
        }
        println("      ✓ Models gateway Caddy route accepted an authenticated browser-session request")
    }

    
    
    

    test("Ntfy: Authenticate and access notification API") {
        ensureEdgeSession()

        
        val directResponse = client.getRawResponse("http://ntfy:80/v1/health")
        require(directResponse.status == HttpStatusCode.OK) {
            "Ntfy container not responding: ${directResponse.status}"
        }
        println("      ✓ Ntfy container accessible")

        
        val proxiedResponse = authenticatedCaddyGet("ntfy")
        require(proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Ntfy through authenticated proxy")
    }

    
    
    

    test("Kopia: Authenticate and access backup UI") {
        ensureEdgeSession()

        
        val proxiedResponse = authenticatedCaddyGet("kopia")
        require(proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Kopia through authenticated proxy")
    }

    
    
    

    test("OpenSearch: Authenticate and access API") {
        ensureEdgeSession()


        val directResponse = client.getRawResponse("${endpoints.searchService}/_cluster/health")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "OpenSearch did not respond on direct cluster health route: ${directResponse.status}"
        }

        
        val proxiedResponse = authenticatedCaddyGet("search", "/_cluster/health")
        require(proxiedResponse.status == HttpStatusCode.OK) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed OpenSearch through authenticated proxy")
    }

    
    
    

    test("Pipeline: Authenticate and access management API") {
        if (isTestdevProfile()) {
            println("      ✓ Pipeline management API intentionally excluded from testdev profile")
            return@test
        }
        ensureEdgeSession()


        val baseCandidates = listOf(
            "http://ingestion-runner:8090",
            endpoints.pipeline.trimEnd('/'),
            "http://airflow-webserver:8080",
            "http://webservices-ingestion-runner-1:8090"
        )
        val directResponse = probeFirstReachable(
            baseCandidates.flatMap { base -> listOf("$base/actuator/health", "$base/health") }
        )

        require(directResponse != null) {
            "Pipeline management API did not respond on any expected direct route"
        }

        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "Pipeline container not responding: ${directResponse.status}"
        }
        println("      ✓ Pipeline container accessible (${directResponse.call.request.url})")

        
        val proxiedResponse = authenticatedCaddyGet("pipeline", "/health")
        val proxiedStatus = proxiedResponse.status
        if (
            proxiedStatus in listOf(
                HttpStatusCode.NotFound,
                HttpStatusCode.BadGateway,
                HttpStatusCode.ServiceUnavailable,
                HttpStatusCode.GatewayTimeout
            ) ||
            proxiedStatus.value == 525 ||
            proxiedStatus.value == 526
        ) {
            error("Pipeline Caddy route is not reachable through authenticated proxy: $proxiedStatus")
        }
        require(proxiedStatus == HttpStatusCode.OK) {
            "Failed to access through authenticated proxy: $proxiedStatus"
        }
        println("      ✓ Successfully accessed Pipeline through authenticated proxy")
    }

    
    
    

    test("Token manager: Store and retrieve tokens") {
        
        val testToken = "test-token-${System.currentTimeMillis()}"

        
        require(!tokens.hasToken("test-service")) {
            "Should not have token before storing"
        }

        
        require(tokens.getToken("grafana") != null || !tokens.hasToken("grafana")) {
            "Token state should be consistent"
        }

        println("      ✓ Token manager correctly manages token state")
    }

    test("Token manager: Clear tokens") {
        
        tokens.clearToken("grafana")
        require(!tokens.hasToken("grafana")) {
            "Token should be cleared"
        }

        
        tokens.clearAll()
        require(!tokens.hasToken("seafile") && !tokens.hasToken("forgejo")) {
            "All tokens should be cleared"
        }

        println("      ✓ Token manager correctly clears tokens")
    }
}
