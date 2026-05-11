package org.webservices.pipeline.monitoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.webservices.pipeline.scheduling.RunMetadata
import org.webservices.pipeline.scheduling.RunType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SourceRuntimeTrackerTest {
    @Test
    fun `register and snapshot initialize default awaiting state`() {
        val tracker = SourceRuntimeTracker()

        tracker.registerSource("rss")
        val snapshot = tracker.snapshot("rss")

        assertEquals("rss", snapshot.source)
        assertEquals("awaiting_first_run", snapshot.runState)
        assertEquals("awaiting_first_run", snapshot.phase)
        assertFalse(snapshot.activeRun)
        assertFalse(snapshot.completedInitialPull)
        assertTrue(tracker.snapshotAll().any { it.source == "rss" })
    }

    @Test
    fun `tracker records successful run lifecycle and counters`() {
        val clock = MutableClock(Instant.parse("2026-04-07T00:00:00Z"))
        val tracker = SourceRuntimeTracker(clock = clock)

        tracker.markRunStarting("rss", RunMetadata(RunType.INITIAL_PULL, attemptNumber = 1, isFirstRun = true))
        clock.advanceSeconds(5)
        tracker.markFetchProgress("rss")
        clock.advanceSeconds(5)
        tracker.markDocumentsStaged("rss", 3)
        tracker.markDeduplicated("rss", 2)
        tracker.markItemFailure("rss", "bad item")
        clock.advanceSeconds(5)
        tracker.markFetchCompleted("rss")
        clock.advanceSeconds(5)
        tracker.markRunSucceeded("rss")

        val snapshot = tracker.snapshot("rss")
        assertFalse(snapshot.activeRun)
        assertEquals("idle", snapshot.runState)
        assertEquals("idle", snapshot.phase)
        assertTrue(snapshot.completedInitialPull)
        assertEquals("initial_pull", snapshot.currentRunType)
        assertEquals(3, snapshot.stagedCurrentRun)
        assertEquals(2, snapshot.deduplicatedCurrentRun)
        assertEquals(1, snapshot.failedCurrentRun)
        assertEquals("bad item", snapshot.lastError)
        assertEquals("2026-04-07T00:00:00Z", snapshot.currentRunStartedAt)
        assertEquals("2026-04-07T00:00:20Z", snapshot.lastProgressAt)
        assertEquals("2026-04-07T00:00:15Z", snapshot.fetchCompletedAt)
        assertEquals("2026-04-07T00:00:20Z", snapshot.lastCompletedAt)
    }

    @Test
    fun `tracker detects stalled active runs and records failures`() {
        val clock = MutableClock(Instant.parse("2026-04-07T01:00:00Z"))
        val tracker = SourceRuntimeTracker(clock = clock, stallThreshold = Duration.ofMinutes(15))

        tracker.markRunStarting("cve", RunMetadata(RunType.RESYNC, attemptNumber = 2, isFirstRun = false))
        clock.advanceSeconds(16 * 60)

        val stalledSnapshot = tracker.snapshot("cve")
        assertTrue(tracker.isStalled(stalledSnapshot))

        tracker.markRunFailed("cve", "network down")
        val failedSnapshot = tracker.snapshot("cve")
        assertFalse(failedSnapshot.activeRun)
        assertEquals("failed", failedSnapshot.runState)
        assertEquals("failed", failedSnapshot.phase)
        assertEquals("network down", failedSnapshot.lastError)
        assertEquals("2026-04-07T01:16:00Z", failedSnapshot.lastFailedAt)
        assertFalse(tracker.isStalled(failedSnapshot))

        assertFalse(
            tracker.isStalled(
                SourceRuntimeSnapshot(
                    source = "broken",
                    activeRun = true,
                    runState = "running",
                    phase = "fetching",
                    completedInitialPull = false,
                    lastProgressAt = "not-a-timestamp"
                )
            )
        )
        assertNull(tracker.snapshot("missing").currentRunStartedAt)
    }
}

private class MutableClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
