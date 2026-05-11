package org.webservices.pipeline.monitoring

import kotlinx.serialization.Serializable

@Serializable
data class PipelineReadinessResponse(
    val generatedAt: String,
    val sources: List<SourceReadiness>
)

@Serializable
data class SourceReadiness(
    val source: String,
    val name: String,
    val enabled: Boolean,
    val runState: String,
    val phase: String,
    val activeRun: Boolean,
    val completedInitialPull: Boolean,
    val initialPullComplete: Boolean,
    val currentRunType: String? = null,
    val currentRunStartedAt: String? = null,
    val fetchStartedAt: String? = null,
    val fetchCompletedAt: String? = null,
    val lastProgressAt: String? = null,
    val lastCompletedAt: String? = null,
    val lastFailedAt: String? = null,
    val lastSuccessfulRun: String? = null,
    val lastAttemptedRun: String? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val stagedCurrentRun: Long = 0,
    val deduplicatedCurrentRun: Long = 0,
    val failedCurrentRun: Long = 0,
    val pendingEmbedding: Long = 0,
    val inProgressEmbedding: Long = 0,
    val searchableDocuments: Long = 0,
    val failedEmbedding: Long = 0,
    val pendingPublication: Long = 0,
    val publishedDocuments: Long = 0,
    val failedPublication: Long = 0,
    val skippedPublication: Long = 0,
    val stalled: Boolean = false,
    val blockers: List<String> = emptyList()
)

@Serializable
data class SourceRuntimeSnapshot(
    val source: String,
    val activeRun: Boolean,
    val runState: String,
    val phase: String,
    val completedInitialPull: Boolean,
    val currentRunType: String? = null,
    val currentRunStartedAt: String? = null,
    val fetchStartedAt: String? = null,
    val fetchCompletedAt: String? = null,
    val lastProgressAt: String? = null,
    val lastCompletedAt: String? = null,
    val lastFailedAt: String? = null,
    val lastError: String? = null,
    val stagedCurrentRun: Long = 0,
    val deduplicatedCurrentRun: Long = 0,
    val failedCurrentRun: Long = 0
)
