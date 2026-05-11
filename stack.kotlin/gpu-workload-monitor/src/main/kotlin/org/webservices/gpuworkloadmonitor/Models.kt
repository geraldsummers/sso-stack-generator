package org.webservices.gpuworkloadmonitor

import kotlinx.serialization.Serializable

@Serializable
data class WorkloadSourceSignal(
    val source: String,
    val enabled: Boolean,
    val decisionInputAvailable: Boolean,
    val evidenceAvailable: Boolean,
    val readinessAvailable: Boolean,
    val initialPullComplete: Boolean,
    val activeRun: Boolean,
    val runState: String? = null,
    val searchableDocuments: Long = 0,
    val pendingEmbedding: Long = 0,
    val inProgressEmbedding: Long = 0,
    val failedEmbedding: Long = 0,
    val publishedDocuments: Long = 0,
    val pendingPublication: Long = 0,
    val failedPublication: Long = 0,
    val skippedPublication: Long = 0,
    val blockers: List<String> = emptyList(),
    val lastError: String? = null
)

@Serializable
data class WorkloadSignal(
    val decisionInputsHealthy: Boolean,
    val initialBuildIncomplete: Boolean,
    val totalPendingEmbedding: Long,
    val totalInProgressEmbedding: Long,
    val incompleteSources: List<String>,
    val sources: List<WorkloadSourceSignal>,
    val lastEvaluatedAt: String? = null,
    val lastError: String? = null
)

@Serializable
data class WorkloadMonitorStatus(
    val status: String,
    val signal: WorkloadSignal,
    val ready: Boolean
)
