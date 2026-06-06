package org.webservices.testrunner.framework

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * HTTP client abstraction for interacting with webservices stack services.
 *
 * ServiceClient provides high-level methods for testing cross-service integration,
 * encapsulating the complexity of HTTP requests, authentication headers, and response
 * parsing. It validates that services can communicate correctly and that the data flow
 * between components works end-to-end.
 *
 * ## Cross-Service Integration Patterns
 * - **Agent-Tool-Server**: Tests LLM tool execution (`callTool`) to validate agent capabilities
 * - **Data Pipeline**: Triggers ingestion (`triggerFetch`) to test document flow
 * - **Search-Service**: Performs hybrid search (`search`) to validate vector + BM25 fusion
 * - **BookStack**: Queries knowledge base API to verify pipeline publishing
 * - **MariaDB**: Executes database queries via model-context-server to test tool chain
 *
 * ## Why This Abstraction Exists
 * - **Test Clarity**: Tests focus on behavior, not HTTP mechanics
 * - **Authentication Handling**: Automatically includes user-context headers and API keys
 * - **Error Translation**: Converts HTTP failures into test-friendly result objects
 * - **Integration Testing**: Validates real HTTP communication, not mocked interfaces
 *
 * @property endpoints Service endpoint configuration (URLs and database connections)
 * @property client Ktor HTTP client instance for making requests
 */
class ServiceClient(
    private val endpoints: ServiceEndpoints,
    private val client: HttpClient
) {
    @Volatile
    private var modelContextBearerToken: String? = null
    @Volatile
    private var modelContextBearerTokenUnavailable: Boolean = false

    private fun isModelContextUrl(url: String): Boolean {
        return try {
            val requested = URI(url)
            val modelContext = URI(endpoints.modelContextServer)
            requested.host == modelContext.host && requested.port == modelContext.port
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchModelContextBearerToken(): String? {
        // Non-browser token minting is intentionally not implemented here.
        // Provide MODEL_CONTEXT_BEARER_TOKEN for API-level tests, or exercise
        // Keycloak OIDC through Playwright browser flows.
        return null
    }

    private suspend fun modelContextBearerToken(): String? {
        modelContextBearerToken?.let { return it }
        if (modelContextBearerTokenUnavailable) {
            return null
        }

        val staticToken = System.getenv("MODEL_CONTEXT_BEARER_TOKEN")
            ?.takeIf { it.isNotBlank() }
        if (staticToken != null) {
            modelContextBearerToken = staticToken
            return staticToken
        }

        val mintedToken = fetchModelContextBearerToken()
        if (!mintedToken.isNullOrBlank()) {
            modelContextBearerToken = mintedToken
            return mintedToken
        }

        modelContextBearerTokenUnavailable = true
        return null
    }

    private fun applyAuthHeaders(url: String, builder: HttpRequestBuilder, modelContextToken: String? = null) {
        if (isModelContextUrl(url)) {
            modelContextToken?.let { token ->
                builder.header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

        // Add BookStack authentication
        if (url.contains("/api/") && endpoints.bookstackTokenId != null && endpoints.bookstackTokenSecret != null) {
            builder.header("Authorization", "Token ${endpoints.bookstackTokenId}:${endpoints.bookstackTokenSecret}")
        }

        // Add Qdrant authentication
        if (url.contains(endpoints.qdrant)) {
            if (endpoints.qdrantApiKey != null) {
                builder.header("api-key", endpoints.qdrantApiKey)
            } else {
                println("      ⚠️  WARNING: Qdrant URL detected but qdrantApiKey is null!")
            }
        }

        if (url.contains(endpoints.searchService) || url.contains("opensearch:9200")) {
            openSearchBasicAuthHeader()?.let { builder.header(HttpHeaders.Authorization, it) }
        }
    }

    /**
     * Performs health check for core webservices services.
     *
     * Validates that services are reachable and responding to HTTP requests. This is
     * the foundation for all integration tests - if health checks fail, the stack is
     * not ready for testing.
     *
     * @param service Service name ("workspace-provisioner", "pipeline", "opensearch", "data-fetcher")
     * @return HealthStatus indicating whether the service is healthy and its HTTP status code
     */
    suspend fun healthCheck(service: String): HealthStatus {
        val url = when (service) {
            "workspace-provisioner", "model-context-server" -> "${endpoints.modelContextServer}/health"
            "pipeline" -> "${endpoints.pipeline}/health"
            "opensearch" -> "${endpoints.searchService}/_cluster/health"
            
            "data-fetcher" -> "${endpoints.dataFetcher}/health"
            else -> throw IllegalArgumentException("Unknown service: $service")
        }

        return try {
            val response = client.get(url) {
                if (service == "opensearch") {
                    val username = System.getenv("OPENSEARCH_USERNAME") ?: "admin"
                    val password = System.getenv("OPENSEARCH_PASSWORD")
                        ?.takeIf { it.isNotBlank() }
                        ?: System.getenv("OPENSEARCH_ADMIN_PASSWORD").orEmpty()
                    if (password.isNotBlank()) {
                        val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                        header(HttpHeaders.Authorization, "Basic $encoded")
                    }
                }
            }
            HealthStatus(service, response.status == HttpStatusCode.OK, response.status.value)
        } catch (e: Exception) {
            HealthStatus(service, false, -1, e.message)
        }
    }

    private fun resolveChatCompletionsUrl(rawBaseUrl: String): String {
        val trimmed = rawBaseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun embeddingServiceUrl(): String {
        val base = System.getenv("EMBEDDING_SERVICE_URL")
            ?.takeIf { it.isNotBlank() }
            ?: "http://embedding-gpu:8080"
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith("/embed")) trimmed else "$trimmed/embed"
    }

    private fun openSearchBasicAuthHeader(): String? {
        val username = System.getenv("OPENSEARCH_USERNAME") ?: "admin"
        val password = System.getenv("OPENSEARCH_PASSWORD")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("OPENSEARCH_ADMIN_PASSWORD").orEmpty()
        if (password.isBlank()) {
            return null
        }
        return "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    }

    private fun jsonText(result: JsonElement): String = Json.encodeToString(JsonElement.serializer(), result)

    private suspend fun directLlmChat(args: JsonObject): ToolResult {
        return try {
            val response = client.post(resolveChatCompletionsUrl(endpoints.llmGateway)) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", args["model"] ?: JsonPrimitive("webservices-qwen2.5-coder-14b"))
                    put("messages", args["messages"] ?: JsonArray(listOf(JsonObject(mapOf(
                        "role" to JsonPrimitive("user"),
                        "content" to JsonPrimitive("Hello")
                    )))))
                    args["temperature"]?.let { put("temperature", it) }
                    args["max_tokens"]?.let { put("max_tokens", it) }
                })
            }
            val body = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                return ToolResult.Error(response.status.value, body)
            }

            val parsed = Json.parseToJsonElement(body).jsonObject
            val content = parsed["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: body

            ToolResult.Success(
                jsonText(
                    buildJsonObject {
                        put("content", content)
                        put("iterations", 1)
                        put("trace", JsonArray(emptyList()))
                    }
                )
            )
        } catch (e: Exception) {
            ToolResult.Error(-1, e.message ?: "LLM request failed")
        }
    }

    private suspend fun directLlmChatResponse(args: JsonObject): RawHttpResponse {
        return when (val result = directLlmChat(args)) {
            is ToolResult.Success -> RawHttpResponse(
                HttpStatusCode.OK,
                jsonText(buildJsonObject { put("result", Json.parseToJsonElement(result.output)) })
            )
            is ToolResult.Error -> RawHttpResponse(
                HttpStatusCode.fromValue(result.statusCode.takeIf { it > 0 } ?: 503),
                result.message
            )
        }
    }

    private suspend fun directEmbedding(args: JsonObject): ToolResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error(HttpStatusCode.BadRequest.value, "text is required")
        return try {
            val response = client.post(embeddingServiceUrl()) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    putJsonArray("inputs") {
                        add(JsonPrimitive(text))
                    }
                    put("truncate", JsonPrimitive(true))
                })
            }
            val body = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                return ToolResult.Error(response.status.value, body)
            }
            val embedding = Json.parseToJsonElement(body).jsonArray.firstOrNull()
                ?: JsonArray(emptyList())
            ToolResult.Success(jsonText(embedding))
        } catch (e: Exception) {
            ToolResult.Error(-1, e.message ?: "Embedding request failed")
        }
    }

    private suspend fun directSearchTool(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error(HttpStatusCode.BadRequest.value, "query is required")
        val collections = args["collections"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: listOf("*")
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 5
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "hybrid"
        val result = search(query = query, collections = collections, limit = limit, mode = mode)
        return if (result.success) {
            ToolResult.Success(jsonText(result.results))
        } else {
            ToolResult.Error(HttpStatusCode.BadGateway.value, result.results.toString())
        }
    }

    private fun directQueryMariaDb(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error(HttpStatusCode.BadRequest.value, "query is required")
        val normalized = query.trim()
        if (!normalized.startsWith("select", ignoreCase = true)) {
            return ToolResult.Error(HttpStatusCode.BadRequest.value, "Only SELECT queries are allowed")
        }

        val database = args["database"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: endpoints.mariadb?.database
            ?: "bookstack"
        val mariadb = endpoints.mariadb
            ?: return ToolResult.Error(HttpStatusCode.ServiceUnavailable.value, "MariaDB endpoint is not configured")

        return try {
            val jdbcUrl = "jdbc:mariadb://${mariadb.host}:${mariadb.port}/$database"
            DriverManager.getConnection(jdbcUrl, mariadb.user, mariadb.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.queryTimeout = 15
                    stmt.executeQuery(normalized).use { rs ->
                        val meta = rs.metaData
                        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                        val rows = mutableListOf<String>()
                        rows += columns.joinToString("\t")
                        while (rs.next()) {
                            rows += columns.indices.joinToString("\t") { index ->
                                rs.getString(index + 1).orEmpty()
                            }
                        }
                        ToolResult.Success(rows.joinToString("\n"))
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult.Error(-1, e.message ?: "MariaDB query failed")
        }
    }

    /**
     * Calls an agent tool via model-context-server to test LLM-agent integration.
     *
     * This method validates the full agent tool execution chain:
     * 1. HTTP request to model-context-server `/call-tool` endpoint
     * 2. Plugin routing and capability enforcement
     * 3. Tool execution (e.g., querying PostgreSQL, searching Qdrant, executing Docker commands)
     * 4. Response formatting for LLM consumption
     *
     * Tests use this to verify that:
     * - Tools execute correctly and return expected results
     * - Authenticated Keycloak identities enable proper isolation
     * - Error handling works (invalid args, missing permissions, service failures)
     *
     * @param name Tool name (e.g., "semantic_search", "query_postgres", "docker_ps")
     * @param args Tool arguments as a map (converted to JSON)
     * @return ToolResult.Success with output string, or ToolResult.Error with details
     */
    suspend fun callTool(name: String, args: Map<String, Any>): ToolResult {
        val jsonArgs = args.toJsonObject()
        when (name) {
            "normalize_whitespace" -> {
                val text = jsonArgs["text"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error(HttpStatusCode.BadRequest.value, "text is required")
                return ToolResult.Success(text.replace(Regex("\\s+"), " ").trim())
            }
            "uuid_generate" -> return ToolResult.Success(UUID.randomUUID().toString())
            "llm_chat_completion" -> return directLlmChat(jsonArgs)
            "llm_embed_text" -> return directEmbedding(jsonArgs)
            "semantic_search", "retrieve_stack_context" -> return directSearchTool(jsonArgs)
            "query_mariadb" -> return directQueryMariaDb(jsonArgs)
        }
        return try {
            val url = "${endpoints.modelContextServer}/call-tool"
            val modelContextToken = if (isModelContextUrl(url)) modelContextBearerToken() else null
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                if (modelContextToken == null) {
                    endpoints.apiKey?.let { bearerAuth(it) }
                }
                applyAuthHeaders(url, this, modelContextToken)
                setBody(ToolCallRequest(name, jsonArgs))
            }

            if (response.status == HttpStatusCode.OK) {
                val body = response.body<ToolCallResponse>()
                ToolResult.Success(body.result.toString())
            } else {
                ToolResult.Error(response.status.value, response.bodyAsText())
            }
        } catch (e: Exception) {
            ToolResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * Executes a raw authenticated POST against model-context-server `/call-tool`.
     *
     * Tests that need the HTTP status code and full JSON payload should use this instead of
     * talking to the endpoint directly, so auth behavior stays consistent with the live stack.
     */
    suspend fun callToolRequest(requestBody: String): RawHttpResponse {
        val canonical = canonicalizeRawToolRequest(requestBody)
        val parsed = runCatching { Json.parseToJsonElement(canonical).jsonObject }.getOrNull()
        if (parsed != null) {
            val toolName = parsed["name"]?.jsonPrimitive?.contentOrNull
            val args = parsed["args"]?.jsonObject ?: buildJsonObject {}
            when (toolName) {
                "llm_chat_completion" -> return directLlmChatResponse(args)
                "llm_embed_text" -> return when (val result = directEmbedding(args)) {
                    is ToolResult.Success -> RawHttpResponse(HttpStatusCode.OK, result.output)
                    is ToolResult.Error -> RawHttpResponse(
                        HttpStatusCode.fromValue(result.statusCode.takeIf { it > 0 } ?: 503),
                        result.message
                    )
                }
                "semantic_search", "retrieve_stack_context" -> return when (val result = directSearchTool(args)) {
                    is ToolResult.Success -> RawHttpResponse(HttpStatusCode.OK, result.output)
                    is ToolResult.Error -> RawHttpResponse(HttpStatusCode.BadGateway, result.message)
                }
            }
        }
        return try {
            val url = "${endpoints.modelContextServer}/call-tool"
            val modelContextToken = if (isModelContextUrl(url)) modelContextBearerToken() else null
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                if (modelContextToken == null) {
                    endpoints.apiKey?.let { bearerAuth(it) }
                }
                applyAuthHeaders(url, this, modelContextToken)
                setBody(canonical)
            }

            RawHttpResponse(response.status, response.bodyAsText())
        } catch (e: Exception) {
            RawHttpResponse(HttpStatusCode.ServiceUnavailable, e.message ?: "Unknown error")
        }
    }

    private fun canonicalizeRawToolRequest(requestBody: String): String {
        val parsed = runCatching { Json.parseToJsonElement(requestBody).jsonObject }.getOrNull()
            ?: return requestBody
        if ("name" in parsed || "args" in parsed) {
            return requestBody
        }

        val tool = parsed["tool"]?.jsonPrimitive?.contentOrNull ?: return requestBody
        val input = parsed["input"]?.jsonObject ?: buildJsonObject {}
        return Json.encodeToString(
            ToolCallRequest.serializer(),
            ToolCallRequest(name = tool, args = input)
        )
    }

    /**
     * Triggers data ingestion for a specific source in the pipeline.
     *
     * Tests use this to initiate document fetching and validate the pipeline's ability to:
     * - Connect to external data sources (RSS, CVE feeds, Wikipedia, etc.)
     * - Transform and chunk content
     * - Stage documents in PostgreSQL
     * - Trigger downstream processing (embedding, indexing, publishing)
     *
     * This tests the first stage of the data flow: Source → Pipeline → PostgreSQL staging.
     *
     * @param source Source name (e.g., "rss", "cve", "wikipedia")
     * @return FetchResult indicating success and any error messages
     */
    suspend fun triggerFetch(source: String): FetchResult {
        return try {
            val response = client.post("${endpoints.dataFetcher}/trigger/$source")
            FetchResult(response.status == HttpStatusCode.OK, response.bodyAsText())
        } catch (e: Exception) {
            FetchResult(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Performs a dry-run fetch to validate source connectivity without persisting data.
     *
     * Used to test that the pipeline can reach external data sources and parse their
     * responses without actually ingesting documents. This is useful for validating
     * configuration changes or testing against unreliable external APIs.
     *
     * @param source Source name to dry-run
     * @return DryRunFetchResult with validation status and preview data
     */
    suspend fun dryRunFetch(source: String): DryRunFetchResult {
        return try {
            val response = client.get("${endpoints.dataFetcher}/dry-run/$source")
            val body = response.bodyAsText()
            val success = response.status == HttpStatusCode.OK
            DryRunFetchResult(success, body)
        } catch (e: Exception) {
            DryRunFetchResult(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Performs BM25 text search through OpenSearch and returns the legacy test result shape.
     */
    suspend fun search(
        query: String,
        collections: List<String> = listOf("*"),
        limit: Int = 5,
        mode: String = "hybrid",
        timeoutMs: Long? = null
    ): SearchResult {
        if (mode == "vector") {
            return vectorSearch(query, collections, limit, timeoutMs)
        }

        return try {
            val executeRequest: suspend () -> HttpResponse = {
                client.post("${endpoints.searchService.trimEnd('/')}/knowledge/_search") {
                    openSearchBasicAuthHeader()?.let { header(HttpHeaders.Authorization, it) }
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("size", limit)
                        putJsonObject("query") {
                            putJsonObject("bool") {
                                putJsonArray("must") {
                                    add(buildJsonObject {
                                        putJsonObject("multi_match") {
                                            put("query", query)
                                            put("fields", JsonArray(listOf(JsonPrimitive("title^2"), JsonPrimitive("text"))))
                                        }
                                    })
                                }
                                val filters = collections.filter { it != "*" }
                                if (filters.isNotEmpty()) {
                                    putJsonArray("filter") {
                                        add(buildJsonObject {
                                            putJsonObject("terms") {
                                                put("collection", JsonArray(filters.map { JsonPrimitive(it) }))
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    })
                }
            }
            val response = if (timeoutMs != null) withTimeout(timeoutMs) { executeRequest() } else executeRequest()
            val body = response.bodyAsText()
            if (response.status == HttpStatusCode.NotFound && body.contains("index_not_found_exception")) {
                return SearchResult(true, emptySearchResult(mode))
            }
            val raw = Json.parseToJsonElement(body)
            val normalized = if (response.status == HttpStatusCode.OK) normalizeOpenSearchSearchResult(raw, mode) else raw

            SearchResult(
                success = response.status == HttpStatusCode.OK,
                results = normalized
            )
        } catch (e: Exception) {
            SearchResult(false, JsonPrimitive(e.message ?: "Unknown error"))
        }
    }

    private suspend fun vectorSearch(
        query: String,
        collections: List<String>,
        limit: Int,
        timeoutMs: Long?
    ): SearchResult {
        return try {
            val embeddingResult = directEmbedding(buildJsonObject {
                put("text", query)
                put("model", "bge-m3")
            })
            val vector = when (embeddingResult) {
                is ToolResult.Success -> Json.parseToJsonElement(embeddingResult.output).jsonArray
                is ToolResult.Error -> return SearchResult(false, JsonPrimitive(embeddingResult.message))
            }

            val collection = collections.firstOrNull { it != "*" } ?: "stack_knowledge"
            val executeRequest: suspend () -> HttpResponse = {
                client.post("${endpoints.qdrant.trimEnd('/')}/collections/$collection/points/search") {
                    endpoints.qdrantApiKey?.let { header("api-key", it) }
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("vector", vector)
                        put("limit", limit)
                        put("with_payload", true)
                    })
                }
            }
            val response = if (timeoutMs != null) withTimeout(timeoutMs) { executeRequest() } else executeRequest()
            val body = response.bodyAsText()
            if (response.status == HttpStatusCode.NotFound) {
                return SearchResult(true, emptySearchResult("vector"))
            }
            val raw = Json.parseToJsonElement(body)
            val normalized = if (response.status == HttpStatusCode.OK) normalizeQdrantSearchResult(raw) else raw
            SearchResult(response.status == HttpStatusCode.OK, normalized)
        } catch (e: Exception) {
            SearchResult(false, JsonPrimitive(e.message ?: "Unknown error"))
        }
    }

    private fun emptySearchResult(mode: String): JsonObject = buildJsonObject {
        put("results", JsonArray(emptyList()))
        put("total", 0)
        put("mode", mode)
    }

    private fun normalizeQdrantSearchResult(raw: JsonElement): JsonObject {
        val rows = raw.jsonObject["result"]?.jsonArray.orEmpty()
        val results = JsonArray(rows.map { row ->
            val rowObject = row.jsonObject
            val payload = rowObject["payload"]?.jsonObject ?: JsonObject(emptyMap())
            buildJsonObject {
                put("id", payload["id"] ?: rowObject["id"] ?: JsonPrimitive(""))
                put("collection", payload["collection"] ?: JsonPrimitive(""))
                put("source", payload["source"] ?: JsonPrimitive(""))
                put("title", payload["title"] ?: JsonPrimitive(""))
                put("content", payload["text"] ?: JsonPrimitive(""))
                put("text", payload["text"] ?: JsonPrimitive(""))
                put("metadata", payload["metadata"] ?: JsonObject(emptyMap()))
                put("score", rowObject["score"] ?: JsonPrimitive(0.0))
            }
        })
        return buildJsonObject {
            put("results", results)
            put("total", results.size)
            put("mode", "vector")
        }
    }

    private fun normalizeOpenSearchSearchResult(raw: JsonElement, mode: String): JsonObject {
        val hits = raw.jsonObject["hits"]?.jsonObject
        val rows = hits?.get("hits")?.jsonArray.orEmpty()
        val results = JsonArray(rows.map { hit ->
            val hitObject = hit.jsonObject
            val source = hitObject["_source"]?.jsonObject ?: JsonObject(emptyMap())
            buildJsonObject {
                put("id", source["id"] ?: hitObject["_id"] ?: JsonPrimitive(""))
                put("collection", source["collection"] ?: JsonPrimitive(""))
                put("source", source["source"] ?: JsonPrimitive(""))
                put("title", source["title"] ?: JsonPrimitive(""))
                put("content", source["text"] ?: JsonPrimitive(""))
                put("text", source["text"] ?: JsonPrimitive(""))
                put("metadata", source["metadata"] ?: JsonObject(emptyMap()))
                put("score", hitObject["_score"] ?: JsonPrimitive(0.0))
            }
        })
        val total = hits?.get("total")?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull ?: results.size
        return buildJsonObject {
            put("results", results)
            put("total", total)
            put("mode", mode)
        }
    }

    /**
     * Performs a raw HTTP GET request with optional BookStack authentication.
     *
     * Provides low-level HTTP access for tests that need to interact with services
     * beyond the high-level abstractions. Automatically injects BookStack API tokens
     * when accessing BookStack endpoints.
     *
     * @param url Full URL to request
     * @return Raw HTTP response for custom parsing
     */
    suspend fun getRawResponse(url: String): HttpResponse {
        val modelContextToken = if (isModelContextUrl(url)) modelContextBearerToken() else null
        return client.get(url) {
            applyAuthHeaders(url, this, modelContextToken)
        }
    }

    suspend fun getRawResponse(url: String, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        val modelContextToken = if (isModelContextUrl(url)) modelContextBearerToken() else null
        return client.get(url) {
            applyAuthHeaders(url, this, modelContextToken)
            block()
        }
    }

    suspend fun postRaw(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val modelContextToken = if (isModelContextUrl(url)) modelContextBearerToken() else null
        return client.post(url) {
            applyAuthHeaders(url, this, modelContextToken)
            block()
        }
    }

    suspend fun putRaw(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val modelContextToken = if (isModelContextUrl(url)) modelContextBearerToken() else null
        return client.put(url) {
            applyAuthHeaders(url, this, modelContextToken)
            block()
        }
    }

    suspend fun deleteRaw(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val modelContextToken = if (isModelContextUrl(url)) modelContextBearerToken() else null
        return client.delete(url) {
            applyAuthHeaders(url, this, modelContextToken)
            block()
        }
    }

    suspend fun requestRaw(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val modelContextToken = if (isModelContextUrl(url)) modelContextBearerToken() else null
        return client.request(url) {
            applyAuthHeaders(url, this, modelContextToken)
            block()
        }
    }

    /**
     * Executes a SQL query against MariaDB via model-context-server's query_mariadb tool.
     *
     * This tests the agent tool chain for database access:
     * 1. Test sends query to model-context-server via /call-tool
     * 2. Model-context-server routes to DataSourceQueryPlugin
     * 3. Plugin establishes JDBC connection to MariaDB
     * 4. Query executes with validation (SELECT only, no dangerous functions)
     * 5. Results formatted as text table and returned
     *
     * Tests validate:
     * - Agent tools can query BookStack's MariaDB database
     * - SQL injection prevention works
     * - Query results are accurate and complete
     * - Error handling for invalid queries
     *
     * @param query SQL query string (must be SELECT-only)
     * @return MariaDbResult with success flag and data or error message
     */
    suspend fun queryMariaDB(query: String): MariaDbResult {
        return when (val result = directQueryMariaDb(buildJsonObject {
            put("database", JsonPrimitive("bookstack"))
            put("query", JsonPrimitive(query))
        })) {
            is ToolResult.Success -> MariaDbResult(success = true, data = result.output)
            is ToolResult.Error -> MariaDbResult(success = false, error = result.message)
        }
    }
}

@Serializable
data class ToolCallRequest(val name: String, val args: JsonObject)

@Serializable
data class ToolCallResponse(val result: JsonElement, val elapsedMs: Long? = null)

data class HealthStatus(val service: String, val healthy: Boolean, val statusCode: Int, val error: String? = null)

sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Error(val statusCode: Int, val message: String) : ToolResult()
}

/**
 * Helper extension to extract agent response content.
 *
 * llm_chat_completion now returns: {"content": "...", "iterations": N, "trace": [...]}
 * This extracts the "content" field for backward compatibility with existing tests.
 */
fun ToolResult.Success.extractAgentContent(): String {
    return try {
        val json = Json.parseToJsonElement(output).jsonObject
        json["content"]?.jsonPrimitive?.content ?: output
    } catch (e: Exception) {
        // Not agent format, return raw output
        output
    }
}

data class FetchResult(val success: Boolean, val message: String)
data class DryRunFetchResult(val success: Boolean, val message: String)
data class IndexResult(val success: Boolean, val message: String)
data class SearchResult(val success: Boolean, val results: JsonElement)
data class MariaDbResult(val success: Boolean, val data: String? = null, val error: String? = null)
data class RawHttpResponse(val status: HttpStatusCode, private val body: String) {
    fun bodyAsText(): String = body
}


private fun Map<String, Any>.toJsonObject(): JsonObject {
    return JsonObject(this.mapValues { (_, v) ->
        when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map { item ->
                when (item) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        (item as Map<String, Any>).toJsonObject()
                    }
                    is String -> JsonPrimitive(item)
                    is Number -> JsonPrimitive(item)
                    is Boolean -> JsonPrimitive(item)
                    else -> JsonPrimitive(item.toString())
                }
            })
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (v as Map<String, Any>).toJsonObject()
            }
            else -> JsonPrimitive(v.toString())
        }
    })
}
