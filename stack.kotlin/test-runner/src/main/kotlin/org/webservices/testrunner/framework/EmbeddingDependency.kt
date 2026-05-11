package org.webservices.testrunner.framework

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class DependencyReadiness(
    val ready: Boolean,
    val reason: String? = null
)

private val embeddingFailureMarkers = listOf(
    "429",
    "overloaded",
    "timeout",
    "timed out",
    "socket timeout",
    "service unavailable",
    "external service unavailable",
    "vector-store",
    "embedding service error",
    "failed to generate embedding",
    "circuit_breaker_open",
    "upstream unavailable",
    "connection refused",
    "connection error",
    "connectexception",
    "bad gateway",
    "gateway timeout",
    "request timeout"
)

fun isKnownEmbeddingDependencyFailure(message: String?): Boolean {
    val lowered = message?.lowercase()?.trim().orEmpty()
    if (lowered.isBlank()) return false
    return embeddingFailureMarkers.any { lowered.contains(it) }
}

fun compactDependencyMessage(message: String?, maxLength: Int = 220): String {
    val normalized = message
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
    if (normalized.length <= maxLength) {
        return normalized
    }
    return normalized.take(maxLength - 3) + "..."
}

suspend fun TestRunner.probeEmbeddingBackendReadiness(
    maxAttempts: Int = 2,
    attemptTimeoutMs: Long = 60_000L,
    retryDelayMs: Long = 5_000L
): DependencyReadiness {
    var lastReason = "embedding readiness probe did not return a vector"
    var sawDependencyFailure = false

    repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
        val result = try {
            withTimeout(attemptTimeoutMs) {
                client.callTool(
                    "llm_embed_text",
                    mapOf(
                        "text" to "embedding readiness probe",
                        "model" to "bge-m3"
                    )
                )
            }
        } catch (e: Exception) {
            ToolResult.Error(-1, e.message ?: "embedding probe timed out")
        }

        when (result) {
            is ToolResult.Success -> {
                if (result.output.contains("[") && result.output.contains("]")) {
                    return DependencyReadiness(ready = true)
                }
                lastReason = "unexpected embedding payload: ${compactDependencyMessage(result.output)}"
            }
            is ToolResult.Error -> {
                val message = compactDependencyMessage(result.message)
                lastReason = if (result.statusCode > 0) {
                    "status=${result.statusCode} $message"
                } else {
                    message
                }
                sawDependencyFailure = sawDependencyFailure || isKnownEmbeddingDependencyFailure(result.message)
            }
        }

        if (attempt < maxAttempts - 1) {
            delay(retryDelayMs)
        }
    }

    return DependencyReadiness(
        ready = false,
        reason = if (sawDependencyFailure) {
            "embedding backend unavailable: $lastReason"
        } else {
            "embedding readiness probe failed: $lastReason"
        }
    )
}

fun explainEmbeddingBackedSearchFailure(status: HttpStatusCode, body: String): String? {
    val compactBody = compactDependencyMessage(body)
    val lowered = compactBody.lowercase()
    val timeoutStatus = status == HttpStatusCode.BadGateway ||
        status == HttpStatusCode.GatewayTimeout ||
        status == HttpStatusCode.ServiceUnavailable ||
        status == HttpStatusCode.TooManyRequests

    if (timeoutStatus || isKnownEmbeddingDependencyFailure(compactBody)) {
        return "embedding-backed search unavailable: status=${status.value} body=$compactBody"
    }

    if (status == HttpStatusCode.InternalServerError &&
        (lowered.contains("embedding") || lowered.contains("vector") || lowered.contains("overloaded"))
    ) {
        return "embedding-backed search unavailable: status=${status.value} body=$compactBody"
    }

    return null
}
