package org.webservices.pipeline.monitoring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.webservices.pipeline.storage.BookStackStats
import org.webservices.pipeline.storage.DocumentStagingStore
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}


class ProgressReporter(
    private val stagingStore: DocumentStagingStore,
    private val statsCache: PipelineStatsCache? = null,
    private val reportIntervalSeconds: Int = 30
) {
    private var lastReportTime = System.currentTimeMillis()
    private var lastStats = mapOf<String, Long>()

    suspend fun start() {
        logger.info { "📊 Progress reporter starting (interval: ${reportIntervalSeconds}s)" }

        while (true) {
            delay(reportIntervalSeconds.seconds)

            try {
                reportProgress()
            } catch (e: Exception) {
                logger.error(e) { "Error reporting progress: ${e.message}" }
            }
        }
    }

    private suspend fun reportProgress() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastReportTime

        val snapshot = statsCache?.snapshot() ?: statsCache?.refreshNow()
        val stats = snapshot?.queueStats ?: stagingStore.getStats()
        val pending = stats["pending"] ?: 0L
        val inProgress = stats["in_progress"] ?: 0L
        val completed = stats["completed"] ?: 0L
        val failed = stats["failed"] ?: 0L

        val publicationStats = if (snapshot?.publicationStatsAvailable == true) {
            snapshot.publicationStatsBySource.values.fold(
                BookStackStats(totalEmbedded = 0, pending = 0, completed = 0, failed = 0, skipped = 0)
            ) { acc, source ->
                BookStackStats(
                    totalEmbedded = acc.totalEmbedded + source.totalEmbedded,
                    pending = acc.pending + source.pending,
                    completed = acc.completed + source.completed,
                    failed = acc.failed + source.failed,
                    skipped = acc.skipped + source.skipped
                )
            }
        } else {
            val bookstackStats = stagingStore.getBookStackStats()
            BookStackStats(
                totalEmbedded = bookstackStats["total_embedded"] ?: 0L,
                pending = bookstackStats["bookstack_pending"] ?: 0L,
                completed = bookstackStats["bookstack_completed"] ?: 0L,
                failed = bookstackStats["bookstack_failed"] ?: 0L,
                skipped = bookstackStats["bookstack_skipped"] ?: 0L
            )
        }
        val bookstackCompleted = publicationStats.completed
        val bookstackPending = publicationStats.pending
        val bookstackFailed = publicationStats.failed
        val bookstackSkipped = publicationStats.skipped

        val lastPending = lastStats["pending"] ?: 0L
        val lastCompleted = lastStats["completed"] ?: 0L
        val lastFailed = lastStats["failed"] ?: 0L
        val lastBookstackCompleted = lastStats["bookstack_completed"] ?: 0L

        val totalProcessed = completed + failed
        val lastTotalProcessed = (lastStats["completed"] ?: 0L) + (lastStats["failed"] ?: 0L)

        val deltaStaged = totalProcessed - lastTotalProcessed
        val deltaEmbedded = completed - lastCompleted
        val deltaEmbedFailed = failed - lastFailed
        val deltaBookstack = bookstackCompleted - lastBookstackCompleted

        val stagedRate = if (elapsed > 0) (deltaStaged * 60000.0 / elapsed).toInt() else 0
        val embeddedRate = if (elapsed > 0) (deltaEmbedded * 60000.0 / elapsed).toInt() else 0
        val bookstackRate = if (elapsed > 0) (deltaBookstack * 60000.0 / elapsed).toInt() else 0

        val summary = buildString {
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("🔥 PIPELINE PROGRESS (last ${reportIntervalSeconds}s)")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            
            if (deltaStaged > 0) {
                append("  📥 SCRAPING → STAGING: +$deltaStaged docs")
                if (stagedRate > 0) append(" @ $stagedRate/min")
                appendLine()
            }

            
            if (deltaEmbedded > 0 || deltaEmbedFailed > 0) {
                append("  🧠 STAGING → QDRANT: +$deltaEmbedded embedded")
                if (deltaEmbedFailed > 0) append(", +$deltaEmbedFailed failed")
                if (embeddedRate > 0) append(" @ $embeddedRate/min")
                appendLine()
            }

            
            if (deltaBookstack > 0) {
                append("  📚 STAGING → BOOKSTACK: +$deltaBookstack written")
                if (bookstackRate > 0) append(" @ $bookstackRate/min")
                appendLine()
            }

            
            if (deltaStaged == 0L && deltaEmbedded == 0L && deltaBookstack == 0L) {
                appendLine("  💤 Idle")
            }
            append("  📊 Queue: $pending pending | Embedded: $completed | BookStack: $bookstackCompleted published, $bookstackPending pending")
            if (bookstackSkipped > 0) append(", $bookstackSkipped skipped")
            if (bookstackFailed > 0) append(", $bookstackFailed failed")
            appendLine()
            if (snapshot != null && (!snapshot.queueFresh || !snapshot.publicationStatsFresh)) {
                appendLine("  ℹ️  Serving cached pipeline stats while the refresh loop catches up")
            }

            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }

        logger.info { "\n$summary" }

        lastStats = mapOf(
            "pending" to pending,
            "completed" to completed,
            "failed" to failed,
            "bookstack_completed" to bookstackCompleted
        )
        lastReportTime = now
    }
}
