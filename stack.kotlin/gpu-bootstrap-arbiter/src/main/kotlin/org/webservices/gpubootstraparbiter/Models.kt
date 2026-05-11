package org.webservices.gpubootstraparbiter

import kotlinx.serialization.Serializable

@Serializable
enum class ArbiterMode {
    EMBEDDING_PRIORITY,
    LLM_PRIORITY
}

@Serializable
data class ArbiterState(
    val mode: ArbiterMode = ArbiterMode.EMBEDDING_PRIORITY,
    val modeChangedAt: String? = null,
    val note: String? = null
)

data class ManagedBackend(
    val serviceName: String,
    val containerName: String,
    val role: String,
    val mode: String,
    val baseUrl: String,
    val healthPath: String = "/health",
    val profiles: List<String> = emptyList()
)

@Serializable
data class PromotionDecision(
    val eligible: Boolean,
    val targetMode: ArbiterMode,
    val reasons: List<String>,
    val totalPendingEmbedding: Long,
    val totalInProgressEmbedding: Long,
    val incompleteSources: List<String>,
    val backlogHigh: Boolean,
    val backlogLow: Boolean
)

@Serializable
data class BackendStatus(
    val serviceName: String,
    val containerName: String,
    val role: String,
    val mode: String,
    val state: String,
    val running: Boolean,
    val healthy: Boolean,
    val managed: Boolean,
    val error: String? = null
)

@Serializable
data class ArbiterStatus(
    val mode: ArbiterMode,
    val llmTarget: String,
    val embeddingTarget: String,
    val state: ArbiterState,
    val decision: PromotionDecision,
    val backends: List<BackendStatus>,
    val targetsReady: Boolean,
    val decisionInputsHealthy: Boolean,
    val ready: Boolean,
    val signalUrl: String,
    val lastEvaluatedAt: String? = null,
    val lastError: String? = null
)

@Serializable
data class WorkloadSignalSnapshot(
    val decisionInputsHealthy: Boolean,
    val initialBuildIncomplete: Boolean,
    val totalPendingEmbedding: Long,
    val totalInProgressEmbedding: Long,
    val incompleteSources: List<String>,
    val lastEvaluatedAt: String? = null,
    val lastError: String? = null
)

val managedBackends = listOf(
    ManagedBackend(
        serviceName = "llm-cpu-fallback",
        containerName = "llm-cpu-fallback",
        role = "llm",
        mode = "cpu",
        baseUrl = "http://llm-cpu-fallback:11434",
        healthPath = "/api/tags",
        profiles = listOf("bootstrap-llm")
    ),
    ManagedBackend(
        serviceName = "vllm-gpu",
        containerName = "vllm-gpu",
        role = "llm",
        mode = "gpu",
        baseUrl = "http://vllm-gpu:11434",
        healthPath = "/api/tags",
        profiles = listOf("gpu-llm")
    ),
    ManagedBackend(
        serviceName = "embedding-gpu",
        containerName = "embedding-gpu",
        role = "embedding",
        mode = "gpu",
        baseUrl = "http://embedding-gpu:8080",
        profiles = listOf("gpu-embedding")
    ),
    ManagedBackend(
        serviceName = "embedding-cpu",
        containerName = "embedding-cpu",
        role = "embedding",
        mode = "cpu",
        baseUrl = "http://embedding-cpu:8080"
    )
)

fun managedBackend(serviceName: String): ManagedBackend =
    managedBackends.first { it.serviceName == serviceName }

fun llmBackendFor(mode: ArbiterMode): ManagedBackend = when (mode) {
    ArbiterMode.EMBEDDING_PRIORITY -> managedBackends.first { it.serviceName == "llm-cpu-fallback" }
    ArbiterMode.LLM_PRIORITY -> managedBackends.first { it.serviceName == "vllm-gpu" }
}

fun embeddingBackendFor(mode: ArbiterMode): ManagedBackend = when (mode) {
    ArbiterMode.EMBEDDING_PRIORITY -> managedBackends.first { it.serviceName == "embedding-gpu" }
    ArbiterMode.LLM_PRIORITY -> managedBackends.first { it.serviceName == "embedding-cpu" }
}
