package org.webservices.chatgptconnector

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class McpFacade(
    private val store: ConnectorStore,
    private val config: ConnectorConfig,
    private val httpClient: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(rawBody: String, token: AgentTokenDto): McpResponse {
        return try {
            val request = json.decodeFromString(McpRequest.serializer(), rawBody)
            when (request.method) {
                "initialize" -> McpResponse(
                    id = request.id,
                    result = buildJsonObject {
                        put("protocolVersion", "2025-03-26")
                        putJsonObject("serverInfo") {
                            put("name", "chatgpt-connector")
                            put("version", "1.0.0")
                        }
                        putJsonObject("capabilities") {
                            putJsonObject("tools") {}
                        }
                    }
                )
                "tools/list" -> McpResponse(id = request.id, result = toolsList())
                "tools/call" -> {
                    val call = json.decodeFromJsonElement(McpToolCall.serializer(), request.params ?: JsonObject(emptyMap()))
                    McpResponse(id = request.id, result = toolCall(call, token))
                }
                else -> McpResponse(id = request.id, error = McpError(-32601, "method not found"))
            }
        } catch (e: Exception) {
            McpResponse(error = McpError(-32603, "internal error: ${e.message}"))
        }
    }

    private fun toolsList(): JsonElement = buildJsonObject {
        putJsonArray("tools") {
            add(toolDef("search", "Search stack knowledge", "query, collections?, mode?, limit?"))
            add(toolDef("fetch", "Fetch a document by id", "id, collection?"))
            add(toolDef("pipeline_status", "Basic pipeline readiness", "none"))
            add(toolDef("workspace_readiness", "Workspace provisioner readiness", "none"))
        }
    }

    private suspend fun toolCall(call: McpToolCall, token: AgentTokenDto): JsonElement {
        if (!token.scopes.contains("*") && !token.scopes.contains(call.name) && !token.scopes.contains("${call.name}:read")) {
            return buildJsonObject { put("error", "scope denied for tool ${call.name}") }
        }
        return when (call.name) {
            "search" -> callSearch(call.arguments)
            "fetch" -> callFetch(call.arguments)
            "pipeline_status" -> callPipelineStatus()
            "workspace_readiness" -> callWorkspaceReadiness()
            else -> buildJsonObject { put("error", "unknown tool: ${call.name}") }
        }
    }

    private suspend fun callSearch(arguments: JsonObject): JsonElement {
        val request = SearchRequest(
            query = arguments["query"]?.let { (it as JsonPrimitive).content } ?: "",
            collections = arguments["collections"]?.let { arr ->
                arr.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            } ?: listOf("*"),
            mode = arguments["mode"]?.let { (it as JsonPrimitive).content } ?: "hybrid",
            limit = (arguments["limit"]?.let { (it as JsonPrimitive).content.toIntOrNull() } ?: 10).coerceIn(1, 50),
            audience = "agent"
        )
        val response = httpClient.post("${config.searchServiceBaseUrl}/_search") {
            applySearchAuth()
            contentType(ContentType.Application.Json)
            setBody(openSearchQuery(request))
        }
        return openSearchResponse(response.body<JsonElement>(), request.mode)
    }

    private suspend fun callFetch(arguments: JsonObject): JsonElement {
        val id = arguments["id"]?.let { (it as JsonPrimitive).content } ?: return buildJsonObject { put("error", "missing id") }
        val response = httpClient.get("${config.searchServiceBaseUrl}/_doc/${pathSegment(id)}") {
            applySearchAuth()
        }
        return openSearchDocument(id, response.body<JsonElement>())
    }

    private suspend fun callPipelineStatus(): JsonElement {
        val response = httpClient.get("http://ingestion-runner:8090/health")
        return json.encodeToJsonElement(response.body<Map<String, String>>())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applySearchAuth() {
        val username = config.searchServiceUsername
        val password = config.searchServicePassword
        if (!username.isNullOrBlank() && password != null) {
            val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            header(HttpHeaders.Authorization, "Basic $encoded")
        }
    }

    private fun openSearchQuery(request: SearchRequest): JsonObject = buildJsonObject {
        put("size", request.limit)
        put("query", buildJsonObject {
            put("bool", buildJsonObject {
                putJsonArray("must") {
                    add(buildJsonObject {
                        putJsonObject("multi_match") {
                            put("query", request.query)
                            put("fields", JsonArray(listOf(JsonPrimitive("title^3"), JsonPrimitive("text"))))
                        }
                    })
                }
                val collections = request.collections.filter { it != "*" }
                if (collections.isNotEmpty()) {
                    putJsonArray("filter") {
                        add(buildJsonObject {
                            putJsonObject("terms") {
                                put("collection", JsonArray(collections.map { JsonPrimitive(it) }))
                            }
                        })
                    }
                }
            })
        })
    }

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun openSearchResponse(raw: JsonElement, mode: String): JsonElement {
        val hits = raw.jsonObject["hits"]?.jsonObject
        val results = hits?.get("hits")?.jsonArray.orEmpty().map { hit ->
                val hitObject = hit.jsonObject
                val source = hitObject["_source"]?.jsonObject ?: JsonObject(emptyMap())
                SearchResult(
                    id = source["id"]?.asString() ?: hitObject["_id"]?.asString().orEmpty(),
                    title = source["title"]?.asString(),
                    score = hitObject["_score"]?.jsonPrimitiveDouble() ?: 0.0,
                    content = source["text"]?.asString(),
                    metadata = source["metadata"]?.jsonObject?.mapValues { it.value.asString().orEmpty() }.orEmpty()
                )
        }
        val total = hits?.get("total")?.jsonObject?.get("value")?.jsonPrimitiveDouble()?.toInt() ?: results.size
        return json.encodeToJsonElement(SearchResponse(results, total, mode))
    }

    private fun openSearchDocument(id: String, raw: JsonElement): JsonElement {
        val source = raw.jsonObject["_source"]?.jsonObject ?: JsonObject(emptyMap())
        return json.encodeToJsonElement(SearchDocument(
            id = source["id"]?.asString() ?: id,
            collection = source["collection"]?.asString().orEmpty(),
            title = source["title"]?.asString(),
            content = source["text"]?.asString().orEmpty(),
            metadata = source["metadata"]?.jsonObject?.mapValues { it.value.asString().orEmpty() }.orEmpty()
        ))
    }

    private suspend fun callWorkspaceReadiness(): JsonElement {
        val response = httpClient.get("http://workspace-provisioner:8120/ready")
        return json.encodeToJsonElement(response.body<JsonElement>())
    }

    private fun toolDef(name: String, description: String, schemaHint: String): JsonElement = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("x-schema-hint") { put("text", schemaHint) }
        }
    }
}

private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.jsonPrimitiveDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
