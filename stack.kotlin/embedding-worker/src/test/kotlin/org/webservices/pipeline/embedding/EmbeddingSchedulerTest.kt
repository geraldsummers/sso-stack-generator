package org.webservices.pipeline.embedding

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.webservices.pipeline.processors.Embedder
import org.webservices.pipeline.sinks.QdrantSink
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.EmbeddingStatus
import org.webservices.pipeline.storage.StagedDocument
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant


class EmbeddingSchedulerTest {

    private lateinit var stagingStore: DocumentStagingStore
    private lateinit var embedder: Embedder
    private lateinit var qdrantSink: QdrantSink
    private lateinit var scheduler: EmbeddingScheduler

    @BeforeEach
    fun setup() {
        stagingStore = mockk(relaxed = true)
        embedder = mockk()
        qdrantSink = mockk(relaxed = true)

        
        coEvery { embedder.process(any()) } returns floatArrayOf(0.1f, 0.2f, 0.3f)

        scheduler = EmbeddingScheduler(
            stagingStore = stagingStore,
            embedder = embedder,
            qdrantSinks = mapOf("test_collection" to qdrantSink),
            batchSize = 10,
            pollInterval = 1,
            maxConcurrentEmbeddings = 5,
            maxRetries = 3
        )
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `scheduler initializes with correct parameters`() {
        assertNotNull(scheduler)
    }

    @Test
    fun `getStats returns configuration values`() = runBlocking {
        coEvery { stagingStore.getStats() } returns mapOf(
            "pending" to 10L,
            "in_progress" to 2L,
            "completed" to 100L,
            "failed" to 1L
        )

        val stats = scheduler.getStats()

        assertEquals(10, stats["batch_size"])
        assertEquals(1, stats["poll_interval_seconds"])
        assertEquals(5, stats["max_concurrent"])
        assertEquals(10L, stats["pending"])
        assertEquals(2L, stats["in_progress"])
    }

    @Test
    fun `getStatsBySource calls staging store`() = runBlocking {
        coEvery { stagingStore.getStatsBySource("test_source") } returns mapOf(
            "pending" to 5L,
            "in_progress" to 1L,
            "completed" to 50L,
            "failed" to 0L
        )

        val stats = scheduler.getStatsBySource("test_source")

        assertEquals(5L, stats["pending"])
        assertEquals(1L, stats["in_progress"])
        assertEquals(50L, stats["completed"])
        assertEquals(0L, stats["failed"])
    }

    @Test
    fun `scheduler handles empty stats gracefully`() = runBlocking {
        coEvery { stagingStore.getStats() } returns emptyMap()

        val stats = scheduler.getStats()

        
        assertEquals(10, stats["batch_size"])
    }

    @Test
    fun `scheduler configuration is immutable after creation`() {
        val stats1 = runBlocking { scheduler.getStats() }
        val stats2 = runBlocking { scheduler.getStats() }

        assertEquals(stats1["batch_size"], stats2["batch_size"])
        assertEquals(stats1["poll_interval_seconds"], stats2["poll_interval_seconds"])
    }

    @Test
    fun `partitionDocsForEmbeddingRequests respects request byte ceiling`() {
        val docs = List(5) { index ->
            stagedDocument("doc-$index", "x".repeat(100))
        }

        val byteBoundScheduler = EmbeddingScheduler(
            stagingStore = stagingStore,
            embedder = embedder,
            qdrantSinks = mapOf("test_collection" to qdrantSink),
            batchSize = 10,
            pollInterval = 1,
            maxConcurrentEmbeddings = 5,
            maxRetries = 3,
            embeddingRequestBatchSize = 10,
            maxConcurrentBatchRequests = 2,
            embeddingRequestMaxBytes = 600
        )

        val batches = byteBoundScheduler.partitionDocsForEmbeddingRequests(docs)

        assertEquals(listOf(2, 2, 1), batches.map { it.size })
    }

    @Test
    fun `processDocumentBatch splits oversized batches before falling back to singles`() = runBlocking {
        val docs = List(4) { index ->
            stagedDocument("doc-$index", "payload-$index")
        }

        coEvery { embedder.processBatch(any()) } coAnswers {
            val texts = firstArg<List<String>>()
            if (texts.size > 2) {
                throw Exception("simulated oversized batch")
            }
            texts.map { floatArrayOf(0.1f, 0.2f, 0.3f) }
        }
        coEvery { qdrantSink.writeBatch(any()) } just Runs

        scheduler.processDocumentBatch(docs)

        coVerify(exactly = 3) { embedder.processBatch(any()) }
        coVerify(exactly = 0) { embedder.process(any()) }
        coVerify(exactly = 2) { qdrantSink.writeBatch(match { it.size == 2 }) }
        coVerify(atLeast = 1) {
            qdrantSink.writeBatch(match { batch ->
                batch.all { item -> item.metadata["document_id"] == item.id }
            })
        }
        coVerify(atLeast = 1) { stagingStore.updateStatusBatch(match { it.size == 4 }, EmbeddingStatus.IN_PROGRESS) }
        coVerify(atLeast = 2) { stagingStore.updateStatusBatch(match { it.size == 2 }, EmbeddingStatus.COMPLETED) }
    }

    @Test
    fun `runIteration defers background work when cpu fallback is reserved`() = runBlocking {
        val reservedScheduler = EmbeddingScheduler(
            stagingStore = stagingStore,
            embedder = embedder,
            qdrantSinks = mapOf("test_collection" to qdrantSink),
            batchSize = 10,
            pollInterval = 1,
            maxConcurrentEmbeddings = 5,
            maxRetries = 3,
            limitsProvider = object : EmbeddingSchedulerLimitsProvider {
                override val defaultLimits = EmbeddingSchedulerLimits(
                    profile = EmbeddingExecutionProfile.GPU_BULK,
                    batchSize = 10,
                    maxConcurrentEmbeddings = 5,
                    embeddingRequestBatchSize = 4,
                    maxConcurrentBatchRequests = 2,
                    embeddingRequestMaxBytes = 1024
                )

                override suspend fun currentLimits(): EmbeddingSchedulerLimits = defaultLimits.cpuReserved()
            }
        )

        val shouldSleep = reservedScheduler.runIteration()

        assertTrue(shouldSleep)
        coVerify(exactly = 0) { stagingStore.getPendingBatch(any()) }
        coVerify(exactly = 0) { embedder.processBatch(any()) }
    }

    @Test
    fun `runIteration uses throttled cpu limits when provided`() = runBlocking {
        val docs = listOf(
            stagedDocument("doc-1", "payload-1"),
            stagedDocument("doc-2", "payload-2")
        )
        coEvery { stagingStore.getPendingBatch(1) } returns docs.take(1) andThen emptyList()
        coEvery { embedder.processBatch(listOf("payload-1")) } returns listOf(floatArrayOf(0.1f, 0.2f, 0.3f))
        coEvery { qdrantSink.writeBatch(any()) } just Runs

        val throttledScheduler = EmbeddingScheduler(
            stagingStore = stagingStore,
            embedder = embedder,
            qdrantSinks = mapOf("test_collection" to qdrantSink),
            batchSize = 10,
            pollInterval = 1,
            maxConcurrentEmbeddings = 5,
            maxRetries = 3,
            limitsProvider = object : EmbeddingSchedulerLimitsProvider {
                override val defaultLimits = EmbeddingSchedulerLimits(
                    profile = EmbeddingExecutionProfile.GPU_BULK,
                    batchSize = 10,
                    maxConcurrentEmbeddings = 5,
                    embeddingRequestBatchSize = 4,
                    maxConcurrentBatchRequests = 2,
                    embeddingRequestMaxBytes = 1024
                )

                override suspend fun currentLimits(): EmbeddingSchedulerLimits = defaultLimits.cpuThrottled()
            }
        )

        val shouldSleep = throttledScheduler.runIteration()

        assertFalse(shouldSleep)
        coVerify(exactly = 1) { stagingStore.getPendingBatch(1) }
        coVerify(exactly = 1) { embedder.processBatch(listOf("payload-1")) }
    }

    private fun stagedDocument(id: String, text: String) = StagedDocument(
        id = id,
        source = "test_source",
        collection = "test_collection",
        text = text,
        metadata = emptyMap(),
        embeddingStatus = EmbeddingStatus.PENDING,
        createdAt = Instant.parse("2026-04-09T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-09T00:00:00Z")
    )
}
