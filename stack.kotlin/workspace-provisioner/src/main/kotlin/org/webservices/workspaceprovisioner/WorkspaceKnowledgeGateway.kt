package org.webservices.workspaceprovisioner

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

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
    private val httpClient: HttpClient
) {
    suspend fun search(request: WorkspaceKnowledgeSearchRequest): JsonProxyResponse {
        val response = httpClient.post("$baseUrl/search") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return JsonProxyResponse(status = response.status, body = response.bodyAsText())
    }

    suspend fun document(documentId: String, collection: String?): JsonProxyResponse {
        val response = httpClient.get("$baseUrl/documents/$documentId") {
            if (!collection.isNullOrBlank()) {
                parameter("collection", collection)
            }
        }
        return JsonProxyResponse(status = response.status, body = response.bodyAsText())
    }
}
