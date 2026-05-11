package org.webservices.searchservice

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.LoadBalancerRegistry
import io.grpc.ManagedChannelBuilder
import io.grpc.NameResolverRegistry
import io.grpc.internal.DnsNameResolverProvider
import io.grpc.internal.PickFirstLoadBalancerProvider
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Points.*
import io.qdrant.client.grpc.Collections.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webservices.pipeline.core.PresentationMetadataKeys
import org.webservices.pipeline.core.PresentationTargets
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

private val logger = KotlinLogging.logger {}

private class EmbeddingServiceException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Core hybrid search gateway that combines vector and full-text search for RAG capabilities.
 *
 * This class implements a sophisticated hybrid search strategy that provides the best of both worlds:
 *
 * ## Search Modes
 * 1. **Vector Search** (semantic understanding)
 *    - Uses Qdrant vector database with cosine similarity
 *    - Queries are vectorized via BGE-M3 embedding model (1024 dimensions)
 *    - Excellent for conceptual similarity and semantic understanding
 *    - Example: "kubernetes scaling" matches "horizontal pod autoscaling" even without keyword overlap
 *
 * 2. **Full-Text Search** (keyword precision)
 *    - Uses PostgreSQL ts_rank with tsvector/tsquery
 *    - Searches the document_staging table with embedding_status=COMPLETED filter
 *    - Excellent for exact keyword matches and domain-specific terminology
 *    - Example: "CVE-2024-1234" requires exact match, not semantic similarity
 *
 * 3. **Hybrid Search** (Reciprocal Rank Fusion)
 *    - Runs both searches in parallel for performance
 *    - Merges results using RRF algorithm (see [rerank] method for details)
 *    - Provides superior results by combining semantic understanding with keyword precision
 *
 * ## Integration with webservices Stack
 * - **Data Source**: Pipeline module writes to document_staging table and Qdrant collections
 * - **Query Vectorization**: Embedding Service (BGE-M3 model on HTTP:8000)
 * - **Vector Search**: Qdrant (gRPC:6334) with collections: rss_feeds, cve, wikipedia, etc.
 * - **Full-Text Search**: PostgreSQL (JDBC:5432) using ts_rank on tsvector
 * - **RAG Consumer**: Model-context-server's semantic_search tool uses this for LLM context retrieval
 *
 * ## Why Hybrid Search?
 * - Vector search alone misses exact technical terms (e.g., error codes, version numbers)
 * - Full-text search alone misses semantic variations (e.g., "container orchestration" vs "k8s")
 * - Hybrid search combines strengths of both approaches for maximum relevance
 *
 * @property qdrantUrl Qdrant gRPC endpoint (default: qdrant:6334)
 * @property postgresJdbcUrl PostgreSQL JDBC URL (default: jdbc:postgresql://postgres-ssd:5432/webservices)
 * @property embeddingServiceUrl Embedding Service HTTP endpoint (default: http://embedding-service:8000)
 */
class SearchGateway(
    private val qdrantUrl: String,
    private val qdrantApiKey: String = "",
    private val postgresJdbcUrl: String,
    private val embeddingServiceUrl: String
) {
    companion object {
        private const val BOOKSTACK_SKIPPED_PREFIX = "skipped://source-filter/"
        private val CVE_ID_REGEX: Pattern = Pattern.compile("""\b(CVE-\d{4}-\d{4,})\b""")
        private val QDRANT_RESULT_PAYLOAD_FIELDS = listOf(
            "title",
            "name",
            "content",
            "text",
            "url",
            "link",
            "presentation_url",
            "bookstack_url",
            "grafana_url",
            "presentation_target",
            "grafana_dashboard_uid",
            "grafana_dashboard_slug",
            "grafana_org_id",
            "grafana_from",
            "grafana_to",
            "grafana_panel_id",
            "grafana_path",
            "timeseries",
            "interactive",
            "source",
            "type",
            "document_id",
            "infohash",
            "cveId",
            "cve_id",
            "severity",
            "cvss_severity",
            "jurisdiction",
            "year",
            "section",
            "filepath",
            "path",
            "wikiType",
            "categories",
            "chunkIndex",
            "chunk_index",
            "total_chunks",
            "is_chunked",
            "wikipedia_id"
        )

        init {
            NameResolverRegistry.getDefaultRegistry().register(DnsNameResolverProvider())
            LoadBalancerRegistry.getDefaultRegistry().register(PickFirstLoadBalancerProvider())
        }

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
            "torrents" -> """
                setweight(
                    to_tsvector(
                        'english',
                        regexp_replace(
                            COALESCE(metadata::json->>'name', '') || ' ' ||
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

    private val postgresUser = System.getenv("POSTGRES_USER") ?: "search_service_user"
    private val postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val embeddingRequestMaxAttempts =
        (System.getenv("SEARCH_EMBEDDING_MAX_ATTEMPTS")?.toIntOrNull() ?: 3).coerceIn(1, 6)
    private val embeddingRetryBaseDelayMs =
        (System.getenv("SEARCH_EMBEDDING_RETRY_BASE_DELAY_MS")?.toLongOrNull() ?: 250L).coerceIn(50L, 5_000L)
    private val embeddingRetryMaxDelayMs =
        (System.getenv("SEARCH_EMBEDDING_RETRY_MAX_DELAY_MS")?.toLongOrNull() ?: 2_000L).coerceIn(100L, 10_000L)
    private val postgresStatementTimeoutSeconds =
        (System.getenv("SEARCH_POSTGRES_STATEMENT_TIMEOUT_SECONDS")?.toIntOrNull() ?: 20).coerceIn(1, 120)
    private val directPresentationCollections = (System.getenv("SEARCH_PRESENTATION_METADATA_COLLECTIONS")
        ?: "market_data,test-market,torrents")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    // Qdrant gRPC client for vector similarity search
    // Uses cosine distance to find semantically similar documents
    // Parse the Qdrant URL - supports formats like "qdrant:6334", "grpc://qdrant:6334", or just "qdrant"
    private val qdrantCleanUrl = qdrantUrl
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("grpc://")
        .trimStart('/') // Remove leading slashes that might cause Unix socket interpretation
    private val qdrantHost = qdrantCleanUrl.split(":")[0]
    private val qdrantPort = qdrantCleanUrl.split(":").getOrNull(1)?.toIntOrNull() ?: 6334
    private val qdrantHttpUrl = (System.getenv("QDRANT_HTTP_URL")
        ?: "http://$qdrantHost:${if (qdrantPort == 6334) 6333 else qdrantPort}")
        .trimEnd('/')
    private val grafanaPublicUrl = (System.getenv("GRAFANA_PUBLIC_URL") ?: "https://grafana.webservices.net").trimEnd('/')
    private val qdrant = QdrantClient(
        QdrantGrpcClient.newBuilder(
            ManagedChannelBuilder.forAddress(qdrantHost, qdrantPort)
                .usePlaintext()
                .build()
        )
            .apply {
                if (qdrantApiKey.isNotBlank()) {
                    withApiKey(qdrantApiKey)
                }
            }
            .build()
    )

    // PostgreSQL connection pool for full-text search
    // Read-only pool to prevent accidental data modification
    // Searches document_staging table with ts_rank for keyword relevance
    private val postgresPool: HikariDataSource by lazy {
        HikariConfig().apply {
            jdbcUrl = postgresJdbcUrl
            username = postgresUser
            password = postgresPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 20
            minimumIdle = 2
            connectionTimeout = 10000
            idleTimeout = 300000
            maxLifetime = 600000
            poolName = "search-postgres-pool"
            isReadOnly = true
        }.let { HikariDataSource(it) }
    }

    private fun isHttpUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.startsWith("http://") || value.startsWith("https://")
    }

    private fun isSkippedBookStackUrl(value: String?): Boolean {
        return value?.startsWith(BOOKSTACK_SKIPPED_PREFIX) == true
    }

    private fun isBookStackUrl(value: String?): Boolean {
        if (!isHttpUrl(value)) return false
        return value!!.contains("bookstack.", ignoreCase = true) || value.contains("/books/")
    }

    private fun isGrafanaUrl(value: String?): Boolean {
        if (!isHttpUrl(value)) return false
        return value!!.contains("grafana.", ignoreCase = true) || value.contains("/d/") || value.contains("/explore")
    }

    private fun deriveGrafanaUrl(metadata: Map<String, String>): String? {
        val explicitGrafanaUrl = metadata[PresentationMetadataKeys.GRAFANA_URL]
        if (isHttpUrl(explicitGrafanaUrl)) {
            return explicitGrafanaUrl
        }

        val explicitPresentationUrl = metadata[PresentationMetadataKeys.URL]
        if (isGrafanaUrl(explicitPresentationUrl)) {
            return explicitPresentationUrl
        }

        val grafanaPath = metadata[PresentationMetadataKeys.GRAFANA_PATH]?.trim()
        if (!grafanaPath.isNullOrBlank()) {
            return if (grafanaPath.startsWith("http://") || grafanaPath.startsWith("https://")) {
                grafanaPath
            } else {
                "$grafanaPublicUrl/${grafanaPath.trimStart('/')}"
            }
        }

        val dashboardUid = metadata[PresentationMetadataKeys.GRAFANA_DASHBOARD_UID]?.trim()
        if (!dashboardUid.isNullOrBlank()) {
            val slug = metadata[PresentationMetadataKeys.GRAFANA_DASHBOARD_SLUG]?.trim()?.takeIf { it.isNotBlank() } ?: dashboardUid
            val queryParams = linkedMapOf<String, String>()
            metadata[PresentationMetadataKeys.GRAFANA_ORG_ID]?.takeIf { it.isNotBlank() }?.let { queryParams["orgId"] = it }
            metadata[PresentationMetadataKeys.GRAFANA_FROM]?.takeIf { it.isNotBlank() }?.let { queryParams["from"] = it }
            metadata[PresentationMetadataKeys.GRAFANA_TO]?.takeIf { it.isNotBlank() }?.let { queryParams["to"] = it }
            metadata[PresentationMetadataKeys.GRAFANA_PANEL_ID]?.takeIf { it.isNotBlank() }?.let { queryParams["viewPanel"] = it }
            val suffix = if (queryParams.isEmpty()) {
                ""
            } else {
                queryParams.entries.joinToString(prefix = "?", separator = "&") { (key, value) ->
                    "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
                }
            }
            return "$grafanaPublicUrl/d/$dashboardUid/$slug$suffix"
        }

        return null
    }

    private fun preferredPresentationTarget(collection: String, metadata: Map<String, String>, url: String): String? {
        val explicitTarget = metadata[PresentationMetadataKeys.TARGET]?.lowercase()
        if (!explicitTarget.isNullOrBlank()) {
            return explicitTarget
        }

        return when {
            metadata["bookstack_url"]?.startsWith("http") == true || isBookStackUrl(url) -> PresentationTargets.BOOKSTACK
            metadata.containsKey(PresentationMetadataKeys.GRAFANA_URL) ||
                metadata.containsKey(PresentationMetadataKeys.GRAFANA_PATH) ||
                metadata.containsKey(PresentationMetadataKeys.GRAFANA_DASHBOARD_UID) ||
                collection.contains("market") ||
                metadata["timeseries"]?.equals("true", ignoreCase = true) == true -> PresentationTargets.GRAFANA
            else -> null
        }
    }

    private fun preferredResultUrl(metadata: Map<String, String>): String {
        val presentationUrl = metadata[PresentationMetadataKeys.URL]
        if (isHttpUrl(presentationUrl) && !isSkippedBookStackUrl(presentationUrl)) {
            return presentationUrl!!
        }

        val bookstackUrl = metadata["bookstack_url"]
        if (isHttpUrl(bookstackUrl) && !isSkippedBookStackUrl(bookstackUrl)) {
            return bookstackUrl!!
        }

        val grafanaUrl = deriveGrafanaUrl(metadata)
        if (isHttpUrl(grafanaUrl)) {
            return grafanaUrl!!
        }

        val canonicalUrl = metadata["url"]
        if (isHttpUrl(canonicalUrl)) {
            return canonicalUrl!!
        }

        val linkUrl = metadata["link"]
        if (isHttpUrl(linkUrl)) {
            return linkUrl!!
        }

        val infohash = metadata["infohash"]?.takeIf { it.isNotBlank() }
        if (infohash != null) {
            return "magnet:?xt=urn:btih:$infohash"
        }

        val cveId = metadata["cveId"]
            ?.takeIf { it.isNotBlank() }
            ?: metadata["cve_id"]?.takeIf { it.isNotBlank() }
            ?: metadata["title"]?.takeIf { it.startsWith("CVE-", ignoreCase = true) }
        if (cveId != null) {
            return "https://nvd.nist.gov/vuln/detail/$cveId"
        }

        return bookstackUrl ?: canonicalUrl ?: linkUrl ?: ""
    }

    private fun extractCveId(vararg candidates: String?): String? {
        candidates.forEach { candidate ->
            if (candidate.isNullOrBlank()) return@forEach
            val matcher = CVE_ID_REGEX.matcher(candidate)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun enrichMetadata(
        collection: String,
        metadata: Map<String, String>,
        text: String = "",
        fallbackTitle: String = ""
    ): Map<String, String> {
        if (collection.contains("wiki", ignoreCase = true)) {
            val title = metadata["title"]
                ?.takeIf { it.isNotBlank() }
                ?: fallbackTitle.takeIf { it.isNotBlank() }
                ?: text.lineSequence()
                    .firstOrNull { it.startsWith("# ") }
                    ?.removePrefix("# ")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

            val derivedUrl = metadata[PresentationMetadataKeys.URL]
                ?.takeIf { it.isNotBlank() }
                ?: metadata["url"]?.takeIf { it.isNotBlank() }
                ?: title
                    ?.takeIf { collection.equals("wikipedia", ignoreCase = true) }
                    ?.let { wikipediaArticleUrl(it) }

            return metadata + buildMap {
                if (!title.isNullOrBlank()) {
                    put("title", title)
                }
                if (!derivedUrl.isNullOrBlank()) {
                    put("url", derivedUrl)
                    put(PresentationMetadataKeys.URL, derivedUrl)
                }
            }
        }

        if (!collection.contains("cve", ignoreCase = true)) {
            return metadata
        }

        val cveId = extractCveId(
            metadata["cveId"],
            metadata["cve_id"],
            metadata["title"],
            fallbackTitle,
            text
        ) ?: return metadata

        val title = metadata["title"]?.takeIf { it.isNotBlank() } ?: cveId
        val url = metadata[PresentationMetadataKeys.URL]
            ?.takeIf { it.isNotBlank() }
            ?: metadata["url"]?.takeIf { it.isNotBlank() }
            ?: "https://nvd.nist.gov/vuln/detail/$cveId"

        return metadata + buildMap {
            put("cveId", cveId)
            put("cve_id", cveId)
            put("title", title)
            put("url", url)
            put(PresentationMetadataKeys.URL, url)
        }
    }

    private fun wikipediaArticleUrl(title: String): String {
        val slug = title.trim()
            .replace(' ', '_')
            .let { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        return "https://en.wikipedia.org/wiki/$slug"
    }

    private fun collectionRequiresPresentation(collection: String): Boolean {
        val normalized = collection.trim().lowercase()
        return when {
            normalized == "cve" -> false
            normalized.contains("wiki") -> false
            normalized.contains("law") -> false
            normalized == "linux_docs" -> false
            normalized == "stack_knowledge" -> false
            else -> true
        }
    }

    private fun identityKey(
        source: String,
        title: String,
        url: String,
        metadata: Map<String, String>
    ): String {
        return metadata["document_id"]?.takeIf { it.isNotBlank() }
            ?: metadata["url"]?.takeIf { it.isNotBlank() }
            ?: metadata["link"]?.takeIf { it.isNotBlank() }
            ?: metadata["infohash"]?.takeIf { it.isNotBlank() }
            ?: url.takeIf { it.isNotBlank() && !isBookStackUrl(it) }
            ?: "$source::$title"
    }

    private fun mergeResults(existing: SearchResult, candidate: SearchResult): SearchResult {
        val mergedMetadata = existing.metadata + candidate.metadata
        val mergedId = existing.id.ifBlank {
            candidate.id.ifBlank {
                mergedMetadata["document_id"].orEmpty()
            }
        }
        val mergedUrl = preferredResultUrl(mergedMetadata).ifBlank {
            when {
                isBookStackUrl(candidate.url) -> candidate.url
                isBookStackUrl(existing.url) -> existing.url
                candidate.url.isNotBlank() -> candidate.url
                else -> existing.url
            }
        }
        val mergedSnippet = listOf(candidate.snippet, existing.snippet).maxByOrNull { it.length }.orEmpty()
        val mergedTitle = if (candidate.title.length > existing.title.length) candidate.title else existing.title

        return existing.copy(
            id = mergedId,
            url = mergedUrl,
            title = mergedTitle,
            snippet = mergedSnippet,
            metadata = mergedMetadata,
            contentType = SearchResult.inferContentType(existing.source, mergedUrl, mergedMetadata),
            capabilities = SearchResult.inferCapabilities(existing.source, mergedUrl, mergedMetadata)
        )
    }

    private fun normalizeSearchText(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun queryTerms(value: String): List<String> {
        return normalizeSearchText(value)
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .distinct()
    }

    private fun snippetForText(text: String, query: String, maxLength: Int = 360): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= maxLength) {
            return compact
        }

        val terms = queryTerms(query)
        val normalized = compact.lowercase()
        val firstHit = terms.asSequence()
            .mapNotNull { term -> normalized.indexOf(term).takeIf { it >= 0 } }
            .minOrNull()
            ?: 0
        val start = (firstHit - maxLength / 3).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(compact.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < compact.length) "..." else ""
        return prefix + compact.substring(start, end).trim() + suffix
    }

    private fun qualityBoost(query: String, result: SearchResult): Double {
        val normalizedQuery = normalizeSearchText(query)
        if (normalizedQuery.isBlank()) return 0.0

        val terms = queryTerms(query)
        val title = normalizeSearchText(result.title)
        val snippet = normalizeSearchText(result.snippet)
        val id = normalizeSearchText(result.id)
        val metadataText = normalizeSearchText(result.metadata.values.joinToString(" "))
        var boost = 0.0

        if (title == normalizedQuery || id == normalizedQuery) boost += 0.08
        if (title.contains(normalizedQuery) || id.contains(normalizedQuery)) boost += 0.04
        if (terms.isNotEmpty()) {
            val titleCoverage = terms.count { title.contains(it) }.toDouble() / terms.size
            val snippetCoverage = terms.count { snippet.contains(it) }.toDouble() / terms.size
            boost += titleCoverage * 0.04
            boost += snippetCoverage * 0.02
        }

        val requestedCve = extractCveId(query)
        if (requestedCve != null) {
            val actualCve = extractCveId(result.id, result.title, result.metadata["cveId"], result.metadata["cve_id"], result.snippet)
            boost += if (actualCve.equals(requestedCve, ignoreCase = true)) 0.20 else -0.04
        }

        val requestedYear = Regex("""\b(19|20)\d{2}\b""").find(query)?.value
        if (requestedYear != null && result.source.contains("cve", ignoreCase = true)) {
            boost += if (metadataText.contains(requestedYear) || id.contains(requestedYear) || title.contains(requestedYear)) 0.04 else -0.02
        }

        if (result.source == "linux_docs") {
            val path = normalizeSearchText(result.metadata["path"].orEmpty())
            val looksLikeReferenceQuery = terms.any { it in setOf("configure", "configuration", "restart", "service", "systemd", "network", "permission", "permissions") }
            if (looksLikeReferenceQuery && (path.contains("changelog") || path.contains("copyright"))) {
                boost -= 0.08
            }
        }

        if (result.source == "stack_knowledge") {
            boost += 0.03
        }

        return boost
    }

    private fun collectionSupportsDirectPresentationMetadata(collection: String): Boolean {
        val normalized = collection.trim().lowercase()
        return normalized in directPresentationCollections ||
            normalized.contains("market") ||
            normalized.contains("grafana")
    }

    /**
     * Main search entry point - supports vector, full-text, and hybrid search modes.
     *
     * This method orchestrates the entire search process:
     * 1. Resolves wildcard collections (["*"]) to all available Qdrant collections
     * 2. Delegates to appropriate search strategy based on mode
     * 3. Returns ranked results ready for audience filtering
     *
     * ## Search Mode Details
     * - **vector**: Semantic search only via Qdrant (best for conceptual queries)
     * - **bm25/fulltext**: Keyword search only via PostgreSQL (best for exact terms)
     * - **hybrid**: Both in parallel, merged with RRF (best overall results)
     *
     * In hybrid mode, each backend receives the requested limit before RRF fusion
     * produces the final ranked list.
     *
     * @param query Natural language query to search for
     * @param collections List of collection names to search (use ["*"] for all)
     * @param mode Search strategy: "vector", "bm25", or "hybrid"
     * @param limit Maximum number of results to return
     * @return Ranked list of SearchResult objects with relevance scores
     */
    suspend fun search(
        query: String,
        collections: List<String>,
        mode: String = "hybrid",
        limit: Int = 20
    ): List<SearchResult> = coroutineScope {
        // Resolve wildcard collections to actual Qdrant collection names
        val targetCollections = if (collections.contains("*")) {
            listCollections()
        } else {
            collections
        }

        logger.info { "Searching in ${targetCollections.size} collections: mode=$mode, query=${redactSearchQueryForLog(query)}" }

        when (mode) {
            "vector" -> searchVector(query, targetCollections, limit)
            "bm25", "fulltext" -> searchFullText(query, targetCollections, limit)
            "hybrid" -> {
                // Execute vector and full-text searches in parallel for performance
                val backendLimit = maxOf(1, limit)
                val vectorDeferred = async {
                    runCatching { searchVector(query, targetCollections, backendLimit) }
                }
                val fulltextDeferred = async {
                    runCatching { searchFullText(query, targetCollections, backendLimit) }
                }

                val vectorAttempt = vectorDeferred.await()
                val fulltextAttempt = fulltextDeferred.await()
                if (vectorAttempt.isFailure && fulltextAttempt.isFailure) {
                    throw vectorAttempt.exceptionOrNull()
                        ?: fulltextAttempt.exceptionOrNull()
                        ?: IOException("Hybrid search failed for all backends")
                }

                val vectorResults = vectorAttempt.getOrElse {
                    logger.warn(it) { "Vector backend failed during hybrid search; returning full-text results only" }
                    emptyList()
                }
                val fulltextResults = fulltextAttempt.getOrElse {
                    logger.warn(it) { "Full-text backend failed during hybrid search; returning vector results only" }
                    emptyList()
                }

                // Merge results with Reciprocal Rank Fusion (RRF) algorithm
                // This is the key innovation that provides superior hybrid search results
                rerank(query, vectorResults, fulltextResults, limit)
            }
            else -> throw IllegalArgumentException("Unknown search mode: $mode (use vector, bm25, or hybrid)")
        }
    }

    suspend fun document(documentId: String, collection: String? = null): SearchDocument? = withContext(Dispatchers.IO) {
        val sql = buildString {
            append(
                """
                SELECT
                    id,
                    source,
                    collection,
                    text,
                    metadata,
                    bookstack_url,
                    created_at,
                    updated_at
                FROM public.document_staging
                WHERE id = ?
                  AND embedding_status = 'COMPLETED'
                """.trimIndent()
            )
            if (!collection.isNullOrBlank()) {
                append("\n  AND collection = ?")
            }
            append("\nLIMIT 1")
        }

        postgresPool.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.queryTimeout = postgresStatementTimeoutSeconds
                stmt.setString(1, documentId)
                if (!collection.isNullOrBlank()) {
                    stmt.setString(2, collection)
                }
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return@withContext null
                    }

                    val source = rs.getString("source") ?: ""
                    val resolvedCollection = rs.getString("collection") ?: ""
                    val text = rs.getString("text") ?: ""
                    val metadataJson = rs.getString("metadata") ?: "{}"
                    val bookstackUrl = rs.getString("bookstack_url")
                    val metadata = try {
                        @Suppress("UNCHECKED_CAST")
                        val raw = gson.fromJson(metadataJson, Map::class.java) as? Map<String, Any?> ?: emptyMap()
                        raw.mapValues { (_, value) -> value?.toString().orEmpty() }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse metadata JSON for document $documentId" }
                        emptyMap()
                    }
                    val enrichedMetadata = enrichMetadata(
                        collection = resolvedCollection,
                        metadata = metadata,
                        text = text,
                        fallbackTitle = documentId
                    ) + buildMap {
                        if (!bookstackUrl.isNullOrBlank()) {
                            put("bookstack_url", bookstackUrl)
                        }
                    }

                    return@withContext SearchDocument(
                        id = documentId,
                        source = source,
                        collection = resolvedCollection,
                        title = titleOrFallback(enrichedMetadata).ifBlank { documentId },
                        text = text,
                        url = preferredResultUrl(enrichedMetadata),
                        metadata = enrichedMetadata,
                        createdAt = rs.getString("created_at"),
                        updatedAt = rs.getString("updated_at")
                    )
                }
            }
        }
    }

    /**
     * Performs semantic vector search across multiple Qdrant collections.
     *
     * This method implements the vector search component of hybrid search:
     * 1. Vectorizes the query text via Embedding Service (BGE-M3 model, 1024 dimensions)
     * 2. Searches each collection in parallel using Qdrant's cosine similarity
     * 3. Aggregates and ranks results by similarity score
     *
     * Vector search excels at semantic understanding - it matches concepts rather than keywords.
     * Example: "kubernetes scaling" will match "horizontal pod autoscaling" even without
     * shared keywords, because their embeddings are similar in vector space.
     *
     * @param query Natural language query to vectorize and search
     * @param collections List of Qdrant collection names to search
     * @param limit Maximum results per collection (de-duplicated and ranked afterward)
     * @return List of SearchResult with cosine similarity scores
     */
    private suspend fun searchVector(query: String, collections: List<String>, limit: Int): List<SearchResult> = coroutineScope {
        if (limit <= 0 || collections.isEmpty()) {
            return@coroutineScope emptyList()
        }

        // Vectorize query via Embedding Service (BGE-M3 model)
        // This converts natural language to a 1024-dimensional vector for semantic comparison
        val embedding = getEmbedding(query)

        // Search all collections in parallel for performance
        // Each collection is independent, so parallel execution reduces latency
        val attempts = collections.map { collection ->
            async {
                try {
                    Result.success(searchQdrant(collection, embedding, limit, query))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to search Qdrant collection: $collection" }
                    Result.failure<List<SearchResult>>(e)
                }
            }
        }.awaitAll()

        if (attempts.all { it.isFailure }) {
            val cause = attempts.firstNotNullOfOrNull { it.exceptionOrNull() }
            throw IOException("Vector search failed for all requested collections", cause)
        }

        val results = attempts.flatMap { it.getOrDefault(emptyList()) }

        results
            .map { it.copy(score = it.score + qualityBoost(query, it)) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Legacy alias for full-text search (BM25-style ranking via PostgreSQL ts_rank).
     */
    private suspend fun searchBM25(query: String, collections: List<String>, limit: Int): List<SearchResult> {
        return searchFullText(query, collections, limit)
    }

    private fun loadDocumentPayload(documentId: String): Pair<String, Map<String, String>>? {
        return try {
            postgresPool.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT text, metadata, bookstack_url
                    FROM public.document_staging
                    WHERE id = ?
                      AND embedding_status = 'COMPLETED'
                    LIMIT 1
                    """.trimIndent()
                ).use { stmt ->
                    stmt.queryTimeout = postgresStatementTimeoutSeconds
                    stmt.setString(1, documentId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            return null
                        }
                        val text = rs.getString("text") ?: ""
                        val metadataJson = rs.getString("metadata") ?: "{}"
                        @Suppress("UNCHECKED_CAST")
                        val metadata = try {
                            val raw = gson.fromJson(metadataJson, Map::class.java) as? Map<String, Any?> ?: emptyMap()
                            raw.mapValues { (_, value) -> value?.toString().orEmpty() }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to parse metadata JSON for vector document $documentId" }
                            emptyMap()
                        } + buildMap {
                            val bookstackUrl = rs.getString("bookstack_url")
                            if (!bookstackUrl.isNullOrBlank()) {
                                put("bookstack_url", bookstackUrl)
                            }
                        }
                        return text to metadata
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load staged document payload for vector result $documentId" }
            null
        }
    }

    /**
     * Performs full-text keyword search across PostgreSQL document_staging table.
     *
     * This method implements the keyword search component of hybrid search:
     * 1. Searches document_staging table using PostgreSQL's full-text search capabilities
     * 2. Uses ts_rank with tsvector/tsquery for BM25-style relevance ranking
     * 3. Filters to only COMPLETED documents (embedding_status=COMPLETED)
     * 4. Aggregates results across collections and ranks by ts_rank score
     *
     * Full-text search excels at exact keyword matches and technical terminology.
     * Example: "CVE-2024-1234" requires exact string matching, not semantic similarity.
     *
     * ## Why Filter by embedding_status=COMPLETED?
     * This ensures only fully indexed documents appear in search results. Pipeline writes
     * documents with PENDING status, which are processed asynchronously. Only COMPLETED
     * documents have been embedded and are ready for search.
     *
     * @param query Keyword query string (converted to PostgreSQL tsquery internally)
     * @param collections List of collection names to filter by in document_staging table
     * @param limit Maximum results per collection (de-duplicated and ranked afterward)
     * @return List of SearchResult with ts_rank scores
     */
    private suspend fun searchFullText(query: String, collections: List<String>, limit: Int): List<SearchResult> = coroutineScope {
        if (limit <= 0 || collections.isEmpty()) {
            return@coroutineScope emptyList()
        }

        val attempts = collections.map { collection ->
            async(Dispatchers.IO) {
                try {
                    Result.success(searchPostgres(collection, query, limit))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to search PostgreSQL for collection: $collection" }
                    Result.failure<List<SearchResult>>(e)
                }
            }
        }.awaitAll()

        if (attempts.all { it.isFailure }) {
            val cause = attempts.firstNotNullOfOrNull { it.exceptionOrNull() }
            throw SQLException("PostgreSQL search failed for all requested collections", cause)
        }

        val results = attempts.flatMap { it.getOrDefault(emptyList()) }

        results
            .map { it.copy(score = it.score + qualityBoost(query, it)) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Executes vector similarity search on a single Qdrant collection.
     *
     * Uses cosine similarity to find documents whose embeddings are closest to the query embedding
     * in vector space. This captures semantic similarity even when keywords don't overlap.
     *
     * @param collection Qdrant collection name to search
     * @param embedding Query vector (1024-dimensional from BGE-M3)
     * @param limit Maximum number of results to return
     * @return List of SearchResult with cosine similarity scores (higher = more similar)
     */
    private suspend fun searchQdrant(collection: String, embedding: List<Float>, limit: Int, query: String): List<SearchResult> {
        if (limit <= 0) {
            return emptyList()
        }

        val requestPayload = mapOf(
            "vector" to embedding,
            "limit" to limit,
            "with_payload" to QDRANT_RESULT_PAYLOAD_FIELDS
        )

        val request = Request.Builder()
            .url("$qdrantHttpUrl/collections/$collection/points/search")
            .post(gson.toJson(requestPayload).toRequestBody(jsonMediaType))
            .apply {
                if (qdrantApiKey.isNotBlank()) {
                    header("api-key", qdrantApiKey)
                }
            }
            .build()

        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(500).orEmpty()
                    throw IllegalStateException(
                        "Qdrant search failed for $collection: HTTP ${response.code} ${response.message} $errorBody"
                    )
                }

                val body = response.body?.string().orEmpty()
                val root = gson.fromJson(body, JsonObject::class.java)
                val points = root?.getAsJsonArray("result") ?: return@withContext emptyList()

                points.mapNotNull { pointElement ->
                    val point = pointElement?.asJsonObject ?: return@mapNotNull null
                    val qdrantPointId = point.get("id")?.let { pointId ->
                        when {
                            pointId.isJsonNull -> ""
                            pointId.isJsonPrimitive -> pointId.asJsonPrimitive.toString().trim('"')
                            else -> pointId.toString()
                        }
                    }.orEmpty()
                    val payload = point.getAsJsonObject("payload")
                    val rawMetadata = payload?.entrySet()?.associate { (key, value) ->
                        key to when {
                            value.isJsonNull -> ""
                            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean.toString()
                            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asNumber.toString()
                            value.isJsonPrimitive -> value.asString
                            else -> value.toString()
                        }
                    } ?: emptyMap()
                    val resultId = rawMetadata["document_id"]?.takeIf { it.isNotBlank() } ?: qdrantPointId
                    val stagedPayload = if (!rawMetadata.containsKey("content") && !rawMetadata.containsKey("text") && resultId.isNotBlank()) {
                        loadDocumentPayload(resultId)
                    } else {
                        null
                    }
                    val content = rawMetadata["content"]
                        ?: rawMetadata["text"]
                        ?: stagedPayload?.first
                        ?: titleOrFallback(rawMetadata)
                    val enrichedMetadata = enrichMetadata(collection, rawMetadata + stagedPayload?.second.orEmpty(), text = content)
                    val metadata = enrichedMetadata + buildMap {
                        preferredPresentationTarget(collection, enrichedMetadata, enrichedMetadata[PresentationMetadataKeys.URL].orEmpty()).let { target ->
                            if (!target.isNullOrBlank()) {
                                put(PresentationMetadataKeys.TARGET, target)
                            }
                        }
                    }
                    val url = preferredResultUrl(metadata)
                    val title = metadata["title"] ?: metadata["name"] ?: ""
                    val snippet = snippetForText(content, query)

                    SearchResult(
                        id = resultId,
                        url = url,
                        title = title,
                        snippet = snippet,
                        score = point.get("score")?.asDouble ?: 0.0,
                        source = collection,
                        metadata = metadata + mapOf("type" to "vector"),
                        contentType = SearchResult.inferContentType(collection, url, metadata),
                        capabilities = SearchResult.inferCapabilities(collection, url, metadata)
                    )
                }
            }
        }
    }

    /**
     * Executes full-text keyword search on PostgreSQL document_staging table.
     *
     * Uses PostgreSQL's full-text search with ts_rank for BM25-style relevance scoring.
     * Only searches documents with embedding_status=COMPLETED to ensure they're fully indexed.
     *
     * ## Why embedding_status=COMPLETED Filter?
     * Pipeline writes documents with PENDING status, which are asynchronously embedded.
     * This filter ensures search results only include fully processed documents that
     * also exist in Qdrant (for hybrid search consistency).
     *
     * @param collection Collection name to filter in document_staging table
     * @param query Keyword query (converted to PostgreSQL tsquery)
     * @param limit Maximum number of results to return
     * @return List of SearchResult with ts_rank scores (higher = better keyword match)
     */
    private suspend fun searchPostgres(collection: String, query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 1000)
        val fullTextVectorSql = fullTextVectorSql(collection)
        val presentationFilter = if (collectionRequiresPresentation(collection)) {
            """
            AND (
                (bookstack_url IS NOT NULL AND bookstack_url NOT LIKE '$BOOKSTACK_SKIPPED_PREFIX%')
                OR (metadata::json->>'presentation_url') IS NOT NULL
                OR (metadata::json->>'url') IS NOT NULL
                OR (metadata::json->>'link') IS NOT NULL
                OR (metadata::json->>'grafana_url') IS NOT NULL
                OR (metadata::json->>'infohash') IS NOT NULL
                OR (
                    metadata::json->>'presentation_target' = '${PresentationTargets.GRAFANA}'
                    AND (
                        (metadata::json->>'grafana_dashboard_uid') IS NOT NULL
                        OR (metadata::json->>'grafana_path') IS NOT NULL
                    )
                )
            )
            """.trimIndent()
        } else {
            ""
        }

        // PostgreSQL full-text search with ts_rank for BM25-style relevance.
        // Accept either BookStack-backed documents or directly presentable documents
        // (for example torrents with magnet URLs or market documents with Grafana links).
        val sql = """
            WITH query AS (
                SELECT websearch_to_tsquery('english', ?) AS value
            ),
            ranked AS (
                SELECT
                    id,
                    text,
                    metadata,
                    bookstack_url,
                    COALESCE(
                        metadata::json->>'presentation_url',
                        metadata::json->>'url',
                        metadata::json->>'link',
                        metadata::json->>'grafana_url'
                    ) AS presentation_url,
                    ts_rank_cd(($fullTextVectorSql), query.value) as rank,
                    query.value AS tsquery
                FROM public.document_staging, query
                WHERE
                    collection = ?
                    AND ($fullTextVectorSql) @@ query.value
                    AND embedding_status = 'COMPLETED'
                    $presentationFilter
                ORDER BY rank DESC
                LIMIT ?
            )
            SELECT
                id,
                text,
                metadata,
                bookstack_url,
                presentation_url,
                ts_headline(
                    'english',
                    text,
                    tsquery,
                    'StartSel=, StopSel=, MaxWords=55, MinWords=18, ShortWord=2, HighlightAll=false'
                ) AS headline,
                rank
            FROM ranked
            ORDER BY rank DESC
        """.trimIndent()

        val results = mutableListOf<SearchResult>()

        postgresPool.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.queryTimeout = postgresStatementTimeoutSeconds
                stmt.setString(1, query)
                stmt.setString(2, collection)
                stmt.setInt(3, safeLimit)

                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val id = rs.getString("id") ?: ""
                    val text = rs.getString("text") ?: ""
                    val metadataJson = rs.getString("metadata") ?: "{}"
                    val bookstackUrl = rs.getString("bookstack_url") ?: ""
                    val presentationUrl = rs.getString("presentation_url") ?: ""
                    val headline = rs.getString("headline") ?: ""
                    val rank = rs.getDouble("rank")

                    // Parse metadata JSON stored in document_staging.metadata column
                    // This contains URL, title, and other document metadata from Pipeline
                    @Suppress("UNCHECKED_CAST")
                    val metadata = try {
                        val raw = gson.fromJson(metadataJson, Map::class.java) as? Map<String, Any?> ?: emptyMap()
                        raw.mapValues { (_, value) -> value?.toString().orEmpty() }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse metadata JSON" }
                        emptyMap()
                    }
                    val enrichedMetadata = enrichMetadata(collection, metadata, text = text, fallbackTitle = id)

                    val effectiveMetadata = enrichedMetadata + mapOf(
                        "document_id" to id,
                        "bookstack_url" to bookstackUrl
                    ) + buildMap {
                        if (presentationUrl.isNotBlank()) {
                            put(PresentationMetadataKeys.URL, presentationUrl)
                        }
                        preferredPresentationTarget(collection, enrichedMetadata, presentationUrl.ifBlank { bookstackUrl }).let { target ->
                            if (!target.isNullOrBlank()) {
                                put(PresentationMetadataKeys.TARGET, target)
                            }
                        }
                    }
                    val url = preferredResultUrl(effectiveMetadata)
                    val title = effectiveMetadata["title"] ?: effectiveMetadata["name"] ?: id
                    val snippet = headline.takeIf { it.isNotBlank() }?.let { snippetForText(it, query) }
                        ?: snippetForText(text, query)

                    results.add(SearchResult(
                        id = id,
                        url = url,
                        title = title,
                        snippet = snippet,
                        score = rank,
                        source = collection,
                        metadata = effectiveMetadata + mapOf("type" to "fulltext"),
                        contentType = SearchResult.inferContentType(collection, url, effectiveMetadata),
                        capabilities = SearchResult.inferCapabilities(collection, url, effectiveMetadata)
                    ))
                }
            }
        }

        results
    }

    private fun titleOrFallback(metadata: Map<String, String>): String {
        return metadata["title"]
            ?: metadata["name"]
            ?: metadata["cveId"]
            ?: metadata["cve_id"]
            ?: ""
    }

    /**
     * Merges vector and full-text search results using Reciprocal Rank Fusion (RRF) algorithm.
     *
     * ## What is Reciprocal Rank Fusion?
     * RRF is a sophisticated rank aggregation method that combines multiple ranked lists without
     * needing to normalize or compare raw scores between different search systems. It's particularly
     * powerful for hybrid search because:
     * 1. Vector search scores (cosine similarity 0-1) and full-text scores (ts_rank) are incompatible
     * 2. RRF uses rank positions instead of raw scores, making it score-agnostic
     * 3. Documents appearing in both result sets get boosted naturally
     *
     * ## The RRF Formula
     * For each document appearing in either result set:
     * ```
     * RRF_score = Σ (1 / (k + rank))
     * ```
     * Where:
     * - `k` is a constant that controls the impact of high-ranked documents (we use k=60)
     * - `rank` is the 0-based position in the result list (0 = top result)
     * - The sum is computed across all result sets containing that document
     *
     * ## Why k=60?
     * The value k=60 is empirically proven optimal for hybrid search (Cormack et al., SIGIR 2009).
     * - Lower k (e.g., 10): Heavily favors top-ranked results, ignoring lower ranks
     * - Higher k (e.g., 100): Treats all ranks more equally, diluting top result importance
     * - k=60: Balances top result importance while still considering lower-ranked matches
     *
     * ## Example Calculation
     * Suppose a document appears at:
     * - Rank 0 in vector results (top result)
     * - Rank 2 in full-text results (3rd result)
     *
     * RRF score = 1/(60+0) + 1/(60+2) = 1/60 + 1/62 = 0.0167 + 0.0161 = 0.0328
     *
     * A document appearing ONLY at rank 0 in one list would score:
     * RRF score = 1/(60+0) = 0.0167
     *
     * The hybrid document scores nearly 2x higher, demonstrating how RRF naturally boosts
     * documents that appear in both result sets (consensus).
     *
     * ## Why RRF vs Simple Score Averaging?
     * - Vector search scores are cosine similarity (0-1 range, higher = better)
     * - Full-text scores are ts_rank (unbounded, highly variable per query)
     * - Normalizing these scores is complex and query-dependent
     * - RRF completely sidesteps score normalization by using ranks instead
     *
     * ## Benefits for RAG (Retrieval-Augmented Generation)
     * - LLMs receive the most relevant documents based on BOTH semantic meaning and keyword precision
     * - Reduces false positives from vector search alone (e.g., "python" matching "snake")
     * - Reduces false negatives from keyword search alone (e.g., missing "k8s" when searching "kubernetes")
     * - Provides more reliable context for LLM knowledge augmentation
     *
     * @param vectorResults Results from Qdrant vector search (cosine similarity ranked)
     * @param fulltextResults Results from PostgreSQL full-text search (ts_rank ranked)
     * @param limit Maximum number of results to return after fusion
     * @return Merged and re-ranked results with RRF scores
     */
    private fun rerank(query: String, vectorResults: List<SearchResult>, fulltextResults: List<SearchResult>, limit: Int): List<SearchResult> {
        val k = 60 // RRF constant - empirically optimal for hybrid search (Cormack et al., SIGIR 2009)
        val scores = mutableMapOf<String, Double>()
        val resultMap = mutableMapOf<String, SearchResult>()
        val normalizedQuery = normalizeSearchText(query)

        // Process vector search results
        // Each document contributes 1/(k + rank) to its RRF score based on its vector rank
        vectorResults.forEachIndexed { index, result ->
            val key = identityKey(result.source, result.title, result.url, result.metadata)
            scores[key] = scores.getOrDefault(key, 0.0) + (1.0 / (k + index + 1))
            resultMap[key] = resultMap[key]?.let { mergeResults(it, result) } ?: result
        }

        // Process full-text search results
        // Documents in both lists accumulate scores from both ranks (natural boosting)
        fulltextResults.forEachIndexed { index, result ->
            val key = identityKey(result.source, result.title, result.url, result.metadata)
            scores[key] = scores.getOrDefault(key, 0.0) + (1.0 / (k + index + 1))
            resultMap[key] = resultMap[key]?.let { mergeResults(it, result) } ?: result
        }

        // Return final ranked list by RRF score (highest first)
        // Documents appearing in both result sets will naturally rank higher due to score accumulation
        return scores.entries
            .sortedByDescending { entry ->
                val result = resultMap[entry.key]
                entry.value + result?.let { qualityBoost(query, it) }.orZero()
            }
            .take(limit)
            .mapNotNull { entry ->
                resultMap[entry.key]?.let { result ->
                    result.copy(score = entry.value + qualityBoost(query, result))
                }
            }
    }

    private fun Double?.orZero(): Double = this ?: 0.0

    
    private suspend fun listCollections(): List<String> {
        return try {
            
            qdrant.listCollectionsAsync().get()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Qdrant collections" }
            emptyList()
        }
    }

    
    suspend fun getCollections(): List<String> = listCollections()

    /**
     * Converts query text to a 1024-dimensional vector via the Embedding Service.
     *
     * This method integrates with the Embedding Service (BGE-M3 model) to vectorize queries
     * for semantic search. The same model is used by the Pipeline to embed documents, ensuring
     * query and document embeddings are in the same vector space.
     *
     * ## BGE-M3 Model Details
     * - Model: BAAI/bge-m3 (multilingual, 1024 dimensions)
     * - Max tokens: 8192
     * - Output: Dense vector representation capturing semantic meaning
     * - Used consistently across Pipeline (document embedding) and Search (query embedding)
     *
     * ## Why Consistent Embeddings Matter
     * Queries and documents MUST use the same embedding model for accurate similarity comparison.
     * Using different models would place them in incompatible vector spaces, breaking semantic search.
     *
     * @param text Query text to vectorize
     * @return 1024-dimensional float vector representing semantic meaning
     * @throws Exception if Embedding Service is unavailable or returns invalid response
     */
    private suspend fun getEmbedding(text: String): List<Float> {
        val requestBody = gson.toJson(mapOf("inputs" to text))
        var lastError: EmbeddingServiceException? = null

        repeat(embeddingRequestMaxAttempts) { attempt ->
            try {
                return withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$embeddingServiceUrl/embed")
                        .post(requestBody.toRequestBody(jsonMediaType))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val responseBody = response.body?.string()
                                ?.replace(Regex("\\s+"), " ")
                                ?.trim()
                                .orEmpty()
                            if (responseBody.isNotEmpty()) {
                                logger.warn {
                                    "Embedding service returned HTTP ${response.code}: ${responseBody.take(200)}"
                                }
                            }
                            throw EmbeddingServiceException(
                                message = "Embedding service error: ${response.code}",
                                retryable = response.code in setOf(429, 502, 503, 504)
                            )
                        }

                        val json = gson.fromJson(response.body?.string(), Array<FloatArray>::class.java)
                        if (json.isEmpty()) {
                            throw EmbeddingServiceException("Empty embedding response")
                        }
                        json[0].toList()
                    }
                }
            } catch (error: EmbeddingServiceException) {
                lastError = error
                if (!error.retryable || attempt == embeddingRequestMaxAttempts - 1) {
                    throw error
                }
            } catch (error: IOException) {
                lastError = EmbeddingServiceException(
                    message = "Embedding service unavailable: ${error.message ?: "I/O error"}",
                    retryable = true,
                    cause = error
                )
                if (attempt == embeddingRequestMaxAttempts - 1) {
                    throw lastError as EmbeddingServiceException
                }
            }

            val delayMs = minOf(
                embeddingRetryBaseDelayMs * (1L shl attempt.coerceAtMost(10)),
                embeddingRetryMaxDelayMs
            )
            logger.warn {
                "Retrying embedding request after attempt ${attempt + 1}/$embeddingRequestMaxAttempts failed: ${lastError?.message}"
            }
            delay(delayMs)
        }

        throw lastError ?: EmbeddingServiceException("Embedding service unavailable")
    }

    /**
     * Gracefully shuts down all external service connections.
     *
     * Closes connections to:
     * - PostgreSQL connection pool (HikariCP)
     * - HTTP client for Embedding Service (OkHttp)
     * - Qdrant gRPC client
     *
     * Called automatically via shutdown hook registered in Main.kt.
     */
    fun close() {
        try {
            // Close PostgreSQL connection pool
            postgresPool.close()
        } catch (e: Exception) {
            logger.debug(e) { "PostgreSQL pool close failed" }
        }

        try {
            // Close HTTP client used for Embedding Service requests
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            logger.debug(e) { "HTTP client close failed" }
        }

        try {
            // Close Qdrant gRPC client
            qdrant.close()
        } catch (e: Exception) {
            logger.debug(e) { "Qdrant client close failed" }
        }

        logger.info { "SearchGateway closed successfully" }
    }
}
/**
 * Search result model with content type inference and capability metadata.
 *
 * Each result represents a document from either Qdrant (vector search) or PostgreSQL (full-text),
 * enriched with inferred capabilities for audience filtering.
 *
 * @property id Stable document identifier for exact retrieval through GET /documents/{id}.
 * @property url Document URL (primary deduplication key in RRF)
 * @property title Document title extracted from metadata
 * @property snippet Content preview (first 200 characters)
 * @property score Relevance score (cosine similarity for vector, ts_rank for full-text, RRF score for hybrid)
 * @property source Collection name (e.g., "rss_feeds", "cve", "wikipedia")
 * @property metadata Additional document metadata from Qdrant payload or PostgreSQL JSON column
 * @property contentType Inferred content type for filtering (article, documentation, vulnerability, code, etc.)
 * @property capabilities Inferred boolean capabilities (humanFriendly, agentFriendly, isStructured, etc.)
 */
@Serializable
data class SearchResult(
    val url: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val source: String,
    val metadata: Map<String, String> = emptyMap(),
    val contentType: String = "unknown",
    val capabilities: Map<String, Boolean> = emptyMap(),
    val id: String = metadata["document_id"].orEmpty()
) {
    companion object {
        /**
         * Infers content type from collection name, URL, and metadata.
         *
         * This heuristic classification enables audience filtering - LLMs can request
         * only "code" and "vulnerability" types, while humans prefer "article" and "documentation".
         *
         * @return Content type: article, documentation, vulnerability, legal, code, or document
         */
        fun inferContentType(collection: String, url: String, metadata: Map<String, String>): String {
            return when {
                metadata[PresentationMetadataKeys.TARGET] == PresentationTargets.GRAFANA || url.contains("grafana.", ignoreCase = true) -> "grafana"
                metadata["bookstack_url"]?.startsWith("http") == true || url.contains("bookstack.", ignoreCase = true) -> "bookstack"
                collection.contains("market") || metadata["timeseries"]?.equals("true", ignoreCase = true) == true -> "market"
                collection.contains("rss") || metadata["type"] == "rss" -> "article"
                collection == "stack_knowledge" || metadata["content_type"] == "stack_documentation" -> "documentation"
                collection.contains("wiki") || url.contains("wiki") -> "documentation"
                collection.contains("cve") -> "vulnerability"
                collection.contains("legal") || collection.contains("law") -> "legal"
                collection.contains("code") || url.contains("github") -> "code"
                else -> "document"
            }
        }

        /**
         * Infers boolean capabilities from content type for audience filtering.
         *
         * These capabilities enable fine-grained filtering in the REST API:
         * - audience=human → filter to humanFriendly=true (articles, docs, legal)
         * - audience=agent → filter to agentFriendly=true (code, APIs, structured data)
         *
         * This is crucial for RAG applications where LLMs need different content than humans:
         * - LLMs benefit from structured data (code, CVEs) that they can parse and reason about
         * - Humans benefit from narrative content (articles, documentation) that's readable
         *
         * @return Map of capability flags indicating content suitability for different audiences
         */
        fun inferCapabilities(collection: String, url: String, metadata: Map<String, String>): Map<String, Boolean> {
            val contentType = inferContentType(collection, url, metadata)
            return mapOf(
                "humanFriendly" to (contentType in listOf("article", "documentation", "legal", "bookstack", "grafana", "market", "vulnerability")),
                "agentFriendly" to (contentType in listOf("code", "documentation", "vulnerability", "bookstack", "grafana", "market")),
                "hasTimeSeries" to (contentType in listOf("grafana", "market") || collection.contains("market") || metadata.containsKey("timeseries")),
                "hasRichContent" to (url.isNotBlank()),
                "isInteractive" to (contentType in listOf("bookstack", "grafana") || (contentType == "vulnerability" && url.isNotBlank()) || metadata.containsKey("interactive")),
                "isStructured" to (contentType in listOf("code", "legal", "vulnerability", "bookstack", "grafana", "market"))
            )
        }
    }
}

@Serializable
data class SearchDocument(
    val id: String,
    val source: String,
    val collection: String,
    val title: String,
    val text: String,
    val url: String,
    val metadata: Map<String, String>,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

internal fun redactSearchQueryForLog(query: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(query.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "sha256=$digest,length=${query.length}"
}
