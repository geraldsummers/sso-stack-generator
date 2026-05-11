package org.webservices.chatgptconnector

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

interface KeycloakAdmin {
    suspend fun createAgentUser(ownerUsername: String, accountId: String, scopes: List<String>): Pair<String, String>
    suspend fun disableUser(userId: String)
}

class KeycloakAdminClient(
    private val config: ConnectorConfig,
    private val httpClient: HttpClient
) : KeycloakAdmin {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createAgentUser(ownerUsername: String, accountId: String, scopes: List<String>): Pair<String, String> {
        val token = adminToken()
        val username = "${ownerUsername}_${config.keycloakAgentUserPrefix}_${accountId.take(8)}"
        val body = KeycloakUserRepresentation(
            username = username,
            enabled = true,
            groups = listOf("/agents")
        )
        val createResponse = httpClient.post("${config.keycloakBaseUrl}/admin/realms/${config.keycloakRealm}/users") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (createResponse.status !in setOf(HttpStatusCode.Created, HttpStatusCode.Conflict)) {
            throw IllegalStateException("Keycloak user create failed with ${createResponse.status}: ${createResponse.bodyAsText()}")
        }

        val searchResponse = httpClient.get("${config.keycloakBaseUrl}/admin/realms/${config.keycloakRealm}/users?username=$username") {
            header("Authorization", "Bearer $token")
        }
        if (searchResponse.status != HttpStatusCode.OK) {
            throw IllegalStateException("Keycloak user lookup failed with ${searchResponse.status}: ${searchResponse.bodyAsText()}")
        }
        val payload = searchResponse.body<String>()
        val first = json.parseToJsonElement(payload).jsonArray.firstOrNull()?.jsonObject
        val userId = first?.get("id")?.toString()?.trim('"')
            ?: throw IllegalStateException("Keycloak user lookup returned no user for $username")
        return userId to username
    }

    override suspend fun disableUser(userId: String) {
        val token = adminToken()
        val response = httpClient.put("${config.keycloakBaseUrl}/admin/realms/${config.keycloakRealm}/users/$userId") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to false))
        }
        if (response.status !in setOf(HttpStatusCode.NoContent, HttpStatusCode.OK)) {
            throw IllegalStateException("Keycloak user disable failed with ${response.status}: ${response.bodyAsText()}")
        }
    }

    private suspend fun adminToken(): String {
        require(config.keycloakAdminUsername.isNotBlank()) { "Keycloak admin username is required" }
        require(config.keycloakAdminPassword.isNotBlank()) { "Keycloak admin password is required" }
        val response = httpClient.submitForm(
            url = "${config.keycloakBaseUrl}/realms/master/protocol/openid-connect/token",
            formParameters = Parameters.build {
                append("grant_type", "password")
                append("client_id", config.keycloakAdminClientId)
                if (config.keycloakAdminClientSecret.isNotBlank()) append("client_secret", config.keycloakAdminClientSecret)
                append("username", config.keycloakAdminUsername)
                append("password", config.keycloakAdminPassword)
            }
        )
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Keycloak admin token request failed with ${response.status}: ${response.bodyAsText()}")
        }
        return json.parseToJsonElement(response.body<String>()).jsonObject["access_token"]?.toString()?.trim('"')
            ?: throw IllegalStateException("Keycloak admin token response did not include access_token")
    }
}

@Serializable
private data class TokenResponse(val access_token: String)
