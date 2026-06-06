package org.webservices.gpuworkloadmonitor

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.webservices.pipeline.monitoring.SourceReadiness
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.SourceReadinessStats
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GpuWorkloadMonitorServiceTest {
    @Test
    fun `uses readiness and evidence to build workload signal`() = runBlocking {
        val service = service(
            evidence = mapOf(
                "wikipedia" to SourceReadinessStats(
                    searchableDocuments = 100,
                    pendingEmbedding = 5,
                    inProgressEmbedding = 2,
                    failedEmbedding = 0,
                    publishedDocuments = 75,
                    pendingPublication = 3,
                    failedPublication = 0,
                    skippedPublication = 0
                )
            ),
            readiness = mapOf(
                "wikipedia" to readiness(source = "wikipedia", initialPullComplete = true, activeRun = false)
            )
        )

        val status = service.evaluateStatus()

        assertEquals("ok", status.status)
        assertTrue(status.ready)
        assertTrue(status.signal.decisionInputsHealthy)
        assertEquals(5, status.signal.totalPendingEmbedding)
        assertEquals(2, status.signal.totalInProgressEmbedding)
        assertTrue(status.signal.incompleteSources.isEmpty())
    }

    @Test
    fun `falls back to evidence when readiness is unavailable`() = runBlocking {
        val service = service(
            evidence = mapOf(
                "rss" to SourceReadinessStats(
                    searchableDocuments = 1,
                    pendingEmbedding = 0,
                    inProgressEmbedding = 0,
                    failedEmbedding = 0,
                    publishedDocuments = 1,
                    pendingPublication = 0,
                    failedPublication = 0,
                    skippedPublication = 0
                )
            ),
            readiness = emptyMap(),
            sourceIds = listOf("rss")
        )

        val status = service.evaluateStatus()

        assertTrue(status.signal.sources.single().decisionInputAvailable)
        assertTrue(status.signal.sources.single().evidenceAvailable)
        assertFalse(status.signal.sources.single().readinessAvailable)
        assertTrue(status.signal.sources.single().initialPullComplete)
        assertTrue(status.ready)
    }

    @Test
    fun `reports degraded when neither evidence nor readiness is available`() = runBlocking {
        val service = service(
            evidence = emptyMap(),
            readiness = emptyMap(),
            sourceIds = listOf("cve")
        )

        val status = service.evaluateStatus()

        assertEquals("degraded", status.status)
        assertFalse(status.ready)
        assertFalse(status.signal.decisionInputsHealthy)
        assertEquals(listOf("cve"), status.signal.incompleteSources)
    }

    private fun service(
        evidence: Map<String, SourceReadinessStats>,
        readiness: Map<String, SourceReadiness>,
        sourceIds: List<String> = listOf("wikipedia")
    ): GpuWorkloadMonitorService {
        val config = WorkloadMonitorConfig(
            port = 8112,
            postgresJdbcUrl = "jdbc:postgresql://postgres-ssd:5432/webservices",
            postgresUser = "pipeline_user",
            postgresPassword = "secret",
            sourceIds = sourceIds,
            knowledgeIngestionReadinessBaseUrl = "http://ingestion-runner:8090/health",
            evaluationIntervalSeconds = 300,
            sourceQueryTimeoutMs = 1000,
            readinessHttpTimeoutMs = 1000
        )
        val stagingStore = mockk<DocumentStagingStore>()
        coEvery {
            stagingStore.getReadinessEvidenceBySourcesWithQueryTimeout(any(), any())
        } answers {
            val sources = firstArg<List<String>>()
            buildMap {
                sources.forEach { source ->
                    evidence[source]?.let { put(source, it) }
                }
            }
        }
        every { stagingStore.close() } returns Unit
        return GpuWorkloadMonitorService(
            config = config,
            stagingStore = stagingStore,
            sourceReadinessClient = object : SourceReadinessClient {
                override suspend fun fetch(sourceId: String): SourceReadiness? = readiness[sourceId]
            },
            clock = Clock.fixed(Instant.parse("2026-04-13T00:00:00Z"), ZoneOffset.UTC)
        )
    }

    private fun readiness(source: String, initialPullComplete: Boolean, activeRun: Boolean): SourceReadiness {
        return SourceReadiness(
            source = source,
            name = source,
            enabled = true,
            runState = if (activeRun) "running" else "idle",
            phase = if (activeRun) "embedding" else "idle",
            activeRun = activeRun,
            completedInitialPull = initialPullComplete,
            initialPullComplete = initialPullComplete,
            pendingEmbedding = 0,
            inProgressEmbedding = 0,
            searchableDocuments = if (initialPullComplete) 1 else 0,
            blockers = emptyList()
        )
    }
}
