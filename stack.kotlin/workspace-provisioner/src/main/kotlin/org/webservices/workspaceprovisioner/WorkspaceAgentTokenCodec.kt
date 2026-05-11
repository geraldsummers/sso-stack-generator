package org.webservices.workspaceprovisioner

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val WORKSPACE_AGENT_TOKEN_PREFIX = "wst_"
private const val WORKSPACE_AGENT_TOKEN_VERSION = "v1"
private val WORKSPACE_AGENT_DEFAULT_SCOPES = listOf("knowledge:search", "knowledge:document")

@Serializable
private data class WorkspaceAgentTokenPayload(
    val version: String,
    val workspaceId: String,
    val ownerUsername: String,
    val scopes: List<String>,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long
)

data class WorkspaceAgentTokenClaims(
    val workspaceId: String,
    val ownerUsername: String,
    val scopes: List<String>,
    val issuedAt: Instant,
    val expiresAt: Instant
)

data class WorkspaceAgentToken(
    val value: String,
    val expiresAt: Instant,
    val scopes: List<String>
)

class WorkspaceAgentTokenCodec(
    private val sharedSecret: String,
    private val ttlSeconds: Long
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val signingKey = SecretKeySpec(sharedSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")

    init {
        require(sharedSecret.isNotBlank()) { "workspace agent token secret must not be blank" }
        require(ttlSeconds > 0) { "workspace agent token ttl must be positive" }
    }

    fun issue(record: WorkspaceRecord, now: Instant = Instant.now()): WorkspaceAgentToken {
        val leaseExpiry = runCatching { Instant.parse(record.leaseExpiresAt) }.getOrElse { now.plusSeconds(ttlSeconds) }
        val expiresAt = minOf(leaseExpiry, now.plusSeconds(ttlSeconds))
        val payload = WorkspaceAgentTokenPayload(
            version = WORKSPACE_AGENT_TOKEN_VERSION,
            workspaceId = record.id,
            ownerUsername = record.ownerUsername,
            scopes = WORKSPACE_AGENT_DEFAULT_SCOPES,
            issuedAtEpochSeconds = now.epochSecond,
            expiresAtEpochSeconds = expiresAt.epochSecond
        )
        val encodedPayload = base64.encodeToString(json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8))
        val signature = sign(encodedPayload)
        return WorkspaceAgentToken(
            value = "$WORKSPACE_AGENT_TOKEN_PREFIX$encodedPayload.$signature",
            expiresAt = expiresAt,
            scopes = payload.scopes
        )
    }

    fun decode(token: String, now: Instant = Instant.now()): WorkspaceAgentTokenClaims? {
        if (!token.startsWith(WORKSPACE_AGENT_TOKEN_PREFIX)) {
            return null
        }
        val compact = token.removePrefix(WORKSPACE_AGENT_TOKEN_PREFIX)
        val separator = compact.lastIndexOf('.')
        if (separator <= 0 || separator == compact.lastIndex) {
            return null
        }
        val encodedPayload = compact.substring(0, separator)
        val suppliedSignature = compact.substring(separator + 1)
        val expectedSignature = sign(encodedPayload)
        if (!MessageDigest.isEqual(
                suppliedSignature.toByteArray(StandardCharsets.UTF_8),
                expectedSignature.toByteArray(StandardCharsets.UTF_8)
            )
        ) {
            return null
        }

        val payload = runCatching {
            val rawPayload = String(decoder.decode(encodedPayload), StandardCharsets.UTF_8)
            json.decodeFromString<WorkspaceAgentTokenPayload>(rawPayload)
        }.getOrNull() ?: return null

        if (payload.version != WORKSPACE_AGENT_TOKEN_VERSION) {
            return null
        }

        val expiresAt = Instant.ofEpochSecond(payload.expiresAtEpochSeconds)
        if (!expiresAt.isAfter(now)) {
            return null
        }

        return WorkspaceAgentTokenClaims(
            workspaceId = payload.workspaceId,
            ownerUsername = payload.ownerUsername,
            scopes = payload.scopes,
            issuedAt = Instant.ofEpochSecond(payload.issuedAtEpochSeconds),
            expiresAt = expiresAt
        )
    }

    private fun sign(encodedPayload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(signingKey)
        val signature = mac.doFinal(encodedPayload.toByteArray(StandardCharsets.UTF_8))
        return base64.encodeToString(signature)
    }
}
