package org.webservices.inferencecontroller

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class InferenceControllerServiceTest {
    @Test
    fun `cpu default keeps cpu backends selected`() = runBlocking {
        val systemd = FakeSystemdController(
            initiallyActive = setOf(
                "webservices-vllm-gpu.service",
                "webservices-embedding-gpu.service"
            )
        )
        val service = service(systemd)

        service.reconcileNow()
        val status = service.currentStatus()

        assertEquals(InferenceMode.CPU_DEFAULT, status.mode)
        assertEquals("llm-cpu-fallback", status.llmTarget)
        assertEquals("embedding-cpu", status.embeddingTarget)
        assertTrue(systemd.started.contains("webservices-llm-cpu-fallback.service"))
        assertTrue(systemd.started.contains("webservices-embedding-cpu.service"))
        assertTrue(systemd.stopped.contains("webservices-vllm-gpu.service"))
        assertTrue(systemd.stopped.contains("webservices-embedding-gpu.service"))
    }

    @Test
    fun `gpu llm mode starts gpu llm and stops gpu embedding`() = runBlocking {
        val systemd = FakeSystemdController(initiallyActive = setOf("webservices-embedding-gpu.service"))
        val service = service(systemd)

        service.setMode(InferenceMode.GPU_LLM)
        val status = service.currentStatus()

        assertEquals("vllm-gpu", status.llmTarget)
        assertEquals("embedding-cpu", status.embeddingTarget)
        assertTrue(systemd.started.contains("webservices-vllm-gpu.service"))
        assertTrue(systemd.stopped.contains("webservices-embedding-gpu.service"))
    }

    @Test
    fun `gpu embedding mode starts gpu embedding and stops gpu llm`() = runBlocking {
        val systemd = FakeSystemdController(initiallyActive = setOf("webservices-vllm-gpu.service"))
        val service = service(systemd)

        service.setMode(InferenceMode.GPU_EMBEDDING)
        val status = service.currentStatus()

        assertEquals("llm-cpu-fallback", status.llmTarget)
        assertEquals("embedding-gpu", status.embeddingTarget)
        assertTrue(systemd.started.contains("webservices-embedding-gpu.service"))
        assertTrue(systemd.stopped.contains("webservices-vllm-gpu.service"))
    }

    @Test
    fun `reports not ready while selected targets are unhealthy`() = runBlocking {
        val systemd = FakeSystemdController(healthy = false)
        val service = service(systemd)

        service.reconcileNow()
        val status = service.currentStatus()

        assertFalse(status.ready)
        assertFalse(status.targetsReady)
    }

    private fun service(systemd: FakeSystemdController) = InferenceControllerService(
        config = InferenceControllerConfig(8110, Files.createTempDirectory("inference-controller-test").resolve("state.json"), 60, "unix:path=/tmp/test-bus", null),
        systemd = systemd,
        stateStore = InferenceControllerStateStore(Files.createTempDirectory("inference-controller-state").resolve("state.json")),
        healthClient = object : HttpHealthClient() {
            override fun isHealthy(backend: ManagedBackend): Boolean = systemd.healthy
        }
    )

    private class FakeSystemdController(
        val healthy: Boolean = true,
        initiallyActive: Set<String> = emptySet()
    ) : SystemdController {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        private val activeUnits = initiallyActive.toMutableSet()

        override suspend fun startUnit(unitName: String) {
            started += unitName
            activeUnits += unitName
        }

        override suspend fun stopUnit(unitName: String) {
            stopped += unitName
            activeUnits -= unitName
        }

        override suspend fun inspectUnit(unitName: String): UnitStatus =
            if (unitName in activeUnits) {
                UnitStatus("active", "running")
            } else {
                UnitStatus("inactive", "dead")
            }
    }
}
