package org.webservices.chatgptconnector

import kotlin.io.path.createTempDirectory
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectorStoreTest {
    @Test
    fun tokenLifecycleAndAccountCloseRevokesTokens() {
        val dbDir = createTempDirectory("chatgpt-connector-test")
        val store = ConnectorStore(dbDir.resolve("test.sqlite"))

        val account = store.createAccount("alice", "Alice Agent", listOf("search", "fetch"))
        val minted = store.mintToken(account.id, listOf("search"), 3600, "alice")
        assertTrue(Regex("^mcp_[A-Za-z0-9_-]{43}$").matches(minted.token))
        val token = store.tokenByValue(minted.token)
        assertNotNull(token)
        assertEquals("search", token.scopes.single())

        store.closeAccount(account.id, "alice")
        val revoked = store.tokenByValue(minted.token)
        assertNotNull(revoked)
        assertNotNull(revoked.revokedAt)

        store.revokeToken(minted.tokenId, "alice")
        val missing = store.tokenByValue("definitely-invalid")
        assertNull(missing)

        store.close()
    }

    @Test
    fun tokenTtlIsBounded() {
        val dbDir = createTempDirectory("chatgpt-connector-ttl-test")
        val store = ConnectorStore(dbDir.resolve("test.sqlite"))
        val account = store.createAccount("alice", "Alice Agent", listOf("search"))

        val shortToken = store.mintToken(account.id, listOf("search"), 1, "alice")
        val longToken = store.mintToken(account.id, listOf("search"), 999_999_999, "alice")

        val shortTtl = Duration.between(Instant.now(), Instant.parse(shortToken.expiresAt)).seconds
        val longTtl = Duration.between(Instant.now(), Instant.parse(longToken.expiresAt)).seconds
        assertTrue(shortTtl in 1..ConnectorStore.MIN_TOKEN_TTL_SECONDS)
        assertTrue(longTtl in (ConnectorStore.MAX_TOKEN_TTL_SECONDS - 5)..ConnectorStore.MAX_TOKEN_TTL_SECONDS)

        store.close()
    }
}
