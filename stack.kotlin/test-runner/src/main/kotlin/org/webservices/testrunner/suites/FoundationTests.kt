package org.webservices.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webservices.testrunner.framework.*
import java.util.Base64

suspend fun TestRunner.ensureInferenceMode(
    mode: String,
    note: String,
    attempts: Int = 24,
    delayMs: Long = 5000
): Pair<kotlinx.serialization.json.JsonObject, String> {
    val currentBody = awaitStatusBody(
        label = "inference-controller status",
        url = "http://inference-controller:8110/api/status"
    )
    val currentStatus = parseStatus(currentBody)
    val currentMode = currentStatus["mode"]?.jsonPrimitive?.contentOrNull

    if (currentMode != mode) {
        val response = requestHttpClient.put("http://inference-controller:8110/api/mode") {
            applyInternalApiAuthHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"mode":"$mode","note":"$note"}""")
        }
        val body = response.bodyAsText()
        require(response.status == HttpStatusCode.Accepted) {
            "unable to set inference controller mode to $mode: status=${response.status} body=$body"
        }
    }

    return awaitSelectedTargetsHealthyStatus(
        label = "inference-controller status",
        url = "http://inference-controller:8110/api/status",
        attempts = attempts,
        delayMs = delayMs
    )
}

suspend fun TestRunner.awaitStatusBody(
    label: String,
    url: String,
    attempts: Int = 3,
    requestTimeoutMs: Long = 7_500L,
    delayMs: Long = 2000
): String {
    var lastStatus: HttpStatusCode? = null
    var lastError: String? = null

    repeat(attempts) { attempt ->
        try {
            val response = withTimeout(requestTimeoutMs) {
                requestHttpClient.get(url) {
                    applyInternalApiAuthHeaders()
                }
            }
            if (response.status == HttpStatusCode.OK) {
                return response.bodyAsText()
            }
            lastStatus = response.status
        } catch (e: Exception) {
            lastError = e.message
        }

        if (attempt == 0) {
            println("      ℹ️  Waiting for $label...")
        }
        if (attempt < attempts - 1) {
            delay(delayMs)
        }
    }

    error("$label did not return status (lastStatus=$lastStatus, lastError=$lastError)")
}

suspend fun TestRunner.awaitRetryableResponse(
    label: String,
    attempts: Int = 12,
    delayMs: Long = 2000,
    request: suspend () -> HttpResponse,
    accept: (HttpResponse) -> Boolean
): HttpResponse {
    var lastStatus: HttpStatusCode? = null
    var lastError: String? = null

    repeat(attempts) { attempt ->
        try {
            val response = request()
            if (accept(response)) {
                return response
            }

            lastStatus = response.status
            val retryableStatus = response.status in setOf(
                HttpStatusCode.NotFound,
                HttpStatusCode.BadGateway,
                HttpStatusCode.ServiceUnavailable,
                HttpStatusCode.GatewayTimeout
            )
            if (!retryableStatus || attempt == attempts - 1) {
                return response
            }
        } catch (e: Exception) {
            lastError = e.message
            val message = e.message.orEmpty()
            val retryable = message.contains("Connection refused", ignoreCase = true) ||
                message.contains("ConnectException", ignoreCase = true) ||
                message.contains("Failed to connect", ignoreCase = true) ||
                message.contains("UnresolvedAddressException", ignoreCase = true) ||
                message.contains("Unresolved address", ignoreCase = true)
            if (!retryable || attempt == attempts - 1) {
                throw e
            }
        }

        if (attempt == 0) {
            println("      ℹ️  Waiting for retryable dependency to become reachable for $label...")
        }
        delay(delayMs)
    }

    error("Dependency did not return the expected response for $label (lastStatus=$lastStatus, lastError=$lastError)")
}

fun parseStatus(statusBody: String) = Json.parseToJsonElement(statusBody).jsonObject

fun selectedBackendStatuses(status: kotlinx.serialization.json.JsonObject, statusBody: String): Pair<String, String> {
    val llmTarget = status["llmTarget"]?.jsonPrimitive?.contentOrNull
    val embeddingTarget = status["embeddingTarget"]?.jsonPrimitive?.contentOrNull
    require(!llmTarget.isNullOrBlank()) { "Inference controller status missing llmTarget: $statusBody" }
    require(!embeddingTarget.isNullOrBlank()) { "Inference controller status missing embeddingTarget: $statusBody" }
    return llmTarget to embeddingTarget
}

fun assertSelectedTargetsHealthy(status: kotlinx.serialization.json.JsonObject, statusBody: String) {
    val (llmTarget, embeddingTarget) = selectedBackendStatuses(status, statusBody)
    val targetsReady = status["targetsReady"]?.jsonPrimitive?.booleanOrNull ?: false
    val backends = status["backends"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    require(targetsReady) { "Inference controller selected targets are not ready: $statusBody" }

    fun backendStatus(serviceName: String) =
        backends.firstOrNull { it["serviceName"]?.jsonPrimitive?.contentOrNull == serviceName }
            ?: error("Inference controller status missing backend '$serviceName': $statusBody")

    fun assertHealthyTarget(serviceName: String, label: String) {
        val backend = backendStatus(serviceName)
        val running = backend["running"]?.jsonPrimitive?.booleanOrNull ?: false
        val healthy = backend["healthy"]?.jsonPrimitive?.booleanOrNull ?: false
        val activeState = backend["activeState"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val subState = backend["subState"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        require(running) { "$label target '$serviceName' is not running (active=$activeState sub=$subState)" }
        require(healthy) { "$label target '$serviceName' is unhealthy (active=$activeState sub=$subState)" }
    }

    assertHealthyTarget(llmTarget, "LLM")
    assertHealthyTarget(embeddingTarget, "Embedding")
}

suspend fun TestRunner.awaitSelectedTargetsHealthyStatus(
    label: String,
    url: String,
    attempts: Int = 12,
    delayMs: Long = 5000
): Pair<kotlinx.serialization.json.JsonObject, String> {
    var lastFailure = "status not fetched"

    repeat(attempts) { attempt ->
        val statusBody = awaitStatusBody(label = label, url = url)
        val status = parseStatus(statusBody)
        val validation = runCatching { assertSelectedTargetsHealthy(status, statusBody) }
        if (validation.isSuccess) {
            return status to statusBody
        }

        lastFailure = validation.exceptionOrNull()?.message ?: "selected targets are not healthy"
        if (attempt == 0) {
            println("      ℹ️  Waiting for controller-selected targets to become healthy...")
        }
        if (attempt < attempts - 1) {
            delay(delayMs)
        }
    }

    error("Inference controller selected targets did not become healthy: $lastFailure")
}

suspend fun TestRunner.foundationTests() = suite("Foundation Tests") {
    test("OpenSearch is healthy") {
        val health = client.healthCheck("opensearch")
        health.healthy shouldBe true
    }

    test("OpenSearch returns 404 for unknown exact document lookup") {
        val response = requestHttpClient.get("${endpoints.searchService}/knowledge/_doc/definitely-missing-document") {
            val username = System.getenv("OPENSEARCH_USERNAME") ?: "admin"
            val password = System.getenv("OPENSEARCH_PASSWORD")
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("OPENSEARCH_ADMIN_PASSWORD").orEmpty()
            if (password.isNotBlank()) {
                val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                header(HttpHeaders.Authorization, "Basic $encoded")
            }
        }
        response.status shouldBe HttpStatusCode.NotFound
    }

    test("Workspace provisioner health endpoint responds") {
        val response = requestHttpClient.get("http://workspace-provisioner:8120/health")
        response.status shouldBe HttpStatusCode.OK

        val body = response.bodyAsText()
        body shouldContain "\"status\""
        body shouldContain "ok"
    }

    test("Workspace provisioner ready endpoint reports usable state") {
        val response = requestHttpClient.get("http://workspace-provisioner:8120/ready")
        response.status shouldBe HttpStatusCode.OK

        val statusBody = response.bodyAsText()
        val status = parseStatus(statusBody)
        val overall = status["status"]?.jsonPrimitive?.contentOrNull
        val workspaces = status["workspaces"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        require(overall == "ok") { "Workspace provisioner ready endpoint is not healthy: $statusBody" }
        require(workspaces != null && workspaces >= 0) {
            "Workspace provisioner ready endpoint returned invalid workspace count: $statusBody"
        }
    }

    test("Workspace provisioner OIDC discovery is publicly readable") {
        val response = requestHttpClient.get("http://workspace-provisioner:8120/api/oidc/discovery")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "\"authorization_endpoint\""
        body shouldContain "\"token_endpoint\""
        body shouldContain "\"client_id\""
    }

    test("Workspace provisioner rejects unauthenticated workspace listing") {
        val response = requestHttpClient.get("http://workspace-provisioner:8120/api/workspaces")
        response.status shouldBe HttpStatusCode.Unauthorized
    }

    test("Embedding backend health endpoint responds") {
        if (System.getenv("TESTDEV_SKIP_GPU_INGESTION") == "1") {
            println("      ✓ Embedding backend intentionally excluded from testdev profile")
            return@test
        }
        val embeddingReadiness = probeEmbeddingBackendReadiness(
            maxAttempts = 2,
            attemptTimeoutMs = 20_000L,
            retryDelayMs = 2_000L
        )
        require(embeddingReadiness.ready) {
            embeddingReadiness.reason ?: "Embedding backend is unavailable"
        }
        val response = requestHttpClient.get("http://embedding-gpu:8080/health")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Caddy exports its local CA bundle for dependent services") {
        val caddyContainer = System.getenv("CADDY_CONTAINER") ?: "caddy"
        val result = DockerCli.run(
            "exec", caddyContainer, "sh", "-lc", "test -s /ca/caddy-ca.crt"
        )
        require(result.exitCode == 0) {
            "Caddy did not export a readable local CA bundle at /ca/caddy-ca.crt: ${result.output}"
        }
        println("      ✓ Caddy exported a readable local CA bundle")
    }
}
