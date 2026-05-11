package org.webservices.testrunner.suites

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

suspend fun TestRunner.searchServiceTests() = suite("Search Service RAG Provider") {
    val runner = this@searchServiceTests
    val bm25ReadyCollections = mutableSetOf<String>()
    val searchReadinessCache = mutableMapOf<String, PipelineSourceReadiness?>()

    suspend fun ensureEmbeddingBackendReady(label: String) {
        val embeddingReadiness = probeEmbeddingBackendReadiness(maxAttempts = 4)
        require(embeddingReadiness.ready) {
            "$label requires query embeddings: ${embeddingReadiness.reason ?: "embedding backend unavailable"}"
        }
    }

    data class SeedDocument(
        val id: String,
        val collection: String,
        val source: String,
        val text: String,
        val metadata: Map<String, String>,
        val bookstackUrl: String? = null
    )

    fun postgresCollectionCount(collection: String): Int {
        DriverManager.getConnection(
            env.endpoints.postgres.jdbcUrl,
            env.endpoints.postgres.user,
            env.endpoints.postgres.password
        ).use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM document_staging WHERE collection = ?").use { stmt ->
                stmt.setString(1, collection)
                stmt.executeQuery().use { rs ->
                    require(rs.next()) { "Failed to count staged docs for $collection" }
                    return rs.getInt(1)
                }
            }
        }
    }

    fun postgresScalar(sql: String, timeoutSeconds: Int = 5): String? {
        DriverManager.getConnection(
            env.endpoints.postgres.jdbcUrl,
            env.endpoints.postgres.user,
            env.endpoints.postgres.password
        ).use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.queryTimeout = timeoutSeconds
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    fun normalizeKeywordQuery(raw: String, maxTerms: Int = 4): String {
        val terms = Regex("""[\p{L}\p{N}]{3,}""")
            .findAll(raw)
            .map { it.value }
            .take(maxTerms)
            .toList()
        return terms.joinToString(" ").ifBlank { raw.trim().take(80) }
    }

    fun fullTextIndexName(collection: String): String =
        "idx_staging_fulltext_${collection.replace(Regex("[^a-zA-Z0-9]+"), "_")}_completed"

    fun fullTextIndexWaitMs(envName: String, defaultSeconds: Long): Long =
        (System.getenv(envName)?.toLongOrNull() ?: defaultSeconds).coerceAtLeast(0L) * 1000L

    fun fullTextIndexTimeoutMessage(
        collection: String,
        maxWaitMs: Long,
        context: String = "PostgreSQL BM25 prerequisites"
    ): String =
        "${fullTextIndexName(collection)} did not become ready within ${maxWaitMs / 1000}s; " +
            "$context for $collection are not satisfied yet"

    suspend fun awaitFullTextIndexReady(
        collection: String,
        maxWaitMs: Long = fullTextIndexWaitMs("TEST_FULLTEXT_INDEX_MAX_WAIT_SECONDS", 180L),
        pollIntervalMs: Long = 5000L,
        allowCachedReady: Boolean = true
    ): Boolean {
        if (allowCachedReady && collection in bm25ReadyCollections) {
            return true
        }

        val indexName = fullTextIndexName(collection)
        val deadline = System.currentTimeMillis() + maxWaitMs
        var lastWaitMessage: String? = null
        while (System.currentTimeMillis() < deadline) {
            DriverManager.getConnection(
                env.endpoints.postgres.jdbcUrl,
                env.endpoints.postgres.user,
                env.endpoints.postgres.password
            ).use { conn ->
                conn.prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_index i
                        JOIN pg_class c ON c.oid = i.indexrelid
                        JOIN pg_class t ON t.oid = i.indrelid
                        WHERE t.relname = 'document_staging'
                          AND c.relname = ?
                          AND i.indisvalid
                          AND i.indisready
                    )
                    """.trimIndent()
                ).use { stmt ->
                    stmt.queryTimeout = 10
                    stmt.setString(1, indexName)
                    stmt.executeQuery().use { rs ->
                        require(rs.next()) { "Failed to query idx_staging_fulltext_completed readiness" }
                        if (rs.getBoolean(1)) {
                            bm25ReadyCollections += collection
                            return true
                        }
                    }
                }

                conn.prepareStatement(
                    """
                    SELECT COALESCE(
                        phase ||
                        CASE
                            WHEN lockers_total > 0 THEN
                                ' lockers=' || lockers_done || '/' || lockers_total
                            ELSE ''
                        END ||
                        CASE
                            WHEN blocks_total > 0 THEN
                                ' blocks=' || blocks_done || '/' || blocks_total
                            ELSE ''
                        END ||
                        CASE
                            WHEN tuples_total > 0 THEN
                                ' tuples=' || tuples_done || '/' || tuples_total
                            ELSE ''
                        END,
                        ''
                    )
                    FROM pg_stat_progress_create_index
                    WHERE index_relid = to_regclass(?)
                    LIMIT 1
                    """.trimIndent()
                ).use { stmt ->
                    stmt.queryTimeout = 10
                    stmt.setString(1, indexName)
                    stmt.executeQuery().use { rs ->
                        val waitMessage = if (rs.next()) {
                            val progress = rs.getString(1)?.trim().orEmpty()
                            if (progress.isNotEmpty()) {
                                "      ℹ️  Waiting for $indexName: $progress"
                            } else {
                                "      ℹ️  Waiting for $indexName to become ready..."
                            }
                        } else {
                            "      ℹ️  Waiting for $indexName to become ready..."
                        }
                        if (waitMessage != lastWaitMessage) {
                            println(waitMessage)
                            lastWaitMessage = waitMessage
                        }
                    }
                }
            }
            delay(pollIntervalMs)
        }

        return false
    }

    suspend fun waitForFullTextIndexReady(
        collection: String,
        maxWaitMs: Long = fullTextIndexWaitMs("TEST_FULLTEXT_INDEX_MAX_WAIT_SECONDS", 180L),
        pollIntervalMs: Long = 5000L,
        context: String = "PostgreSQL BM25 prerequisites",
        allowCachedReady: Boolean = true
    ) {
        require(
            awaitFullTextIndexReady(
                collection,
                maxWaitMs = maxWaitMs,
                pollIntervalMs = pollIntervalMs,
                allowCachedReady = allowCachedReady
            )
        ) {
            fullTextIndexTimeoutMessage(collection, maxWaitMs, context)
        }
    }

    suspend fun waitForBm25SearchReady(
        collection: String,
        queryProvider: () -> String,
        maxWaitMs: Long = fullTextIndexWaitMs("TEST_FULLTEXT_INDEX_MAX_WAIT_SECONDS", 180L),
        pollIntervalMs: Long = 5000L
    ) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var lastFailure = "bm25 results not available yet"

        while (System.currentTimeMillis() < deadline) {
            try {
                val query = queryProvider()
                val response = client.postRaw("${env.endpoints.searchService}/search") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("query", query)
                        put("mode", "bm25")
                        putJsonArray("collections") { add(collection) }
                        put("limit", 3)
                    })
                }
                if (response.status == HttpStatusCode.OK) {
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val results = body["results"]?.jsonArray
                    if (!results.isNullOrEmpty()) {
                        bm25ReadyCollections += collection
                        return
                    }
                    lastFailure = "bm25 query returned no results"
                } else {
                    lastFailure = "bm25 query returned ${response.status}"
                }
            } catch (error: Exception) {
                lastFailure = compactDependencyMessage(error.message)
            }

            delay(pollIntervalMs)
        }

        error("BM25 search for $collection did not become ready within ${maxWaitMs / 1000}s: $lastFailure")
    }

    suspend fun requireFullTextIndexReadyNow(
        collection: String,
        context: String = "PostgreSQL BM25 prerequisites"
    ) {
        require(
            awaitFullTextIndexReady(
                collection,
                maxWaitMs = 0L,
                pollIntervalMs = 0L,
                allowCachedReady = true
            )
        ) {
            "${fullTextIndexName(collection)} is not ready; $context for $collection are not satisfied yet"
        }
    }
    fun sampleRssQuery(): String {
        return postgresScalar(
            """
            SELECT word
            FROM ts_stat($$
                SELECT to_tsvector(
                    'english',
                    COALESCE(metadata::json->>'title', '') || ' ' ||
                    COALESCE(metadata::json->>'name', '')
                )
                FROM public.document_staging
                WHERE collection = 'rss_feeds'
                  AND embedding_status = 'COMPLETED'
                  AND COALESCE(
                      metadata::json->>'presentation_url',
                      metadata::json->>'url',
                      metadata::json->>'link',
                      bookstack_url
                  ) IS NOT NULL
                  AND COALESCE(metadata::json->>'title', metadata::json->>'name') IS NOT NULL
            $$)
            WHERE length(word) >= 4
              AND word NOT IN ('source', 'published', 'author', 'article', 'https', 'http', 'just')
            ORDER BY ndoc DESC, nentry DESC, word ASC
            LIMIT 1
            """.trimIndent(),
            timeoutSeconds = 15
        ) ?: error(
            "No searchable RSS BM25 lexeme found in document_staging within 15s; " +
                "check idx_staging_collection_status_created and RSS staging metadata"
        )
    }

    fun sampleCveQuery(): String {
        return postgresScalar(
            """
            SELECT COALESCE(
                metadata::json->>'cveId',
                metadata::json->>'cve_id',
                substring(text from '(CVE-[0-9]{4}-[0-9]+)'),
                substring(id from '(CVE-[0-9]{4}-[0-9]+)')
            )
            FROM document_staging
            WHERE collection = 'cve'
              AND embedding_status = 'COMPLETED'
              AND COALESCE(
                  metadata::json->>'cveId',
                  metadata::json->>'cve_id',
                  substring(text from '(CVE-[0-9]{4}-[0-9]+)'),
                  substring(id from '(CVE-[0-9]{4}-[0-9]+)')
              ) IS NOT NULL
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            timeoutSeconds = 15
        ) ?: error(
            "No searchable CVE identifier found in document_staging within 15s; " +
                "check idx_staging_collection_status_created and CVE staging metadata"
        )
    }

    fun sampleTorrentQuery(): String {
        val title = postgresScalar(
            """
            SELECT COALESCE(metadata::json->>'name', metadata::json->>'title')
            FROM document_staging
            WHERE collection = 'torrents'
              AND embedding_status = 'COMPLETED'
              AND COALESCE(
                  metadata::json->>'presentation_url',
                  metadata::json->>'url',
                  metadata::json->>'link',
                  metadata::json->>'infohash',
                  bookstack_url
              ) IS NOT NULL
              AND COALESCE(metadata::json->>'name', metadata::json->>'title') IS NOT NULL
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            timeoutSeconds = 15
        ) ?: error(
            "No searchable torrent title found in document_staging within 15s; " +
                "check idx_staging_collection_status_created and document_staging load"
        )
        return normalizeKeywordQuery(title)
    }

    fun sampleWikipediaQuery(): String {
        val title = postgresScalar(
            """
            WITH candidates AS (
                SELECT
                    COALESCE(metadata::json->>'title', substring(text from '#\s+([^\n]+)')) AS title,
                    metadata,
                    text,
                    created_at
                FROM document_staging
                WHERE collection = 'wikipedia'
                  AND embedding_status = 'COMPLETED'
            ),
            normalized AS (
                SELECT
                    title,
                    created_at,
                    trim(regexp_replace(regexp_replace(title, '[^[:alnum:] ]+', ' ', 'g'), '\s+', ' ', 'g')) AS query
                FROM candidates
                WHERE title IS NOT NULL
                  AND title !~ '[^[:ascii:]]'
                  AND title !~ '^(File|Wikipedia):'
            )
            SELECT title
            FROM normalized
            WHERE query ~ '^[[:alnum:]]{3,}( [[:alnum:]]{3,}){3,}$'
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            timeoutSeconds = 15
        ) ?: error(
            "No searchable Wikipedia title found in document_staging within 15s; " +
                "check idx_staging_collection_status_created and Wikipedia staging metadata"
        )
        return normalizeKeywordQuery(title)
    }

    fun sampleAustralianLawQuery(): String {
        val citation = postgresScalar(
            """
            SELECT COALESCE(metadata::json->>'citation', metadata::json->>'title')
            FROM document_staging
            WHERE collection = 'australian_laws'
              AND embedding_status = 'COMPLETED'
              AND COALESCE(metadata::json->>'citation', metadata::json->>'title') IS NOT NULL
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            timeoutSeconds = 15
        ) ?: error(
            "No searchable Australian law citation found in document_staging within 15s; " +
                "check idx_staging_collection_status_created and legislation staging metadata"
        )
        return normalizeKeywordQuery(citation)
    }

    val semanticWikipediaQuery = "science technology"
    val semanticAustralianLawQuery = "commonwealth industrial law"

    fun seedDocumentStaging(documents: List<SeedDocument>) {
        val collections = documents.map { it.collection }.distinct()
        val now = java.sql.Timestamp.from(Instant.now())

        DriverManager.getConnection(
            env.endpoints.postgres.jdbcUrl,
            env.endpoints.postgres.user,
            env.endpoints.postgres.password
        ).use { conn ->
            conn.autoCommit = false

            conn.prepareStatement(
                "DELETE FROM document_staging WHERE collection = ANY (?)"
            ).use { stmt ->
                stmt.setArray(1, conn.createArrayOf("text", collections.toTypedArray()))
                stmt.executeUpdate()
            }

            conn.prepareStatement(
                """
                INSERT INTO document_staging (
                    id, source, collection, text, metadata, embedding_status,
                    chunk_index, total_chunks, created_at, updated_at, retry_count, error_message, bookstack_url
                ) VALUES (?, ?, ?, ?, ?::text, 'COMPLETED', NULL, NULL, ?, ?, 0, NULL, ?)
                """.trimIndent()
            ).use { stmt ->
                for (doc in documents) {
                    stmt.setString(1, doc.id)
                    stmt.setString(2, doc.source)
                    stmt.setString(3, doc.collection)
                    stmt.setString(4, doc.text)
                    stmt.setString(5, Json.encodeToString(doc.metadata))
                    stmt.setTimestamp(6, now)
                    stmt.setTimestamp(7, now)
                    stmt.setString(8, doc.bookstackUrl)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }

            conn.commit()
        }
    }

    suspend fun deleteQdrantCollection(collection: String) {
        val response = client.deleteRaw("${env.endpoints.qdrant}/collections/$collection")
        require(
            response.status == HttpStatusCode.OK ||
                response.status == HttpStatusCode.Accepted ||
                response.status == HttpStatusCode.NotFound
        ) {
            "Failed to delete Qdrant collection $collection: ${response.status} ${response.bodyAsText()}"
        }
    }

    suspend fun recreateQdrantCollection(collection: String, vectorSize: Int) {
        val response = client.putRaw("${env.endpoints.qdrant}/collections/$collection") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonObject("vectors") {
                    put("size", vectorSize)
                    put("distance", "Cosine")
                }
            })
        }
        require(response.status == HttpStatusCode.OK) {
            "Failed to create Qdrant collection $collection: ${response.status} ${response.bodyAsText()}"
        }
    }

    suspend fun generateEmbeddingsForSeedDocuments(documents: List<SeedDocument>): List<List<Float>> {
        val embeddingUrl = (System.getenv("EMBEDDING_SERVICE_URL") ?: "http://embedding-gpu:8080").trimEnd('/')
        val response = client.postRaw("$embeddingUrl/embed") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("inputs") {
                    documents.forEach { add(it.text) }
                }
                put("truncate", true)
            })
        }
        val body = response.bodyAsText()
        require(response.status == HttpStatusCode.OK) {
            explainEmbeddingBackedSearchFailure(response.status, body)
                ?: "Failed to generate seed embeddings: ${response.status} ${compactDependencyMessage(body)}"
        }

        return Json.parseToJsonElement(body).jsonArray.map { embeddingElement ->
            embeddingElement.jsonArray.map { it.jsonPrimitive.float }
        }
    }

    suspend fun seedQdrantCollections(documents: List<SeedDocument>) {
        if (documents.isEmpty()) return

        val embeddings = generateEmbeddingsForSeedDocuments(documents)
        require(embeddings.size == documents.size) {
            "Embedding count mismatch for seeded vector corpus: ${embeddings.size} != ${documents.size}"
        }

        val vectorSize = embeddings.firstOrNull()?.size
            ?: error("No seed embeddings generated for deterministic search corpus")
        require(embeddings.all { it.size == vectorSize }) {
            "Seed embeddings returned inconsistent vector sizes"
        }

        documents.groupBy { it.collection }.forEach { (collection, collectionDocs) ->
            deleteQdrantCollection(collection)
            recreateQdrantCollection(collection, vectorSize)

            val response = client.putRaw("${env.endpoints.qdrant}/collections/$collection/points?wait=true") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    putJsonArray("points") {
                        collectionDocs.forEach { doc ->
                            val embedding = embeddings[documents.indexOf(doc)]
                            add(buildJsonObject {
                                put("id", UUID.nameUUIDFromBytes(doc.id.toByteArray()).toString())
                                putJsonArray("vector") {
                                    embedding.forEach { add(it) }
                                }
                                putJsonObject("payload") {
                                    put("title", doc.metadata["title"] ?: doc.metadata["name"].orEmpty())
                                    put("url", doc.metadata["url"].orEmpty())
                                    put("document_id", doc.id)
                                    doc.metadata.forEach { (key, value) -> put(key, value) }
                                }
                            })
                        }
                    }
                })
            }
            require(response.status == HttpStatusCode.OK) {
                "Failed to seed Qdrant collection $collection: ${response.status} ${response.bodyAsText()}"
            }
        }
    }

    suspend fun TestContext.searchRequest(
        label: String,
        query: String,
        mode: String,
        collections: List<String>,
        limit: Int = 10,
        audience: String? = null,
        timeoutMs: Long = 20_000L
    ): JsonObject {
        if (mode != "bm25") {
            ensureEmbeddingBackendReady(label)
        }

        val response = withTimeout(timeoutMs) {
            client.postRaw("${env.endpoints.searchService}/search") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("query", query)
                    put("mode", mode)
                    putJsonArray("collections") {
                        collections.forEach { add(it) }
                    }
                    put("limit", limit)
                    audience?.let { put("audience", it) }
                })
            }
        }
        val body = response.bodyAsText()
        require(response.status == HttpStatusCode.OK) {
            explainEmbeddingBackedSearchFailure(response.status, body)
                ?: "$label returned ${response.status}: ${compactDependencyMessage(body)}"
        }
        return Json.parseToJsonElement(body).jsonObject
    }

    suspend fun TestContext.searchCollection(
        label: String,
        query: String,
        collection: String,
        mode: String = "bm25",
        limit: Int = 10,
        timeoutMs: Long = 20_000L
    ): JsonArray {
        val body = searchRequest(
            label = label,
            query = query,
            mode = mode,
            collections = listOf(collection),
            limit = limit,
            timeoutMs = timeoutMs
        )
        return body["results"]?.jsonArray ?: JsonArray(emptyList())
    }

    suspend fun seedTestData() {
        println("      [SETUP] Seeding deterministic test data into PostgreSQL...")

        val collections = listOf("test-docs", "test-market", "test-bookstack", "test-cve")

        val testDocuments = listOf(
            SeedDocument(
                id = "seed-test-docs-k8s",
                collection = "test-docs",
                source = "documentation",
                text = "Kubernetes deployment guide for production environments with rollout and scaling guidance.",
                metadata = mapOf(
                    "title" to "Kubernetes Deployment Guide",
                    "url" to "https://docs.test.example.com/k8s-deploy",
                    "presentation_url" to "https://docs.test.example.com/k8s-deploy"
                )
            ),
            SeedDocument(
                id = "seed-test-market-btc",
                collection = "test-market",
                source = "market",
                text = "Bitcoin market dashboard with price analysis, time series charts, and volatility trends.",
                metadata = mapOf(
                    "title" to "Bitcoin Market Analysis",
                    "url" to "https://grafana.test.example.com/d/bitcoin-market",
                    "presentation_url" to "https://grafana.test.example.com/d/bitcoin-market",
                    "grafana_url" to "https://grafana.test.example.com/d/bitcoin-market",
                    "presentation_target" to "grafana",
                    "timeseries" to "true"
                )
            ),
            SeedDocument(
                id = "seed-test-bookstack-docs",
                collection = "test-bookstack",
                source = "bookstack",
                text = "BookStack documentation for collaborative knowledge management and interactive editing.",
                metadata = mapOf(
                    "title" to "BookStack Documentation",
                    "url" to "https://bookstack.test.example.com/books/documentation",
                    "presentation_url" to "https://bookstack.test.example.com/books/documentation"
                ),
                bookstackUrl = "https://bookstack.test.example.com/books/documentation"
            ),
            SeedDocument(
                id = "seed-test-cve-rce",
                collection = "test-cve",
                source = "cve",
                text = "CVE-2026-0001 critical remote code execution vulnerability affecting a web application login component.",
                metadata = mapOf(
                    "title" to "CVE-2026-0001",
                    "url" to "https://nvd.nist.gov/vuln/detail/CVE-2026-0001",
                    "presentation_url" to "https://nvd.nist.gov/vuln/detail/CVE-2026-0001",
                    "severity" to "CRITICAL",
                    "cveId" to "CVE-2026-0001"
                )
            )
        )

        seedDocumentStaging(testDocuments)
        seedQdrantCollections(testDocuments)

        for (collection in collections) {
            val stagedCount = postgresCollectionCount(collection)
            require(stagedCount > 0) { "document_staging has no rows for $collection after seed" }
        }

        println("      ✓ Test data seeded into PostgreSQL and Qdrant")
    }

    
    test("Setup: Seed test data") {
        seedTestData()
        kotlinx.coroutines.delay(1000)
    }

    test("Search service is healthy") {
        val health = client.healthCheck("search-service")
        health.healthy shouldBe true
        health.statusCode shouldBe 200
    }

    test("Can list available collections") {
        val response = client.getRawResponse("${env.endpoints.searchService}/collections")
        response.status shouldBe HttpStatusCode.OK

        val body = Json.parseToJsonElement(response.body<String>())
        val collections = when {
            body is kotlinx.serialization.json.JsonObject -> body["collections"]?.jsonArray
            body is kotlinx.serialization.json.JsonArray -> body
            else -> null
        }

        require(!collections.isNullOrEmpty()) { "Collections endpoint returned no collections or unexpected format" }
        val collectionNames = collections.map { it.jsonPrimitive.content }.toSet()
        val missingSeedCollections = listOf("test-docs", "test-market", "test-bookstack", "test-cve")
            .filter { it !in collectionNames }
        require(missingSeedCollections.isEmpty()) {
            "Collections endpoint is missing seeded collections: ${missingSeedCollections.joinToString()}"
        }
        println("      ✓ Found ${collections.size} collections")
    }

    test("Search returns results with content type") {
        val results = searchCollection(
            label = "BM25 test-docs search",
            query = "kubernetes deployment",
            collection = "test-docs",
            mode = "bm25",
            limit = 5
        )

        val firstResult = results.firstOrNull()?.jsonObject
        require(firstResult != null) { "Search returned no results for contentType validation" }
        require(firstResult.containsKey("contentType")) { "contentType field not present in search results" }
        println("      ✓ Search results include contentType field")
    }

    test("Exact staged document retrieval returns raw text and metadata") {
        val response = client.getRawResponse("${env.endpoints.searchService}/documents/seed-test-docs-k8s?collection=test-docs")
        require(response.status == HttpStatusCode.OK) {
            "Exact document lookup failed: ${response.status} ${response.bodyAsText()}"
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        body["id"]?.jsonPrimitive?.content shouldBe "seed-test-docs-k8s"
        body["collection"]?.jsonPrimitive?.content shouldBe "test-docs"
        body["text"]?.jsonPrimitive?.content?.contains("Kubernetes deployment guide") shouldBe true
        body["metadata"]?.jsonObject?.get("title")?.jsonPrimitive?.content shouldBe "Kubernetes Deployment Guide"
    }

    test("Exact staged document retrieval is collection-scoped") {
        val response = client.getRawResponse("${env.endpoints.searchService}/documents/seed-test-docs-k8s?collection=test-cve")
        response.status shouldBe HttpStatusCode.NotFound
    }

    test("Search returns results with capabilities") {
        val results = searchCollection(
            label = "BM25 test-market search",
            query = "bitcoin market",
            collection = "test-market",
            mode = "bm25",
            limit = 10
        )
        require(!results.isNullOrEmpty()) { "Search returned no results for capabilities validation" }

        val testResult = results.firstOrNull()?.jsonObject
        require(testResult != null) { "No valid search result object found for capabilities validation" }

        val capabilities = testResult.get("capabilities")?.jsonObject
        require(capabilities != null) { "Capabilities field not present in search results" }

        println("      ✓ Search results include capabilities field")

        require(capabilities.containsKey("humanFriendly")) { "Missing humanFriendly field" }
        require(capabilities.containsKey("agentFriendly")) { "Missing agentFriendly field" }
        require(capabilities.containsKey("hasTimeSeries")) { "Missing hasTimeSeries field" }
        require(capabilities.containsKey("hasRichContent")) { "Missing hasRichContent field" }
        require(capabilities.containsKey("isInteractive")) { "Missing isInteractive field" }
        require(capabilities.containsKey("isStructured")) { "Missing isStructured field" }
    }

    test("Human audience filter works") {
        val body = searchRequest(
            label = "Human audience filter",
            query = "documentation",
            mode = "bm25",
            collections = listOf("test-bookstack", "test-market", "test-cve", "test-docs"),
            limit = 20,
            audience = "human"
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "Human audience filter returned no seeded results" }
        results?.forEach { result ->
            val capabilities = result.jsonObject["capabilities"]?.jsonObject
            val humanFriendly = capabilities?.get("humanFriendly")?.jsonPrimitive?.boolean
            humanFriendly shouldBe true
        }
    }

    test("Agent audience filter works") {
        val body = searchRequest(
            label = "Agent audience filter",
            query = "CVE-2026-0001",
            mode = "bm25",
            collections = listOf("test-cve", "test-docs"),
            limit = 20,
            audience = "agent"
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "Agent audience filter returned no seeded results" }
        results?.forEach { result ->
            val capabilities = result.jsonObject["capabilities"]?.jsonObject
            val agentFriendly = capabilities?.get("agentFriendly")?.jsonPrimitive?.boolean
            agentFriendly shouldBe true
        }
    }

    test("BookStack content has correct capabilities") {
        val results = searchCollection(
            label = "BM25 test-bookstack search",
            query = "bookstack documentation",
            collection = "test-bookstack",
            mode = "bm25",
            limit = 10
        )
        val bookstackResult = results?.find {
            it.jsonObject["contentType"]?.jsonPrimitive?.content == "bookstack"
        }

        require(bookstackResult != null) { "No BookStack result found for capabilities validation" }
        val capabilities = bookstackResult.jsonObject["capabilities"]?.jsonObject
        capabilities?.get("humanFriendly")?.jsonPrimitive?.boolean shouldBe true
        capabilities?.get("agentFriendly")?.jsonPrimitive?.boolean shouldBe true
        capabilities?.get("hasRichContent")?.jsonPrimitive?.boolean shouldBe true
        capabilities?.get("isInteractive")?.jsonPrimitive?.boolean shouldBe true
        capabilities?.get("hasTimeSeries")?.jsonPrimitive?.boolean shouldBe false
    }

    test("CVE content has correct capabilities") {
        val results = searchCollection(
            label = "BM25 test-cve search",
            query = "CVE-2026-0001",
            collection = "test-cve",
            mode = "bm25",
            limit = 10
        )
        val cveResult = results?.find {
            it.jsonObject["contentType"]?.jsonPrimitive?.content == "vulnerability"
        }

        require(cveResult != null) { "No CVE result found for capabilities validation" }
        val capabilities = cveResult.jsonObject["capabilities"]?.jsonObject
        capabilities?.get("humanFriendly")?.jsonPrimitive?.boolean shouldBe true
        capabilities?.get("agentFriendly")?.jsonPrimitive?.boolean shouldBe true
        capabilities?.get("hasRichContent")?.jsonPrimitive?.boolean shouldBe true
        capabilities?.get("isInteractive")?.jsonPrimitive?.boolean shouldBe true
        capabilities?.get("isStructured")?.jsonPrimitive?.boolean shouldBe true
    }

    test("Vector search mode works") {
        val body = searchRequest(
            label = "Vector search mode",
            query = "kubernetes deployment",
            mode = "vector",
            collections = listOf("test-docs"),
            limit = 5
        )
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "vector"
    }

    test("Vector results include useful source snippets for agents") {
        val body = searchRequest(
            label = "Vector snippet quality",
            query = "kubernetes deployment production",
            mode = "vector",
            collections = listOf("test-docs"),
            limit = 5
        )
        val first = body["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("No vector result returned for seeded Kubernetes document")
        first["id"]?.jsonPrimitive?.content shouldBe "seed-test-docs-k8s"
        val snippet = first["snippet"]?.jsonPrimitive?.content.orEmpty()
        require(snippet.contains("production environments", ignoreCase = true)) {
            "Vector result snippet did not include staged source text: $snippet"
        }
    }

    test("BM25 search mode works") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "machine learning")
                put("mode", "bm25")
                put("limit", 5)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "bm25"
    }

    test("Hybrid search mode works (default)") {
        val body = searchRequest(
            label = "Hybrid search mode",
            query = "kubernetes deployment",
            mode = "hybrid",
            collections = listOf("test-docs"),
            limit = 5
        )
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
    }

    test("Hybrid ranking favors exact CVE identifiers") {
        val body = searchRequest(
            label = "Exact CVE hybrid ranking",
            query = "CVE-2026-0001 remote code execution",
            mode = "hybrid",
            collections = listOf("test-cve", "test-docs"),
            limit = 5
        )
        val first = body["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("No hybrid result returned for exact CVE query")
        first["id"]?.jsonPrimitive?.content shouldBe "seed-test-cve-rce"
        first["title"]?.jsonPrimitive?.content shouldBe "CVE-2026-0001"
    }

    test("Search respects limit parameter") {
        val limit = 3
        val result = client.search(
            query = "documentation",
            collections = listOf("test-bookstack", "test-docs", "test-market", "test-cve"),
            limit = limit,
            mode = "bm25",
            timeoutMs = 20_000L
        )
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val actualSize = results?.size ?: 0
        require(actualSize <= limit) { "Expected results size ($actualSize) to be <= limit ($limit)" }
    }

    test("Search with specific collection works") {
        val results = searchCollection(
            label = "Specific collection BM25 search",
            query = "bookstack documentation",
            collection = "test-bookstack",
            mode = "bm25",
            limit = 5
        )

        results.forEach { result ->
            val source = result.jsonObject["source"]?.jsonPrimitive?.content
            source?.contains("bookstack") shouldBe true
        }
    }

    test("Search UI page is served at root") {
        val response = client.getRawResponse(env.endpoints.searchService)
        response.status shouldBe HttpStatusCode.OK

        val html = response.body<String>()
        html.uppercase() shouldContain "<!DOCTYPE HTML>"
        html shouldContain "Search Knowledge Base"
        html shouldContain "searchInput"
    }

    test("Results include all required fields") {
        val result = client.search(
            query = "kubernetes deployment",
            collections = listOf("test-docs"),
            limit = 10,
            mode = "bm25",
            timeoutMs = 20_000L
        )
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "Search returned no results for required field validation" }

        val firstResult = results.firstOrNull()?.jsonObject
        require(firstResult != null) { "No valid result object found for required field validation" }

        val requiredFields = listOf("url", "title", "snippet", "score", "source", "contentType", "capabilities")
        val missingFields = requiredFields.filter { !firstResult.containsKey(it) }

        require(missingFields.isEmpty()) { "Missing fields: ${missingFields.joinToString()}" }
        println("      ✓ All required fields present")
    }

    test("Empty query returns validation error") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "")
                put("limit", 5)
            })
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    test("Malformed and wrongly typed search payloads return bad request") {
        val malformed = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody("{not-json")
        }
        malformed.status shouldBe HttpStatusCode.BadRequest

        val numericQuery = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"query":123,"limit":5}""")
        }
        numericQuery.status shouldBe HttpStatusCode.BadRequest
    }

    test("Invalid search collections return validation error") {
        val emptyCollections = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "documentation")
                putJsonArray("collections") {}
                put("limit", 5)
            })
        }
        emptyCollections.status shouldBe HttpStatusCode.BadRequest

        val unsafeCollection = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "documentation")
                putJsonArray("collections") { add("../postgres") }
                put("limit", 5)
            })
        }
        unsafeCollection.status shouldBe HttpStatusCode.BadRequest
    }

    test("Invalid search parameters return validation errors") {
        val cases = listOf(
            "invalid mode" to buildJsonObject {
                put("query", "documentation")
                put("mode", "phrase")
                put("limit", 5)
            },
            "invalid audience" to buildJsonObject {
                put("query", "documentation")
                put("audience", "robot")
                put("limit", 5)
            },
            "limit too low" to buildJsonObject {
                put("query", "documentation")
                put("limit", 0)
            },
            "limit too high" to buildJsonObject {
                put("query", "documentation")
                put("limit", 1001)
            },
            "too many collections" to buildJsonObject {
                put("query", "documentation")
                putJsonArray("collections") {
                    (1..51).forEach { add("collection_$it") }
                }
                put("limit", 5)
            },
            "collection fanout too large" to buildJsonObject {
                put("query", "documentation")
                putJsonArray("collections") {
                    (1..50).forEach { add("collection_$it") }
                }
                put("limit", 101)
            }
        )

        cases.forEach { (label, payload) ->
            val response = client.postRaw("${env.endpoints.searchService}/search") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            require(response.status == HttpStatusCode.BadRequest) {
                "$label should return BadRequest, got ${response.status}: ${response.bodyAsText()}"
            }
        }
    }

    test("Interactive content can be chatted with (OpenWebUI ready)") {
        val results = searchCollection(
            label = "Interactive content BM25 search",
            query = "bookstack documentation",
            collection = "test-bookstack",
            mode = "bm25",
            limit = 10
        )
        val interactiveResult = results?.find {
            it.jsonObject["capabilities"]?.jsonObject?.get("isInteractive")?.jsonPrimitive?.boolean == true
        }

        require(interactiveResult != null) { "No interactive result found in seeded BookStack data" }
        val capabilities = interactiveResult.jsonObject["capabilities"]?.jsonObject
        val hasRichContent = capabilities?.get("hasRichContent")?.jsonPrimitive?.boolean
        hasRichContent shouldBe true
    }

    test("Time series content can be graphed (Grafana ready)") {
        val results = searchCollection(
            label = "Time series BM25 search",
            query = "bitcoin market",
            collection = "test-market",
            mode = "bm25",
            limit = 10
        )
        val timeSeriesResult = results?.find {
            it.jsonObject["capabilities"]?.jsonObject?.get("hasTimeSeries")?.jsonPrimitive?.boolean == true
        }

        require(timeSeriesResult != null) { "No time-series capable result found in seeded market data" }

        val capabilities = timeSeriesResult.jsonObject["capabilities"]?.jsonObject
        val isStructured = capabilities?.get("isStructured")?.jsonPrimitive?.boolean
        isStructured shouldBe true
    }

    
    
    
    

    
    test("RSS: BM25 search finds feed articles") {
        requireSourceSearchReady(runner, searchReadinessCache, "rss", "RSS search corpus")
        val query = sampleRssQuery()
        waitForFullTextIndexReady("rss_feeds", context = "PostgreSQL BM25 correctness prerequisites")
        waitForBm25SearchReady("rss_feeds", queryProvider = { query })
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("mode", "bm25")
                putJsonArray("collections") { add("rss_feeds") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No RSS articles found for BM25 search" }
        val rssResult = results.first().jsonObject
        val source = rssResult["source"]?.jsonPrimitive?.content
        source shouldBe "rss_feeds"
        println("      ✓ BM25 found ${results.size} RSS articles")
    }

    test("RSS: Semantic search finds relevant articles") {
        requireSourceSearchReady(runner, searchReadinessCache, "rss", "RSS search corpus")
        val query = sampleRssQuery()
        val body = searchRequest(
            label = "RSS semantic search",
            query = query,
            mode = "vector",
            collections = listOf("rss_feeds"),
            limit = 10
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No RSS vectors found for semantic search" }
        println("      ✓ Semantic search found ${results.size} RSS articles")
        val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
        require(firstScore != null && firstScore > 0) { "Invalid vector similarity score" }
    }

    test("RSS: Hybrid search combines BM25 and semantic") {
        requireSourceSearchReady(runner, searchReadinessCache, "rss", "RSS search corpus")
        waitForFullTextIndexReady("rss_feeds", context = "PostgreSQL hybrid-search correctness prerequisites")
        val query = sampleRssQuery()
        val body = searchRequest(
            label = "RSS hybrid search",
            query = query,
            mode = "hybrid",
            collections = listOf("rss_feeds"),
            limit = 10
        )
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"

        val results = body.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No RSS results found for hybrid search" }
        println("      ✓ Hybrid search found ${results.size} RSS results")
    }

    
    test("CVE: BM25 fulltext index becomes ready within budget") {
        val budgetWaitMs = fullTextIndexWaitMs("TEST_FULLTEXT_INDEX_MAX_WAIT_SECONDS", 180L)
        waitForBm25SearchReady(
            collection = "cve",
            queryProvider = ::sampleCveQuery,
            maxWaitMs = budgetWaitMs
        )
        println("      ✓ CVE BM25 search became ready within ${budgetWaitMs / 1000}s")
    }

    test("CVE: BM25 search finds vulnerabilities by keyword") {
        requireSourceSearchReady(runner, searchReadinessCache, "cve", "CVE search corpus")
        val query = sampleCveQuery()
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("mode", "bm25")
                putJsonArray("collections") { add("cve") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No CVE data found for BM25 search" }
        val cveResult = results.first().jsonObject
        val source = cveResult["source"]?.jsonPrimitive?.content
        source shouldBe "cve"
        println("      ✓ BM25 found ${results.size} CVE entries")
    }

    test("CVE: Semantic search finds similar vulnerabilities") {
        requireSourceSearchReady(runner, searchReadinessCache, "cve", "CVE search corpus")
        val query = sampleCveQuery()
        val body = searchRequest(
            label = "CVE semantic search",
            query = query,
            mode = "vector",
            collections = listOf("cve"),
            limit = 10
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No CVE vectors found for semantic search" }
        println("      ✓ Semantic search found ${results.size} related CVEs")
        val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
        require(firstScore != null && firstScore > 0) { "Invalid CVE vector score" }
    }

    test("CVE: Hybrid search finds CVEs effectively") {
        requireSourceSearchReady(runner, searchReadinessCache, "cve", "CVE search corpus")
        val query = sampleCveQuery()
        val body = searchRequest(
            label = "CVE hybrid search",
            query = query,
            mode = "hybrid",
            collections = listOf("cve"),
            limit = 10
        )
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
        val results = body.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No CVE results found for hybrid search" }
        val firstResult = results.first().jsonObject
        firstResult["source"]?.jsonPrimitive?.content shouldBe "cve"
    }

    
    test("Torrents: BM25 search finds torrents by name") {
        requireSourceSearchReady(runner, searchReadinessCache, "torrents", "Torrent search corpus")
        waitForFullTextIndexReady("torrents", context = "PostgreSQL BM25 correctness prerequisites")
        val query = sampleTorrentQuery()
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("mode", "bm25")
                putJsonArray("collections") { add("torrents") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No torrent data found for BM25 search" }
        val torrentResult = results.first().jsonObject
        val source = torrentResult["source"]?.jsonPrimitive?.content
        source shouldBe "torrents"
        println("      ✓ BM25 found ${results.size} torrents")
    }

    test("Torrents: Semantic search finds similar content") {
        requireSourceSearchReady(runner, searchReadinessCache, "torrents", "Torrent search corpus")
        val query = sampleTorrentQuery()
        val body = searchRequest(
            label = "Torrent semantic search",
            query = query,
            mode = "vector",
            collections = listOf("torrents"),
            limit = 10
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No torrent vectors found for semantic search" }
        println("      ✓ Semantic search found ${results.size} similar torrents")
        val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
        require(firstScore != null && firstScore > 0) { "Invalid torrent vector score" }
    }

    test("Torrents: Hybrid search combines keyword and semantic") {
        requireSourceSearchReady(runner, searchReadinessCache, "torrents", "Torrent search corpus")
        waitForFullTextIndexReady("torrents", context = "PostgreSQL hybrid-search correctness prerequisites")
        val query = sampleTorrentQuery()
        val body = searchRequest(
            label = "Torrent hybrid search",
            query = query,
            mode = "hybrid",
            collections = listOf("torrents"),
            limit = 10
        )
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
        val results = body.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No torrent results found for hybrid search" }
        val firstResult = results.first().jsonObject
        firstResult["source"]?.jsonPrimitive?.content shouldBe "torrents"
    }

    
    test("Wikipedia: BM25 search finds articles by title/content") {
        requireSourceSearchReady(runner, searchReadinessCache, "wikipedia", "Wikipedia search corpus")
        waitForFullTextIndexReady("wikipedia", context = "PostgreSQL BM25 correctness prerequisites")
        val query = sampleWikipediaQuery()
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("mode", "bm25")
                putJsonArray("collections") { add("wikipedia") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No Wikipedia data found for BM25 search" }
        val wikiResult = results.first().jsonObject
        val source = wikiResult["source"]?.jsonPrimitive?.content
        source shouldBe "wikipedia"
        println("      ✓ BM25 found ${results.size} Wikipedia articles")
    }

    test("Wikipedia: Semantic search finds conceptually related articles") {
        requireSourceSearchReady(runner, searchReadinessCache, "wikipedia", "Wikipedia search corpus")
        val body = searchRequest(
            label = "Wikipedia semantic search",
            query = semanticWikipediaQuery,
            mode = "vector",
            collections = listOf("wikipedia"),
            limit = 10
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No Wikipedia vectors found for semantic search" }
        println("      ✓ Semantic search found ${results.size} related Wikipedia articles")
        val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
        require(firstScore != null && firstScore > 0) { "Invalid Wikipedia vector score" }
    }

    test("Wikipedia: Hybrid search leverages both methods") {
        requireSourceSearchReady(runner, searchReadinessCache, "wikipedia", "Wikipedia search corpus")
        waitForFullTextIndexReady("wikipedia", context = "PostgreSQL hybrid-search correctness prerequisites")
        val body = searchRequest(
            label = "Wikipedia hybrid search",
            query = semanticWikipediaQuery,
            mode = "hybrid",
            collections = listOf("wikipedia"),
            limit = 10,
            timeoutMs = 60_000L
        )
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
        val results = body.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No Wikipedia results found for hybrid search" }
        val firstResult = results.first().jsonObject
        firstResult["source"]?.jsonPrimitive?.content shouldBe "wikipedia"
    }

    
    test("Australian Laws: BM25 search finds legislation") {
        requireSourceSearchReady(runner, searchReadinessCache, "australian_laws", "Australian laws search corpus")
        waitForFullTextIndexReady("australian_laws", context = "PostgreSQL BM25 correctness prerequisites")
        val query = sampleAustralianLawQuery()
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("mode", "bm25")
                putJsonArray("collections") { add("australian_laws") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No Australian laws data found for BM25 search" }
        val lawResult = results.first().jsonObject
        val source = lawResult["source"]?.jsonPrimitive?.content
        source shouldBe "australian_laws"
        println("      ✓ BM25 found ${results.size} Australian laws")
    }

    test("Australian Laws: Semantic search finds related legislation") {
        requireSourceSearchReady(runner, searchReadinessCache, "australian_laws", "Australian laws search corpus")
        val body = searchRequest(
            label = "Australian laws semantic search",
            query = semanticAustralianLawQuery,
            mode = "vector",
            collections = listOf("australian_laws"),
            limit = 10
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No Australian laws vectors found for semantic search" }
        println("      ✓ Semantic search found ${results.size} related laws")
        val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
        require(firstScore != null && firstScore > 0) { "Invalid law vector score" }
    }

    test("Australian Laws: Hybrid search combines approaches") {
        requireSourceSearchReady(runner, searchReadinessCache, "australian_laws", "Australian laws search corpus")
        waitForFullTextIndexReady("australian_laws", context = "PostgreSQL hybrid-search correctness prerequisites")
        val body = searchRequest(
            label = "Australian laws hybrid search",
            query = semanticAustralianLawQuery,
            mode = "hybrid",
            collections = listOf("australian_laws"),
            limit = 10
        )
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
        val results = body.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No Australian laws results found for hybrid search" }
        val firstResult = results.first().jsonObject
        firstResult["source"]?.jsonPrimitive?.content shouldBe "australian_laws"
    }

    
    test("Linux Docs: BM25 search finds man pages by command") {
        requireSourceSearchReady(runner, searchReadinessCache, "linux_docs", "Linux docs search corpus")
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "tar")
                put("mode", "bm25")
                putJsonArray("collections") { add("linux_docs") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No Linux docs found for BM25 search" }
        val docResult = results.first().jsonObject
        val source = docResult["source"]?.jsonPrimitive?.content
        source shouldBe "linux_docs"
        println("      ✓ BM25 found ${results.size} Linux docs")
    }

    test("Linux Docs: Semantic search finds related documentation") {
        requireSourceSearchReady(runner, searchReadinessCache, "linux_docs", "Linux docs search corpus")
        val body = searchRequest(
            label = "Linux docs semantic search",
            query = "file system directory permissions",
            mode = "vector",
            collections = listOf("linux_docs"),
            limit = 10
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No Linux doc vectors found for semantic search" }
        println("      ✓ Semantic search found ${results.size} related docs")
        val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
        require(firstScore != null && firstScore > 0) { "Invalid doc vector score" }
    }

    test("Linux Docs: Hybrid search leverages both methods") {
        requireSourceSearchReady(runner, searchReadinessCache, "linux_docs", "Linux docs search corpus")
        val body = searchRequest(
            label = "Linux docs hybrid search",
            query = "network configuration interface",
            mode = "hybrid",
            collections = listOf("linux_docs"),
            limit = 10
        )
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
        val results = body.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No Linux docs results found for hybrid search" }
        val firstResult = results.first().jsonObject
        firstResult["source"]?.jsonPrimitive?.content shouldBe "linux_docs"
    }

    test("Stack Knowledge: Agent-facing search service docs are searchable") {
        requireSourceSearchReady(runner, searchReadinessCache, "stack_knowledge", "Stack knowledge corpus")
        waitForFullTextIndexReady("stack_knowledge", context = "Stack knowledge BM25 correctness prerequisites")
        val body = searchRequest(
            label = "Stack knowledge agent search",
            query = "semantic_search get_document collections",
            mode = "hybrid",
            collections = listOf("stack_knowledge"),
            limit = 5,
            audience = "agent"
        )
        val results = body.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No stack knowledge results found for agent-facing search API query" }
        val firstResult = results.first().jsonObject
        firstResult["source"]?.jsonPrimitive?.content shouldBe "stack_knowledge"
        val combined = listOf(
            firstResult["title"]?.jsonPrimitive?.content.orEmpty(),
            firstResult["snippet"]?.jsonPrimitive?.content.orEmpty()
        ).joinToString(" ")
        require(
            combined.contains("search", ignoreCase = true) ||
                combined.contains("document", ignoreCase = true) ||
                combined.contains("collection", ignoreCase = true)
        ) {
            "Stack knowledge top result was not useful for search API query: $combined"
        }
    }

    
    
    

    test("All sources: Cross-collection search works") {
        val body = searchRequest(
            label = "Cross-collection search",
            query = "technology innovation market vulnerability",
            mode = "vector",
            collections = listOf("test-docs", "test-market", "test-bookstack", "test-cve"),
            limit = 20
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No cross-collection results returned" }
        val sources = results.mapNotNull {
            it.jsonObject["source"]?.jsonPrimitive?.content
                ?: it.jsonObject["metadata"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull
        }.toSet()
        println("      ✓ Cross-collection search found results from: ${sources.joinToString()}")
        println("      ✓ Total results across all sources: ${results.size}")
    }

    test("All sources: Vectorization quality check") {
        val body = searchRequest(
            label = "Vectorization quality check",
            query = "technology innovation",
            mode = "vector",
            collections = listOf("test-docs", "test-market", "test-bookstack", "test-cve"),
            limit = 50
        )
        val results = body.jsonObject["results"]?.jsonArray

        require(!results.isNullOrEmpty()) { "No vectors returned for quality validation" }
        val scores = results.mapNotNull {
            it.jsonObject["score"]?.jsonPrimitive?.double
        }

        val avgScore = scores.average()
        val minScore = scores.minOrNull() ?: 0.0
        val maxScore = scores.maxOrNull() ?: 0.0

        println("      ✓ Vector scores - Min: ${"%.4f".format(minScore)}, " +
               "Max: ${"%.4f".format(maxScore)}, Avg: ${"%.4f".format(avgScore)}")

        require(minScore >= 0.0 && maxScore <= 1.0) { "Invalid vector scores detected" }
        require(avgScore > 0.0) { "Average score should be > 0" }
    }
}
