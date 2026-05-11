package org.webservices.inferencegateway

import kotlinx.serialization.Serializable

@Serializable
data class GatewayStatus(
    val status: String,
    val ready: Boolean,
    val controllerReachable: Boolean,
    val controllerReady: Boolean,
    val targetsReady: Boolean,
    val transitioning: Boolean,
    val activeMode: String? = null,
    val llmTarget: String? = null,
    val embeddingTarget: String? = null,
    val lastError: String? = null
)

@Serializable
data class InferenceControllerBackendStatusPayload(
    val serviceName: String,
    val running: Boolean,
    val healthy: Boolean,
    val activeState: String = "unknown",
    val subState: String = "unknown"
)

@Serializable
data class InferenceControllerStatePayload(
    val mode: String
)

@Serializable
data class InferenceControllerStatusPayload(
    val mode: String,
    val llmTarget: String,
    val embeddingTarget: String,
    val state: InferenceControllerStatePayload? = null,
    val backends: List<InferenceControllerBackendStatusPayload> = emptyList(),
    val targetsReady: Boolean,
    val ready: Boolean,
    val transitioning: Boolean = false,
    val lastEvaluatedAt: String? = null,
    val lastError: String? = null
)

enum class InferenceRole {
    LLM,
    EMBEDDING
}

data class GatewayBackend(
    val serviceName: String,
    val role: InferenceRole,
    val baseUrl: String,
    val healthPath: String = "/health",
    val supportedModels: Set<String> = emptySet(),
    val defaultModel: String? = null
)

data class ResolvedGatewayTarget(
    val serviceName: String,
    val baseUrl: String,
    val healthPath: String,
    val defaultModel: String? = null
)
