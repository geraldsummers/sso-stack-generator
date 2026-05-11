package org.webservices.inferencecontroller

import kotlinx.serialization.Serializable

@Serializable
enum class InferenceMode {
    CPU_DEFAULT,
    GPU_LLM,
    GPU_EMBEDDING
}

@Serializable
data class InferenceControllerState(
    val mode: InferenceMode = InferenceMode.CPU_DEFAULT,
    val modeChangedAt: String? = null,
    val note: String? = null
)

data class ManagedBackend(
    val serviceName: String,
    val containerName: String,
    val role: String,
    val mode: String,
    val unitName: String,
    val baseUrl: String,
    val healthPath: String,
    val expectedModel: String? = null
)

@Serializable
data class BackendStatus(
    val serviceName: String,
    val containerName: String,
    val role: String,
    val mode: String,
    val unitName: String,
    val activeState: String,
    val subState: String,
    val running: Boolean,
    val healthy: Boolean,
    val reachable: Boolean,
    val expectedModel: String? = null,
    val error: String? = null
)

@Serializable
data class InferenceControllerStatus(
    val mode: InferenceMode,
    val llmTarget: String,
    val embeddingTarget: String,
    val state: InferenceControllerState,
    val backends: List<BackendStatus>,
    val targetsReady: Boolean,
    val ready: Boolean,
    val transitioning: Boolean,
    val lastEvaluatedAt: String? = null,
    val lastError: String? = null
)

@Serializable
data class SetModeRequest(
    val mode: String,
    val note: String? = null
)

val managedBackends = listOf(
    ManagedBackend(
        serviceName = "llm-cpu-fallback",
        containerName = "llm-cpu-fallback",
        role = "llm",
        mode = "cpu",
        unitName = "webservices-llm-cpu-fallback.service",
        baseUrl = "http://llm-cpu-fallback:11434",
        healthPath = "/api/tags",
        expectedModel = "webservices-qwen2.5-coder-14b-cpu"
    ),
    ManagedBackend(
        serviceName = "vllm-gpu",
        containerName = "vllm-gpu",
        role = "llm",
        mode = "gpu",
        unitName = "webservices-vllm-gpu.service",
        baseUrl = "http://vllm-gpu:11434",
        healthPath = "/api/tags",
        expectedModel = "webservices-qwen2.5-coder-14b-gpu"
    ),
    ManagedBackend(
        serviceName = "embedding-cpu",
        containerName = "embedding-cpu",
        role = "embedding",
        mode = "cpu",
        unitName = "webservices-embedding-cpu.service",
        baseUrl = "http://embedding-cpu:8080",
        healthPath = "/health"
    ),
    ManagedBackend(
        serviceName = "embedding-gpu",
        containerName = "embedding-gpu",
        role = "embedding",
        mode = "gpu",
        unitName = "webservices-embedding-gpu.service",
        baseUrl = "http://embedding-gpu:8080",
        healthPath = "/health"
    )
)

fun managedBackend(serviceName: String): ManagedBackend = managedBackends.first { it.serviceName == serviceName }

fun llmTargetFor(mode: InferenceMode): ManagedBackend = when (mode) {
    InferenceMode.CPU_DEFAULT, InferenceMode.GPU_EMBEDDING -> managedBackend("llm-cpu-fallback")
    InferenceMode.GPU_LLM -> managedBackend("vllm-gpu")
}

fun embeddingTargetFor(mode: InferenceMode): ManagedBackend = when (mode) {
    InferenceMode.CPU_DEFAULT, InferenceMode.GPU_LLM -> managedBackend("embedding-cpu")
    InferenceMode.GPU_EMBEDDING -> managedBackend("embedding-gpu")
}

fun desiredRunningBackends(mode: InferenceMode): Set<String> = buildSet {
    when (mode) {
        InferenceMode.CPU_DEFAULT -> {
            add("llm-cpu-fallback")
            add("embedding-cpu")
        }
        InferenceMode.GPU_LLM -> {
            add("vllm-gpu")
            add("embedding-cpu")
        }
        InferenceMode.GPU_EMBEDDING -> {
            add("llm-cpu-fallback")
            add("embedding-gpu")
        }
    }
}
