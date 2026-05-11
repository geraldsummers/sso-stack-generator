package org.webservices.pipeline.monitoring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.webservices.pipeline.storage.BookStackStats
import org.webservices.pipeline.storage.DocumentStagingStore
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val pipelineStatsLogger = KotlinLogging.logger {}

data class PipelineStatsSnapshot(
    val refreshedAt: Instant,
    val queueStats: Map<String, Long>?,
    val queueStatsBySource: Map<String, Map<String, Long>>,
    val publicationStatsBySource: Map<String, BookStackStats>,
    val queueAvailable: Boolean,
    val sourceStatsAvailable: Boolean,
    val publicationStatsAvailable: Boolean,
    val queueFresh: Boolean,
    val sourceStatsFresh: Boolean,
    val publicationStatsFresh: Boolean
) {
    fun queueStatsFor(source: String): Map<String, Long>? =
        queueStatsBySource[source]

    fun publicationStatsFor(source: String): BookStackStats? =
        publicationStatsBySource[source]
}

class PipelineStatsCache(
    private val stagingStore: DocumentStagingStore,
    private val sourceIdsProvider: () -> List<String>,
    private val refreshIntervalSeconds: Int = 15,
    private val queryTimeoutMs: Long = 20_000,
    private val clock: Clock = Clock.systemUTC()
) {
    private val snapshotRef = AtomicReference<PipelineStatsSnapshot?>()

    private fun emptyQueueStats(): Map<String, Long> = mapOf(
        "pending" to 0L,
        "in_progress" to 0L,
        "completed" to 0L,
        "failed" to 0L
    )

    private fun emptyPublicationStats(): BookStackStats =
        BookStackStats(
            totalEmbedded = 0,
            pending = 0,
            completed = 0,
            failed = 0,
            skipped = 0
        )

    fun snapshot(): PipelineStatsSnapshot? = snapshotRef.get()

    suspend fun refreshNow(): PipelineStatsSnapshot {
        val sourceIds = sourceIdsProvider().distinct()
        val previous = snapshotRef.get()

        val queueStats = stagingStore.getStatsWithQueryTimeout(queryTimeoutMs)
        val sourceStats = if (sourceIds.isEmpty()) {
            emptyMap()
        } else {
            stagingStore.getStatsBySourcesWithQueryTimeout(sourceIds, queryTimeoutMs)
        }
        val publicationStats = if (sourceIds.isEmpty()) {
            emptyMap()
        } else {
            stagingStore.getBookStackStatsBySourcesWithQueryTimeout(sourceIds, queryTimeoutMs)
        }

        val snapshot = PipelineStatsSnapshot(
            refreshedAt = Instant.now(clock),
            queueStats = queueStats ?: previous?.queueStats,
            queueStatsBySource = when {
                sourceStats != null -> sourceIds.associateWith { source -> sourceStats[source] ?: emptyQueueStats() }
                previous?.sourceStatsAvailable == true -> previous.queueStatsBySource
                else -> emptyMap()
            },
            publicationStatsBySource = when {
                publicationStats != null -> sourceIds.associateWith { source -> publicationStats[source] ?: emptyPublicationStats() }
                previous?.publicationStatsAvailable == true -> previous.publicationStatsBySource
                else -> emptyMap()
            },
            queueAvailable = queueStats != null || previous?.queueAvailable == true,
            sourceStatsAvailable = sourceStats != null || previous?.sourceStatsAvailable == true,
            publicationStatsAvailable = publicationStats != null || previous?.publicationStatsAvailable == true,
            queueFresh = queueStats != null,
            sourceStatsFresh = sourceStats != null,
            publicationStatsFresh = publicationStats != null
        )

        snapshotRef.set(snapshot)
        return snapshot
    }

    suspend fun refreshLoop() {
        refreshNow()
        while (true) {
            delay(refreshIntervalSeconds.seconds)
            try {
                refreshNow()
            } catch (error: Exception) {
                pipelineStatsLogger.warn(error) { "Pipeline stats cache refresh failed: ${error.message}" }
            }
        }
    }
}
