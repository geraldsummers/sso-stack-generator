package org.webservices.inferencecontroller

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

class InferenceControllerService(
    private val config: InferenceControllerConfig,
    private val systemd: SystemdController,
    private val stateStore: InferenceControllerStateStore,
    private val healthClient: HttpHealthClient = HttpHealthClient(),
    private val clock: Clock = Clock.systemUTC()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var persistedState: InferenceControllerState = stateStore.load()

    @Volatile
    private var status: InferenceControllerStatus = emptyStatus(persistedState)

    fun start() {
        scope.launch { reconcileLoop() }
    }

    fun stop() {
        scope.cancel()
    }

    fun currentStatus(): InferenceControllerStatus = status

    suspend fun setMode(mode: InferenceMode, note: String? = null): InferenceControllerStatus {
        mutex.withLock {
            val now = Instant.now(clock).toString()
            persistedState = persistedState.copy(
                mode = mode,
                modeChangedAt = now,
                note = note ?: "mode request ${mode.name.lowercase()}"
            )
            stateStore.save(persistedState)
        }
        reconcileOnce()
        return status
    }

    suspend fun reconcileNow(): InferenceControllerStatus {
        reconcileOnce()
        return status
    }

    private suspend fun reconcileLoop() {
        while (scope.isActive) {
            runCatching { reconcileOnce() }
                .onFailure { error ->
                    logger.error(error) { "Inference controller reconcile failed: ${error.message}" }
                    status = status.copy(
                        ready = false,
                        targetsReady = false,
                        transitioning = false,
                        lastEvaluatedAt = Instant.now(clock).toString(),
                        lastError = error.message
                    )
                }
            delay(config.reconcileIntervalSeconds * 1000)
        }
    }

    private suspend fun reconcileOnce() {
        mutex.withLock {
            val now = Instant.now(clock)
            val desired = desiredRunningBackends(persistedState.mode)
            managedBackends.forEach { backend ->
                val unitStatus = systemd.inspectUnit(backend.unitName)
                if (backend.serviceName in desired) {
                    if (!unitStatus.running && !unitStatus.transitioning) {
                        systemd.startUnit(backend.unitName)
                    }
                } else if (unitStatus.activeState !in setOf("inactive", "failed") || unitStatus.transitioning) {
                    systemd.stopUnit(backend.unitName)
                }
            }
            val backends = buildList {
                for (backend in managedBackends) {
                    add(inspectBackend(backend))
                }
            }
            val llmTarget = llmTargetFor(persistedState.mode)
            val embeddingTarget = embeddingTargetFor(persistedState.mode)
            val llmStatus = backends.first { it.serviceName == llmTarget.serviceName }
            val embeddingStatus = backends.first { it.serviceName == embeddingTarget.serviceName }
            val targetsReady = llmStatus.running && llmStatus.healthy && embeddingStatus.running && embeddingStatus.healthy
            val transitioning = backends.any { it.activeState == "activating" || it.subState in setOf("start", "stop-sigterm", "auto-restart") }
            status = InferenceControllerStatus(
                mode = persistedState.mode,
                llmTarget = llmTarget.serviceName,
                embeddingTarget = embeddingTarget.serviceName,
                state = persistedState,
                backends = backends,
                targetsReady = targetsReady,
                ready = targetsReady && !transitioning,
                transitioning = transitioning,
                lastEvaluatedAt = now.toString(),
                lastError = backends.firstOrNull { it.error != null }?.error
            )
        }
    }

    private suspend fun inspectBackend(backend: ManagedBackend): BackendStatus {
        val unitStatus = systemd.inspectUnit(backend.unitName)
        val running = unitStatus.running
        val healthy = running && healthClient.isHealthy(backend)
        return BackendStatus(
            serviceName = backend.serviceName,
            containerName = backend.containerName,
            role = backend.role,
            mode = backend.mode,
            unitName = backend.unitName,
            activeState = unitStatus.activeState,
            subState = unitStatus.subState,
            running = running,
            healthy = healthy,
            reachable = healthy,
            expectedModel = backend.expectedModel,
            error = when {
                running && !healthy -> "health probe failed"
                unitStatus.activeState == "failed" -> "systemd unit failed"
                else -> null
            }
        )
    }

    private fun emptyStatus(state: InferenceControllerState): InferenceControllerStatus {
        val llmTarget = llmTargetFor(state.mode)
        val embeddingTarget = embeddingTargetFor(state.mode)
        return InferenceControllerStatus(
            mode = state.mode,
            llmTarget = llmTarget.serviceName,
            embeddingTarget = embeddingTarget.serviceName,
            state = state,
            backends = managedBackends.map { backend ->
                BackendStatus(
                    serviceName = backend.serviceName,
                    containerName = backend.containerName,
                    role = backend.role,
                    mode = backend.mode,
                    unitName = backend.unitName,
                    activeState = "inactive",
                    subState = "dead",
                    running = false,
                    healthy = false,
                    reachable = false,
                    expectedModel = backend.expectedModel
                )
            },
            targetsReady = false,
            ready = false,
            transitioning = false
        )
    }
}
