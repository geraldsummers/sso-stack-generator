package org.webservices.searchservice

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val logger = KotlinLogging.logger {}
private val requestJson = Json {
    prettyPrint = false
    isLenient = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}
private val collectionNameRegex = Regex("^[a-zA-Z0-9_-]+$")

private fun isValidCollectionName(collection: String): Boolean =
    collectionNameRegex.matches(collection)

private fun isValidDocumentId(documentId: String): Boolean =
    documentId.length <= 512 && documentId.none { it.isISOControl() }

/**
 * Search Gateway Service - Unified hybrid search entry point for the webservices stack.
 *
 * This service provides RAG (Retrieval-Augmented Generation) capabilities for LLMs by combining:
 * - **Vector Search** (Qdrant) - Semantic similarity using cosine distance on BGE-M3 embeddings
 * - **Full-Text Search** (PostgreSQL) - Keyword precision using PostgreSQL's ts_rank on tsvector
 * - **Hybrid Search** - Reciprocal Rank Fusion (RRF) algorithm to merge both result sets
 *
 * The service integrates with three external dependencies:
 * 1. Qdrant (gRPC:6334) - Vector database for semantic search
 * 2. PostgreSQL (JDBC:5432) - document_staging table for full-text search
 * 3. Embedding Service (HTTP:8000) - BGE-M3 model for query vectorization
 *
 * Used by workspace agents and internal stack services to provide contextual knowledge
 * from the entire webservices knowledge base (RSS, CVE, Wikipedia, legal docs, etc.).
 */
fun main() {
    logger.info { "Starting Search Gateway Service..." }

    val qdrantUrl = System.getenv("QDRANT_URL") ?: "qdrant:6334"
    val qdrantApiKey = System.getenv("QDRANT_API_KEY") ?: ""
    val postgresJdbcUrl = System.getenv("POSTGRES_JDBC_URL") ?: "jdbc:postgresql://postgres-ssd:5432/webservices"
    val embeddingUrl = System.getenv("EMBEDDING_SERVICE_URL") ?: "http://inference-gateway:8111"

    val gateway = SearchGateway(
        qdrantUrl = qdrantUrl,
        qdrantApiKey = qdrantApiKey,
        postgresJdbcUrl = postgresJdbcUrl,
        embeddingServiceUrl = embeddingUrl
    )

    val port = System.getenv("SEARCH_SERVICE_PORT")?.toIntOrNull() ?: 8098
    val server = embeddedServer(Netty, port = port) {
        configureServer(gateway)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down Search Gateway Service..." }
        gateway.close()
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

/**
 * Request model for hybrid search operations.
 *
 * @property query The search query text. Will be vectorized by the Embedding Service for semantic search
 *                 and used directly for PostgreSQL full-text search.
 * @property collections Target collections to search (e.g., "rss_feeds", "cve", "wikipedia").
 *                      Use ["*"] to search all available collections. Each collection maps to both
 *                      a Qdrant collection and a PostgreSQL collection filter.
 * @property mode Search mode: "vector" (semantic only), "bm25" (full-text only), or "hybrid" (RRF fusion).
 *                Hybrid mode provides the best results by combining semantic understanding with keyword precision.
 * @property limit Maximum number of results to return. In hybrid mode, each backend receives limit/2 to balance
 *                 representation before RRF fusion.
 * @property audience Filter results by intended audience: "human" (articles, docs), "agent" (code, APIs),
 *                    or "both" (no filtering). This enables LLMs to request only agent-friendly content.
 */
@Serializable
data class SearchRequest(
    val query: String,
    val collections: List<String> = listOf("*"),
    val mode: String = "hybrid",
    val limit: Int = 20,
    val audience: String = "both"
)

/**
 * Response model containing ranked search results.
 *
 * @property results Ordered list of search results, ranked by relevance score.
 *                   In hybrid mode, scores are RRF fusion scores combining vector and full-text ranks.
 * @property total Number of results returned after audience filtering.
 * @property mode The search mode used ("vector", "bm25", or "hybrid").
 */
@Serializable
data class SearchResponse(
    val results: List<SearchResult>,
    val total: Int,
    val mode: String
)

/**
 * Sealed class hierarchy for structured error responses.
 *
 * Used to provide detailed error information to clients when search operations fail
 * due to validation issues, external service failures, or timeouts.
 */
@Serializable
sealed class SearchError {
    @Serializable
    data class ValidationError(val field: String, val reason: String) : SearchError()
    @Serializable
    data class DatabaseError(val service: String) : SearchError()
    @Serializable
    data class TimeoutError(val service: String) : SearchError()
    @Serializable
    data object ServiceUnavailable : SearchError()
}


/**
 * Validates search request parameters to prevent injection attacks and ensure system stability.
 *
 * Performs comprehensive validation:
 * - Query length and character sanitization (prevent control character injection)
 * - Collection name validation (alphanumeric + wildcards only, max 50 collections)
 * - Mode validation (vector, bm25, or hybrid only)
 * - Limit bounds checking (1-1000 to prevent resource exhaustion)
 * - Audience validation (human, agent, or both)
 *
 * @return ValidationError if any parameter is invalid, null if validation passes
 */
fun validateSearchRequest(request: SearchRequest): SearchError.ValidationError? {
    // Query validation - prevent excessively long queries and control character injection
    if (request.query.length > 1000) {
        return SearchError.ValidationError("query", "Query must not exceed 1000 characters")
    }
    if (request.query.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        return SearchError.ValidationError("query", "Query contains invalid control characters")
    }

    // Collection validation - prevent excessive parallel searches and name injection
    if (request.collections.isEmpty()) {
        return SearchError.ValidationError("collections", "At least one collection is required")
    }
    if (request.collections.size > 50) {
        return SearchError.ValidationError("collections", "Cannot specify more than 50 collections")
    }
    request.collections.forEach { collection ->
        if (collection.length > 128) {
            return SearchError.ValidationError("collections", "Collection name exceeds 128 characters: $collection")
        }
        if (collection != "*" && !isValidCollectionName(collection)) {
            return SearchError.ValidationError("collections", "Collection name contains invalid characters: $collection")
        }
    }

    // Mode validation - only allow supported search strategies
    val validModes = setOf("vector", "bm25", "hybrid")
    if (request.mode !in validModes) {
        return SearchError.ValidationError("mode", "Mode must be one of: ${validModes.joinToString(", ")}")
    }

    // Limit validation - prevent resource exhaustion from excessive result sets
    if (request.limit < 1 || request.limit > 1000) {
        return SearchError.ValidationError("limit", "Limit must be between 1 and 1000")
    }
    if (request.collections.size * request.limit > 5000) {
        return SearchError.ValidationError("limit", "Requested collection fanout is too large")
    }

    // Audience validation - ensure only supported content filters are used
    val validAudiences = setOf("human", "agent", "both")
    if (request.audience !in validAudiences) {
        return SearchError.ValidationError("audience", "Audience must be one of: ${validAudiences.joinToString(", ")}")
    }

    return null
}

/**
 * Configures the Ktor HTTP server with REST API endpoints for search operations.
 *
 * Provides three endpoints:
 * - GET / - Service information and optional HTML UI
 * - GET /health - Health check for monitoring (Prometheus, load balancers)
 * - POST /search - Main search endpoint with request validation, error handling, and audience filtering
 * - GET /collections - List available collections from Qdrant
 *
 * The /search endpoint integrates with SearchGateway to perform hybrid search and applies
 * audience-based filtering to results based on inferred content capabilities.
 *
 * @param gateway The SearchGateway instance that handles Qdrant, PostgreSQL, and Embedding Service integration
 */
fun Application.configureServer(gateway: SearchGateway) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    routing {
        get("/") {
            val html = this::class.java.classLoader.getResource("static/index.html")?.readText()
            if (html != null) {
                call.respondText(html, ContentType.Text.Html)
            } else {
                call.respondText("Search Gateway Service v1.0.0", ContentType.Text.Plain)
            }
        }

        get("/theme.css") {
            call.respondText(stackThemeCss(), ContentType.Text.CSS)
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/tools") {
            call.respond(
                buildJsonObject {
                    put("service", "search-service")
                    put("version", "1.0.0")
                    putJsonArray("tools") {
                        add(buildJsonObject {
                            put("name", "semantic_search")
                            put("method", "POST")
                            put("path", "/search")
                            put("description", "Search stack knowledge with vector, BM25, or hybrid retrieval.")
                            putJsonObject("input") {
                                put("query", "string, required")
                                put("mode", "vector | bm25 | hybrid, default hybrid")
                                put("collections", "array<string>, default [\"*\"]")
                                put("limit", "integer 1..1000, default 20")
                                put("audience", "agent | human | both, default both")
                            }
                            putJsonObject("output") {
                                put("results", "array<SearchResult>")
                                put("results[].id", "stable document id for GET /documents/{id}")
                                put("results[].metadata.document_id", "same document id when available from source metadata")
                            }
                        })
                        add(buildJsonObject {
                            put("name", "get_document")
                            put("method", "GET")
                            put("path", "/documents/{id}")
                            put("description", "Fetch exact source text for a search result id.")
                            putJsonObject("input") {
                                put("id", "string, required")
                                put("collection", "string, optional query parameter")
                            }
                            put("output", "SearchDocument")
                        })
                        add(buildJsonObject {
                            put("name", "list_collections")
                            put("method", "GET")
                            put("path", "/collections")
                            put("description", "List searchable vector collections.")
                        })
                    }
                }
            )
        }

        get("/documents/{id}") {
            val documentId = call.parameters["id"]?.trim().orEmpty()
            if (documentId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "document id is required"))
                return@get
            }
            if (!isValidDocumentId(documentId)) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
                return@get
            }

            val collection = call.request.queryParameters["collection"]?.trim()?.takeIf { it.isNotBlank() }
            if (collection != null && !isValidCollectionName(collection)) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid collection"))
                return@get
            }

            try {
                val document = gateway.document(
                    documentId = documentId,
                    collection = collection
                )
                if (document == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "document not found"))
                } else {
                    call.respond(document)
                }
            } catch (e: java.sql.SQLException) {
                logger.error(e) { "Database error during document lookup - id=${redactSearchQueryForLog(documentId)}" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database service error", "service" to "postgresql"))
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error during document lookup - id=${redactSearchQueryForLog(documentId)}" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
            }
        }

        post("/search") {
            val request = try {
                requestJson.decodeFromString<SearchRequest>(call.receiveText())
            } catch (e: SerializationException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid search request JSON")
                )
                return@post
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid search request JSON")
                )
                return@post
            }

            // Basic empty query check before validation
            if (request.query.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Query cannot be empty")
                )
                return@post
            }

            // Comprehensive validation to prevent injection attacks and resource exhaustion
            validateSearchRequest(request)?.let { validationError ->
                logger.warn { "Validation failed for search request: ${validationError.field} - ${validationError.reason}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to "Validation failed",
                        "field" to validationError.field,
                        "reason" to validationError.reason
                    )
                )
                return@post
            }

            val queryForLog = redactSearchQueryForLog(request.query)
            try {
                // Delegate to SearchGateway for hybrid search execution
                // This will:
                // 1. Vectorize query via Embedding Service (BGE-M3)
                // 2. Search Qdrant (semantic similarity via cosine distance)
                // 3. Search PostgreSQL (full-text ranking via ts_rank)
                // 4. Merge results with Reciprocal Rank Fusion (RRF) algorithm
                val results = gateway.search(
                    query = request.query,
                    collections = request.collections,
                    mode = request.mode,
                    limit = request.limit
                )

                // Apply audience filtering based on inferred content capabilities
                // This enables LLMs to request only agent-friendly content (code, APIs, structured data)
                // while human users can filter for articles, documentation, and readable content
                val filteredResults = when (request.audience) {
                    "human" -> results.filter { it.capabilities["humanFriendly"] == true }
                    "agent" -> results.filter { it.capabilities["agentFriendly"] == true }
                    else -> results // "both" returns all results without filtering
                }

                call.respond(SearchResponse(
                    results = filteredResults,
                    total = filteredResults.size,
                    mode = request.mode
                ))
            } catch (e: java.sql.SQLException) {
                // PostgreSQL connection failure - full-text search unavailable
                logger.error(e) { "Database error during search - query: $queryForLog, collections: ${request.collections}, mode: ${request.mode}" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Database service error", "service" to "postgresql")
                )
            } catch (e: java.net.SocketTimeoutException) {
                // Timeout from Qdrant, PostgreSQL, or Embedding Service (30s read timeout)
                logger.error(e) { "Timeout during search - query: $queryForLog, collections: ${request.collections}, mode: ${request.mode}" }
                call.respond(
                    HttpStatusCode.GatewayTimeout,
                    mapOf("error" to "Search operation timed out")
                )
            } catch (e: java.io.IOException) {
                // Qdrant or Embedding Service connection failure - vector search unavailable
                logger.error(e) { "IO error during search - query: $queryForLog, collections: ${request.collections}, mode: ${request.mode}" }
                val service = if (e.message?.contains("Embedding service", ignoreCase = true) == true) {
                    "embedding-service"
                } else {
                    "vector-store"
                }
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf(
                        "error" to "External service unavailable",
                        "service" to service
                    )
                )
            } catch (e: Exception) {
                // Catch-all for unexpected errors (should never happen with proper validation)
                logger.error(e) { "Unexpected error during search - query: $queryForLog, collections: ${request.collections}, mode: ${request.mode}, error: ${e::class.simpleName}" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Internal server error")
                )
            }
        }

        get("/collections") {
            try {
                val collections = gateway.getCollections()
                call.respond(mapOf("collections" to collections))
            } catch (e: Exception) {
                logger.error(e) { "Failed to list collections" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to list collections")
                )
            }
        }
    }
}
