package org.webservices.pipeline.monitoring

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.webservices.pipeline.storage.DocumentStagingStore

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressReporterTest {
    @Test
    fun `start records latest staging and bookstack stats after one interval`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        coEvery { stagingStore.getStats() } returns mapOf(
            "pending" to 4L,
            "in_progress" to 2L,
            "completed" to 9L,
            "failed" to 1L
        )
        coEvery { stagingStore.getBookStackStats() } returns mapOf(
            "bookstack_completed" to 5L,
            "bookstack_pending" to 3L,
            "bookstack_failed" to 1L,
            "bookstack_skipped" to 2L
        )

        val reporter = ProgressReporter(stagingStore, reportIntervalSeconds = 1)
        val job = launch {
            reporter.start()
        }
        advanceTimeBy(1_000)
        runCurrent()
        job.cancelAndJoin()

        assertEquals(
            mapOf(
                "pending" to 4L,
                "completed" to 9L,
                "failed" to 1L,
                "bookstack_completed" to 5L
            ),
            readField<Map<String, Long>>(reporter, "lastStats")
        )
        coVerify(exactly = 1) { stagingStore.getStats() }
        coVerify(exactly = 1) { stagingStore.getBookStackStats() }
    }

    @Test
    fun `start keeps running when stats fetch throws`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        coEvery { stagingStore.getStats() } throws IllegalStateException("boom")

        val reporter = ProgressReporter(stagingStore, reportIntervalSeconds = 1)
        val job = launch {
            reporter.start()
        }
        advanceTimeBy(1_000)
        runCurrent()
        job.cancelAndJoin()

        assertTrue(readField<Map<String, Long>>(reporter, "lastStats").isEmpty())
        coVerify(exactly = 1) { stagingStore.getStats() }
        coVerify(exactly = 0) { stagingStore.getBookStackStats() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, fieldName: String): T {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target) as T
    }
}
