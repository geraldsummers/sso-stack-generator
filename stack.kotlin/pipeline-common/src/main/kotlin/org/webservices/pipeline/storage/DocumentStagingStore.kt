package org.webservices.pipeline.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}
private const val BOOKSTACK_SKIPPED_PREFIX = "skipped://source-filter/"

data class BookStackStats(
    val totalEmbedded: Long,
    val pending: Long,
    val completed: Long,
    val failed: Long,
    val skipped: Long
)

data class SourceReadinessStats(
    val searchableDocuments: Long,
    val pendingEmbedding: Long,
    val inProgressEmbedding: Long,
    val failedEmbedding: Long,
    val publishedDocuments: Long,
    val pendingPublication: Long,
    val failedPublication: Long,
    val skippedPublication: Long
)

/**
 * Tracks the lifecycle of document embeddings from ingestion through vector storage.
 *
 * The staging pattern decouples data ingestion from embedding generation:
 * - PENDING: Document staged, waiting for EmbeddingScheduler to process
 * - IN_PROGRESS: Currently being embedded by the embedding service
 * - COMPLETED: Vector stored in Qdrant, ready for BookStackWriter
 * - FAILED: Embedding failed (transient errors retry via retryCount)
 *
 * This state machine enables fault-tolerant async processing where ingestion continues
 * even when the embedding service (BGE-M3 model) is slow or temporarily unavailable.
 */
enum class EmbeddingStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * Represents a document in the staging buffer between ingestion and vector storage.
 *
 * Documents flow through the pipeline:
 * 1. Data sources (RSS, CVE, Wikipedia) create documents with status=PENDING
 * 2. EmbeddingScheduler polls for PENDING docs, generates vectors, marks COMPLETED
 * 3. BookStackWriter polls for COMPLETED docs, publishes to BookStack knowledge base
 *
 * Large documents may be split into chunks (chunkIndex/totalChunks) to fit within
 * the embedding model's 8192 token limit while maintaining traceability.
 *
 * @property id Unique identifier (SHA-256 hash of content for deduplication)
 * @property source Origin system (e.g., "rss", "cve", "wikipedia")
 * @property collection Qdrant collection name for vector storage routing
 * @property text Raw document content (may be a chunk of larger document)
 * @property metadata Source-specific fields (URL, timestamp, author, etc.)
 * @property embeddingStatus Current processing state in the staging lifecycle
 * @property chunkIndex 0-based chunk number if document was split (null if not chunked)
 * @property totalChunks Total chunks for this document (null if not chunked)
 * @property createdAt When document first entered staging (for FIFO ordering)
 * @property updatedAt Last status change (for monitoring stuck documents)
 * @property retryCount Failed embedding attempts (max 3 before permanent FAILED)
 * @property errorMessage Last error from embedding service (for debugging)
 * @property bookstackUrl URL of published BookStack page (set by BookStackWriter)
 */
data class StagedDocument(
    val id: String,
    val source: String,
    val collection: String,
    val text: String,
    val metadata: Map<String, String>,
    val embeddingStatus: EmbeddingStatus,
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val bookstackUrl: String? = null
)

/**
 * PostgreSQL schema for the document staging table.
 *
 * This table acts as the central buffer in the webservices pipeline, enabling:
 * - Persistent storage across pipeline restarts (unlike in-memory queues)
 * - Queryable status tracking for monitoring and debugging
 * - Transactional updates to prevent race conditions during status changes
 * - Full-text search via PostgreSQL's tsvector (used by search-service)
 *
 * Indexes optimize common access patterns:
 * - idx_staging_status_created: Broad status/time queries
 * - idx_staging_pending_created: Fast PENDING document polling by EmbeddingScheduler
 * - idx_staging_bookstack_pending_created: Fast COMPLETED+unpublished polling by BookStackWriter
 * - idx_staging_collection_status: Search-service collection-scoped reads
 * - idx_staging_collection_status_created: Fast "latest document in collection" reads for tests/debugging
 * - idx_staging_source: Per-source stats and debugging queries
 */
object DocumentStagingTable : Table("document_staging") {
    val id = varchar("id", 500)
    val sourceName = varchar("source", 255)
    val collection = varchar("collection", 255)
    val text = text("text")
    val metadata = text("metadata")  
    val embeddingStatus = varchar("embedding_status", 50)
    val chunkIndex = integer("chunk_index").nullable()
    val totalChunks = integer("total_chunks").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val retryCount = integer("retry_count").default(0)
    val errorMessage = text("error_message").nullable()
    val bookstackUrl = text("bookstack_url").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_staging_source", false, sourceName)
        index("idx_staging_status_created", false, embeddingStatus, createdAt)
        index("idx_staging_collection_status", false, collection, embeddingStatus)
    }
}

/**
 * PostgreSQL-backed staging buffer for fault-tolerant document processing.
 *
 * This store implements the staging pattern (ARCHITECTURE.md Flow 1) that decouples
 * data ingestion from embedding generation. Documents are written to PostgreSQL
 * immediately upon ingestion (status=PENDING), then processed asynchronously by
 * downstream components:
 *
 * **Integration with Pipeline Components:**
 * - Data sources (RSS, CVE, Wikipedia) → stageBatch() with status=PENDING
 * - EmbeddingScheduler → getPendingBatch() → updateStatus(COMPLETED) after Qdrant write
 * - BookStackWriter → getPendingForBookStack() → updateBookStackUrl() after publishing
 * - Search-Service → Reads COMPLETED docs for hybrid search (vector + full-text)
 *
 * **Why PostgreSQL?**
 * - Persistence: Survives pipeline crashes/restarts (vs. in-memory queues)
 * - Queryability: Status tracking, per-source stats, debugging stuck documents
 * - Transactions: ACID guarantees prevent duplicate processing during status changes
 * - Full-text: Built-in tsvector enables keyword search (BM25 ranking in search-service)
 * - Scalability: HikariCP connection pooling supports high-throughput concurrent access
 *
 * **Fault Tolerance:**
 * - Retry logic: Failed embeddings retry up to 3 times via retryCount field
 * - Idempotency: Documents use content-based IDs (SHA-256) to prevent duplicates
 * - Graceful degradation: Ingestion continues even when embedding service is down
 *
 * @property jdbcUrl PostgreSQL connection string (e.g., jdbc:postgresql://db:5432/webservices)
 * @property user Database username (default: pipeline_user)
 * @property dbPassword Database password
 */
class DocumentStagingStore(
    private val jdbcUrl: String,
    private val user: String = "pipeline_user",
    private val dbPassword: String = ""
) : AutoCloseable {
    private data class FullTextIndexState(
        val exists: Boolean,
        val valid: Boolean,
        val ready: Boolean
    )

    private data class FullTextIndexSpec(
        val collection: String,
        val indexName: String
    )

    companion object {
        private const val LEGACY_FULLTEXT_INDEX_NAME = "idx_staging_fulltext_completed"
        private const val STAGING_INDEX_LOCK_KEY = 68423019L
        private val FULLTEXT_INDEX_COLLECTIONS = listOf(
            "rss_feeds",
            "cve",
            "australian_laws",
            "linux_docs",
            "stack_knowledge",
            "debian_wiki",
            "arch_wiki",
            "wikipedia",
        )
        private val SUPPORTING_INDEX_STATEMENTS = listOf(
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_status_created
            ON document_staging(embedding_status, created_at)
            """.trimIndent(),
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_pending_created
            ON document_staging(created_at)
            WHERE embedding_status = 'PENDING'
            """.trimIndent(),
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_bookstack_pending_created
            ON document_staging(created_at)
            WHERE embedding_status = 'COMPLETED'
              AND bookstack_url IS NULL
              AND retry_count < 3
            """.trimIndent(),
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_source
            ON document_staging(source)
            """.trimIndent(),
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_source_status
            ON document_staging(source, embedding_status)
            """.trimIndent(),
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_source_published_created
            ON document_staging(source, created_at DESC)
            WHERE bookstack_url IS NOT NULL
            """.trimIndent(),
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_source_failed_publication_created
            ON document_staging(source, created_at DESC)
            WHERE retry_count >= 3
              AND error_message LIKE 'BookStack write failed:%'
            """.trimIndent(),
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_collection_status
            ON document_staging(collection, embedding_status)
            """.trimIndent(),
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_collection_status_created
            ON document_staging(collection, embedding_status, created_at DESC)
            """.trimIndent()
        )
        private val PROCESSING_PRIORITY = listOf(
            "cve",
            "rss_feeds",
            "australian_laws",
            "linux_docs",
            "stack_knowledge",
            "debian_wiki",
            "arch_wiki",
            "wikipedia"
        )

        private fun fullTextIndexName(collection: String): String =
            "idx_staging_fulltext_${collection.replace(Regex("[^a-zA-Z0-9]+"), "_")}_completed"

        internal fun fullTextIndexCollectionsInBuildOrder(): List<String> = FULLTEXT_INDEX_COLLECTIONS.toList()

        private fun fullTextVectorSql(collection: String): String = when (collection) {
            "rss_feeds" -> """
                setweight(
                    to_tsvector(
                        'english',
                        COALESCE(metadata::json->>'title', '') || ' ' ||
                        COALESCE(metadata::json->>'name', '')
                    ),
                    'A'
                ) ||
                setweight(to_tsvector('english', text), 'B')
            """.trimIndent()
            "cve" -> """
                setweight(
                    to_tsvector(
                        'english',
                        COALESCE(metadata::json->>'cveId', '') || ' ' ||
                        COALESCE(metadata::json->>'cve_id', '') || ' ' ||
                        COALESCE(substring(id from '(CVE-[0-9]{4}-[0-9]+)'), '') || ' ' ||
                        COALESCE(substring(text from '(CVE-[0-9]{4}-[0-9]+)'), '') || ' ' ||
                        COALESCE(metadata::json->>'title', '')
                    ),
                    'A'
                ) ||
                setweight(
                    to_tsvector(
                        'english',
                        regexp_replace(
                            COALESCE(metadata::json->>'cveId', '') || ' ' ||
                            COALESCE(metadata::json->>'cve_id', '') || ' ' ||
                            COALESCE(substring(id from '(CVE-[0-9]{4}-[0-9]+)'), '') || ' ' ||
                            COALESCE(substring(text from '(CVE-[0-9]{4}-[0-9]+)'), '') || ' ' ||
                            COALESCE(metadata::json->>'title', ''),
                            '[^[:alnum:]]+',
                            ' ',
                            'g'
                        )
                    ),
                    'A'
                ) ||
                setweight(to_tsvector('english', text), 'B')
            """.trimIndent()
            "australian_laws" -> """
                setweight(
                    to_tsvector(
                        'english',
                        COALESCE(metadata::json->>'citation', '') || ' ' ||
                        COALESCE(metadata::json->>'title', '')
                    ),
                    'A'
                ) ||
                setweight(to_tsvector('english', text), 'B')
            """.trimIndent()
            "linux_docs" -> """
                setweight(
                    to_tsvector(
                        'english',
                        COALESCE(metadata::json->>'title', '') || ' ' ||
                        COALESCE(metadata::json->>'name', '') || ' ' ||
                        COALESCE(metadata::json->>'path', '') || ' ' ||
                        COALESCE(metadata::json->>'section', '')
                    ),
                    'A'
                ) ||
                setweight(to_tsvector('english', text), 'B')
            """.trimIndent()
            "stack_knowledge" -> """
                setweight(
                    to_tsvector(
                        'english',
                        COALESCE(metadata::json->>'title', '') || ' ' ||
                        COALESCE(metadata::json->>'section', '') || ' ' ||
                        COALESCE(metadata::json->>'filepath', '') || ' ' ||
                        COALESCE(metadata::json->>'path', '')
                    ),
                    'A'
                ) ||
                setweight(to_tsvector('english', text), 'B')
            """.trimIndent()
            "wikipedia" -> """
                setweight(to_tsvector('english', COALESCE(metadata::json->>'title', '')), 'A') ||
                setweight(to_tsvector('english', text), 'B')
            """.trimIndent()
            else -> """
                setweight(
                    to_tsvector(
                        'english',
                        COALESCE(metadata::json->>'title', '') || ' ' ||
                        COALESCE(metadata::json->>'name', '') || ' ' ||
                        COALESCE(metadata::json->>'citation', '') || ' ' ||
                        COALESCE(metadata::json->>'cveId', '') || ' ' ||
                        COALESCE(metadata::json->>'cve_id', '')
                    ),
                    'A'
                ) ||
                setweight(to_tsvector('english', text), 'B')
            """.trimIndent()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val dataSource: HikariDataSource

    private fun normalizeStats(stats: Map<String, Long>): Map<String, Long> {
        return mapOf(
            "pending" to (stats["pending"] ?: 0L),
            "in_progress" to (stats["in_progress"] ?: 0L),
            "completed" to (stats["completed"] ?: 0L),
            "failed" to (stats["failed"] ?: 0L)
        )
    }

    private fun Transaction.applyLocalStatementTimeout(timeoutMs: Long) {
        if (!jdbcUrl.startsWith("jdbc:postgresql") || timeoutMs <= 0) {
            return
        }

        val boundedTimeoutMs = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong())
        exec("SET LOCAL statement_timeout = $boundedTimeoutMs")
    }

    private fun toStagedDocument(row: ResultRow): StagedDocument {
        val metadataStr = row[DocumentStagingTable.metadata]
        val metadata = if (metadataStr.isNotBlank()) {
            json.decodeFromString<Map<String, String>>(metadataStr)
        } else {
            emptyMap()
        }

        return StagedDocument(
            id = row[DocumentStagingTable.id],
            source = row[DocumentStagingTable.sourceName],
            collection = row[DocumentStagingTable.collection],
            text = row[DocumentStagingTable.text],
            metadata = metadata,
            embeddingStatus = EmbeddingStatus.valueOf(row[DocumentStagingTable.embeddingStatus]),
            chunkIndex = row[DocumentStagingTable.chunkIndex],
            totalChunks = row[DocumentStagingTable.totalChunks],
            createdAt = row[DocumentStagingTable.createdAt],
            updatedAt = row[DocumentStagingTable.updatedAt],
            retryCount = row[DocumentStagingTable.retryCount],
            errorMessage = row[DocumentStagingTable.errorMessage],
            bookstackUrl = row[DocumentStagingTable.bookstackUrl]
        )
    }

    private fun queryStats(): Map<String, Long> {
        val countExpr = DocumentStagingTable.id.count()
        val stats = DocumentStagingTable
            .select(DocumentStagingTable.embeddingStatus, countExpr)
            .groupBy(DocumentStagingTable.embeddingStatus)
            .associate { row ->
                row[DocumentStagingTable.embeddingStatus].lowercase() to row[countExpr]
            }

        return normalizeStats(stats)
    }

    private fun queryStatsBySource(source: String): Map<String, Long> {
        val countExpr = DocumentStagingTable.id.count()
        val stats = DocumentStagingTable
            .select(DocumentStagingTable.embeddingStatus, countExpr)
            .where { DocumentStagingTable.sourceName eq source }
            .groupBy(DocumentStagingTable.embeddingStatus)
            .associate { row ->
                row[DocumentStagingTable.embeddingStatus].lowercase() to row[countExpr]
            }

        return normalizeStats(stats)
    }

    private fun queryStatsBySources(sources: List<String>): Map<String, Map<String, Long>> {
        if (sources.isEmpty()) {
            return emptyMap()
        }

        val countExpr = DocumentStagingTable.id.count()
        val grouped = DocumentStagingTable
            .select(DocumentStagingTable.sourceName, DocumentStagingTable.embeddingStatus, countExpr)
            .where { DocumentStagingTable.sourceName.inList(sources) }
            .groupBy(DocumentStagingTable.sourceName, DocumentStagingTable.embeddingStatus)
            .groupBy { row -> row[DocumentStagingTable.sourceName] }
            .mapValues { (_, rows) ->
                normalizeStats(
                    rows.associate { row ->
                        row[DocumentStagingTable.embeddingStatus].lowercase() to row[countExpr]
                    }
                )
            }

        return sources.associateWith { source -> grouped[source] ?: normalizeStats(emptyMap()) }
    }

    private fun priorityRank(collection: String): Int =
        PROCESSING_PRIORITY.indexOf(collection).takeIf { it >= 0 } ?: PROCESSING_PRIORITY.size

    private fun decodeMetadata(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }
        return json.decodeFromString(raw)
    }

    private fun toStagedDocument(resultSet: ResultSet): StagedDocument {
        return StagedDocument(
            id = resultSet.getString("id"),
            source = resultSet.getString("source"),
            collection = resultSet.getString("collection"),
            text = resultSet.getString("text"),
            metadata = decodeMetadata(resultSet.getString("metadata")),
            embeddingStatus = EmbeddingStatus.valueOf(resultSet.getString("embedding_status")),
            chunkIndex = resultSet.getObject("chunk_index") as? Int,
            totalChunks = resultSet.getObject("total_chunks") as? Int,
            createdAt = resultSet.getTimestamp("created_at").toInstant(),
            updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
            retryCount = resultSet.getInt("retry_count"),
            errorMessage = resultSet.getString("error_message"),
            bookstackUrl = resultSet.getString("bookstack_url")
        )
    }

    private fun getActiveCollections(
        conn: java.sql.Connection,
        status: EmbeddingStatus,
        allowedSources: Set<String> = emptySet(),
        requirePendingBookStackWrite: Boolean = false,
        requireMissingBookStackUrl: Boolean = false,
        requirePublishedBookStackUrl: Boolean = false
    ): List<String> {
        val sourceFilter = if (allowedSources.isEmpty()) {
            ""
        } else {
            " AND source IN (${allowedSources.joinToString(",") { "?" }})"
        }
        val bookStackFilter = if (requirePendingBookStackWrite) {
            """
              AND bookstack_url IS NULL
              AND retry_count < 3
            """.trimIndent()
        } else if (requireMissingBookStackUrl) {
            "AND bookstack_url IS NULL"
        } else if (requirePublishedBookStackUrl) {
            """
              AND bookstack_url IS NOT NULL
              AND bookstack_url <> ''
              AND bookstack_url NOT LIKE '$BOOKSTACK_SKIPPED_PREFIX%'
            """.trimIndent()
        } else {
            ""
        }
        val ageColumn = if (requirePublishedBookStackUrl) "updated_at" else "created_at"
        val sql = """
            SELECT collection, MIN($ageColumn) AS oldest_created
            FROM document_staging
            WHERE embedding_status = ?
            $bookStackFilter
            $sourceFilter
            GROUP BY collection
            ORDER BY ${priorityCaseSql("collection")}, oldest_created ASC
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            var index = 1
            stmt.setString(index++, status.name)
            allowedSources.forEach { source -> stmt.setString(index++, source) }
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(rs.getString("collection"))
                    }
                }
            }
        }
    }

    private fun fetchBatchForCollection(
        conn: java.sql.Connection,
        collection: String,
        status: EmbeddingStatus,
        limit: Int,
        allowedSources: Set<String> = emptySet(),
        requirePendingBookStackWrite: Boolean = false,
        requireMissingBookStackUrl: Boolean = false,
        requirePublishedBookStackUrl: Boolean = false
    ): List<StagedDocument> {
        val sourceFilter = if (allowedSources.isEmpty()) {
            ""
        } else {
            " AND source IN (${allowedSources.joinToString(",") { "?" }})"
        }
        val bookStackFilter = if (requirePendingBookStackWrite) {
            """
              AND bookstack_url IS NULL
              AND retry_count < 3
            """.trimIndent()
        } else if (requireMissingBookStackUrl) {
            "AND bookstack_url IS NULL"
        } else if (requirePublishedBookStackUrl) {
            """
              AND bookstack_url IS NOT NULL
              AND bookstack_url <> ''
              AND bookstack_url NOT LIKE '$BOOKSTACK_SKIPPED_PREFIX%'
            """.trimIndent()
        } else {
            ""
        }
        val orderColumn = if (requirePublishedBookStackUrl) "updated_at" else "created_at"
        val sql = """
            SELECT id, source, collection, text, metadata, embedding_status, chunk_index, total_chunks,
                   created_at, updated_at, retry_count, error_message, bookstack_url
            FROM document_staging
            WHERE embedding_status = ?
              AND collection = ?
              $bookStackFilter
              $sourceFilter
            ORDER BY $orderColumn ASC
            LIMIT ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            var index = 1
            stmt.setString(index++, status.name)
            stmt.setString(index++, collection)
            allowedSources.forEach { source -> stmt.setString(index++, source) }
            stmt.setInt(index, limit)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(toStagedDocument(rs))
                    }
                }
            }
        }
    }

    private fun interleaveCollectionsFairly(
        docsByCollection: List<Pair<String, List<StagedDocument>>>,
        limit: Int
    ): List<StagedDocument> {
        if (limit <= 0 || docsByCollection.isEmpty()) {
            return emptyList()
        }

        val orderedBuckets = docsByCollection
            .filter { it.second.isNotEmpty() }
            .sortedWith(
                compareBy<Pair<String, List<StagedDocument>>> { priorityRank(it.first) }
                    .thenBy { bucket -> bucket.second.first().createdAt }
            )
            .map { it.first to ArrayDeque(it.second) }

        val result = ArrayList<StagedDocument>(limit)
        while (result.size < limit) {
            var madeProgress = false
            orderedBuckets.forEach { (_, queue) ->
                if (result.size >= limit) {
                    return@forEach
                }
                val next = queue.removeFirstOrNull() ?: return@forEach
                result += next
                madeProgress = true
            }
            if (!madeProgress) {
                break
            }
        }

        return result
    }

    init {
        
        logger.info { "DocumentStagingStore init: jdbcUrl=$jdbcUrl, user=$user, password.length=${dbPassword.length}" }

        
        val config = HikariConfig().apply {
            
            setJdbcUrl(this@DocumentStagingStore.jdbcUrl)
            setUsername(user)
            setPassword(dbPassword)

            
            logger.info { "HikariConfig: jdbcUrl=$jdbcUrl, username=$username, password.length=${dbPassword.length}" }
            
            driverClassName = when {
                this@DocumentStagingStore.jdbcUrl.startsWith("jdbc:postgresql") -> "org.postgresql.Driver"
                this@DocumentStagingStore.jdbcUrl.startsWith("jdbc:h2") -> "org.h2.Driver"
                else -> "org.postgresql.Driver"
            }

            
            maximumPoolSize = 20
            minimumIdle = 5
            connectionTimeout = 30000  
            idleTimeout = 600000       
            maxLifetime = 1800000      

            
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            
            connectionTestQuery = "SELECT 1"
            validationTimeout = 5000
        }

        dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        logger.info { "Connected to PostgreSQL: $jdbcUrl (HikariCP pool size: ${config.maximumPoolSize})" }

        ensureTableExists()
        if (jdbcUrl.startsWith("jdbc:postgresql")) {
            schedulePostgresIndexCreation()
        }
    }

    /**
     * Creates table schema and performance indexes if not present.
     *
     * **Critical indexes for staging pattern:**
     * - idx_staging_pending_created: Partial index on PENDING docs ordered by creation time
     *   Enables EmbeddingScheduler to efficiently poll oldest unprocessed documents (FIFO)
     * - idx_staging_bookstack_pending_created: Partial index on COMPLETED docs awaiting publication
     *   Enables BookStackWriter to efficiently drain the publish queue on large tables
     * - idx_staging_collection_status: Supports SearchGateway full-text reads by collection
     * - idx_staging_collection_status_created: Supports latest-per-collection lookups without large sorts
     * - idx_staging_source: Per-source filtering for stats and debugging
     *
     * These indexes are PostgreSQL-specific optimizations. H2 (used in tests) silently
     * skips them, allowing the same code to work in both production and test environments.
     */
    private fun ensureTableExists() {
        try {
            transaction {
                SchemaUtils.createMissingTablesAndColumns(DocumentStagingTable)

                if (jdbcUrl.startsWith("jdbc:postgresql")) {
                    // Apply grants on every startup so privilege fixes reach existing
                    // persistent databases instead of only fresh Postgres volumes.
                    exec("GRANT SELECT ON document_staging TO search_service_user")
                    exec("GRANT SELECT, INSERT, UPDATE, DELETE ON document_staging TO test_runner_user")

                }
            }

            logger.info { "PostgreSQL document_staging table ready" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create document_staging table: ${e.message}" }
            throw e
        }
    }

    private fun schedulePostgresIndexCreation() {
        val worker = Thread({
            runCatching { ensurePostgresIndexes() }
                .onFailure { e ->
                    logger.error(e) { "Failed to ensure PostgreSQL document_staging indexes: ${e.message}" }
                }
        }, "document-staging-indexes")
        worker.isDaemon = true
        worker.start()
    }

    private fun ensurePostgresIndexes() {
        DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
            conn.autoCommit = true

            conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { stmt ->
                stmt.setLong(1, STAGING_INDEX_LOCK_KEY)
                stmt.executeQuery().use { rs ->
                    require(rs.next()) { "Failed to acquire advisory lock row for full-text index" }
                    if (!rs.getBoolean(1)) {
                        logger.info { "Another process is already ensuring document_staging indexes; skipping duplicate attempt" }
                        return
                    }
                }
            }

            try {
                conn.createStatement().use { stmt ->
                    stmt.execute("SET statement_timeout = 0")
                    SUPPORTING_INDEX_STATEMENTS.forEach(stmt::execute)
                    stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS $LEGACY_FULLTEXT_INDEX_NAME")
                }

                FULLTEXT_INDEX_COLLECTIONS.forEach { collection ->
                    val spec = FullTextIndexSpec(collection, fullTextIndexName(collection))
                    val indexState = getFullTextIndexState(conn, spec.indexName)
                    val indexNeedsRebuild = indexState.exists && fullTextIndexDefinitionNeedsRebuild(conn, spec)
                    if (!indexState.exists || !indexState.valid || !indexState.ready || indexNeedsRebuild) {
                        val vectorSql = fullTextVectorSql(spec.collection)
                        if (indexState.exists) {
                            logger.warn {
                                "PostgreSQL full-text index ${spec.indexName} needs rebuild " +
                                    "(valid=${indexState.valid}, ready=${indexState.ready}, definitionChanged=$indexNeedsRebuild)"
                            }
                            conn.createStatement().use { stmt ->
                                stmt.execute("SET statement_timeout = 0")
                                stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS ${spec.indexName}")
                            }
                        }

                        conn.createStatement().use { stmt ->
                            stmt.execute("SET statement_timeout = 0")
                            stmt.execute(
                                """
                                CREATE INDEX CONCURRENTLY IF NOT EXISTS ${spec.indexName}
                                ON document_staging
                                USING GIN (
                                    ($vectorSql)
                                )
                                WHERE embedding_status = 'COMPLETED'
                                  AND collection = '${spec.collection}'
                                """.trimIndent()
                            )
                        }

                        val rebuiltState = getFullTextIndexState(conn, spec.indexName)
                        require(rebuiltState.exists && rebuiltState.valid && rebuiltState.ready) {
                            "PostgreSQL full-text index ${spec.indexName} was not built successfully " +
                                "(valid=${rebuiltState.valid}, ready=${rebuiltState.ready})"
                        }
                        logger.info { "PostgreSQL full-text index ${spec.indexName} is ready" }
                    }
                }

                conn.createStatement().use { stmt ->
                    stmt.execute("ANALYZE document_staging")
                }
            } finally {
                conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { stmt ->
                    stmt.setLong(1, STAGING_INDEX_LOCK_KEY)
                    stmt.execute()
                }
            }
        }
    }

    private fun fullTextIndexDefinitionNeedsRebuild(conn: java.sql.Connection, spec: FullTextIndexSpec): Boolean {
        val definition = getFullTextIndexDefinition(conn, spec.indexName) ?: return false
        val normalized = definition.lowercase()
        val requiredFragments = buildList {
            add("to_tsvector('english'::regconfig, text)")
            when (spec.collection) {
                "cve" -> {
                    add("substring(")
                    add("regexp_replace(")
                }
            }
        }
        return requiredFragments.any { it !in normalized }
    }

    private fun getFullTextIndexState(conn: java.sql.Connection, indexName: String): FullTextIndexState {
        conn.prepareStatement(
            """
            SELECT idx.indisvalid, idx.indisready
            FROM pg_class cls
            JOIN pg_namespace ns ON ns.oid = cls.relnamespace
            JOIN pg_index idx ON idx.indexrelid = cls.oid
            WHERE ns.nspname = current_schema()
              AND cls.relname = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, indexName)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    return FullTextIndexState(exists = false, valid = false, ready = false)
                }
                return FullTextIndexState(
                    exists = true,
                    valid = rs.getBoolean("indisvalid"),
                    ready = rs.getBoolean("indisready")
                )
            }
        }
    }

    private fun getFullTextIndexDefinition(conn: java.sql.Connection, indexName: String): String? {
        conn.prepareStatement(
            """
            SELECT pg_get_indexdef(cls.oid)
            FROM pg_class cls
            JOIN pg_namespace ns ON ns.oid = cls.relnamespace
            WHERE ns.nspname = current_schema()
              AND cls.relname = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, indexName)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return rs.getString(1)
            }
        }
    }

    /**
     * Stages a single document for asynchronous embedding processing.
     * Convenience wrapper around stageBatch() for single-document workflows.
     */
    suspend fun stage(doc: StagedDocument) {
        stageBatch(listOf(doc))
    }

    /**
     * Atomically inserts multiple documents into staging buffer.
     *
     * Used by data sources (RSS, CVE, Wikipedia) after deduplication to queue documents
     * for embedding. Batch insertion reduces database round-trips for high-throughput
     * ingestion (e.g., processing 10,000+ Wikipedia articles).
     *
     * Documents are inserted with status=PENDING, making them visible to EmbeddingScheduler's
     * next polling cycle. Metadata is serialized to JSON for flexible schema evolution.
     *
     * Uses INSERT ... ON CONFLICT DO NOTHING to gracefully handle re-runs and avoid
     * duplicate key violations when the same document is ingested multiple times.
     *
     * @param docs Documents to stage (typically all have embeddingStatus=PENDING)
     * @throws Exception if database write fails (caller should handle retry logic)
     */
    suspend fun stageBatch(docs: List<StagedDocument>) {
        if (docs.isEmpty()) return

        try {
            transaction {
                DocumentStagingTable.batchInsert(docs, ignore = true) { doc ->
                    this[DocumentStagingTable.id] = doc.id
                    this[DocumentStagingTable.sourceName] = doc.source
                    this[DocumentStagingTable.collection] = doc.collection
                    this[DocumentStagingTable.text] = doc.text
                    this[DocumentStagingTable.metadata] = json.encodeToString(doc.metadata)
                    this[DocumentStagingTable.embeddingStatus] = doc.embeddingStatus.name
                    this[DocumentStagingTable.chunkIndex] = doc.chunkIndex
                    this[DocumentStagingTable.totalChunks] = doc.totalChunks
                    this[DocumentStagingTable.createdAt] = doc.createdAt
                    this[DocumentStagingTable.updatedAt] = doc.updatedAt
                    this[DocumentStagingTable.retryCount] = doc.retryCount
                    this[DocumentStagingTable.errorMessage] = doc.errorMessage
                    this[DocumentStagingTable.bookstackUrl] = doc.bookstackUrl
                }
            }

            logger.debug { "Staged ${docs.size} documents (duplicates ignored)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to stage batch: ${e.message}" }
            throw e
        }
    }

    /**
     * Polls for oldest unprocessed documents awaiting embedding.
     *
     * Called by EmbeddingScheduler on a fixed interval (e.g., every 5 seconds) to fetch
     * the next batch of work. Documents are returned in FIFO order (oldest first) to
     * ensure fair processing across all data sources.
     *
     * The partial index idx_staging_pending_created makes this query extremely fast even
     * when the table contains millions of COMPLETED documents, since it only scans the
     * subset of PENDING rows.
     *
     * @param limit Maximum documents to return (default 100 for embedding service rate limiting)
     * @return List of PENDING documents, oldest first (empty if nothing to process)
     */
    suspend fun getPendingBatch(limit: Int = 100): List<StagedDocument> {
        return try {
            if (jdbcUrl.startsWith("jdbc:postgresql")) {
                getPendingBatchWithPriority(limit)
            } else {
                transaction {
                    DocumentStagingTable
                        .selectAll()
                        .where { DocumentStagingTable.embeddingStatus eq EmbeddingStatus.PENDING.name }
                        .orderBy(DocumentStagingTable.createdAt to SortOrder.ASC)
                        .limit(limit)
                        .map(::toStagedDocument)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pending batch: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Updates document processing status after embedding service interaction.
     *
     * Called by EmbeddingScheduler to record state transitions:
     * - PENDING → IN_PROGRESS: Embedding request sent to BGE-M3 service
     * - IN_PROGRESS → COMPLETED: Vector successfully stored in Qdrant
     * - IN_PROGRESS → FAILED: Embedding service error (retry if count < 3)
     *
     * The updatedAt timestamp enables monitoring for stuck documents (e.g., if a document
     * stays IN_PROGRESS for > 5 minutes, the embedding service may have crashed).
     *
     * @param id Document ID to update
     * @param newStatus Target state (typically COMPLETED or FAILED)
     * @param errorMessage Optional error details from embedding service (for debugging)
     * @param incrementRetry True to increment retry counter on failure (max 3 attempts)
     */
    suspend fun updateStatus(
        id: String,
        newStatus: EmbeddingStatus,
        errorMessage: String? = null,
        incrementRetry: Boolean = false
    ) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[embeddingStatus] = newStatus.name
                    it[updatedAt] = Instant.now()

                    if (errorMessage != null) {
                        it[DocumentStagingTable.errorMessage] = errorMessage
                    }

                    if (incrementRetry) {
                        it[retryCount] = DocumentStagingTable.retryCount + 1
                    }
                }
            }

            logger.debug { "Updated status for $id: $newStatus" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update status: ${e.message}" }
        }
    }

    /**
     * Bulk status transition for a batch of document IDs.
     *
     * This is optimized for high-throughput embedding workers that move many documents
     * through the same state transition together (e.g. PENDING -> IN_PROGRESS -> COMPLETED).
     */
    suspend fun updateStatusBatch(
        ids: List<String>,
        newStatus: EmbeddingStatus,
        errorMessage: String? = null
    ) {
        if (ids.isEmpty()) return

        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id inList ids }) {
                    it[embeddingStatus] = newStatus.name
                    it[updatedAt] = Instant.now()
                    if (errorMessage != null) {
                        it[DocumentStagingTable.errorMessage] = errorMessage
                    }
                }
            }
            logger.debug { "Updated status for ${ids.size} docs: $newStatus" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update batch status: ${e.message}" }
        }
    }

    /**
     * Aggregates document counts by processing status for monitoring dashboards.
     *
     * Used by MonitoringServer to expose Prometheus metrics showing pipeline health:
     * - High pending count: Embedding service may be bottleneck
     * - Growing failed count: Investigate error messages for systemic issues
     * - Low completed count: Pipeline may not be ingesting data
     *
     * @return Map of status → count (keys: "pending", "in_progress", "completed", "failed")
     */
    suspend fun getStats(): Map<String, Long> {
        return try {
            transaction {
                queryStats()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get stats: ${e.message}" }
            normalizeStats(emptyMap())
        }
    }

    suspend fun getStatsWithQueryTimeout(timeoutMs: Long): Map<String, Long>? {
        return try {
            transaction {
                applyLocalStatementTimeout(timeoutMs)
                queryStats()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Timed out or failed to get stats within ${timeoutMs}ms" }
            null
        }
    }

    /**
     * Records BookStack publication URL after document is published to knowledge base.
     *
     * Called by BookStackWriter after successfully creating a page via BookStack API.
     * The URL enables:
     * - Users to view published documents in human-readable format
     * - Monitoring dashboards to link from staging table to BookStack pages
     * - Deduplication across pipeline restarts (skip if bookstackUrl already set)
     *
     * @param id Document ID
     * @param bookstackUrl Full URL to BookStack page (e.g., https://docs.webservices.net/books/123/page/456)
     */
    suspend fun updateBookStackUrl(id: String, bookstackUrl: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[DocumentStagingTable.bookstackUrl] = bookstackUrl
                    it[updatedAt] = Instant.now()
                }
            }
            logger.debug { "Updated BookStack URL for $id: $bookstackUrl" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update BookStack URL for $id: ${e.message}" }
        }
    }

    suspend fun resetBookStackPublication(id: String, reason: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[DocumentStagingTable.bookstackUrl] = null
                    it[retryCount] = 0
                    it[errorMessage] = "BookStack publication reset: $reason"
                    it[updatedAt] = Instant.now()
                }
            }
            logger.info { "Reset BookStack publication for $id: $reason" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to reset BookStack publication for $id: ${e.message}" }
        }
    }

    suspend fun markBookStackPublicationValidated(id: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[updatedAt] = Instant.now()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark BookStack publication validated for $id: ${e.message}" }
        }
    }

    /**
     * Provides per-source status breakdown for debugging and capacity planning.
     *
     * Enables operators to identify which data sources are:
     * - Producing most content (high completed count)
     * - Experiencing errors (high failed count)
     * - Bottlenecked (high pending count relative to ingestion rate)
     *
     * @param source Source identifier (e.g., "rss", "cve", "wikipedia")
     * @return Map of status → count for this source only
     */
    suspend fun getStatsBySource(source: String): Map<String, Long> {
        return try {
            transaction {
                queryStatsBySource(source)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get source stats: ${e.message}" }
            normalizeStats(emptyMap())
        }
    }

    suspend fun getStatsBySourceWithQueryTimeout(source: String, timeoutMs: Long): Map<String, Long>? {
        return try {
            transaction {
                applyLocalStatementTimeout(timeoutMs)
                queryStatsBySource(source)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Timed out or failed to get stats for source '$source' within ${timeoutMs}ms" }
            null
        }
    }

    suspend fun getStatsBySourcesWithQueryTimeout(sources: List<String>, timeoutMs: Long): Map<String, Map<String, Long>>? {
        if (sources.isEmpty()) {
            return emptyMap()
        }

        return try {
            if (jdbcUrl.startsWith("jdbc:postgresql")) {
                DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("SET statement_timeout = $timeoutMs")
                    }

                    val sql =
                        """
                        SELECT source AS source_name, embedding_status, COUNT(*) AS doc_count
                        FROM document_staging
                        WHERE source = ANY (?)
                        GROUP BY source, embedding_status
                        """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setArray(1, conn.createArrayOf("text", sources.toTypedArray()))

                        val grouped = mutableMapOf<String, MutableMap<String, Long>>()
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val source = rs.getString("source_name")
                                val status = rs.getString("embedding_status").lowercase()
                                val count = rs.getLong("doc_count")
                                grouped.getOrPut(source) { mutableMapOf() }[status] = count
                            }
                        }

                        sources.associateWith { source ->
                            normalizeStats(grouped[source] ?: emptyMap())
                        }
                    }
                }
            } else {
                transaction {
                    applyLocalStatementTimeout(timeoutMs)
                    queryStatsBySources(sources)
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Timed out or failed to get stats for ${sources.size} sources within ${timeoutMs}ms" }
            null
        }
    }

    /**
     * Polls for embedded documents awaiting BookStack publication.
     *
     * Called by BookStackWriter on a fixed interval to fetch the next batch of documents
     * that have been successfully embedded in Qdrant and are ready for human consumption
     * in the knowledge base.
     *
     * Documents must have:
     * - embeddingStatus = COMPLETED (vector stored in Qdrant)
     * - retryCount < 3 (not permanently failed due to BookStack API errors)
     *
     * Note: The bookstackUrl field tracks publication separately from embedding status.
     * This decoupling allows the same document to be searchable (via Qdrant) even if
     * BookStack publishing fails.
     *
     * @param limit Maximum documents to return (default 50 for BookStack API rate limiting)
     * @return List of COMPLETED documents ready for publication, oldest first
     */
    suspend fun getPendingForBookStack(limit: Int = 50, allowedSources: Set<String> = emptySet()): List<StagedDocument> {
        return try {
            if (jdbcUrl.startsWith("jdbc:postgresql")) {
                getPendingForBookStackWithPriority(limit, allowedSources)
            } else {
                transaction {
                    var query = DocumentStagingTable
                        .selectAll()
                        .where {
                            (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                                (DocumentStagingTable.bookstackUrl.isNull()) and
                                (DocumentStagingTable.retryCount less 3)
                        }

                    if (allowedSources.isNotEmpty()) {
                        query = query.andWhere { DocumentStagingTable.sourceName.inList(allowedSources.toList()) }
                    }

                    query
                        .orderBy(DocumentStagingTable.createdAt to SortOrder.ASC)
                        .limit(limit)
                        .map(::toStagedDocument)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pending BookStack docs: ${e.message}" }
            emptyList()
        }
    }

    suspend fun getBookStackReconciliationCandidates(limit: Int = 200, allowedSources: Set<String> = emptySet()): List<StagedDocument> {
        return try {
            if (jdbcUrl.startsWith("jdbc:postgresql")) {
                getBookStackReconciliationCandidatesWithPriority(limit, allowedSources)
            } else {
                transaction {
                    var query = DocumentStagingTable
                        .selectAll()
                        .where {
                            (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                                (DocumentStagingTable.bookstackUrl.isNull())
                        }

                    if (allowedSources.isNotEmpty()) {
                        query = query.andWhere { DocumentStagingTable.sourceName.inList(allowedSources.toList()) }
                    }

                    query
                        .orderBy(DocumentStagingTable.updatedAt to SortOrder.ASC)
                        .limit(limit)
                        .map(::toStagedDocument)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get BookStack reconciliation candidates: ${e.message}" }
            emptyList()
        }
    }

    suspend fun getPublishedBookStackValidationCandidates(limit: Int = 200, allowedSources: Set<String> = emptySet()): List<StagedDocument> {
        return try {
            if (jdbcUrl.startsWith("jdbc:postgresql")) {
                getPublishedBookStackValidationCandidatesWithPriority(limit, allowedSources)
            } else {
                transaction {
                    var query = DocumentStagingTable
                        .selectAll()
                        .where {
                            (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                                DocumentStagingTable.bookstackUrl.isNotNull() and
                                (DocumentStagingTable.bookstackUrl neq "") and
                                (DocumentStagingTable.bookstackUrl notLike "$BOOKSTACK_SKIPPED_PREFIX%")
                        }

                    if (allowedSources.isNotEmpty()) {
                        query = query.andWhere { DocumentStagingTable.sourceName.inList(allowedSources.toList()) }
                    }

                    query
                        .orderBy(DocumentStagingTable.updatedAt to SortOrder.ASC)
                        .limit(limit)
                        .map(::toStagedDocument)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get published BookStack validation candidates: ${e.message}" }
            emptyList()
        }
    }

    private fun priorityCaseSql(column: String = "collection"): String {
        return buildString {
            append("CASE ")
            PROCESSING_PRIORITY.forEachIndexed { index, collection ->
                append("WHEN $column = '$collection' THEN $index ")
            }
            append("ELSE ${PROCESSING_PRIORITY.size} END")
        }
    }

    private fun getPendingBatchWithPriority(limit: Int): List<StagedDocument> {
        DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
            val collections = getActiveCollections(conn, EmbeddingStatus.PENDING)
            val docsByCollection = collections.mapNotNull { collection ->
                fetchBatchForCollection(
                    conn = conn,
                    collection = collection,
                    status = EmbeddingStatus.PENDING,
                    limit = limit
                ).takeIf { it.isNotEmpty() }?.let { collection to it }
            }
            return interleaveCollectionsFairly(docsByCollection, limit)
        }
    }

    private fun getPendingForBookStackWithPriority(limit: Int, allowedSources: Set<String>): List<StagedDocument> {
        DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
            val collections = getActiveCollections(
                conn = conn,
                status = EmbeddingStatus.COMPLETED,
                allowedSources = allowedSources,
                requirePendingBookStackWrite = true
            )
            val docsByCollection = collections.mapNotNull { collection ->
                fetchBatchForCollection(
                    conn = conn,
                    collection = collection,
                    status = EmbeddingStatus.COMPLETED,
                    limit = limit,
                    allowedSources = allowedSources,
                    requirePendingBookStackWrite = true
                ).takeIf { it.isNotEmpty() }?.let { collection to it }
            }
            return interleaveCollectionsFairly(docsByCollection, limit)
        }
    }

    private fun getBookStackReconciliationCandidatesWithPriority(limit: Int, allowedSources: Set<String>): List<StagedDocument> {
        DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
            val collections = getActiveCollections(
                conn = conn,
                status = EmbeddingStatus.COMPLETED,
                allowedSources = allowedSources,
                requirePendingBookStackWrite = false
            )
            val docsByCollection = collections.mapNotNull { collection ->
                fetchBatchForCollection(
                    conn = conn,
                    collection = collection,
                    status = EmbeddingStatus.COMPLETED,
                    limit = limit,
                    allowedSources = allowedSources,
                    requirePendingBookStackWrite = false,
                    requireMissingBookStackUrl = true
                ).takeIf { it.isNotEmpty() }?.let { collection to it }
            }
            return interleaveCollectionsFairly(docsByCollection, limit)
        }
    }

    private fun getPublishedBookStackValidationCandidatesWithPriority(limit: Int, allowedSources: Set<String>): List<StagedDocument> {
        DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
            val collections = getActiveCollections(
                conn = conn,
                status = EmbeddingStatus.COMPLETED,
                allowedSources = allowedSources,
                requirePublishedBookStackUrl = true
            )
            val docsByCollection = collections.mapNotNull { collection ->
                fetchBatchForCollection(
                    conn = conn,
                    collection = collection,
                    status = EmbeddingStatus.COMPLETED,
                    limit = limit,
                    allowedSources = allowedSources,
                    requirePublishedBookStackUrl = true
                ).takeIf { it.isNotEmpty() }?.let { collection to it }
            }
            return interleaveCollectionsFairly(docsByCollection, limit)
        }
    }

    suspend fun getPublishedForQdrantSync(limit: Int = 1000, allowedSources: Set<String> = emptySet()): List<StagedDocument> {
        return try {
            transaction {
                var query = DocumentStagingTable
                    .selectAll()
                    .where {
                        (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                            DocumentStagingTable.bookstackUrl.isNotNull() and
                            (DocumentStagingTable.bookstackUrl notLike "$BOOKSTACK_SKIPPED_PREFIX%")
                    }

                if (allowedSources.isNotEmpty()) {
                    query = query.andWhere { DocumentStagingTable.sourceName.inList(allowedSources.toList()) }
                }

                query
                    .orderBy(DocumentStagingTable.updatedAt to SortOrder.DESC)
                    .limit(limit)
                    .map(::toStagedDocument)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get published docs for Qdrant sync: ${e.message}" }
            emptyList()
        }
    }

    
    suspend fun markBookStackComplete(id: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[updatedAt] = Instant.now()
                    
                }
            }
            logger.debug { "Marked BookStack write complete for $id" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark BookStack complete: ${e.message}" }
        }
    }

    
    suspend fun markBookStackFailed(id: String, error: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[updatedAt] = Instant.now()
                    it[retryCount] = DocumentStagingTable.retryCount + 1
                    it[errorMessage] = "BookStack write failed: $error"
                }
            }
            logger.debug { "Marked BookStack write failed for $id: $error" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark BookStack failed: ${e.message}" }
        }
    }

    
    suspend fun getBookStackStats(): Map<String, Long> {
        return try {
            transaction {
                getBookStackStatsMap()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get BookStack stats: ${e.message}" }
            mapOf(
                "total_embedded" to 0L,
                "bookstack_pending" to 0L,
                "bookstack_failed" to 0L,
                "bookstack_completed" to 0L,
                "bookstack_skipped" to 0L
            )
        }
    }

    suspend fun getBookStackStatsBySource(source: String): BookStackStats {
        return try {
            transaction {
                getBookStackStatsModel(source)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get BookStack stats for $source: ${e.message}" }
            BookStackStats(
                totalEmbedded = 0,
                pending = 0,
                completed = 0,
                failed = 0,
                skipped = 0
            )
        }
    }

    suspend fun getBookStackStatsBySourcesWithQueryTimeout(
        sources: List<String>,
        timeoutMs: Long
    ): Map<String, BookStackStats>? {
        if (sources.isEmpty()) {
            return emptyMap()
        }

        return try {
            DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
                if (jdbcUrl.startsWith("jdbc:postgresql")) {
                    conn.createStatement().use { stmt ->
                        stmt.execute("SET statement_timeout = $timeoutMs")
                    }
                    val sql =
                        """
                        SELECT
                            source AS source_name,
                            SUM(CASE WHEN embedding_status = 'COMPLETED' THEN 1 ELSE 0 END) AS total_embedded,
                            SUM(CASE WHEN bookstack_url IS NOT NULL AND bookstack_url NOT LIKE ? THEN 1 ELSE 0 END) AS completed,
                            SUM(CASE WHEN bookstack_url LIKE ? THEN 1 ELSE 0 END) AS skipped,
                            SUM(CASE WHEN embedding_status = 'COMPLETED' AND bookstack_url IS NULL AND retry_count < 3 THEN 1 ELSE 0 END) AS pending,
                            SUM(CASE WHEN retry_count >= 3 AND error_message LIKE 'BookStack write failed:%' THEN 1 ELSE 0 END) AS failed
                        FROM document_staging
                        WHERE source = ANY (?)
                        GROUP BY source
                        """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, "$BOOKSTACK_SKIPPED_PREFIX%")
                        stmt.setString(2, "$BOOKSTACK_SKIPPED_PREFIX%")
                        stmt.setArray(3, conn.createArrayOf("text", sources.toTypedArray()))

                        val stats = mutableMapOf<String, BookStackStats>()
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val source = rs.getString("source_name")
                                stats[source] = BookStackStats(
                                    totalEmbedded = rs.getLong("total_embedded"),
                                    pending = rs.getLong("pending"),
                                    completed = rs.getLong("completed"),
                                    failed = rs.getLong("failed"),
                                    skipped = rs.getLong("skipped")
                                )
                            }
                        }

                        return sources.associateWith { source ->
                            stats[source] ?: BookStackStats(
                                totalEmbedded = 0,
                                pending = 0,
                                completed = 0,
                                failed = 0,
                                skipped = 0
                            )
                        }
                    }
                }

                val sql = sources.joinToString(separator = " UNION ALL ") {
                    """
                    SELECT
                        ? AS source_name,
                        SUM(CASE WHEN embedding_status = 'COMPLETED' THEN 1 ELSE 0 END) AS total_embedded,
                        SUM(CASE WHEN bookstack_url IS NOT NULL AND bookstack_url NOT LIKE ? THEN 1 ELSE 0 END) AS completed,
                        SUM(CASE WHEN bookstack_url LIKE ? THEN 1 ELSE 0 END) AS skipped,
                        SUM(CASE WHEN embedding_status = 'COMPLETED' AND bookstack_url IS NULL AND retry_count < 3 THEN 1 ELSE 0 END) AS pending,
                        SUM(CASE WHEN retry_count >= 3 AND error_message LIKE 'BookStack write failed:%' THEN 1 ELSE 0 END) AS failed
                    FROM document_staging
                    WHERE "source" = ?
                    """.trimIndent()
                }

                conn.prepareStatement(sql).use { stmt ->
                    sources.forEachIndexed { index, source ->
                        val base = index * 4
                        stmt.setString(base + 1, source)
                        stmt.setString(base + 2, "$BOOKSTACK_SKIPPED_PREFIX%")
                        stmt.setString(base + 3, "$BOOKSTACK_SKIPPED_PREFIX%")
                        stmt.setString(base + 4, source)
                    }

                    val stats = mutableMapOf<String, BookStackStats>()
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val source = rs.getString("source_name")
                            stats[source] = BookStackStats(
                                totalEmbedded = rs.getLong("total_embedded"),
                                pending = rs.getLong("pending"),
                                completed = rs.getLong("completed"),
                                failed = rs.getLong("failed"),
                                skipped = rs.getLong("skipped")
                            )
                        }
                    }

                    return sources.associateWith { source ->
                        stats[source] ?: BookStackStats(
                            totalEmbedded = 0,
                            pending = 0,
                            completed = 0,
                            failed = 0,
                            skipped = 0
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Timed out or failed to get BookStack stats for ${sources.size} sources within ${timeoutMs}ms" }
            null
        }
    }

    suspend fun getReadinessStatsBySourcesWithQueryTimeout(
        sources: List<String>,
        timeoutMs: Long
    ): Map<String, SourceReadinessStats>? {
        if (sources.isEmpty()) {
            return emptyMap()
        }

        return try {
            DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
                if (jdbcUrl.startsWith("jdbc:postgresql")) {
                    conn.createStatement().use { stmt ->
                        stmt.execute("SET statement_timeout = $timeoutMs")
                    }
                    val sql =
                        """
                        SELECT
                            source AS source_name,
                            SUM(CASE WHEN embedding_status = 'COMPLETED' THEN 1 ELSE 0 END) AS searchable_documents,
                            SUM(CASE WHEN embedding_status = 'PENDING' THEN 1 ELSE 0 END) AS pending_embedding,
                            SUM(CASE WHEN embedding_status = 'IN_PROGRESS' THEN 1 ELSE 0 END) AS in_progress_embedding,
                            SUM(CASE WHEN embedding_status = 'FAILED' THEN 1 ELSE 0 END) AS failed_embedding,
                            SUM(CASE WHEN bookstack_url IS NOT NULL AND bookstack_url <> '' AND bookstack_url NOT LIKE ? THEN 1 ELSE 0 END) AS published_documents,
                            SUM(CASE WHEN embedding_status = 'COMPLETED' AND bookstack_url IS NULL AND retry_count < 3 THEN 1 ELSE 0 END) AS pending_publication,
                            SUM(CASE WHEN retry_count >= 3 AND error_message LIKE 'BookStack write failed:%' THEN 1 ELSE 0 END) AS failed_publication,
                            SUM(CASE WHEN bookstack_url LIKE ? THEN 1 ELSE 0 END) AS skipped_publication
                        FROM document_staging
                        WHERE source = ANY (?)
                        GROUP BY source
                        """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, "$BOOKSTACK_SKIPPED_PREFIX%")
                        stmt.setString(2, "$BOOKSTACK_SKIPPED_PREFIX%")
                        stmt.setArray(3, conn.createArrayOf("text", sources.toTypedArray()))

                        val stats = mutableMapOf<String, SourceReadinessStats>()
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val source = rs.getString("source_name")
                                stats[source] = SourceReadinessStats(
                                    searchableDocuments = rs.getLong("searchable_documents"),
                                    pendingEmbedding = rs.getLong("pending_embedding"),
                                    inProgressEmbedding = rs.getLong("in_progress_embedding"),
                                    failedEmbedding = rs.getLong("failed_embedding"),
                                    publishedDocuments = rs.getLong("published_documents"),
                                    pendingPublication = rs.getLong("pending_publication"),
                                    failedPublication = rs.getLong("failed_publication"),
                                    skippedPublication = rs.getLong("skipped_publication")
                                )
                            }
                        }

                        return sources.associateWith { source ->
                            stats[source] ?: SourceReadinessStats(
                                searchableDocuments = 0,
                                pendingEmbedding = 0,
                                inProgressEmbedding = 0,
                                failedEmbedding = 0,
                                publishedDocuments = 0,
                                pendingPublication = 0,
                                failedPublication = 0,
                                skippedPublication = 0
                            )
                        }
                    }
                }

                val valuesClause = sources.joinToString(", ") { "(?)" }
                val sql =
                    """
                    WITH requested_sources(source_name) AS (
                        VALUES $valuesClause
                    )
                    SELECT
                        rs.source_name,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'COMPLETED'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS searchable_documents,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'PENDING'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS pending_embedding,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'IN_PROGRESS'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS in_progress_embedding,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'FAILED'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS failed_embedding,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.bookstack_url IS NOT NULL
                              AND ds.bookstack_url <> ''
                              AND ds.bookstack_url NOT LIKE ?
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS published_documents,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'COMPLETED'
                              AND ds.bookstack_url IS NULL
                              AND ds.retry_count < 3
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS pending_publication,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.retry_count >= 3
                              AND ds.error_message LIKE 'BookStack write failed:%'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS failed_publication,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.bookstack_url LIKE ?
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS skipped_publication
                    FROM requested_sources rs
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    var parameterIndex = 1
                    sources.forEach { source ->
                        stmt.setString(parameterIndex++, source)
                    }
                    stmt.setString(parameterIndex++, "$BOOKSTACK_SKIPPED_PREFIX%")
                    stmt.setString(parameterIndex, "$BOOKSTACK_SKIPPED_PREFIX%")

                    val stats = mutableMapOf<String, SourceReadinessStats>()
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val source = rs.getString("source_name")
                            stats[source] = SourceReadinessStats(
                                searchableDocuments = rs.getLong("searchable_documents"),
                                pendingEmbedding = rs.getLong("pending_embedding"),
                                inProgressEmbedding = rs.getLong("in_progress_embedding"),
                                failedEmbedding = rs.getLong("failed_embedding"),
                                publishedDocuments = rs.getLong("published_documents"),
                                pendingPublication = rs.getLong("pending_publication"),
                                failedPublication = rs.getLong("failed_publication"),
                                skippedPublication = rs.getLong("skipped_publication")
                            )
                        }
                    }

                    sources.associateWith { source ->
                        stats[source] ?: SourceReadinessStats(
                            searchableDocuments = 0,
                            pendingEmbedding = 0,
                            inProgressEmbedding = 0,
                            failedEmbedding = 0,
                            publishedDocuments = 0,
                            pendingPublication = 0,
                            failedPublication = 0,
                            skippedPublication = 0
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Timed out or failed to get readiness stats for ${sources.size} sources within ${timeoutMs}ms" }
            null
        }
    }

    suspend fun getReadinessEvidenceBySourcesWithQueryTimeout(
        sources: List<String>,
        timeoutMs: Long
    ): Map<String, SourceReadinessStats>? {
        if (sources.isEmpty()) {
            return emptyMap()
        }

        return try {
            DriverManager.getConnection(jdbcUrl, user, dbPassword).use { conn ->
                if (jdbcUrl.startsWith("jdbc:postgresql")) {
                    conn.createStatement().use { stmt ->
                        stmt.execute("SET statement_timeout = $timeoutMs")
                    }
                }

                val valuesClause = sources.joinToString(", ") { "(?)" }
                val sql =
                    """
                    WITH requested_sources(source_name) AS (
                        VALUES $valuesClause
                    )
                    SELECT
                        rs.source_name,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'COMPLETED'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS searchable_documents,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'PENDING'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS pending_embedding,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'IN_PROGRESS'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS in_progress_embedding,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'FAILED'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS failed_embedding,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.bookstack_url IS NOT NULL
                              AND ds.bookstack_url <> ''
                              AND ds.bookstack_url NOT LIKE ?
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS published_documents,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.embedding_status = 'COMPLETED'
                              AND ds.bookstack_url IS NULL
                              AND ds.retry_count < 3
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS pending_publication,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.retry_count >= 3
                              AND ds.error_message LIKE 'BookStack write failed:%'
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS failed_publication,
                        CASE WHEN EXISTS (
                            SELECT 1
                            FROM document_staging ds
                            WHERE ds.source = rs.source_name
                              AND ds.bookstack_url LIKE ?
                            LIMIT 1
                        ) THEN 1 ELSE 0 END AS skipped_publication
                    FROM requested_sources rs
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    var parameterIndex = 1
                    sources.forEach { source ->
                        stmt.setString(parameterIndex++, source)
                    }
                    stmt.setString(parameterIndex++, "$BOOKSTACK_SKIPPED_PREFIX%")
                    stmt.setString(parameterIndex, "$BOOKSTACK_SKIPPED_PREFIX%")

                    val stats = mutableMapOf<String, SourceReadinessStats>()
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val source = rs.getString("source_name")
                            stats[source] = SourceReadinessStats(
                                searchableDocuments = rs.getLong("searchable_documents"),
                                pendingEmbedding = rs.getLong("pending_embedding"),
                                inProgressEmbedding = rs.getLong("in_progress_embedding"),
                                failedEmbedding = rs.getLong("failed_embedding"),
                                publishedDocuments = rs.getLong("published_documents"),
                                pendingPublication = rs.getLong("pending_publication"),
                                failedPublication = rs.getLong("failed_publication"),
                                skippedPublication = rs.getLong("skipped_publication")
                            )
                        }
                    }

                    return sources.associateWith { source ->
                        stats[source] ?: SourceReadinessStats(
                            searchableDocuments = 0,
                            pendingEmbedding = 0,
                            inProgressEmbedding = 0,
                            failedEmbedding = 0,
                            publishedDocuments = 0,
                            pendingPublication = 0,
                            failedPublication = 0,
                            skippedPublication = 0
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Timed out or failed to get readiness evidence for ${sources.size} sources within ${timeoutMs}ms" }
            null
        }
    }

    private fun Transaction.getBookStackStatsMap(source: String? = null): Map<String, Long> {
        val stats = getBookStackStatsModel(source)
        return mapOf(
            "total_embedded" to stats.totalEmbedded,
            "bookstack_completed" to stats.completed,
            "bookstack_pending" to stats.pending,
            "bookstack_failed" to stats.failed,
            "bookstack_skipped" to stats.skipped
        )
    }

    private fun Transaction.getBookStackStatsModel(source: String? = null): BookStackStats {
        val sourceFilter = if (source.isNullOrBlank()) {
            Op.TRUE
        } else {
            Op.build { DocumentStagingTable.sourceName eq source }
        }

        val totalEmbedded = DocumentStagingTable
            .select(DocumentStagingTable.id.count())
            .where {
                (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and sourceFilter
            }
            .single()[DocumentStagingTable.id.count()]

        val completed = DocumentStagingTable
            .select(DocumentStagingTable.id.count())
            .where {
                DocumentStagingTable.bookstackUrl.isNotNull() and
                    (DocumentStagingTable.bookstackUrl notLike "$BOOKSTACK_SKIPPED_PREFIX%") and
                    sourceFilter
            }
            .single()[DocumentStagingTable.id.count()]

        val skipped = DocumentStagingTable
            .select(DocumentStagingTable.id.count())
            .where {
                (DocumentStagingTable.bookstackUrl like "$BOOKSTACK_SKIPPED_PREFIX%") and sourceFilter
            }
            .single()[DocumentStagingTable.id.count()]

        val pending = DocumentStagingTable
            .select(DocumentStagingTable.id.count())
            .where {
                (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                    DocumentStagingTable.bookstackUrl.isNull() and
                    (DocumentStagingTable.retryCount less 3) and
                    sourceFilter
            }
            .single()[DocumentStagingTable.id.count()]

        val failed = DocumentStagingTable
            .select(DocumentStagingTable.id.count())
            .where {
                (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                    DocumentStagingTable.bookstackUrl.isNull() and
                    (DocumentStagingTable.retryCount greaterEq 3) and
                    sourceFilter
            }
            .single()[DocumentStagingTable.id.count()]

        return BookStackStats(
            totalEmbedded = totalEmbedded,
            pending = pending,
            completed = completed,
            failed = failed,
            skipped = skipped
        )
    }

    
    override fun close() {
        dataSource.close()
        logger.info { "DocumentStagingStore closed (connection pool released)" }
    }
}
