package org.webservices.chatgptconnector

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ConnectorStore(databasePath: Path) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")

    init {
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("""
                CREATE TABLE IF NOT EXISTS agent_accounts (
                    id TEXT PRIMARY KEY,
                    owner_username TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    scopes TEXT NOT NULL,
                    keycloak_user_id TEXT,
                    keycloak_username TEXT,
                    created_at TEXT NOT NULL,
                    closed_at TEXT
                )
            """.trimIndent())
            st.execute("""
                CREATE TABLE IF NOT EXISTS agent_tokens (
                    id TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL,
                    token_hash TEXT NOT NULL,
                    token_prefix TEXT NOT NULL,
                    scopes TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    revoked_at TEXT,
                    last_used_at TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(account_id) REFERENCES agent_accounts(id)
                )
            """.trimIndent())
            st.execute("""
                CREATE TABLE IF NOT EXISTS audit_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    actor_username TEXT NOT NULL,
                    account_id TEXT,
                    token_id TEXT,
                    event_type TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """.trimIndent())
        }
    }

    fun createAccount(ownerUsername: String, displayName: String, scopes: List<String>): AgentAccountDto {
        val now = Instant.now().toString()
        val id = UUID.randomUUID().toString()
        val scopeCsv = scopes.joinToString(",")
        connection.prepareStatement(
            "INSERT INTO agent_accounts(id, owner_username, display_name, scopes, created_at) VALUES (?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, ownerUsername)
            ps.setString(3, displayName)
            ps.setString(4, scopeCsv)
            ps.setString(5, now)
            ps.executeUpdate()
        }
        return AgentAccountDto(id, ownerUsername, displayName, null, null, scopes, now, null)
    }

    fun setKeycloakUser(accountId: String, userId: String, username: String) {
        connection.prepareStatement("UPDATE agent_accounts SET keycloak_user_id=?, keycloak_username=? WHERE id=?").use { ps ->
            ps.setString(1, userId)
            ps.setString(2, username)
            ps.setString(3, accountId)
            ps.executeUpdate()
        }
    }

    fun listAccounts(ownerUsername: String): List<AgentAccountDto> =
        connection.prepareStatement("SELECT * FROM agent_accounts WHERE owner_username = ? ORDER BY created_at DESC").use { ps ->
            ps.setString(1, ownerUsername)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AgentAccountDto(
                                id = rs.getString("id"),
                                ownerUsername = rs.getString("owner_username"),
                                displayName = rs.getString("display_name"),
                                keycloakUserId = rs.getString("keycloak_user_id"),
                                keycloakUsername = rs.getString("keycloak_username"),
                                scopes = rs.getString("scopes").split(',').filter { it.isNotBlank() },
                                createdAt = rs.getString("created_at"),
                                closedAt = rs.getString("closed_at")
                            )
                        )
                    }
                }
            }
        }

    fun getAccount(accountId: String): AgentAccountDto? =
        connection.prepareStatement("SELECT * FROM agent_accounts WHERE id=?").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                AgentAccountDto(
                    id = rs.getString("id"),
                    ownerUsername = rs.getString("owner_username"),
                    displayName = rs.getString("display_name"),
                    keycloakUserId = rs.getString("keycloak_user_id"),
                    keycloakUsername = rs.getString("keycloak_username"),
                    scopes = rs.getString("scopes").split(',').filter { it.isNotBlank() },
                    createdAt = rs.getString("created_at"),
                    closedAt = rs.getString("closed_at")
                )
            }
        }

    fun closeAccount(accountId: String, actorUsername: String): AgentAccountDto {
        val now = Instant.now().toString()
        connection.prepareStatement("UPDATE agent_accounts SET closed_at=? WHERE id=? AND closed_at IS NULL").use {
            it.setString(1, now)
            it.setString(2, accountId)
            it.executeUpdate()
        }
        connection.prepareStatement("UPDATE agent_tokens SET revoked_at=COALESCE(revoked_at, ?) WHERE account_id=?").use {
            it.setString(1, now)
            it.setString(2, accountId)
            it.executeUpdate()
        }
        appendAudit(actorUsername, accountId, null, "account.closed", "account closed and tokens revoked")
        return requireNotNull(getAccount(accountId))
    }

    fun mintToken(accountId: String, scopes: List<String>, ttlSeconds: Long, actorUsername: String): MintedTokenResponse {
        val tokenId = UUID.randomUUID().toString()
        val rawToken = "mcp_${Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().toByteArray())}"
        val tokenPrefix = rawToken.take(12)
        val now = Instant.now()
        val expires = now.plusSeconds(ttlSeconds.coerceAtLeast(60))
        val hash = hashToken(rawToken)
        connection.prepareStatement(
            "INSERT INTO agent_tokens(id, account_id, token_hash, token_prefix, scopes, expires_at, created_at) VALUES (?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, tokenId)
            ps.setString(2, accountId)
            ps.setString(3, hash)
            ps.setString(4, tokenPrefix)
            ps.setString(5, scopes.joinToString(","))
            ps.setString(6, expires.toString())
            ps.setString(7, now.toString())
            ps.executeUpdate()
        }
        appendAudit(actorUsername, accountId, tokenId, "token.minted", "token minted with scopes=${scopes.joinToString(",")}")
        return MintedTokenResponse(tokenId, tokenPrefix, rawToken, scopes, expires.toString())
    }

    fun revokeToken(tokenId: String, actorUsername: String): AgentTokenDto? {
        val now = Instant.now().toString()
        connection.prepareStatement("UPDATE agent_tokens SET revoked_at=COALESCE(revoked_at, ?) WHERE id=?").use {
            it.setString(1, now)
            it.setString(2, tokenId)
            it.executeUpdate()
        }
        val token = tokenById(tokenId)
        if (token != null) appendAudit(actorUsername, token.accountId, token.id, "token.revoked", "token revoked")
        return token
    }

    fun tokenByValue(rawToken: String): AgentTokenDto? {
        val hash = hashToken(rawToken)
        return connection.prepareStatement("SELECT * FROM agent_tokens WHERE token_hash=?").use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                AgentTokenDto(
                    id = rs.getString("id"),
                    accountId = rs.getString("account_id"),
                    tokenPrefix = rs.getString("token_prefix"),
                    scopes = rs.getString("scopes").split(',').filter { it.isNotBlank() },
                    expiresAt = rs.getString("expires_at"),
                    revokedAt = rs.getString("revoked_at"),
                    lastUsedAt = rs.getString("last_used_at")
                )
            }
        }
    }

    fun markTokenUsed(tokenId: String) {
        connection.prepareStatement("UPDATE agent_tokens SET last_used_at=? WHERE id=?").use {
            it.setString(1, Instant.now().toString())
            it.setString(2, tokenId)
            it.executeUpdate()
        }
    }

    fun appendAudit(actor: String, accountId: String?, tokenId: String?, eventType: String, detail: String) {
        connection.prepareStatement(
            "INSERT INTO audit_events(actor_username, account_id, token_id, event_type, detail, created_at) VALUES (?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, actor)
            ps.setString(2, accountId)
            ps.setString(3, tokenId)
            ps.setString(4, eventType)
            ps.setString(5, detail.take(512))
            ps.setString(6, Instant.now().toString())
            ps.executeUpdate()
        }
    }

    fun listAudit(ownerUsername: String, limit: Int = 100): List<AuditEventDto> {
        return connection.prepareStatement(
            """
            SELECT ae.*
            FROM audit_events ae
            LEFT JOIN agent_accounts aa ON aa.id = ae.account_id
            WHERE ae.account_id IS NULL OR aa.owner_username = ?
            ORDER BY ae.id DESC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ownerUsername)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AuditEventDto(
                                id = rs.getLong("id"),
                                actorUsername = rs.getString("actor_username"),
                                accountId = rs.getString("account_id"),
                                tokenId = rs.getString("token_id"),
                                eventType = rs.getString("event_type"),
                                detail = rs.getString("detail"),
                                createdAt = rs.getString("created_at")
                            )
                        )
                    }
                }
            }
        }
    }

    fun listTokensForAccount(accountId: String): List<AgentTokenDto> =
        connection.prepareStatement("SELECT * FROM agent_tokens WHERE account_id=? ORDER BY created_at DESC").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AgentTokenDto(
                                id = rs.getString("id"),
                                accountId = rs.getString("account_id"),
                                tokenPrefix = rs.getString("token_prefix"),
                                scopes = rs.getString("scopes").split(',').filter { it.isNotBlank() },
                                expiresAt = rs.getString("expires_at"),
                                revokedAt = rs.getString("revoked_at"),
                                lastUsedAt = rs.getString("last_used_at")
                            )
                        )
                    }
                }
            }
        }

    fun tokenById(tokenId: String): AgentTokenDto? =
        connection.prepareStatement("SELECT * FROM agent_tokens WHERE id=?").use { ps ->
            ps.setString(1, tokenId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                AgentTokenDto(
                    id = rs.getString("id"),
                    accountId = rs.getString("account_id"),
                    tokenPrefix = rs.getString("token_prefix"),
                    scopes = rs.getString("scopes").split(',').filter { it.isNotBlank() },
                    expiresAt = rs.getString("expires_at"),
                    revokedAt = rs.getString("revoked_at"),
                    lastUsedAt = rs.getString("last_used_at")
                )
            }
        }

    private fun hashToken(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        connection.close()
    }
}
