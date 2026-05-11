package org.webservices.inferencegateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InferenceGatewayServiceTest {
    @Test
    fun `reports ready when controller exposes healthy targets`() = runBlocking {
        val service = service(
            controller = controllerStatus(
                llmTarget = "vllm-gpu",
                embeddingTarget = "embedding-cpu",
                mode = "gpu_llm"
            )
        )

        val status = service.currentStatus()

        assertEquals("ok", status.status)
        assertTrue(status.ready)
        assertTrue(status.controllerReachable)
        assertEquals("vllm-gpu", status.llmTarget)
        assertEquals("embedding-cpu", status.embeddingTarget)
    }

    @Test
    fun `resolves target base url from controller selection`() = runBlocking {
        val service = service(
            controller = controllerStatus(
                llmTarget = "llm-cpu-fallback",
                embeddingTarget = "embedding-gpu",
                mode = "gpu_embedding",
                backends = listOf(
                    backend("llm-cpu-fallback"),
                    backend("embedding-gpu")
                )
            )
        )

        val llm = service.resolveTarget(InferenceRole.LLM)
        val embedding = service.resolveTarget(InferenceRole.EMBEDDING)

        assertEquals("http://llm-cpu-fallback:11434", llm.baseUrl)
        assertEquals("/v1/models", llm.healthPath)
        assertEquals("webservices-qwen2.5-coder-14b-cpu", llm.defaultModel)
        assertEquals("http://embedding-gpu:8080", embedding.baseUrl)
        assertEquals("/health", embedding.healthPath)
        assertEquals(null, embedding.defaultModel)
    }

    @Test
    fun `resolves direct target without consulting controller`() = runBlocking {
        val service = InferenceGatewayService(
            config = config(),
            controllerStatusClient = object : InferenceControllerStatusClient {
                override suspend fun fetch(): InferenceControllerStatusPayload {
                    error("controller should not be called")
                }
            }
        )

        val directCpu = service.resolveDirectTarget("llm-cpu-fallback")
        val directGpu = service.resolveDirectTarget("vllm-gpu")

        assertEquals("http://llm-cpu-fallback:11434", directCpu.baseUrl)
        assertEquals("/v1/models", directCpu.healthPath)
        assertEquals("webservices-qwen2.5-coder-14b-cpu", directCpu.defaultModel)
        assertEquals("http://vllm-gpu:11434", directGpu.baseUrl)
        assertEquals("/v1/models", directGpu.healthPath)
        assertEquals("webservices-qwen2.5-coder-14b-gpu", directGpu.defaultModel)
    }

    @Test
    fun `routes requested gpu model to healthy gpu backend even when controller defaults to cpu llm`() = runBlocking {
        val service = service(
            controller = controllerStatus(
                llmTarget = "llm-cpu-fallback",
                embeddingTarget = "embedding-cpu",
                backends = listOf(
                    backend("llm-cpu-fallback"),
                    backend("vllm-gpu"),
                    backend("embedding-cpu")
                )
            )
        )

        val llm = service.resolveTarget(InferenceRole.LLM, requestedModel = "webservices-qwen2.5-coder-14b-gpu")

        assertEquals("vllm-gpu", llm.serviceName)
        assertEquals("http://vllm-gpu:11434", llm.baseUrl)
        assertEquals("webservices-qwen2.5-coder-14b-gpu", llm.defaultModel)
    }

    @Test
    fun `keeps selected llm when requested model is generic`() = runBlocking {
        val service = service(
            controller = controllerStatus(
                llmTarget = "llm-cpu-fallback",
                embeddingTarget = "embedding-cpu",
                backends = listOf(
                    backend("llm-cpu-fallback"),
                    backend("vllm-gpu"),
                    backend("embedding-cpu")
                )
            )
        )

        val llm = service.resolveTarget(InferenceRole.LLM, requestedModel = "webservices-qwen2.5-coder-14b")

        assertEquals("llm-cpu-fallback", llm.serviceName)
        assertEquals("http://llm-cpu-fallback:11434", llm.baseUrl)
        assertEquals("webservices-qwen2.5-coder-14b-cpu", llm.defaultModel)
    }

    @Test
    fun `lists healthy llm backends with selected backend first`() = runBlocking {
        val service = service(
            controller = controllerStatus(
                llmTarget = "llm-cpu-fallback",
                embeddingTarget = "embedding-cpu",
                backends = listOf(
                    backend("llm-cpu-fallback"),
                    backend("vllm-gpu"),
                    backend("embedding-cpu")
                )
            )
        )

        val llmTargets = service.resolveHealthyTargets(InferenceRole.LLM)

        assertEquals(listOf("llm-cpu-fallback", "vllm-gpu"), llmTargets.map { it.serviceName })
        assertEquals(
            listOf("webservices-qwen2.5-coder-14b-cpu", "webservices-qwen2.5-coder-14b-gpu"),
            llmTargets.map { it.defaultModel }
        )
    }

    @Test
    fun `reports unavailable when controller cannot be reached`() = runBlocking {
        val service = InferenceGatewayService(
            config = config(),
            controllerStatusClient = object : InferenceControllerStatusClient {
                override suspend fun fetch(): InferenceControllerStatusPayload {
                    error("timed out")
                }
            }
        )

        val status = service.currentStatus()

        assertEquals("unavailable", status.status)
        assertFalse(status.ready)
        assertFalse(status.controllerReachable)
        assertTrue(status.lastError?.contains("timed out") == true)
    }

    private fun service(controller: InferenceControllerStatusPayload) = InferenceGatewayService(
        config = config(),
        controllerStatusClient = object : InferenceControllerStatusClient {
            override suspend fun fetch(): InferenceControllerStatusPayload = controller
        }
    )

    private fun config() = InferenceGatewayConfig(
        port = 8111,
        controllerStatusUrl = "http://inference-controller:8110/api/status",
        controllerApiToken = null,
        internalApiToken = null,
        controllerTimeoutMs = 10000,
        llmCpuBaseUrl = "http://llm-cpu-fallback:11434",
        llmGpuBaseUrl = "http://vllm-gpu:11434",
        llmCpuDefaultModel = "webservices-qwen2.5-coder-14b-cpu",
        llmGpuDefaultModel = "webservices-qwen2.5-coder-14b-gpu",
        llmCpuModels = setOf("webservices-qwen2.5-coder-14b-cpu", "webservices-qwen2.5-coder-14b", "qwen2.5-coder:14b"),
        llmGpuModels = setOf("webservices-qwen2.5-coder-14b-gpu", "webservices-qwen2.5-coder-14b", "qwen2.5-coder:14b"),
        embeddingCpuBaseUrl = "http://embedding-cpu:8080",
        embeddingGpuBaseUrl = "http://embedding-gpu:8080"
    )

    private fun controllerStatus(
        llmTarget: String = "llm-cpu-fallback",
        embeddingTarget: String = "embedding-cpu",
        mode: String = "cpu_default",
        backends: List<InferenceControllerBackendStatusPayload> = listOf(
            backend("llm-cpu-fallback"),
            backend("vllm-gpu"),
            backend("embedding-cpu")
        )
    ) = InferenceControllerStatusPayload(
        mode = mode,
        llmTarget = llmTarget,
        embeddingTarget = embeddingTarget,
        targetsReady = true,
        ready = true,
        transitioning = false,
        backends = backends
    )

    private fun backend(serviceName: String) = InferenceControllerBackendStatusPayload(
        serviceName = serviceName,
        running = true,
        healthy = true,
        activeState = "active",
        subState = "running"
    )
}
