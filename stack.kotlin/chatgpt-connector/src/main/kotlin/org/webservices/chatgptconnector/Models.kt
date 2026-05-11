package org.webservices.chatgptconnector

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PrincipalIdentity(
    val username: String,
    val email: String? = null,
    val groups: List<String> = emptyList(),
    val source: String = "unknown"
)

@Serializable
data class MeResponse(
    val username: String,
    val email: String? = null,
    val groups: List<String> = emptyList()
)

@Serializable
data class CreateAccountRequest(
    val displayName: String,
    val scopes: List<String> = listOf("search", "fetch")
)

@Serializable
data class AgentAccountDto(
    val id: String,
    val ownerUsername: String,
    val displayName: String,
    val keycloakUserId: String?,
    val keycloakUsername: String?,
    val scopes: List<String>,
    val createdAt: String,
    val closedAt: String?
)

@Serializable
data class MintTokenRequest(
    val scopes: List<String> = listOf("search", "fetch"),
    val ttlSeconds: Long = 86_400
)

@Serializable
data class MintedTokenResponse(
    val tokenId: String,
    val tokenPrefix: String,
    val token: String,
    val scopes: List<String>,
    val expiresAt: String
)

@Serializable
data class AgentTokenDto(
    val id: String,
    val accountId: String,
    val tokenPrefix: String,
    val scopes: List<String>,
    val expiresAt: String,
    val revokedAt: String?,
    val lastUsedAt: String?
)

@Serializable
data class AuditEventDto(
    val id: Long,
    val actorUsername: String,
    val accountId: String?,
    val tokenId: String?,
    val eventType: String,
    val detail: String,
    val createdAt: String
)

@Serializable
data class ApiError(val error: String)

@Serializable
data class McpInitializeParams(
    val protocolVersion: String? = null,
    val capabilities: Map<String, String> = emptyMap(),
    val clientInfo: Map<String, String> = emptyMap()
)

@Serializable
data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: kotlinx.serialization.json.JsonElement? = null,
    val method: String,
    val params: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: kotlinx.serialization.json.JsonElement? = null,
    val result: kotlinx.serialization.json.JsonElement? = null,
    val error: McpError? = null
)

@Serializable
data class McpError(
    val code: Int,
    val message: String,
    val data: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class McpToolCall(
    val name: String,
    val arguments: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {}
)

@Serializable
data class SearchRequest(
    val query: String,
    val collections: List<String> = listOf("*"),
    val mode: String = "hybrid",
    val limit: Int = 10,
    val audience: String = "agent"
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult>,
    val total: Int,
    val mode: String
)

@Serializable
data class SearchResult(
    val id: String,
    val title: String? = null,
    val score: Double,
    val content: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class SearchDocument(
    val id: String,
    val collection: String,
    val title: String? = null,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class KeycloakUserRepresentation(
    val id: String? = null,
    val username: String,
    val enabled: Boolean,
    val emailVerified: Boolean = true,
    val groups: List<String> = emptyList(),
    @SerialName("requiredActions") val requiredActions: List<String> = emptyList()
)
