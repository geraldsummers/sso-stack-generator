package org.webservices.inferencegateway

class InferenceGatewayService(
    internal val config: InferenceGatewayConfig,
    private val controllerStatusClient: InferenceControllerStatusClient
) {
    private fun llmBackend(serviceName: String, baseUrl: String, defaultModel: String, supportedModels: Set<String>) =
        GatewayBackend(
            serviceName = serviceName,
            role = InferenceRole.LLM,
            baseUrl = baseUrl,
            healthPath = "/v1/models",
            supportedModels = supportedModels,
            defaultModel = defaultModel
        )

    private val backends = listOf(
        llmBackend("llm-cpu-fallback", config.llmCpuBaseUrl, config.llmCpuDefaultModel, config.llmCpuModels),
        llmBackend("vllm-gpu", config.llmGpuBaseUrl, config.llmGpuDefaultModel, config.llmGpuModels),
        GatewayBackend("embedding-cpu", InferenceRole.EMBEDDING, config.embeddingCpuBaseUrl),
        GatewayBackend("embedding-gpu", InferenceRole.EMBEDDING, config.embeddingGpuBaseUrl)
    ).associateBy { it.serviceName }

    suspend fun currentStatus(): GatewayStatus {
        return runCatching { controllerStatusClient.fetch() }
            .fold(
                onSuccess = { controller ->
                    if (controller == null) {
                        GatewayStatus(
                            status = "unavailable",
                            ready = false,
                            controllerReachable = false,
                            controllerReady = false,
                            targetsReady = false,
                            transitioning = false,
                            lastError = "controller status unavailable"
                        )
                    } else {
                        GatewayStatus(
                            status = when {
                                controller.ready -> "ok"
                                controller.transitioning -> "transitioning"
                                else -> "degraded"
                            },
                            ready = controller.ready,
                            controllerReachable = true,
                            controllerReady = controller.ready,
                            targetsReady = controller.targetsReady,
                            transitioning = controller.transitioning,
                            activeMode = controller.mode,
                            llmTarget = controller.llmTarget,
                            embeddingTarget = controller.embeddingTarget,
                            lastError = controller.lastError
                        )
                    }
                },
                onFailure = { error ->
                    GatewayStatus(
                        status = "unavailable",
                        ready = false,
                        controllerReachable = false,
                        controllerReady = false,
                        targetsReady = false,
                        transitioning = false,
                        lastError = error.message ?: "controller status request failed"
                    )
                }
            )
    }

    suspend fun resolveTarget(role: InferenceRole, requestedModel: String? = null): ResolvedGatewayTarget {
        val controller = controllerStatusClient.fetch()
            ?: error("controller status unavailable")
        val serviceName = when (role) {
            InferenceRole.LLM -> selectLlmServiceName(controller, requestedModel)
            InferenceRole.EMBEDDING -> controller.embeddingTarget
        }
        val backend = backends[serviceName] ?: error("unknown backend '$serviceName'")
        require(isBackendReady(controller, serviceName)) {
            "$serviceName is not ready"
        }
        return ResolvedGatewayTarget(backend.serviceName, backend.baseUrl, backend.healthPath, backend.defaultModel)
    }

    fun resolveDirectTarget(serviceName: String): ResolvedGatewayTarget {
        val backend = backends[serviceName] ?: error("unknown backend '$serviceName'")
        return ResolvedGatewayTarget(backend.serviceName, backend.baseUrl, backend.healthPath, backend.defaultModel)
    }

    suspend fun resolveHealthyTargets(role: InferenceRole): List<ResolvedGatewayTarget> {
        val controller = controllerStatusClient.fetch()
            ?: error("controller status unavailable")
        val selectedServiceName = when (role) {
            InferenceRole.LLM -> controller.llmTarget
            InferenceRole.EMBEDDING -> controller.embeddingTarget
        }
        return backends.values
            .filter { it.role == role }
            .filter { isBackendReady(controller, it.serviceName) }
            .sortedWith(compareBy<GatewayBackend>({ if (it.serviceName == selectedServiceName) 0 else 1 }, { backendPriority(it) }))
            .map { ResolvedGatewayTarget(it.serviceName, it.baseUrl, it.healthPath, it.defaultModel) }
    }

    private fun selectLlmServiceName(controller: InferenceControllerStatusPayload, requestedModel: String?): String {
        val selectedService = controller.llmTarget
        val normalizedModel = requestedModel?.trim()?.lowercase().orEmpty()
        if (normalizedModel.isBlank()) {
            return selectedService
        }

        val selectedBackend = backends[selectedService]
        if (selectedBackend?.supportedModels?.contains(normalizedModel) == true) {
            return selectedService
        }

        val matchingBackend = backends.values.firstOrNull { backend ->
            backend.role == InferenceRole.LLM &&
                backend.supportedModels.contains(normalizedModel) &&
                isBackendReady(controller, backend.serviceName)
        }
        return matchingBackend?.serviceName ?: selectedService
    }

    private fun isBackendReady(controller: InferenceControllerStatusPayload, serviceName: String): Boolean {
        val backendStatus = controller.backends.firstOrNull { it.serviceName == serviceName }
        return backendStatus?.running == true && backendStatus.healthy
    }

    private fun backendPriority(backend: GatewayBackend): Int {
        return when (backend.serviceName) {
            "vllm-gpu" -> 0
            "llm-cpu-fallback" -> 1
            "embedding-gpu" -> 2
            "embedding-cpu" -> 3
            else -> 100
        }
    }
}
