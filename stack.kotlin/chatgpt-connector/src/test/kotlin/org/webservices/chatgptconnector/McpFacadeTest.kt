package org.webservices.chatgptconnector

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class McpFacadeTest {
    @Test
    fun initializeAndToolsList() = runTest {
        val engine = MockEngine { _ ->
            respond("{\"status\":\"ok\"}", HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json"))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val cfg = loadConfig().copy(searchServiceBaseUrl = "https://opensearch:9200/knowledge")
        val store = ConnectorStore(createTempDirectory("mcp-test").resolve("db.sqlite"))
        val facade = McpFacade(store, cfg, client)
        val token = AgentTokenDto("t", "a", "mcp_abc", listOf("*"), "2999-01-01T00:00:00Z", null, null)

        val init = facade.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", token)
        assertNotNull(init.result)

        val list = facade.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", token)
        assertNotNull(list.result)
        assertEquals(JsonPrimitive(2), list.id)

        client.close()
        store.close()
    }
}
