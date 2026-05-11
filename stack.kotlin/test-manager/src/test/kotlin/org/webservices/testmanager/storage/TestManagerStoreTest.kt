package org.webservices.testmanager.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TestManagerStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `persists suite state and queued run lifecycle`() {
        TestManagerStore(tempDir.resolve("test-manager.db")).use { store ->
            assertNull(store.getSuiteState("auth-smoke"))
            assertNull(store.claimNextQueuedRun())

            store.upsertSuiteState(
                SuiteStateRecord(
                    suiteName = "auth-smoke",
                    title = "Auth Smoke",
                    command = "./run-tests.sh ts-e2e-smoke",
                    state = "blocked",
                    blockers = listOf("container caddy is missing"),
                    prerequisitesMet = false,
                    fresh = false,
                    lastTriggerReason = "initial",
                    lastEvaluatedAt = "2026-04-07T00:00:00Z",
                    consumedReleaseFingerprint = "release-a",
                    consumedDomainFingerprint = "domain-a",
                    consumedWatchFingerprint = "watch-a",
                    lastQueuedAt = "2026-04-07T00:01:00Z"
                )
            )

            val persistedState = store.getSuiteState("auth-smoke")
            assertNotNull(persistedState)
            assertEquals(listOf("container caddy is missing"), persistedState?.blockers)
            assertFalse(persistedState!!.prerequisitesMet)
            assertEquals("blocked", persistedState.state)

            val firstId = store.queueRun(
                suiteName = "auth-smoke",
                title = "Auth Smoke",
                command = "./run-tests.sh ts-e2e-smoke",
                triggerReason = "manual",
                requestedBy = "gerald",
                releaseFingerprint = "release-a",
                domainFingerprint = "domain-a",
                watchFingerprint = "watch-a"
            )
            val secondId = store.queueRun(
                suiteName = "browser-deep",
                title = "Browser Deep",
                command = "./run-tests.sh ts-e2e-deep",
                triggerReason = "cadence",
                requestedBy = "scheduler",
                releaseFingerprint = "release-b",
                domainFingerprint = "domain-b",
                watchFingerprint = null
            )

            assertTrue(store.hasQueuedOrRunningRun("auth-smoke"))
            assertEquals(2, store.listRuns(limit = 10).size)

            val claimed = store.claimNextQueuedRun()
            assertNotNull(claimed)
            assertEquals(firstId, claimed!!.id)
            assertEquals("running", claimed.status)
            assertNotNull(claimed.startedAt)

            store.completeRun(
                id = firstId,
                status = "passed",
                exitCode = 0,
                logPath = "/tmp/auth-smoke.log",
                logTail = "ok"
            )

            val completed = store.getRun(firstId)
            assertEquals("passed", completed?.status)
            assertEquals(0, completed?.exitCode)
            assertEquals("/tmp/auth-smoke.log", completed?.logPath)
            assertEquals("ok", completed?.logTail)
            assertNotNull(completed?.finishedAt)

            val latest = store.latestRunsBySuite()
            assertEquals(setOf("auth-smoke", "browser-deep"), latest.keys)
            assertEquals(secondId, latest["browser-deep"]?.id)

            store.upsertSuiteState(
                persistedState.copy(
                    state = "passing",
                    blockers = emptyList(),
                    prerequisitesMet = true,
                    fresh = true,
                    lastRunId = firstId
                )
            )

            val updatedState = store.getSuiteState("auth-smoke")
            assertEquals("passing", updatedState?.state)
            assertTrue(updatedState?.fresh == true)
            assertEquals(firstId, updatedState?.lastRunId)
        }
    }

    @Test
    fun `run view mirrors run record fields`() {
        val record = RunRecord(
            id = 42,
            suiteName = "kotlin-integration",
            title = "Kotlin Integration",
            command = "./run-tests.sh kt",
            status = "failed",
            triggerReason = "cadence",
            requestedBy = "scheduler",
            requestedAt = "2026-04-07T00:00:00Z",
            startedAt = "2026-04-07T00:00:30Z",
            finishedAt = "2026-04-07T00:01:30Z",
            exitCode = 1,
            logPath = "/tmp/run.log",
            logTail = "boom"
        )

        val view = record.toView()
        assertEquals(record.id, view.id)
        assertEquals(record.suiteName, view.suiteName)
        assertEquals(record.status, view.status)
        assertEquals(record.logTail, view.logTail)
    }
}
