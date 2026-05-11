package org.webservices.inferencegateway

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
private fun envOptional(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
private fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
private fun envLong(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default
private fun envBoolean(name: String, default: Boolean = false): Boolean =
    when (System.getenv(name)?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
    }
private fun envCsv(name: String, default: String): Set<String> =
    env(name, default)
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

fun loadConfig(): InferenceGatewayConfig {
    val llmCpuDefaultModel = env("INFERENCE_GATEWAY_LLM_CPU_DEFAULT_MODEL", "webservices-qwen2.5-coder-14b-cpu")
    val llmGpuDefaultModel = env("INFERENCE_GATEWAY_LLM_GPU_DEFAULT_MODEL", "webservices-qwen2.5-coder-14b-gpu")
    val sharedToken = envOptional("INFERENCE_GATEWAY_INTERNAL_API_TOKEN")
        ?: envOptional("MODEL_CONTEXT_PROXY_AUTH_SECRET")
    return InferenceGatewayConfig(
        port = envInt("INFERENCE_GATEWAY_PORT", 8111),
        controllerStatusUrl = env("INFERENCE_GATEWAY_CONTROLLER_STATUS_URL", "http://inference-controller:8110/api/status"),
        controllerApiToken = envOptional("INFERENCE_GATEWAY_CONTROLLER_API_TOKEN") ?: sharedToken,
        internalApiToken = sharedToken,
        allowUnauthenticatedInternalApi = envBoolean("INFERENCE_GATEWAY_ALLOW_UNAUTHENTICATED_INTERNAL_API"),
        controllerTimeoutMs = envLong("INFERENCE_GATEWAY_CONTROLLER_TIMEOUT_MS", 10000),
        llmCpuBaseUrl = env("INFERENCE_GATEWAY_LLM_CPU_BASE_URL", "http://llm-cpu-fallback:11434"),
        llmGpuBaseUrl = env("INFERENCE_GATEWAY_LLM_GPU_BASE_URL", "http://vllm-gpu:11434"),
        llmCpuDefaultModel = llmCpuDefaultModel,
        llmGpuDefaultModel = llmGpuDefaultModel,
        llmCpuModels = envCsv(
            "INFERENCE_GATEWAY_LLM_CPU_MODELS",
            "$llmCpuDefaultModel,webservices-qwen2.5-coder-14b,qwen2.5-coder:14b"
        ),
        llmGpuModels = envCsv(
            "INFERENCE_GATEWAY_LLM_GPU_MODELS",
            "$llmGpuDefaultModel,webservices-qwen2.5-coder-14b,qwen2.5-coder:14b"
        ),
        embeddingCpuBaseUrl = env("INFERENCE_GATEWAY_EMBEDDING_CPU_BASE_URL", "http://embedding-cpu:8080"),
        embeddingGpuBaseUrl = env("INFERENCE_GATEWAY_EMBEDDING_GPU_BASE_URL", "http://embedding-gpu:8080")
    )
}

data class InferenceGatewayConfig(
    val port: Int,
    val controllerStatusUrl: String,
    val controllerApiToken: String?,
    val internalApiToken: String?,
    val allowUnauthenticatedInternalApi: Boolean = false,
    val controllerTimeoutMs: Long,
    val llmCpuBaseUrl: String,
    val llmGpuBaseUrl: String,
    val llmCpuDefaultModel: String,
    val llmGpuDefaultModel: String,
    val llmCpuModels: Set<String>,
    val llmGpuModels: Set<String>,
    val embeddingCpuBaseUrl: String,
    val embeddingGpuBaseUrl: String
)
