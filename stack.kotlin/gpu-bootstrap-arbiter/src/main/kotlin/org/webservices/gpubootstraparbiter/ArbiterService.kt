package org.webservices.gpubootstraparbiter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

class GpuBootstrapArbiterService(
    private val config: ArbiterConfig,
    private val docker: DockerController,
    private val signalClient: WorkloadSignalClient,
    private val stateStore: ArbiterStateStore,
    private val clock: Clock = Clock.systemUTC()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var persistedState: ArbiterState = stateStore.load()

    @Volatile
    private var status: ArbiterStatus = emptyStatus(persistedState)

    fun start() {
        scope.launch { controlLoop() }
    }

    fun stop() {
        scope.cancel()
    }

    fun currentStatus(): ArbiterStatus = status

    suspend fun forcePromote() {
        forceLlmPriority()
    }

    suspend fun forceLlmPriority() {
        mutex.withLock {
            persistedState = persistedState.copy(
                mode = ArbiterMode.LLM_PRIORITY,
                modeChangedAt = Instant.now(clock).toString(),
                note = "manual llm-priority request"
            )
            stateStore.save(persistedState)
        }
        reconcileOnce()
    }

    suspend fun reconcileNow() {
        reconcileOnce()
    }

    private suspend fun controlLoop() {
        while (scope.isActive) {
            runCatching { reconcileOnce() }
                .onFailure { error ->
                    logger.error(error) { "Arbiter reconcile failed: ${error.message}" }
                    status = status.copy(
                        lastEvaluatedAt = Instant.now(clock).toString(),
                        lastError = error.message,
                        targetsReady = false,
                        ready = false
                    )
                }
            delay(config.evaluationIntervalSeconds * 1000)
        }
    }

    private suspend fun reconcileOnce() {
        mutex.withLock {
            val now = Instant.now(clock)
            val signalResult = runCatching { signalClient.fetch() }
            val signal = signalResult.getOrElse { error ->
                logger.warn(error) { "Workload signal fetch failed: ${error.message}" }
                null
            }
            val decision = evaluatePriority(signal, persistedState.mode)
            val state = persistDecision(decision, now)

            applyDesiredMode(state.mode)
            val backends = inspectBackends()

            status = buildStatus(
                state = state,
                decision = decision,
                backends = backends,
                signal = signal,
                evaluatedAt = now,
                lastError = signalResult.exceptionOrNull()?.message ?: signal?.lastError
            )
        }
    }

    private fun persistDecision(
        decision: PromotionDecision,
        now: Instant
    ): ArbiterState {
        val previous = persistedState
        val note = decision.reasons.joinToString("; ").ifBlank {
            when (decision.targetMode) {
                ArbiterMode.EMBEDDING_PRIORITY -> "embeddings currently own the gpu"
                ArbiterMode.LLM_PRIORITY -> "interactive llm currently owns the gpu"
            }
        }
        val next = if (previous.mode != decision.targetMode) {
            ArbiterState(
                mode = decision.targetMode,
                modeChangedAt = now.toString(),
                note = note
            )
        } else {
            previous.copy(
                modeChangedAt = previous.modeChangedAt ?: now.toString(),
                note = note
            )
        }
        if (next != previous) {
            stateStore.save(next)
        }
        persistedState = next
        return next
    }

    private suspend fun applyDesiredMode(mode: ArbiterMode) {
        when (mode) {
            ArbiterMode.EMBEDDING_PRIORITY -> {
                ensureRunning("llm-cpu-fallback")
                ensureRunning("embedding-cpu")
                ensureStopped("vllm-gpu")
                ensureRunning("embedding-gpu")
            }
            ArbiterMode.LLM_PRIORITY -> {
                ensureRunning("llm-cpu-fallback")
                ensureRunning("embedding-cpu")
                ensureStopped("embedding-gpu")
                ensureRunning("vllm-gpu")
            }
        }
    }

    private suspend fun ensureRunning(serviceName: String) {
        docker.ensureRunning(managedBackend(serviceName))
    }

    private suspend fun ensureStopped(serviceName: String) {
        docker.ensureStopped(managedBackend(serviceName))
    }

    private suspend fun inspectBackends(): List<BackendStatus> {
        return managedBackends.map { backend ->
            docker.inspect(backend.serviceName) ?: BackendStatus(
                serviceName = backend.serviceName,
                containerName = backend.containerName,
                role = backend.role,
                mode = backend.mode,
                state = "missing",
                running = false,
                healthy = false,
                managed = false,
                error = "container not created"
            )
        }
    }

    private fun evaluatePriority(
        signal: WorkloadSignalSnapshot?,
        currentMode: ArbiterMode
    ): PromotionDecision {
        if (signal == null) {
            return PromotionDecision(
                eligible = currentMode == ArbiterMode.LLM_PRIORITY,
                targetMode = currentMode,
                reasons = listOf("workload monitor unavailable"),
                totalPendingEmbedding = 0,
                totalInProgressEmbedding = 0,
                incompleteSources = emptyList(),
                backlogHigh = false,
                backlogLow = false
            )
        }

        if (!signal.decisionInputsHealthy) {
            return PromotionDecision(
                eligible = currentMode == ArbiterMode.LLM_PRIORITY,
                targetMode = currentMode,
                reasons = listOfNotNull(
                    "workload monitor is degraded; holding ${currentMode.name.lowercase()}",
                    signal.lastError
                ),
                totalPendingEmbedding = signal.totalPendingEmbedding,
                totalInProgressEmbedding = signal.totalInProgressEmbedding,
                incompleteSources = signal.incompleteSources,
                backlogHigh = false,
                backlogLow = false
            )
        }

        val pending = signal.totalPendingEmbedding
        val inProgress = signal.totalInProgressEmbedding
        val backlogHigh = pending >= config.embeddingPriorityPendingHighThreshold ||
            inProgress >= config.embeddingPriorityInProgressHighThreshold
        val backlogLow = pending <= config.embeddingPriorityPendingLowThreshold &&
            inProgress <= config.embeddingPriorityInProgressLowThreshold
        val llmPriorityBlockedByInitialPull =
            config.requireInitialPullCompleteForLlmPriority && signal.initialBuildIncomplete

        val targetMode = when {
            llmPriorityBlockedByInitialPull -> ArbiterMode.EMBEDDING_PRIORITY
            currentMode == ArbiterMode.EMBEDDING_PRIORITY && backlogLow -> ArbiterMode.LLM_PRIORITY
            currentMode == ArbiterMode.LLM_PRIORITY && backlogHigh -> ArbiterMode.EMBEDDING_PRIORITY
            else -> currentMode
        }

        val reasons = mutableListOf<String>()
        if (llmPriorityBlockedByInitialPull) {
            reasons += "initial pull incomplete: ${signal.incompleteSources.joinToString(", ")}"
            reasons += "holding embedding priority until initial pulls complete"
        } else if (backlogHigh) {
            if (pending >= config.embeddingPriorityPendingHighThreshold) {
                reasons += "pending embeddings $pending exceed or match ${config.embeddingPriorityPendingHighThreshold}"
            }
            if (inProgress >= config.embeddingPriorityInProgressHighThreshold) {
                reasons += "in-progress embeddings $inProgress exceed or match ${config.embeddingPriorityInProgressHighThreshold}"
            }
        } else if (backlogLow) {
            reasons += "pending embeddings $pending are at or below ${config.embeddingPriorityPendingLowThreshold}"
            reasons += "in-progress embeddings $inProgress are at or below ${config.embeddingPriorityInProgressLowThreshold}"
        } else {
            reasons += "backlog within hysteresis band; holding ${currentMode.name.lowercase()}"
        }

        return PromotionDecision(
            eligible = targetMode == ArbiterMode.LLM_PRIORITY,
            targetMode = targetMode,
            reasons = reasons,
            totalPendingEmbedding = pending,
            totalInProgressEmbedding = inProgress,
            incompleteSources = signal.incompleteSources,
            backlogHigh = backlogHigh,
            backlogLow = backlogLow
        )
    }

    private fun buildStatus(
        state: ArbiterState,
        decision: PromotionDecision,
        backends: List<BackendStatus>,
        signal: WorkloadSignalSnapshot?,
        evaluatedAt: Instant,
        lastError: String?
    ): ArbiterStatus {
        val llmTarget = resolveTargetBackend(
            preferred = llmBackendFor(state.mode),
            backends = backends
        ).serviceName
        val embeddingTarget = resolveTargetBackend(
            preferred = embeddingBackendFor(state.mode),
            backends = backends
        ).serviceName
        val llmStatus = backends.first { it.serviceName == llmTarget }
        val embeddingStatus = backends.first { it.serviceName == embeddingTarget }
        val llmReady = llmStatus.running && llmStatus.healthy
        val embeddingReady = embeddingStatus.running && embeddingStatus.healthy
        val targetsReady = llmReady && embeddingReady
        return ArbiterStatus(
            mode = state.mode,
            llmTarget = llmTarget,
            embeddingTarget = embeddingTarget,
            state = state,
            decision = decision,
            backends = backends,
            targetsReady = targetsReady,
            decisionInputsHealthy = signal?.decisionInputsHealthy ?: false,
            ready = targetsReady,
            signalUrl = config.signalUrl,
            lastEvaluatedAt = evaluatedAt.toString(),
            lastError = lastError
        )
    }

    private fun resolveTargetBackend(
        preferred: ManagedBackend,
        backends: List<BackendStatus>
    ): ManagedBackend {
        val preferredStatus = backends.firstOrNull { it.serviceName == preferred.serviceName }
        if (preferredStatus?.running == true && preferredStatus.healthy) {
            return preferred
        }

        val alternative = backends.firstOrNull { backend ->
            backend.role == preferred.role && backend.running && backend.healthy
        }
        return alternative?.let { managedBackend(it.serviceName) } ?: preferred
    }

    private fun emptyStatus(state: ArbiterState): ArbiterStatus = ArbiterStatus(
        mode = state.mode,
        llmTarget = llmBackendFor(state.mode).serviceName,
        embeddingTarget = embeddingBackendFor(state.mode).serviceName,
        state = state,
        decision = PromotionDecision(
            eligible = false,
            targetMode = state.mode,
            reasons = listOf("awaiting first reconcile"),
            totalPendingEmbedding = 0,
            totalInProgressEmbedding = 0,
            incompleteSources = emptyList(),
            backlogHigh = false,
            backlogLow = false
        ),
        backends = managedBackends.map {
            BackendStatus(
                serviceName = it.serviceName,
                containerName = it.containerName,
                role = it.role,
                mode = it.mode,
                state = "unknown",
                running = false,
                healthy = false,
                managed = false,
                error = "awaiting first reconcile"
            )
        },
        targetsReady = false,
        decisionInputsHealthy = false,
        ready = false,
        signalUrl = config.signalUrl,
        lastEvaluatedAt = null,
        lastError = null
    )
}
