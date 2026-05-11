package org.webservices.gpuworkloadmonitor

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webservices.pipeline.monitoring.SourceReadiness
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.SourceReadinessStats
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

class GpuWorkloadMonitorService(
    private val config: WorkloadMonitorConfig,
    private val stagingStore: DocumentStagingStore,
    private val sourceReadinessClient: SourceReadinessClient,
    private val clock: Clock = Clock.systemUTC()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var status: WorkloadMonitorStatus = WorkloadMonitorStatus(
        status = "starting",
        signal = WorkloadSignal(
            decisionInputsHealthy = false,
            initialBuildIncomplete = true,
            totalPendingEmbedding = 0,
            totalInProgressEmbedding = 0,
            incompleteSources = emptyList(),
            sources = emptyList()
        ),
        ready = false
    )

    fun start() {
        scope.launch { refreshLoop() }
    }

    fun stop() {
        scope.cancel()
    }

    fun currentStatus(): WorkloadMonitorStatus = status

    suspend fun refreshNow() {
        status = evaluateStatus()
    }

    internal suspend fun evaluateStatus(): WorkloadMonitorStatus {
        val evaluatedAt = Instant.now(clock).toString()
        val sourceSignals = config.sourceIds.map { sourceId -> buildSourceSignal(sourceId) }
        val enabledSignals = sourceSignals.filter { it.enabled }
        val incompleteSources = enabledSignals.filter { !it.initialPullComplete }.map { it.source }
        val decisionInputsHealthy = enabledSignals.all { it.decisionInputAvailable }
        val lastError = sourceSignals.mapNotNull { it.lastError }.firstOrNull()
        val signal = WorkloadSignal(
            decisionInputsHealthy = decisionInputsHealthy,
            initialBuildIncomplete = incompleteSources.isNotEmpty(),
            totalPendingEmbedding = enabledSignals.sumOf { it.pendingEmbedding },
            totalInProgressEmbedding = enabledSignals.sumOf { it.inProgressEmbedding },
            incompleteSources = incompleteSources,
            sources = sourceSignals,
            lastEvaluatedAt = evaluatedAt,
            lastError = lastError
        )
        return WorkloadMonitorStatus(
            status = if (decisionInputsHealthy) "ok" else "degraded",
            signal = signal,
            ready = decisionInputsHealthy
        )
    }

    private suspend fun refreshLoop() {
        while (scope.isActive) {
            status = runCatching { evaluateStatus() }
                .onFailure { error -> logger.warn(error) { "GPU workload monitor refresh failed: ${error.message}" } }
                .getOrElse { error ->
                    WorkloadMonitorStatus(
                        status = "degraded",
                        signal = status.signal.copy(
                            decisionInputsHealthy = false,
                            lastEvaluatedAt = Instant.now(clock).toString(),
                            lastError = error.message ?: "unknown error"
                        ),
                        ready = false
                    )
                }
            delay(config.evaluationIntervalSeconds * 1000)
        }
    }

    private suspend fun buildSourceSignal(sourceId: String): WorkloadSourceSignal {
        val sourceErrors = mutableListOf<String>()

        val evidenceResult = runCatching {
            stagingStore.getReadinessEvidenceBySourcesWithQueryTimeout(listOf(sourceId), config.sourceQueryTimeoutMs)
        }
        val evidence = evidenceResult.getOrNull()?.get(sourceId)
        val evidenceAvailable = evidence != null
        if (evidenceResult.exceptionOrNull() != null) {
            sourceErrors += "$sourceId evidence: ${evidenceResult.exceptionOrNull()?.message}"
        } else if (!evidenceAvailable) {
            sourceErrors += "$sourceId evidence unavailable"
        }

        val readinessResult = runCatching { sourceReadinessClient.fetch(sourceId) }
        val readiness = readinessResult.getOrNull()
        val readinessAvailable = readiness != null
        if (readinessResult.exceptionOrNull() != null) {
            sourceErrors += "$sourceId readiness: ${readinessResult.exceptionOrNull()?.message}"
        } else if (!readinessAvailable) {
            sourceErrors += "$sourceId readiness unavailable"
        }

        val stats = evidence ?: zeroEvidence()
        val initialPullComplete = readiness?.initialPullComplete ?: inferInitialPullComplete(stats)
        val decisionInputAvailable = readinessAvailable || evidenceAvailable

        return WorkloadSourceSignal(
            source = sourceId,
            enabled = readiness?.enabled ?: true,
            decisionInputAvailable = decisionInputAvailable,
            evidenceAvailable = evidenceAvailable,
            readinessAvailable = readinessAvailable,
            initialPullComplete = initialPullComplete,
            activeRun = readiness?.activeRun ?: false,
            runState = readiness?.runState,
            searchableDocuments = stats.searchableDocuments,
            pendingEmbedding = stats.pendingEmbedding,
            inProgressEmbedding = stats.inProgressEmbedding,
            failedEmbedding = stats.failedEmbedding,
            publishedDocuments = stats.publishedDocuments,
            pendingPublication = stats.pendingPublication,
            failedPublication = stats.failedPublication,
            skippedPublication = stats.skippedPublication,
            blockers = readiness?.blockers ?: emptyList(),
            lastError = sourceErrors.firstOrNull()
        )
    }

    private fun inferInitialPullComplete(stats: SourceReadinessStats): Boolean {
        return stats.searchableDocuments > 0 ||
            stats.pendingEmbedding > 0 ||
            stats.inProgressEmbedding > 0 ||
            stats.failedEmbedding > 0 ||
            stats.publishedDocuments > 0 ||
            stats.pendingPublication > 0 ||
            stats.failedPublication > 0 ||
            stats.skippedPublication > 0
    }

    private fun zeroEvidence(): SourceReadinessStats = SourceReadinessStats(
        searchableDocuments = 0,
        pendingEmbedding = 0,
        inProgressEmbedding = 0,
        failedEmbedding = 0,
        publishedDocuments = 0,
        pendingPublication = 0,
        failedPublication = 0,
        skippedPublication = 0
    )
}
