package org.webservices.gpubootstraparbiter

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GpuBootstrapArbiterServiceTest {
    @Test
    fun `keeps embedding priority when backlog is high`() = runBlocking {
        val docker = dockerForEmbeddingPriority()
        val service = service(
            docker = docker,
            signal = signal(initialBuildIncomplete = false, pending = 12, inProgress = 2)
        )

        service.reconcileNow()

        val status = service.currentStatus()
        assertEquals(ArbiterMode.EMBEDDING_PRIORITY, status.mode)
        assertFalse(status.decision.eligible)
        assertEquals("llm-cpu-fallback", status.llmTarget)
        assertEquals("embedding-gpu", status.embeddingTarget)
        assertTrue(status.decision.backlogHigh)
        assertTrue(status.decisionInputsHealthy)
        assertTrue(status.targetsReady)
    }

    @Test
    fun `switches to llm priority once backlog is low`() = runBlocking {
        val docker = dockerForEmbeddingPriority()
        val service = service(
            docker = docker,
            signal = signal(initialBuildIncomplete = true, pending = 0, inProgress = 0),
            initialState = ArbiterState(mode = ArbiterMode.EMBEDDING_PRIORITY, modeChangedAt = "2026-04-08T00:00:00Z")
        )

        service.reconcileNow()

        val status = service.currentStatus()
        assertEquals(ArbiterMode.LLM_PRIORITY, status.mode)
        assertEquals("vllm-gpu", status.llmTarget)
        assertEquals("embedding-cpu", status.embeddingTarget)
        assertTrue(docker.startCalls.contains("vllm-gpu"))
        assertTrue(docker.stopCalls.contains("embedding-gpu"))
    }

    @Test
    fun `holds llm priority while backlog sits inside hysteresis band`() = runBlocking {
        val docker = dockerForLlmPriority()
        val service = service(
            docker = docker,
            signal = signal(initialBuildIncomplete = false, pending = 5, inProgress = 1),
            initialState = ArbiterState(mode = ArbiterMode.LLM_PRIORITY, modeChangedAt = "2026-04-08T00:00:00Z")
        )

        service.reconcileNow()

        val status = service.currentStatus()
        assertEquals(ArbiterMode.LLM_PRIORITY, status.mode)
        assertTrue(status.decision.eligible)
        assertTrue(status.decision.reasons.any { it.contains("hysteresis band") })
    }

    @Test
    fun `returns to embedding priority when backlog rises again`() = runBlocking {
        val docker = dockerForLlmPriority()
        val service = service(
            docker = docker,
            signal = signal(initialBuildIncomplete = false, pending = 10, inProgress = 3),
            initialState = ArbiterState(mode = ArbiterMode.LLM_PRIORITY, modeChangedAt = "2026-04-08T00:00:00Z")
        )

        service.reconcileNow()

        val status = service.currentStatus()
        assertEquals(ArbiterMode.EMBEDDING_PRIORITY, status.mode)
        assertEquals("llm-cpu-fallback", status.llmTarget)
        assertEquals("embedding-gpu", status.embeddingTarget)
        assertTrue(docker.stopCalls.contains("vllm-gpu"))
        assertTrue(docker.startCalls.contains("embedding-gpu"))
    }

    @Test
    fun `signal failure does not prevent live target status from updating`() = runBlocking {
        val docker = dockerForLlmPriority()
        val service = service(
            docker = docker,
            initialState = ArbiterState(mode = ArbiterMode.LLM_PRIORITY),
            signalClient = object : WorkloadSignalClient {
                override suspend fun fetch(): WorkloadSignalSnapshot {
                    error("signal unavailable")
                }
            }
        )

        service.reconcileNow()

        val status = service.currentStatus()
        assertEquals(ArbiterMode.LLM_PRIORITY, status.mode)
        assertEquals("vllm-gpu", status.llmTarget)
        assertEquals("embedding-cpu", status.embeddingTarget)
        assertTrue(status.ready)
        assertFalse(status.decisionInputsHealthy)
        assertTrue(status.lastError?.contains("signal unavailable") == true)
    }

    @Test
    fun `can hold embedding priority until initial pulls complete when configured`() = runBlocking {
        val docker = dockerForLlmPriority()
        val service = service(
            docker = docker,
            signal = signal(initialBuildIncomplete = true, pending = 0, inProgress = 0),
            initialState = ArbiterState(mode = ArbiterMode.LLM_PRIORITY),
            requireInitialPullCompleteForLlmPriority = true
        )

        service.reconcileNow()

        val status = service.currentStatus()
        assertEquals(ArbiterMode.EMBEDDING_PRIORITY, status.mode)
        assertTrue(status.decision.reasons.any { it.contains("initial pull incomplete") })
    }

    @Test
    fun `holds current mode when monitor is degraded`() = runBlocking {
        val docker = dockerForLlmPriority()
        val service = service(
            docker = docker,
            signal = signal(initialBuildIncomplete = false, pending = 0, inProgress = 0, decisionInputsHealthy = false),
            initialState = ArbiterState(mode = ArbiterMode.LLM_PRIORITY)
        )

        service.reconcileNow()

        val status = service.currentStatus()
        assertEquals(ArbiterMode.LLM_PRIORITY, status.mode)
        assertFalse(status.decisionInputsHealthy)
        assertTrue(status.decision.reasons.first().contains("degraded"))
    }

    private fun service(
        docker: FakeDockerController,
        initialState: ArbiterState = ArbiterState(),
        signal: WorkloadSignalSnapshot = signal(initialBuildIncomplete = false),
        signalClient: WorkloadSignalClient? = null,
        requireInitialPullCompleteForLlmPriority: Boolean = false
    ): GpuBootstrapArbiterService {
        val tempDir = Files.createTempDirectory("arbiter-test")
        val statePath = tempDir.resolve("state.json")
        ArbiterStateStore(statePath).save(initialState)
        val config = ArbiterConfig(
            port = 8110,
            workspaceRoot = tempDir,
            dataRoot = tempDir,
            statePath = statePath,
            dockerHost = "tcp://docker-socket-proxy:2375",
            composeProjectName = "webservices",
            composeFile = tempDir.resolve("docker-compose.yml"),
            composeEnvFile = tempDir.resolve("stack.env"),
            signalUrl = "http://gpu-workload-monitor:8112/api/signal",
            signalApiKey = null,
            apiToken = null,
            allowUnauthenticatedInternalApi = true,
            signalTimeoutMs = 10000,
            evaluationIntervalSeconds = 3600,
            embeddingPriorityPendingHighThreshold = 10,
            embeddingPriorityPendingLowThreshold = 2,
            embeddingPriorityInProgressHighThreshold = 2,
            embeddingPriorityInProgressLowThreshold = 0,
            requireInitialPullCompleteForLlmPriority = requireInitialPullCompleteForLlmPriority
        )
        return GpuBootstrapArbiterService(
            config = config,
            docker = docker,
            signalClient = signalClient ?: object : WorkloadSignalClient {
                override suspend fun fetch(): WorkloadSignalSnapshot = signal
            },
            stateStore = ArbiterStateStore(statePath),
            clock = Clock.fixed(Instant.parse("2026-04-08T00:10:00Z"), ZoneOffset.UTC)
        )
    }

    private fun signal(
        initialBuildIncomplete: Boolean,
        pending: Long = 0,
        inProgress: Long = 0,
        decisionInputsHealthy: Boolean = true
    ): WorkloadSignalSnapshot {
        return WorkloadSignalSnapshot(
            decisionInputsHealthy = decisionInputsHealthy,
            initialBuildIncomplete = initialBuildIncomplete,
            totalPendingEmbedding = pending,
            totalInProgressEmbedding = inProgress,
            incompleteSources = if (initialBuildIncomplete) listOf("wikipedia") else emptyList(),
            lastEvaluatedAt = "2026-04-08T00:00:00Z",
            lastError = if (decisionInputsHealthy) null else "monitor degraded"
        )
    }

    private fun dockerForEmbeddingPriority() = FakeDockerController(
        mutableMapOf(
            "llm-cpu-fallback" to healthyBackend("llm-cpu-fallback", "llm", "cpu"),
            "vllm-gpu" to stoppedBackend("vllm-gpu", "llm", "gpu"),
            "embedding-gpu" to healthyBackend("embedding-gpu", "embedding", "gpu"),
            "embedding-cpu" to healthyBackend("embedding-cpu", "embedding", "cpu")
        )
    )

    private fun dockerForLlmPriority() = FakeDockerController(
        mutableMapOf(
            "llm-cpu-fallback" to healthyBackend("llm-cpu-fallback", "llm", "cpu"),
            "vllm-gpu" to healthyBackend("vllm-gpu", "llm", "gpu"),
            "embedding-gpu" to stoppedBackend("embedding-gpu", "embedding", "gpu"),
            "embedding-cpu" to healthyBackend("embedding-cpu", "embedding", "cpu")
        )
    )

    private fun healthyBackend(serviceName: String, role: String, mode: String) = BackendStatus(
        serviceName = serviceName,
        containerName = serviceName,
        role = role,
        mode = mode,
        state = "running",
        running = true,
        healthy = true,
        managed = true
    )

    private fun stoppedBackend(serviceName: String, role: String, mode: String) = BackendStatus(
        serviceName = serviceName,
        containerName = serviceName,
        role = role,
        mode = mode,
        state = "exited",
        running = false,
        healthy = false,
        managed = true
    )
}

private class FakeDockerController(
    private val statuses: MutableMap<String, BackendStatus>
) : DockerController {
    val startCalls = mutableListOf<String>()
    val stopCalls = mutableListOf<String>()

    override suspend fun inspect(serviceName: String): BackendStatus? = statuses[serviceName]

    override suspend fun ensureRunning(backend: ManagedBackend) {
        startCalls += backend.serviceName
        val current = statuses[backend.serviceName]
        statuses[backend.serviceName] = if (current == null) {
            BackendStatus(
                serviceName = backend.serviceName,
                containerName = backend.containerName,
                role = backend.role,
                mode = backend.mode,
                state = "running",
                running = true,
                healthy = true,
                managed = true
            )
        } else current.copy(state = "running", running = true, healthy = true, managed = true, error = null)
    }

    override suspend fun ensureStopped(backend: ManagedBackend) {
        stopCalls += backend.serviceName
        statuses[backend.serviceName] = statuses[backend.serviceName]?.copy(state = "exited", running = false, healthy = false, managed = true)
            ?: BackendStatus(
                serviceName = backend.serviceName,
                containerName = backend.containerName,
                role = backend.role,
                mode = backend.mode,
                state = "exited",
                running = false,
                healthy = false,
                managed = true
            )
    }
}
