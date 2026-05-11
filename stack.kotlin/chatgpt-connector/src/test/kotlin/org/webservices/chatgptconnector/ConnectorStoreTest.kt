package org.webservices.chatgptconnector

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConnectorStoreTest {
    @Test
    fun tokenLifecycleAndAccountCloseRevokesTokens() {
        val dbDir = createTempDirectory("chatgpt-connector-test")
        val store = ConnectorStore(dbDir.resolve("test.sqlite"))

        val account = store.createAccount("alice", "Alice Agent", listOf("search", "fetch"))
        val minted = store.mintToken(account.id, listOf("search"), 3600, "alice")
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
}
