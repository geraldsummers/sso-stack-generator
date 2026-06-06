package org.webservices.workspaceprovisioner

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

@kotlinx.serialization.Serializable
data class WorkspaceKnowledgeSearchRequest(
    val query: String,
    val collections: List<String> = listOf("*"),
    val mode: String = "hybrid",
    val limit: Int = 20,
    val audience: String = "agent"
)

data class JsonProxyResponse(
    val status: HttpStatusCode,
    val body: String
)

class WorkspaceKnowledgeGateway(
    private val baseUrl: String,
    private val token: String?,
    private val username: String? = null,
    private val password: String? = null,
    private val httpClient: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(request: WorkspaceKnowledgeSearchRequest): JsonProxyResponse {
        val response = httpClient.post("$baseUrl/_search") {
            token?.let { header("X-Internal-Token", it) }
            applyBasicAuth()
            contentType(ContentType.Application.Json)
            setBody(openSearchQuery(request))
        }
        return JsonProxyResponse(status = response.status, body = toSearchResponse(response.bodyAsText(), request.mode))
    }

    suspend fun document(documentId: String, collection: String?): JsonProxyResponse {
        val response = httpClient.get("$baseUrl/_doc/$documentId") {
            token?.let { header("X-Internal-Token", it) }
            applyBasicAuth()
        }
        return JsonProxyResponse(status = response.status, body = toDocumentResponse(documentId, collection, response.bodyAsText()))
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyBasicAuth() {
        if (!username.isNullOrBlank() && password != null) {
            val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            header(HttpHeaders.Authorization, "Basic $encoded")
        }
    }

    private fun openSearchQuery(request: WorkspaceKnowledgeSearchRequest): JsonObject = buildJsonObject {
        put("size", request.limit)
        put("query", buildJsonObject {
            put("bool", buildJsonObject {
                put("must", buildJsonArray {
                    add(buildJsonObject {
                        put("multi_match", buildJsonObject {
                            put("query", request.query)
                            put("fields", JsonArray(listOf(JsonPrimitive("title^3"), JsonPrimitive("text"))))
                        })
                    })
                })
                val collections = request.collections.filter { it != "*" }
                if (collections.isNotEmpty()) {
                    put("filter", buildJsonArray {
                        add(buildJsonObject {
                            put("terms", buildJsonObject {
                                put("collection", JsonArray(collections.map { JsonPrimitive(it) }))
                            })
                        })
                    })
                }
            })
        })
    }

    private fun toSearchResponse(raw: String, mode: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val hits = root["hits"]?.jsonObject
        val results = buildJsonArray {
            hits?.get("hits")?.jsonArray.orEmpty().forEach { hit ->
                val hitObject = hit.jsonObject
                val source = hitObject["_source"]?.jsonObject ?: JsonObject(emptyMap())
                add(buildJsonObject {
                    put("id", source["id"]?.asString() ?: hitObject["_id"]?.asString().orEmpty())
                    put("title", source["title"]?.asString())
                    put("score", hitObject["_score"]?.jsonPrimitiveDouble() ?: 0.0)
                    put("content", source["text"]?.asString())
                    put("metadata", source["metadata"] ?: JsonObject(emptyMap()))
                })
            }
        }
        val total = hits?.get("total")?.jsonObject?.get("value")?.jsonPrimitiveDouble()?.toInt() ?: results.size
        return buildJsonObject {
            put("results", results)
            put("total", total)
            put("mode", mode)
        }.toString()
    }

    private fun toDocumentResponse(documentId: String, collection: String?, raw: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val source = root["_source"]?.jsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("id", source["id"]?.asString() ?: documentId)
            put("collection", source["collection"]?.asString() ?: collection.orEmpty())
            put("title", source["title"]?.asString())
            put("content", source["text"]?.asString().orEmpty())
            put("metadata", source["metadata"] ?: JsonObject(emptyMap()))
        }.toString()
    }
}

private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.jsonPrimitiveDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
