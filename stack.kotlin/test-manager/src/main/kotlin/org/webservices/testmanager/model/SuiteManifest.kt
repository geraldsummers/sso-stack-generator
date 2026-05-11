package org.webservices.testmanager.model

import kotlinx.serialization.Serializable

@Serializable
data class SuiteManifestFile(
    val suites: List<SuiteManifest> = emptyList()
)

@Serializable
data class SuiteManifest(
    val name: String,
    val title: String = name,
    val command: String,
    val cadenceMinutes: Long = 1440,
    val freshnessMinutes: Long = 1440,
    val triggers: SuiteTriggers = SuiteTriggers(),
    val watchPaths: List<String> = emptyList(),
    val prerequisites: SuitePrerequisites = SuitePrerequisites()
)

@Serializable
data class SuiteTriggers(
    val runOnFirstSeen: Boolean = true,
    val onPrereqReady: Boolean = true,
    val onReleaseChange: Boolean = true,
    val onDomainChange: Boolean = false,
    val onWatchPathsChange: Boolean = true
)

@Serializable
data class SuitePrerequisites(
    val containersHealthy: List<String> = emptyList(),
    val sources: List<SourcePrerequisite> = emptyList()
)

@Serializable
data class SourcePrerequisite(
    val source: String,
    val completedInitialPull: Boolean = false,
    val requireIdle: Boolean = false,
    val minSearchableDocuments: Long = 0,
    val minPublishedDocuments: Long = 0,
    val maxPendingEmbedding: Long? = null,
    val maxPendingPublication: Long? = null
)

@Serializable
data class SuiteView(
    val name: String,
    val title: String,
    val command: String,
    val state: String,
    val blockers: List<String>,
    val fresh: Boolean,
    val cadenceMinutes: Long,
    val freshnessMinutes: Long,
    val lastTriggerReason: String? = null,
    val lastEvaluatedAt: String? = null,
    val latestRun: RunView? = null
)

@Serializable
data class RunView(
    val id: Long,
    val suiteName: String,
    val title: String,
    val command: String,
    val status: String,
    val triggerReason: String,
    val requestedBy: String,
    val requestedAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val exitCode: Int? = null,
    val logPath: String? = null,
    val logTail: String? = null
)

@Serializable
data class OverviewResponse(
    val generatedAt: String,
    val releaseFingerprint: String,
    val domainFingerprint: String,
    val releaseInfo: ReleaseInfo? = null,
    val knownGoodBaseline: KnownGoodBaselineView? = null,
    val suites: List<SuiteView>,
    val recentRuns: List<RunView>
)

@Serializable
data class KnownGoodBaselineView(
    val source: String = "filesystem",
    val suite: String,
    val status: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val durationMs: Long? = null,
    val recordedAt: String? = null,
    val resultsPath: String,
    val summaryPath: String? = null,
    val logPath: String? = null
)

@Serializable
data class ReleaseInfo(
    val version: String? = null,
    val gitSha: String? = null,
    val gitShortSha: String? = null,
    val gitBranch: String? = null,
    val gitDirty: Boolean? = null,
    val sourceBuiltAt: String? = null,
    val renderedAt: String? = null,
    val builtBy: String? = null,
    val renderedBy: String? = null,
    val siteName: String? = null,
    val buildSystem: String? = null
)
