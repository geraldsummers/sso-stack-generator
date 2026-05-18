package org.webservices.pipeline.sources.standardized

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.webservices.pipeline.scheduling.RunMetadata
import org.webservices.pipeline.scheduling.RunType
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoeNinjaPriceStandardizedSourceTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `fetches currency and item overview prices`() = runBlocking {
        server = jsonServer(
            "/poe1/api/economy/exchange/current/overview" to """
                {
                  "core": {
                    "items": [
                      {"id": "chaos", "name": "Chaos Orb", "detailsId": "chaos-orb"},
                      {"id": "divine", "name": "Divine Orb", "detailsId": "divine-orb"}
                    ],
                    "primary": "chaos"
                  },
                  "lines": [
                    {"id": "divine", "primaryValue": 694.6, "sparkline": {"totalChange": 0.44}}
                  ]
                }
            """.trimIndent(),
            "/poe1/api/economy/stash/current/item/overview" to """
                {
                  "lines": [
                    {
                      "id": 1715,
                      "name": "Tremor Rod",
                      "baseType": "Military Staff",
                      "variant": "6L",
                      "chaosValue": 7742930,
                      "divineValue": 11147,
                      "count": 2,
                      "listingCount": 9,
                      "detailsId": "tremor-rod-military-staff-6l",
                      "sparkLine": {"totalChange": -3.5}
                    }
                  ]
                }
            """.trimIndent()
        )

        val source = PoeNinjaPriceStandardizedSource(
            baseUrl = serverUrl(),
            leagues = listOf("Standard"),
            currencyTypes = listOf("Currency"),
            itemTypes = listOf("UniqueWeapon"),
            maxEntriesPerType = 10,
            requestDelayMs = 0
        )

        val docs = source.fetchForRun(initialRun()).toList()

        assertEquals("poe_ninja_prices", source.name)
        assertFalse(source.needsChunking())
        assertEquals(2, docs.size)
        assertTrue(docs.any { it.name == "Divine Orb" && it.category == "currency" })
        assertTrue(docs.any { it.name == "Tremor Rod" && it.category == "item" })
        assertEquals("https://poe.ninja/poe1/economy/standard/unique-weapons", docs.last().getMetadata()["url"])
        assertEquals("11147.0", docs.last().getMetadata()["divine_value"])
    }

    @Test
    fun `limits entries per type`() = runBlocking {
        server = jsonServer(
            "/poe1/api/economy/exchange/current/overview" to """{"core":{"items":[]},"lines":[]}""",
            "/poe1/api/economy/stash/current/item/overview" to """
                {"lines":[
                  {"id": 1, "name": "First", "chaosValue": 1},
                  {"id": 2, "name": "Second", "chaosValue": 2}
                ]}
            """.trimIndent()
        )

        val source = PoeNinjaPriceStandardizedSource(
            baseUrl = serverUrl(),
            leagues = listOf("Standard"),
            currencyTypes = emptyList(),
            itemTypes = listOf("UniqueWeapon"),
            maxEntriesPerType = 1,
            requestDelayMs = 0
        )

        val docs = source.fetchForRun(initialRun()).toList()

        assertEquals(1, docs.size)
        assertEquals("First", docs.single().name)
    }

    private fun jsonServer(vararg responses: Pair<String, String>): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val responseMap = responses.toMap()
        httpServer.createContext("/") { exchange ->
            val body = responseMap[exchange.requestURI.path] ?: """{"lines":[]}"""
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return httpServer
    }

    private fun serverUrl(): String =
        "http://127.0.0.1:${server?.address?.port ?: error("server not started")}"

    private fun initialRun(): RunMetadata =
        RunMetadata(RunType.INITIAL_PULL, attemptNumber = 1, isFirstRun = true)
}

