package org.webservices.pipeline

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.SourceMetadataStore
import org.webservices.pipeline.storage.SourceReadinessStats
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PipelineStateRecoveryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `recover persisted pipeline state repairs missing success metadata from staged evidence`() = runBlocking {
        val stagingStore = mockk<DocumentStagingStore>()
        coEvery {
            stagingStore.getReadinessEvidenceBySourcesWithQueryTimeout(listOf("rss"), any())
        } returns mapOf(
            "rss" to SourceReadinessStats(
                searchableDocuments = 1,
                pendingEmbedding = 0,
                inProgressEmbedding = 0,
                failedEmbedding = 0,
                publishedDocuments = 0,
                pendingPublication = 0,
                failedPublication = 0,
                skippedPublication = 0
            )
        )
        coEvery {
            stagingStore.getReadinessEvidenceBySourcesWithQueryTimeout(listOf("cve"), any())
        } returns mapOf(
            "cve" to SourceReadinessStats(
                searchableDocuments = 0,
                pendingEmbedding = 1,
                inProgressEmbedding = 0,
                failedEmbedding = 0,
                publishedDocuments = 0,
                pendingPublication = 0,
                failedPublication = 0,
                skippedPublication = 0
            )
        )

        val metadataStore = SourceMetadataStore(tempDir.toString())

        val repaired = recoverPersistedPipelineState(
            stagingStore = stagingStore,
            metadataStore = metadataStore,
            sourceIds = listOf("rss", "cve"),
            clock = Clock.fixed(Instant.parse("2026-04-13T01:00:00Z"), ZoneOffset.UTC)
        )

        assertEquals(1, repaired)
        assertNotNull(metadataStore.load("rss").lastSuccessfulRun)
        assertNull(metadataStore.load("cve").lastSuccessfulRun)
    }
}
