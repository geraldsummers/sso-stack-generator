package org.webservices.testrunner.framework

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

private val internalApiToken: String? by lazy {
    sequenceOf(
        "INFERENCE_CONTROLLER_API_TOKEN",
        "INFERENCE_GATEWAY_INTERNAL_API_TOKEN",
        "GPU_ARBITER_API_TOKEN",
        "MODEL_CONTEXT_PROXY_AUTH_SECRET"
    )
        .mapNotNull { System.getenv(it)?.trim() }
        .firstOrNull { it.isNotEmpty() }
}

fun HttpRequestBuilder.applyInternalApiAuthHeaders() {
    val token = internalApiToken ?: return
    header("X-Internal-Api-Token", token)
    header("X-Trusted-Proxy-Secret", token)
    header(HttpHeaders.Authorization, "Bearer $token")
}
