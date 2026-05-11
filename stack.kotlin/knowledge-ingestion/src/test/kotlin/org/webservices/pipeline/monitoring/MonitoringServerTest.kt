package org.webservices.pipeline.monitoring

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.webservices.pipeline.scheduling.RunMetadata
import org.webservices.pipeline.scheduling.RunType
import org.webservices.pipeline.storage.BookStackStats
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.SourceReadinessStats
import org.webservices.pipeline.storage.SourceMetadata
import org.webservices.pipeline.storage.SourceMetadataStore
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MonitoringServerTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `routes require api key and expose readiness queue and status data`() = testApplication {
        val metadataStore = SourceMetadataStore(tempDir.toString())
        metadataStore.save(
            SourceMetadata(
                sourceName = "rss",
                lastSuccessfulRun = "2026-04-07T00:30:00Z",
                lastAttemptedRun = "2026-04-07T00:30:00Z",
                totalItemsProcessed = 12,
                totalItemsFailed = 1,
                consecutiveFailures = 4,
                checkpointData = mapOf("cursor" to "123")
            )
        )
        metadataStore.save(SourceMetadata(sourceName = "disabled"))

        val tracker = SourceRuntimeTracker(Clock.fixed(Instant.parse("2026-04-07T00:40:00Z"), ZoneOffset.UTC))
        tracker.markRunStarting("rss", RunMetadata(RunType.INITIAL_PULL, attemptNumber = 1, isFirstRun = true))
        tracker.markDocumentsStaged("rss", 9)
        tracker.markDeduplicated("rss", 2)
        tracker.markFetchCompleted("rss")
        tracker.markRunSucceeded("rss")
        tracker.registerSource("disabled")

        val stagingStore = mockk<DocumentStagingStore>()
        coEvery { stagingStore.getStatsWithQueryTimeout(any()) } returns mapOf(
            "pending" to 3L,
            "in_progress" to 1L,
            "completed" to 9L,
            "failed" to 1L
        )
        coEvery { stagingStore.getStatsBySourceWithQueryTimeout("rss", any()) } returns mapOf(
            "pending" to 2L,
            "in_progress" to 1L,
            "completed" to 9L,
            "failed" to 1L
        )
        coEvery { stagingStore.getStatsBySourceWithQueryTimeout("disabled", any()) } returns emptyMap()
        coEvery { stagingStore.getStatsBySourcesWithQueryTimeout(listOf("rss", "disabled"), any()) } returns mapOf(
            "rss" to mapOf(
                "pending" to 2L,
                "in_progress" to 1L,
                "completed" to 9L,
                "failed" to 1L
            ),
            "disabled" to emptyMap()
        )
        coEvery { stagingStore.getStatsBySourcesWithQueryTimeout(listOf("rss"), any()) } returns mapOf(
            "rss" to mapOf(
                "pending" to 2L,
                "in_progress" to 1L,
                "completed" to 9L,
                "failed" to 1L
            )
        )
        coEvery { stagingStore.getBookStackStatsBySourcesWithQueryTimeout(listOf("rss", "disabled"), any()) } returns mapOf(
            "rss" to BookStackStats(
                totalEmbedded = 9,
                pending = 2,
                completed = 6,
                failed = 1,
                skipped = 2
            ),
            "disabled" to BookStackStats(
                totalEmbedded = 0,
                pending = 0,
                completed = 0,
                failed = 0,
                skipped = 0
            )
        )
        coEvery { stagingStore.getBookStackStatsBySourcesWithQueryTimeout(listOf("rss"), any()) } returns mapOf(
            "rss" to BookStackStats(
                totalEmbedded = 9,
                pending = 2,
                completed = 6,
                failed = 1,
                skipped = 2
            )
        )

        val server = MonitoringServer(
            port = 0,
            metadataStore = metadataStore,
            sources = listOf(
                MonitoredSourceDefinition("rss", "RSS", "RSS feed ingestion", enabled = true),
                MonitoredSourceDefinition("disabled", "Disabled", "Disabled source", enabled = false)
            ),
            stagingStore = stagingStore,
            runtimeTracker = tracker,
            apiKey = "secret",
            clock = Clock.fixed(Instant.parse("2026-04-07T00:40:00Z"), ZoneOffset.UTC)
        )

        application {
            with(server) {
                configureMonitoringModule()
            }
        }

        val unauthorized = client.get("/health")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val dashboard = client.get("/") {
            header("X-API-Key", "secret")
        }
        assertEquals(HttpStatusCode.OK, dashboard.status)
        assertTrue(dashboard.bodyAsText().contains("Pipeline Readiness"))

        val actuator = client.get("/actuator/health") {
            header("Authorization", "Bearer secret")
        }
        val actuatorBody = json.decodeFromString<HealthResponse>(actuator.bodyAsText())
        assertEquals("UP", actuatorBody.status)

        val sourcesResponse = json.decodeFromString<SourcesResponse>(
            client.get("/sources") { header("X-API-Key", "secret") }.bodyAsText()
        )
        assertEquals(2, sourcesResponse.sources.size)
        assertEquals("rss", sourcesResponse.sources.first().id)

        val statusResponse = json.decodeFromString<StatusResponse>(
            client.get("/status") { header("X-API-Key", "secret") }.bodyAsText()
        )
        val rssStatus = statusResponse.sources.first { it.source == "rss" }
        assertEquals("degraded", rssStatus.status)
        assertEquals(mapOf("cursor" to "123"), rssStatus.checkpointData)
        assertEquals(true, rssStatus.initialPullComplete)
        assertEquals(9L, rssStatus.searchableDocuments)
        assertEquals(6L, rssStatus.publishedDocuments)
        assertTrue(rssStatus.blockers.isEmpty())
        assertEquals("disabled", statusResponse.sources.first { it.source == "disabled" }.status)

        val queueResponse = json.decodeFromString<QueueStatusResponse>(
            client.get("/queue") { header("X-API-Key", "secret") }.bodyAsText()
        )
        assertTrue(queueResponse.available)
        assertEquals(3, queueResponse.pending)
        assertEquals(1, queueResponse.inProgress)

        val sourceQueue = json.decodeFromString<SourceQueueStatusResponse>(
            client.get("/queue/rss") { header("X-API-Key", "secret") }.bodyAsText()
        )
        assertTrue(sourceQueue.available)
        assertEquals("rss", sourceQueue.source)
        assertEquals(9, sourceQueue.completed)

        val readiness = json.decodeFromString<PipelineReadinessResponse>(
            client.get("/readiness") { header("X-API-Key", "secret") }.bodyAsText()
        )
        val rssReadiness = readiness.sources.first { it.source == "rss" }
        assertTrue(rssReadiness.initialPullComplete)
        assertEquals(9, rssReadiness.searchableDocuments)
        assertEquals(6, rssReadiness.publishedDocuments)
        assertEquals(2, rssReadiness.pendingPublication)
        assertTrue(rssReadiness.blockers.isEmpty())

        val disabledReadiness = readiness.sources.first { it.source == "disabled" }
        assertTrue(disabledReadiness.blockers.contains("source disabled"))
        assertTrue(disabledReadiness.blockers.contains("initial pull not yet completed since service start"))
        assertTrue(disabledReadiness.blockers.contains("no searchable documents staged yet"))

        val rssDetail = json.decodeFromString<SourceReadiness>(
            client.get("/readiness/rss") { header("X-API-Key", "secret") }.bodyAsText()
        )
        assertEquals("rss", rssDetail.source)

        val missing = client.get("/readiness/unknown") {
            header("X-API-Key", "secret")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `queue endpoints report unavailable when no staging store is configured`() = testApplication {
        val server = MonitoringServer(
            port = 0,
            metadataStore = SourceMetadataStore(tempDir.toString()),
            sources = listOf(MonitoredSourceDefinition("rss", "RSS", "RSS feed ingestion", enabled = true)),
            stagingStore = null,
            apiKey = null
        )

        application {
            with(server) {
                configureMonitoringModule()
            }
        }

        val queue = json.decodeFromString<QueueStatusResponse>(client.get("/queue").bodyAsText())
        assertEquals(false, queue.available)
        assertEquals("Queue monitoring not available", queue.message)

        val sourceQueue = json.decodeFromString<SourceQueueStatusResponse>(client.get("/queue/rss").bodyAsText())
        assertEquals(false, sourceQueue.available)
        assertEquals("Queue monitoring not available", sourceQueue.message)
    }

    @Test
    fun `readiness treats persisted success as initial pull complete after restart`() = testApplication {
        val metadataStore = SourceMetadataStore(tempDir.toString())
        metadataStore.save(
            SourceMetadata(
                sourceName = "rss",
                lastSuccessfulRun = "2026-04-07T00:30:00Z",
                lastAttemptedRun = "2026-04-07T00:30:00Z"
            )
        )

        val tracker = SourceRuntimeTracker()
        tracker.registerSource("rss")

        val stagingStore = mockk<DocumentStagingStore>()
        coEvery { stagingStore.getStatsBySourcesWithQueryTimeout(listOf("rss"), any()) } returns mapOf(
            "rss" to mapOf(
                "completed" to 9L,
                "pending" to 0L,
                "in_progress" to 0L,
                "failed" to 0L
            )
        )
        coEvery { stagingStore.getBookStackStatsBySourcesWithQueryTimeout(listOf("rss"), any()) } returns mapOf(
            "rss" to BookStackStats(
                totalEmbedded = 9,
                pending = 0,
                completed = 6,
                failed = 0,
                skipped = 0
            )
        )

        val server = MonitoringServer(
            port = 0,
            metadataStore = metadataStore,
            sources = listOf(MonitoredSourceDefinition("rss", "RSS", "RSS feed ingestion", enabled = true)),
            stagingStore = stagingStore,
            runtimeTracker = tracker,
            apiKey = null
        )

        application {
            with(server) {
                configureMonitoringModule()
            }
        }

        val readiness = json.decodeFromString<PipelineReadinessResponse>(client.get("/readiness").bodyAsText())
        val rssReadiness = readiness.sources.first()
        assertTrue(rssReadiness.initialPullComplete)
        assertTrue("initial pull not yet completed since service start" !in rssReadiness.blockers)
    }

    @Test
    fun `queue endpoints report timeout when staging queries time out`() = testApplication {
        val stagingStore = mockk<DocumentStagingStore>()
        coEvery { stagingStore.getStatsWithQueryTimeout(any()) } returns null
        coEvery { stagingStore.getStatsBySourceWithQueryTimeout(any(), any()) } returns null
        coEvery { stagingStore.getStatsBySourcesWithQueryTimeout(any(), any()) } returns null
        coEvery { stagingStore.getBookStackStatsBySourcesWithQueryTimeout(any(), any()) } returns null
        coEvery { stagingStore.getReadinessEvidenceBySourcesWithQueryTimeout(any(), any()) } returns null

        val server = MonitoringServer(
            port = 0,
            metadataStore = SourceMetadataStore(tempDir.toString()),
            sources = listOf(MonitoredSourceDefinition("rss", "RSS", "RSS feed ingestion", enabled = true)),
            stagingStore = stagingStore,
            apiKey = null
        )

        application {
            with(server) {
                configureMonitoringModule()
            }
        }

        val queue = json.decodeFromString<QueueStatusResponse>(client.get("/queue").bodyAsText())
        assertEquals(false, queue.available)
        assertTrue(queue.message.contains("timed out"))

        val sourceQueue = json.decodeFromString<SourceQueueStatusResponse>(client.get("/queue/rss").bodyAsText())
        assertEquals(false, sourceQueue.available)
        assertTrue(sourceQueue.message.contains("timed out"))

        val readiness = json.decodeFromString<PipelineReadinessResponse>(client.get("/readiness").bodyAsText())
        val rssReadiness = readiness.sources.single()
        assertTrue(rssReadiness.blockers.contains("queue stats unavailable"))
        assertTrue(rssReadiness.blockers.contains("publication stats unavailable"))

        val statusResponse = json.decodeFromString<StatusResponse>(client.get("/status").bodyAsText())
        val rssStatus = statusResponse.sources.single()
        assertEquals("degraded", rssStatus.status)
        assertTrue(rssStatus.blockers.contains("queue stats unavailable"))
        assertTrue(rssStatus.blockers.contains("publication stats unavailable"))
    }

    @Test
    fun `readiness falls back to cheap evidence query when aggregate stats time out`() = testApplication {
        val stagingStore = mockk<DocumentStagingStore>()
        coEvery { stagingStore.getStatsBySourcesWithQueryTimeout(any(), any()) } returns null
        coEvery { stagingStore.getBookStackStatsBySourcesWithQueryTimeout(any(), any()) } returns null
        coEvery { stagingStore.getReadinessEvidenceBySourcesWithQueryTimeout(listOf("rss"), any()) } returns mapOf(
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
        )

        val server = MonitoringServer(
            port = 0,
            metadataStore = SourceMetadataStore(tempDir.toString()),
            sources = listOf(MonitoredSourceDefinition("rss", "RSS", "RSS feed ingestion", enabled = true)),
            stagingStore = stagingStore,
            apiKey = null
        )

        application {
            with(server) {
                configureMonitoringModule()
            }
        }

        val readiness = json.decodeFromString<PipelineReadinessResponse>(client.get("/readiness").bodyAsText())
        val rssReadiness = readiness.sources.single()
        assertEquals(1, rssReadiness.searchableDocuments)
        assertEquals(1, rssReadiness.publishedDocuments)
        assertTrue("queue stats unavailable" !in rssReadiness.blockers)
        assertTrue("publication stats unavailable" !in rssReadiness.blockers)
    }

    @Test
    fun `readiness falls back when cached snapshot exists but source stats are unavailable`() = testApplication {
        val stagingStore = mockk<DocumentStagingStore>()
        coEvery { stagingStore.getStatsWithQueryTimeout(any()) } returns null
        coEvery { stagingStore.getStatsBySourcesWithQueryTimeout(any(), any()) } returns null
        coEvery { stagingStore.getBookStackStatsBySourcesWithQueryTimeout(any(), any()) } returns null
        coEvery { stagingStore.getReadinessEvidenceBySourcesWithQueryTimeout(listOf("rss"), any()) } returns mapOf(
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
        )

        val statsCache = PipelineStatsCache(
            stagingStore = stagingStore,
            sourceIdsProvider = { listOf("rss") },
            clock = Clock.fixed(Instant.parse("2026-04-13T02:00:00Z"), ZoneOffset.UTC)
        )
        statsCache.refreshNow()

        val server = MonitoringServer(
            port = 0,
            metadataStore = SourceMetadataStore(tempDir.toString()),
            sources = listOf(MonitoredSourceDefinition("rss", "RSS", "RSS feed ingestion", enabled = true)),
            stagingStore = stagingStore,
            statsCache = statsCache,
            apiKey = null
        )

        application {
            with(server) {
                configureMonitoringModule()
            }
        }

        val readiness = json.decodeFromString<PipelineReadinessResponse>(client.get("/readiness").bodyAsText())
        val rssReadiness = readiness.sources.single()
        assertEquals(1, rssReadiness.searchableDocuments)
        assertEquals(1, rssReadiness.publishedDocuments)
        assertTrue("queue stats unavailable" !in rssReadiness.blockers)
        assertTrue("publication stats unavailable" !in rssReadiness.blockers)
    }
}
