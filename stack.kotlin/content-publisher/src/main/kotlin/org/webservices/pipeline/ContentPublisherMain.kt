package org.webservices.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.webservices.pipeline.config.PipelineConfig
import org.webservices.pipeline.sinks.BookStackSink
import org.webservices.pipeline.sinks.QdrantPublicationSync
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.workers.BookStackWriter

private val logger = KotlinLogging.logger {}
private const val DB_INIT_MAX_ATTEMPTS = 60
private const val DB_INIT_DELAY_MS = 2000L

fun main() {
    logger.info { "📚 Content publisher starting" }

    val config = PipelineConfig.fromEnv()

    if (!config.bookstack.enabled) {
        logger.warn { "BookStack publishing disabled (BOOKSTACK_ENABLED=false). Exiting." }
        return
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

    val bookStackSink = BookStackSink(
        bookstackUrl = config.bookstack.url,
        publicBookstackUrl = config.bookstack.publicUrl,
        tokenId = config.bookstack.tokenId,
        tokenSecret = config.bookstack.tokenSecret
    )
    val qdrantPublicationSync = System.getenv("QDRANT_HTTP_URL")
        ?.takeIf { it.isNotBlank() }
        ?.let { QdrantPublicationSync(it, apiKey = config.qdrant.apiKey) }

    val allowedSources = System.getenv("BOOKSTACK_ALLOWED_SOURCES")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()
    if (allowedSources.isEmpty()) {
        logger.info { "BookStack source filter disabled; publishing all embedded sources" }
    } else {
        logger.info { "BookStack source filter enabled: ${allowedSources.joinToString(",")}" }
    }

    runBlocking {
        val bookStackWriter = BookStackWriter(
            stagingStore = stagingStore,
            bookStackSink = bookStackSink,
            qdrantPublicationSync = qdrantPublicationSync,
            pollIntervalSeconds = 5,
            batchSize = 50,
            maxConcurrentWrites = (System.getenv("BOOKSTACK_MAX_CONCURRENT_WRITES")?.toIntOrNull() ?: 1).coerceAtLeast(1),
            allowedSources = allowedSources,
            reconciliationBatchSize = (System.getenv("BOOKSTACK_RECONCILIATION_BATCH_SIZE")?.toIntOrNull() ?: 200).coerceAtLeast(1),
            reconciliationIntervalSeconds = (System.getenv("BOOKSTACK_RECONCILIATION_INTERVAL_SECONDS")?.toLongOrNull() ?: 300L).coerceAtLeast(5L)
        )

        bookStackWriter.start()
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
