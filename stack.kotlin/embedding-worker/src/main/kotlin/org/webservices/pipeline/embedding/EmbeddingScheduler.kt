package org.webservices.pipeline.embedding

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.webservices.pipeline.processors.Embedder
import org.webservices.pipeline.sinks.QdrantSink
import org.webservices.pipeline.sinks.VectorDocument
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.EmbeddingStatus
import org.webservices.pipeline.storage.StagedDocument
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private const val REQUEST_BODY_OVERHEAD_BYTES = 128


class EmbeddingScheduler(
    private val stagingStore: DocumentStagingStore,
    private val embedder: Embedder,
    private val qdrantSinks: Map<String, QdrantSink>,
    private val batchSize: Int = 50,
    private val pollInterval: Int = 10,
    private val maxConcurrentEmbeddings: Int = 10,
    private val maxRetries: Int = 3,
    private val embeddingRequestBatchSize: Int = 32,
    private val maxConcurrentBatchRequests: Int = 4,
    private val embeddingRequestMaxBytes: Int = 262_144,
    private val limitsProvider: EmbeddingSchedulerLimitsProvider = StaticEmbeddingSchedulerLimitsProvider(
        EmbeddingSchedulerLimits(
            profile = EmbeddingExecutionProfile.GPU_BULK,
            batchSize = batchSize,
            maxConcurrentEmbeddings = maxConcurrentEmbeddings,
            embeddingRequestBatchSize = embeddingRequestBatchSize,
            maxConcurrentBatchRequests = maxConcurrentBatchRequests,
            embeddingRequestMaxBytes = embeddingRequestMaxBytes
        )
    )
) {
    private val configuredLimits = EmbeddingSchedulerLimits(
        profile = EmbeddingExecutionProfile.GPU_BULK,
        batchSize = batchSize,
        maxConcurrentEmbeddings = maxConcurrentEmbeddings,
        embeddingRequestBatchSize = embeddingRequestBatchSize,
        maxConcurrentBatchRequests = maxConcurrentBatchRequests,
        embeddingRequestMaxBytes = embeddingRequestMaxBytes
    )

    @Volatile
    private var lastResolvedLimits: EmbeddingSchedulerLimits = configuredLimits


    suspend fun start() {
        logger.info { "🚀 Embedding scheduler starting..." }
        logger.info { "  Batch size: $batchSize docs" }
        logger.info { "  Poll interval: $pollInterval seconds" }
        logger.info { "  Max concurrent embeddings: $maxConcurrentEmbeddings" }
        logger.info { "  Max retries: $maxRetries" }
        logger.info { "  Embedding request batch size: $embeddingRequestBatchSize docs" }
        logger.info { "  Embedding request max bytes: $embeddingRequestMaxBytes" }
        logger.info { "  Max concurrent embedding batches: $maxConcurrentBatchRequests" }

        while (true) {
            var shouldSleep = true
            try {
                shouldSleep = runIteration()
            } catch (e: Exception) {
                logger.error(e) { "Scheduler error: ${e.message}" }
                shouldSleep = true
            }

            if (shouldSleep) {
                delay(pollInterval.seconds)
            }
        }
    }

    internal suspend fun runIteration(): Boolean {
        val limits = resolveLimits()
        if (limits.pauseBackgroundWork) {
            logger.debug { "CPU fallback reserved for interactive embeddings; deferring background batch work" }
            return true
        }

        val processed = processBatch(limits)
        if (processed == 0) {
            logger.debug { "No pending documents, sleeping..." }
        }
        return processed == 0
    }

    private suspend fun resolveLimits(): EmbeddingSchedulerLimits {
        val limits = limitsProvider.currentLimits()
        if (limits != lastResolvedLimits) {
            logger.info {
                "Embedding execution profile -> ${limits.profile.name.lowercase()} " +
                    "(batchSize=${limits.batchSize}, requestBatchSize=${limits.embeddingRequestBatchSize}, " +
                    "maxConcurrentBatches=${limits.maxConcurrentBatchRequests}, " +
                    "pauseBackgroundWork=${limits.pauseBackgroundWork})"
            }
            lastResolvedLimits = limits
        }
        return limits
    }

    private suspend fun processBatch(limits: EmbeddingSchedulerLimits): Int {
        val pendingDocs = stagingStore.getPendingBatch(limits.batchSize)

        if (pendingDocs.isEmpty()) {
            return 0
        }

        logger.debug { "🔄 Processing batch of ${pendingDocs.size} documents" }

        val requestBatches = partitionDocsForEmbeddingRequests(
            docs = pendingDocs,
            maxDocs = limits.embeddingRequestBatchSize,
            maxBytes = limits.embeddingRequestMaxBytes
        )
        val semaphore = Semaphore(limits.maxConcurrentBatchRequests.coerceAtLeast(1))
        coroutineScope {
            requestBatches.map { chunk ->
                async {
                    semaphore.withPermit {
                        processDocumentBatch(chunk, limits)
                    }
                }
            }.awaitAll()
        }

        return pendingDocs.size
    }

    internal fun partitionDocsForEmbeddingRequests(
        docs: List<StagedDocument>,
        maxDocs: Int = configuredLimits.embeddingRequestBatchSize,
        maxBytes: Int = configuredLimits.embeddingRequestMaxBytes
    ): List<List<StagedDocument>> {
        if (docs.isEmpty()) return emptyList()

        val safeMaxDocs = maxDocs.coerceAtLeast(1)
        val safeMaxBytes = maxBytes.coerceAtLeast(1)
        val batches = mutableListOf<List<StagedDocument>>()
        var currentBatch = mutableListOf<StagedDocument>()
        var currentBytes = REQUEST_BODY_OVERHEAD_BYTES

        docs.forEach { doc ->
            val docBytes = doc.text.toByteArray(Charsets.UTF_8).size + REQUEST_BODY_OVERHEAD_BYTES
            val wouldExceedDocCount = currentBatch.size >= safeMaxDocs
            val wouldExceedByteLimit = currentBatch.isNotEmpty() && (currentBytes + docBytes > safeMaxBytes)

            if (wouldExceedDocCount || wouldExceedByteLimit) {
                batches += currentBatch
                currentBatch = mutableListOf()
                currentBytes = REQUEST_BODY_OVERHEAD_BYTES
            }

            currentBatch += doc
            currentBytes += docBytes
        }

        if (currentBatch.isNotEmpty()) {
            batches += currentBatch
        }

        return batches
    }

    internal suspend fun processDocumentBatch(docs: List<StagedDocument>) {
        processDocumentBatch(docs, configuredLimits)
    }

    internal suspend fun processDocumentBatch(
        docs: List<StagedDocument>,
        limits: EmbeddingSchedulerLimits
    ) {
        if (docs.isEmpty()) return

        val invalidDocs = docs.filter { it.text.isBlank() }
        invalidDocs.forEach { doc ->
            stagingStore.updateStatus(
                id = doc.id,
                newStatus = EmbeddingStatus.FAILED,
                errorMessage = "Document text is empty",
                incrementRetry = false
            )
        }

        val validDocs = docs.filterNot { it.text.isBlank() }
        if (validDocs.isEmpty()) return

        try {
            // Mark entire batch as in-progress before issuing the embedding request.
            val batchIds = validDocs.map { it.id }
            if (validDocs.any { it.embeddingStatus != EmbeddingStatus.IN_PROGRESS }) {
                stagingStore.updateStatusBatch(batchIds, EmbeddingStatus.IN_PROGRESS)
            }

            val vectors = embedder.processBatch(validDocs.map { it.text })
            if (vectors.size != validDocs.size) {
                throw IllegalStateException(
                    "Embedding size mismatch: got ${vectors.size} vectors for ${validDocs.size} docs"
                )
            }

            val vectorsByCollection = linkedMapOf<String, MutableList<VectorDocument>>()
            validDocs.forEachIndexed { index, doc ->
                val enrichedMetadata = if (doc.bookstackUrl != null) {
                    doc.metadata + ("bookstack_url" to doc.bookstackUrl)
                } else {
                    doc.metadata
                }
                val safeMetadata: Map<String, Any> =
                    enrichedMetadata.mapValues { it.value ?: "" } + ("document_id" to doc.id)
                val vectorDoc = VectorDocument(
                    id = doc.id,
                    vector = vectors[index],
                    metadata = safeMetadata
                )
                vectorsByCollection.getOrPut(doc.collection) { mutableListOf() }.add(vectorDoc)
            }

            vectorsByCollection.forEach { (collection, items) ->
                val sink = qdrantSinks[collection]
                if (sink == null) {
                    throw IllegalStateException("No Qdrant sink configured for collection: $collection")
                }
                sink.writeBatch(items)
            }

            stagingStore.updateStatusBatch(batchIds, EmbeddingStatus.COMPLETED)
        } catch (e: Exception) {
            if (validDocs.size > 1) {
                logger.warn(e) {
                    "Batched processing failed for ${validDocs.size} docs, retrying with smaller batches"
                }

                val midpoint = validDocs.size / 2
                processDocumentBatch(validDocs.subList(0, midpoint), limits)
                processDocumentBatch(validDocs.subList(midpoint, validDocs.size), limits)
                return
            }

            logger.warn(e) {
                "Single-doc batch failed for ${validDocs.first().id}, falling back to per-doc retry handling"
            }

            // Fallback path isolates bad inputs and keeps throughput moving.
            val fallbackSemaphore = Semaphore(limits.maxConcurrentEmbeddings.coerceAtLeast(1))
            coroutineScope {
                validDocs.map { doc ->
                    async {
                        fallbackSemaphore.withPermit {
                            val sink = qdrantSinks[doc.collection]
                            if (sink == null) {
                                logger.error { "No Qdrant sink configured for collection: ${doc.collection}" }
                                stagingStore.updateStatus(
                                    id = doc.id,
                                    newStatus = EmbeddingStatus.FAILED,
                                    errorMessage = "No sink configured for collection ${doc.collection}"
                                )
                            } else {
                                processDocument(doc, sink)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun processDocument(doc: StagedDocument, sink: QdrantSink) {
        try {
            if (doc.text.isBlank()) {
                stagingStore.updateStatus(
                    id = doc.id,
                    newStatus = EmbeddingStatus.FAILED,
                    errorMessage = "Document text is empty",
                    incrementRetry = false
                )
                return
            }

            // For Postgres, getPendingBatch atomically claims rows as IN_PROGRESS.
            // Keep this fallback for non-Postgres test environments.
            if (doc.embeddingStatus != EmbeddingStatus.IN_PROGRESS) {
                stagingStore.updateStatus(doc.id, EmbeddingStatus.IN_PROGRESS)
            }

            
            logger.debug { "Embedding document: ${doc.id}" }
            val vector = embedder.process(doc.text)

            
            val enrichedMetadata = if (doc.bookstackUrl != null) {
                doc.metadata + ("bookstack_url" to doc.bookstackUrl)
            } else {
                doc.metadata
            }

            val safeMetadata: Map<String, Any> =
                enrichedMetadata.mapValues { it.value ?: "" } + ("document_id" to doc.id)

            val vectorDoc = VectorDocument(
                id = doc.id,
                vector = vector,
                metadata = safeMetadata
            )

            
            logger.debug { "Inserting to Qdrant: ${doc.id}" }
            sink.write(vectorDoc)

            
            stagingStore.updateStatus(doc.id, EmbeddingStatus.COMPLETED)
            logger.debug { "✓ Completed: ${doc.id}" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to process ${doc.id}: ${e.message}" }

            
            if (doc.retryCount >= maxRetries) {
                logger.error { "Max retries exceeded for ${doc.id}, marking as failed" }
                stagingStore.updateStatus(
                    id = doc.id,
                    newStatus = EmbeddingStatus.FAILED,
                    errorMessage = "Max retries exceeded: ${e.message}",
                    incrementRetry = false
                )
            } else {
                
                val backoffSeconds = 2.0.pow(doc.retryCount.toDouble()).toLong()
                logger.warn { "Retry ${doc.retryCount + 1}/$maxRetries for ${doc.id} after ${backoffSeconds}s backoff" }

                
                delay(backoffSeconds * 1000)

                
                stagingStore.updateStatus(
                    id = doc.id,
                    newStatus = EmbeddingStatus.PENDING,
                    errorMessage = e.message,
                    incrementRetry = true
                )
            }
        }
    }

    
    suspend fun getStats(): Map<String, Any> {
        val stats = stagingStore.getStats()
        return mapOf(
            "pending" to (stats["pending"] ?: 0),
            "in_progress" to (stats["in_progress"] ?: 0),
            "completed" to (stats["completed"] ?: 0),
            "failed" to (stats["failed"] ?: 0),
            "batch_size" to batchSize,
            "poll_interval_seconds" to pollInterval,
            "max_concurrent" to maxConcurrentEmbeddings,
            "effective_profile" to lastResolvedLimits.profile.name.lowercase(),
            "effective_batch_size" to lastResolvedLimits.batchSize,
            "effective_request_batch_size" to lastResolvedLimits.embeddingRequestBatchSize,
            "effective_max_concurrent_batches" to lastResolvedLimits.maxConcurrentBatchRequests
        )
    }

    
    suspend fun getStatsBySource(source: String): Map<String, Long> {
        return stagingStore.getStatsBySource(source)
    }
}
