package org.webservices.workspaceprovisioner

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

class Authenticator(
    private val config: WorkspaceProvisionerConfig,
    private val httpClient: HttpClient
) {
    private val workspaceTokenCodec = WorkspaceAgentTokenCodec(
        sharedSecret = config.agentTokenSecret,
        ttlSeconds = config.agentTokenTtlSeconds
    )

    suspend fun authenticate(call: ApplicationCall): PrincipalIdentity? {
        val headerUser = call.request.header("Remote-User")?.trim()
            ?: call.request.header("X-Remote-User")?.trim()
            ?: ""
        if (headerUser.isNotBlank() && hasTrustedProxySecret(call)) {
            val groupsHeader = call.request.header("Remote-Groups")
                ?: call.request.header("X-Remote-Groups")
            val groups = groupsHeader
                ?.split(',', ';')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            return PrincipalIdentity(
                username = headerUser,
                email = call.request.header("Remote-Email")
                    ?: call.request.header("X-Remote-Email"),
                groups = groups,
                source = "forward_auth"
            )
        }
        val authHeader = call.request.headers[HttpHeaders.Authorization] ?: return null
        if (!authHeader.startsWith("Bearer ")) return null
        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isBlank()) return null
        workspaceTokenCodec.decode(token)?.let { claims ->
            return PrincipalIdentity(
                username = claims.ownerUsername,
                source = "workspace_agent_token",
                subjectKind = "workspace_agent",
                workspaceId = claims.workspaceId,
                scopes = claims.scopes
            )
        }
        return validateBearer(token)
    }

    private suspend fun validateBearer(token: String): PrincipalIdentity? {
        val response = httpClient.get("${config.oidcBaseUrl}/protocol/openid-connect/userinfo") {
            bearerAuth(token)
        }
        if (response.status != HttpStatusCode.OK) return null
        val userInfo = response.body<UserInfoResponse>()
        val username = userInfo.preferredUsername?.takeIf { it.isNotBlank() }
            ?: userInfo.sub.substringBefore('@').ifBlank { userInfo.sub }
        return PrincipalIdentity(
            username = username,
            email = userInfo.email,
            groups = userInfo.groups.orEmpty(),
            source = "oidc_bearer"
        )
    }

    private fun hasTrustedProxySecret(call: ApplicationCall): Boolean {
        val configured = config.trustedProxySecret ?: return false
        val provided = call.request.header("X-Trusted-Proxy-Secret")?.trim()
        return provided != null && MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            configured.toByteArray(StandardCharsets.UTF_8)
        )
    }
}

@Serializable
private data class UserInfoResponse(
    val sub: String,
    val email: String? = null,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    val groups: List<String>? = null
)
