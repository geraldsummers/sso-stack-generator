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

class OpenDotaMatchStandardizedSourceTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `fetches public match summaries as searchable documents`() = runBlocking {
        server = jsonServer(
            "/publicMatches" to """
                [
                  {
                    "match_id": 8815485654,
                    "match_seq_num": 7408718890,
                    "radiant_win": false,
                    "start_time": 1779065940,
                    "duration": 203,
                    "lobby_type": 7,
                    "game_mode": 22,
                    "avg_rank_tier": 64,
                    "cluster": 413,
                    "radiant_team": [21, 138, 93, 75, 60],
                    "dire_team": [41, 88, 36, 25, 84]
                  }
                ]
            """.trimIndent()
        )

        val source = OpenDotaMatchStandardizedSource(
            baseUrl = serverUrl(),
            maxMatches = 10
        )

        val docs = source.fetchForRun(initialRun()).toList()

        assertEquals("opendota_matches", source.name)
        assertFalse(source.needsChunking())
        assertEquals(1, docs.size)
        assertEquals("opendota_match_8815485654", docs.single().getId())
        assertTrue(docs.single().toText().contains("Radiant heroes: 21, 138, 93, 75, 60"))
        assertEquals("22", docs.single().getMetadata()["game_mode"])
        assertEquals("https://www.opendota.com/matches/8815485654", docs.single().getMetadata()["url"])
    }

    @Test
    fun `optionally fetches match detail JSON`() = runBlocking {
        server = jsonServer(
            "/publicMatches" to """[{"match_id": 42, "radiant_team": [], "dire_team": []}]""",
            "/matches/42" to """{"match_id":42,"radiant_score":35,"dire_score":20}"""
        )

        val source = OpenDotaMatchStandardizedSource(
            baseUrl = serverUrl(),
            maxMatches = 1,
            fetchMatchDetails = true
        )

        val doc = source.fetchForRun(initialRun()).toList().single()

        assertTrue(source.needsChunking())
        assertTrue(doc.toText().contains("radiant_score"))
    }

    private fun jsonServer(vararg responses: Pair<String, String>): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        responses.forEach { (path, body) ->
            httpServer.createContext(path) { exchange ->
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        httpServer.start()
        return httpServer
    }

    private fun serverUrl(): String =
        "http://127.0.0.1:${server?.address?.port ?: error("server not started")}"

    private fun initialRun(): RunMetadata =
        RunMetadata(RunType.INITIAL_PULL, attemptNumber = 1, isFirstRun = true)
}

