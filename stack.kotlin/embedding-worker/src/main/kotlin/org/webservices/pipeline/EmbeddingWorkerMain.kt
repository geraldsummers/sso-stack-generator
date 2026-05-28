package org.webservices.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.webservices.pipeline.embedding.InferenceControllerAwareEmbeddingSchedulerLimitsProvider
import org.webservices.pipeline.embedding.InferenceControllerStatusClient
import org.webservices.pipeline.embedding.EmbeddingExecutionProfile
import org.webservices.pipeline.config.PipelineConfig
import org.webservices.pipeline.embedding.EmbeddingScheduler
import org.webservices.pipeline.embedding.EmbeddingSchedulerLimits
import org.webservices.pipeline.embedding.StaticEmbeddingSchedulerLimitsProvider
import org.webservices.pipeline.processors.Embedder
import org.webservices.pipeline.sinks.QdrantSink
import org.webservices.pipeline.storage.DocumentStagingStore

private val logger = KotlinLogging.logger {}
private const val DB_INIT_MAX_ATTEMPTS = 60
private const val DB_INIT_DELAY_MS = 2000L

fun main() {
    logger.info { "🧠 Embedding worker starting" }

    val config = PipelineConfig.fromEnv()
    val batchSize = envInt("EMBEDDING_WORKER_BATCH_SIZE", 200, min = 1, max = 5000)
    val pollIntervalSeconds = envInt("EMBEDDING_WORKER_POLL_INTERVAL_SECONDS", 1, min = 0, max = 3600)
    val maxConcurrentEmbeddings = envInt("EMBEDDING_WORKER_MAX_CONCURRENT", 24, min = 1, max = 256)
    val embeddingRequestBatchSize = envInt("EMBEDDING_WORKER_REQUEST_BATCH_SIZE", 32, min = 1, max = 256)
    val embeddingRequestMaxBytes = envInt("EMBEDDING_WORKER_REQUEST_MAX_BYTES", 262_144, min = 4_096, max = 8_388_608)
    val maxConcurrentBatchRequests = envInt("EMBEDDING_WORKER_MAX_CONCURRENT_BATCHES", 8, min = 1, max = 128)
    val schedulerMaxRetries = envInt("EMBEDDING_WORKER_MAX_RETRIES", 3, min = 0, max = 20)
    val controllerStatusUrl = envString("EMBEDDING_WORKER_CONTROLLER_STATUS_URL")
    val reserveCpuForInteractive = envBoolean("EMBEDDING_WORKER_RESERVE_CPU_FOR_INTERACTIVE", true)

    val configuredLimits = EmbeddingSchedulerLimits(
        profile = EmbeddingExecutionProfile.GPU_BULK,
        batchSize = batchSize,
        maxConcurrentEmbeddings = maxConcurrentEmbeddings,
        embeddingRequestBatchSize = embeddingRequestBatchSize,
        maxConcurrentBatchRequests = maxConcurrentBatchRequests,
        embeddingRequestMaxBytes = embeddingRequestMaxBytes
    )
    val limitsProvider = if (controllerStatusUrl != null) {
        InferenceControllerAwareEmbeddingSchedulerLimitsProvider(
            controllerStatusClient = InferenceControllerStatusClient(controllerStatusUrl),
            defaultLimits = configuredLimits,
            reserveCpuForInteractive = reserveCpuForInteractive
        )
    } else {
        StaticEmbeddingSchedulerLimitsProvider(configuredLimits)
    }

    logger.info {
        "Embedding worker tuning: batchSize=$batchSize, pollIntervalSeconds=$pollIntervalSeconds, " +
            "maxConcurrentEmbeddings=$maxConcurrentEmbeddings, embeddingRequestBatchSize=$embeddingRequestBatchSize, " +
            "embeddingRequestMaxBytes=$embeddingRequestMaxBytes, maxConcurrentBatchRequests=$maxConcurrentBatchRequests, " +
            "schedulerMaxRetries=$schedulerMaxRetries, controllerStatusUrl=${controllerStatusUrl ?: "disabled"}, " +
            "reserveCpuForInteractive=$reserveCpuForInteractive"
    }

    val stagingStore = withRetry(
        label = "DocumentStagingStore",
        maxAttempts = DB_INIT_MAX_ATTEMPTS,
        delayMs = DB_INIT_DELAY_MS
    ) {
        DocumentStagingStore(
            jdbcUrl = config.postgres.jdbcUrl,
            user = config.postgres.user,
            dbPassword = config.postgres.password
        )
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            stagingStore.close()
        } catch (e: Exception) {
            logger.error(e) { "Error during shutdown: ${e.message}" }
        }
    })

    val embedder = Embedder(
        serviceUrl = config.embedding.serviceUrl,
        maxTokens = config.embedding.maxTokens
    )

    val qdrantSinks = mapOf(
        config.qdrant.rssCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.rssCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.cveCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.cveCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.wikipediaCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.wikipediaCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.australianLawsCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.australianLawsCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.linuxDocsCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.linuxDocsCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.openDotaCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.openDotaCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.poeNinjaCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.poeNinjaCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.stackKnowledgeCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.stackKnowledgeCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.debianWikiCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.debianWikiCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.archWikiCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.archWikiCollection, config.embedding.vectorSize, config.qdrant.apiKey)
    )

    runBlocking {
        val embeddingScheduler = EmbeddingScheduler(
            stagingStore = stagingStore,
            embedder = embedder,
            qdrantSinks = qdrantSinks,
            batchSize = batchSize,
            pollInterval = pollIntervalSeconds,
            maxConcurrentEmbeddings = maxConcurrentEmbeddings,
            maxRetries = schedulerMaxRetries,
            embeddingRequestBatchSize = embeddingRequestBatchSize,
            maxConcurrentBatchRequests = maxConcurrentBatchRequests,
            embeddingRequestMaxBytes = embeddingRequestMaxBytes,
            limitsProvider = limitsProvider
        )

        launch {
            embeddingScheduler.start()
        }

        awaitCancellation()
    }
}

private fun envInt(
    key: String,
    default: Int,
    min: Int,
    max: Int
): Int {
    val raw = System.getenv(key)?.trim()
    if (raw.isNullOrEmpty()) {
        return default
    }
    val parsed = raw.toIntOrNull()
    if (parsed == null) {
        logger.warn { "Invalid integer for $key=$raw, using default=$default" }
        return default
    }
    return parsed.coerceIn(min, max)
}

private fun envString(
    key: String,
    default: String? = null
): String? {
    return System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default
}

private fun envBoolean(
    key: String,
    default: Boolean
): Boolean {
    return when (System.getenv(key)?.trim()?.lowercase()) {
        null, "" -> default
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> {
            logger.warn { "Invalid boolean for $key=${System.getenv(key)}, using default=$default" }
            default
        }
    }
}

private fun <T> withRetry(
    label: String,
    maxAttempts: Int,
    delayMs: Long,
    block: () -> T
): T {
    var lastError: Exception? = null
    for (attempt in 1..maxAttempts) {
        try {
            if (attempt > 1) {
                logger.info { "$label init retry $attempt/$maxAttempts" }
            }
            return block()
        } catch (e: Exception) {
            lastError = e
            logger.warn { "$label init failed ($attempt/$maxAttempts): ${e.message}" }
            if (attempt < maxAttempts) {
                Thread.sleep(delayMs)
            }
        }
    }
    throw IllegalStateException("$label failed to initialize after $maxAttempts attempts", lastError)
}
