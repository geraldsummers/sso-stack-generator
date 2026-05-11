package org.webservices.chatgptconnector

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
            limit = arguments["limit"]?.let { (it as JsonPrimitive).content.toIntOrNull() } ?: 10,
            audience = "agent"
        )
        val response = httpClient.post("${config.searchServiceBaseUrl}/search") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return json.encodeToJsonElement(response.body<SearchResponse>())
    }

    private suspend fun callFetch(arguments: JsonObject): JsonElement {
        val id = arguments["id"]?.let { (it as JsonPrimitive).content } ?: return buildJsonObject { put("error", "missing id") }
        val collection = arguments["collection"]?.let { (it as JsonPrimitive).content }
        val response = httpClient.get("${config.searchServiceBaseUrl}/documents/$id") {
            collection?.let { parameter("collection", it) }
        }
        return json.encodeToJsonElement(response.body<SearchDocument>())
    }

    private suspend fun callPipelineStatus(): JsonElement {
        val response = httpClient.get("http://knowledge-ingestion:8090/health")
        return json.encodeToJsonElement(response.body<Map<String, String>>())
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
