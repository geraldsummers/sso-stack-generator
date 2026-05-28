package org.webservices.searchservice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.webservices.pipeline.core.PresentationMetadataKeys
import org.webservices.pipeline.core.PresentationTargets
import java.io.IOException
import java.sql.SQLException

class SearchGatewayVectorTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterEach
    fun tearDown() {
        servers.forEach { it.shutdown() }
        servers.clear()
    }

    @Test
    fun `search rejects unknown mode before invoking backends`() {
        val gateway = gateway()

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                gateway.search("query", listOf("docs"), mode = "invalid", limit = 5)
            }
        }

        assertTrue(error.message!!.contains("Unknown search mode"))
    }

    @Test
    fun `vector search parses qdrant payload and forwards api key`() {
        val embeddingServer = server()
        val qdrantServer = server()
        embeddingServer.enqueue(MockResponse().setResponseCode(200).setBody("""[[0.1, 0.2, 0.3]]"""))
        qdrantServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "result": [
                    {
                      "id": 42,
                      "score": 0.91,
                      "payload": {
                        "document_id": "market-doc-1",
                        "title": "Market Overview",
                        "content": "Detailed market analysis content",
                        "presentation_url": "https://grafana.example/d/market-home",
                        "timeseries": true,
                        "count": 5,
                        "nullable": null
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val gateway = gateway(
            qdrantUrl = "${qdrantServer.hostName}:${qdrantServer.port}",
            qdrantApiKey = "secret-key",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val results = runBlocking {
            gateway.search("market overview", listOf("market_data"), mode = "vector", limit = 5)
        }

        assertEquals(1, results.size)
        val result = results.single()
        assertEquals("market-doc-1", result.id)
        assertEquals("market-doc-1", result.metadata["document_id"])
        assertEquals("https://grafana.example/d/market-home", result.url)
        assertEquals("Market Overview", result.title)
        assertEquals("grafana", result.contentType)
        assertEquals("vector", result.metadata["type"])
        assertEquals("5", result.metadata["count"])
        assertEquals("true", result.metadata["timeseries"])
        assertEquals("", result.metadata["nullable"])
        assertTrue(result.capabilities["hasTimeSeries"] == true)
        assertTrue(result.capabilities["isInteractive"] == true)

        val embeddingRequest = embeddingServer.takeRequest()
        assertEquals("/embed", embeddingRequest.path)
        assertTrue(embeddingRequest.body.readUtf8().contains("market overview"))

        val qdrantRequest = qdrantServer.takeRequest()
        assertEquals("/collections/market_data/points/search", qdrantRequest.path)
        assertEquals("secret-key", qdrantRequest.getHeader("api-key"))
        assertTrue(qdrantRequest.body.readUtf8().contains("\"with_payload\""))
    }

    @Test
    fun `vector search exposes qdrant point id when document id payload is absent`() {
        val embeddingServer = server()
        val qdrantServer = server()
        embeddingServer.enqueue(MockResponse().setResponseCode(200).setBody("""[[0.1, 0.2, 0.3]]"""))
        qdrantServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "result": [
                    {
                      "id": "legacy-point-1",
                      "score": 0.66,
                      "payload": {
                        "title": "Legacy Vector",
                        "content": "Legacy vector without document id",
                        "url": "https://docs.example/legacy"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val gateway = gateway(
            qdrantUrl = "${qdrantServer.hostName}:${qdrantServer.port}",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val results = runBlocking {
            gateway.search("legacy", listOf("docs"), mode = "vector", limit = 5)
        }

        assertEquals("legacy-point-1", results.single().id)
    }

    @Test
    fun `vector search fails when every qdrant collection errors`() {
        val embeddingServer = server()
        val qdrantServer = server()
        embeddingServer.enqueue(MockResponse().setResponseCode(200).setBody("""[[0.1, 0.2, 0.3]]"""))
        qdrantServer.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val gateway = gateway(
            qdrantUrl = "${qdrantServer.hostName}:${qdrantServer.port}",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                gateway.search("query", listOf("docs"), mode = "vector", limit = 5)
            }
        }

        assertTrue(error.message!!.contains("Vector search failed for all requested collections"))
        assertEquals(1, qdrantServer.requestCount)
    }

    @Test
    fun `vector search fails when embedding service returns empty payload`() {
        val embeddingServer = server()
        embeddingServer.enqueue(MockResponse().setResponseCode(200).setBody("""[]"""))

        val gateway = gateway(
            qdrantUrl = "127.0.0.1:6553",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val error = assertThrows(Exception::class.java) {
            runBlocking {
                gateway.search("query", listOf("docs"), mode = "vector", limit = 5)
            }
        }

        assertTrue(error.message!!.contains("Empty embedding response"))
    }

    @Test
    fun `vector search retries embedding overload before succeeding`() {
        val embeddingServer = server()
        val qdrantServer = server()
        embeddingServer.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"overloaded"}"""))
        embeddingServer.enqueue(MockResponse().setResponseCode(200).setBody("""[[0.1, 0.2, 0.3]]"""))
        qdrantServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "result": [
                    {
                      "score": 0.77,
                      "payload": {
                        "title": "Recovered Result",
                        "content": "Recovered after retry",
                        "url": "https://docs.example/recovered"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val gateway = gateway(
            qdrantUrl = "${qdrantServer.hostName}:${qdrantServer.port}",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val results = runBlocking {
            gateway.search("retry me", listOf("docs"), mode = "vector", limit = 5)
        }

        assertEquals(1, results.size)
        assertEquals("Recovered Result", results.single().title)
        assertEquals(2, embeddingServer.requestCount)
    }

    @Test
    fun `vector search surfaces embedding overload as IO failure after retries`() {
        val embeddingServer = server()
        repeat(3) {
            embeddingServer.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"overloaded","secret":"do-not-leak"}"""))
        }

        val gateway = gateway(
            qdrantUrl = "127.0.0.1:6553",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                gateway.search("still overloaded", listOf("docs"), mode = "vector", limit = 5)
            }
        }

        assertTrue(error.message!!.contains("Embedding service error: 429"))
        assertFalse(error.message!!.contains("do-not-leak"))
        assertEquals(3, embeddingServer.requestCount)
    }

    @Test
    fun `vector search with zero limit short circuits qdrant request`() {
        val embeddingServer = server()
        val qdrantServer = server()
        embeddingServer.enqueue(MockResponse().setResponseCode(200).setBody("""[[0.1, 0.2, 0.3]]"""))

        val gateway = gateway(
            qdrantUrl = "${qdrantServer.hostName}:${qdrantServer.port}",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val results = runBlocking {
            gateway.search("query", listOf("docs"), mode = "vector", limit = 0)
        }

        assertTrue(results.isEmpty())
        assertEquals(0, qdrantServer.requestCount)
    }

    @Test
    fun `hybrid search returns vector results when full text backend fails`() {
        val embeddingServer = server()
        val qdrantServer = server()
        embeddingServer.enqueue(MockResponse().setResponseCode(200).setBody("""[[0.1, 0.2, 0.3]]"""))
        qdrantServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "result": [
                    {
                      "score": 0.8,
                      "payload": {
                        "title": "Docs Result",
                        "content": "Vector content",
                        "url": "https://docs.example/item"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val gateway = gateway(
            qdrantUrl = "${qdrantServer.hostName}:${qdrantServer.port}",
            postgresJdbcUrl = "jdbc:postgresql://localhost:1/webservices?connectTimeout=1&socketTimeout=1",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val results = runBlocking {
            gateway.search("docs", listOf("docs"), mode = "hybrid", limit = 4)
        }

        assertEquals(1, results.size)
        assertEquals("Docs Result", results.single().title)
        assertTrue(results.single().score > 0.0)
    }

    @Test
    fun `hybrid search with limit one still queries each backend with a usable limit`() {
        val embeddingServer = server()
        val qdrantServer = server()
        embeddingServer.enqueue(MockResponse().setResponseCode(200).setBody("""[[0.1, 0.2, 0.3]]"""))
        qdrantServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "result": [
                    {
                      "score": 0.8,
                      "payload": {
                        "title": "Limit One Result",
                        "content": "Vector content",
                        "url": "https://docs.example/limit-one"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val gateway = gateway(
            qdrantUrl = "${qdrantServer.hostName}:${qdrantServer.port}",
            postgresJdbcUrl = "jdbc:postgresql://localhost:1/webservices?connectTimeout=1&socketTimeout=1",
            embeddingServiceUrl = embeddingServer.url("/").toString().trimEnd('/')
        )

        val results = runBlocking {
            gateway.search("docs", listOf("docs"), mode = "hybrid", limit = 1)
        }

        assertEquals(1, results.size)
        assertEquals("Limit One Result", results.single().title)
        assertEquals(1, qdrantServer.requestCount)
        assertTrue(qdrantServer.takeRequest().body.readUtf8().contains("\"limit\":1"))
    }

    @Test
    fun `bm25 and fulltext modes fail when every postgres collection fails`() {
        val gateway = gateway(postgresJdbcUrl = "jdbc:postgresql://localhost:1/webservices?connectTimeout=1&socketTimeout=1")

        val bm25Error = assertThrows(SQLException::class.java) {
            runBlocking {
                gateway.search("query", listOf("docs"), mode = "bm25", limit = 5)
            }
        }
        val fulltextError = assertThrows(SQLException::class.java) {
            runBlocking {
                gateway.search("query", listOf("docs"), mode = "fulltext", limit = 5)
            }
        }

        assertTrue(bm25Error.message!!.contains("PostgreSQL search failed for all requested collections"))
        assertTrue(fulltextError.message!!.contains("PostgreSQL search failed for all requested collections"))
    }

    @Test
    fun `preferred URL helpers honor priority and skipped bookstack rules`() {
        val gateway = gateway()

        val preferredPresentation = invoke<String>(
            gateway,
            "preferredResultUrl",
            mapOf(
                PresentationMetadataKeys.URL to "https://app.example/item",
                "bookstack_url" to "https://bookstack.example/books/item",
                "url" to "https://canonical.example/item",
                "link" to "https://link.example/item"
            )
        )
        assertEquals("https://app.example/item", preferredPresentation)

        val preferredBookStack = invoke<String>(
            gateway,
            "preferredResultUrl",
            mapOf(
                PresentationMetadataKeys.URL to "skipped://source-filter/rss",
                "bookstack_url" to "https://bookstack.example/books/item",
                "url" to "https://canonical.example/item"
            )
        )
        assertEquals("https://bookstack.example/books/item", preferredBookStack)

        val derivedGrafana = invoke<String>(
            gateway,
            "preferredResultUrl",
            mapOf(PresentationMetadataKeys.GRAFANA_PATH to "/d/ops-overview")
        )
        assertEquals("https://grafana.webservices.net/d/ops-overview", derivedGrafana)

        val fallbackRawValue = invoke<String>(
            gateway,
            "preferredResultUrl",
            mapOf("url" to "relative/path")
        )
        assertEquals("relative/path", fallbackRawValue)
    }

    @Test
    fun `presentation target and direct metadata rules are detected`() {
        val gateway = gateway()

        assertEquals(
            PresentationTargets.GRAFANA,
            invoke<String>(
                gateway,
                "preferredPresentationTarget",
                "docs",
                mapOf(PresentationMetadataKeys.TARGET to "GRAFANA"),
                ""
            )
        )
        assertEquals(
            PresentationTargets.BOOKSTACK,
            invoke<String>(
                gateway,
                "preferredPresentationTarget",
                "docs",
                mapOf("bookstack_url" to "https://bookstack.example/books/item"),
                ""
            )
        )
        assertEquals(
            PresentationTargets.GRAFANA,
            invoke<String>(
                gateway,
                "preferredPresentationTarget",
                "metrics",
                mapOf(PresentationMetadataKeys.GRAFANA_PATH to "/d/ops"),
                ""
            )
        )
        assertEquals(
            PresentationTargets.GRAFANA,
            invoke<String>(
                gateway,
                "preferredPresentationTarget",
                "market_data",
                emptyMap<String, String>(),
                ""
            )
        )
        assertEquals(
            null,
            invoke<String?>(
                gateway,
                "preferredPresentationTarget",
                "docs",
                emptyMap<String, String>(),
                "https://example.com/docs"
            )
        )

        assertTrue(invoke(gateway, "collectionSupportsDirectPresentationMetadata", " test-market ") as Boolean)
        assertTrue(invoke(gateway, "collectionSupportsDirectPresentationMetadata", "grafana_dashboards") as Boolean)
        assertFalse(invoke(gateway, "collectionSupportsDirectPresentationMetadata", "docs") as Boolean)
        assertFalse(invoke(gateway, "collectionRequiresPresentation", "wikipedia") as Boolean)
        assertFalse(invoke(gateway, "collectionRequiresPresentation", "australian_laws") as Boolean)
        assertTrue(invoke(gateway, "collectionRequiresPresentation", "opendota_matches") as Boolean)
    }

    @Test
    fun `identity key prefers stable identifiers and classifiers handle edge cases`() {
        val gateway = gateway()

        assertEquals(
            "doc-1",
            invoke<String>(
                gateway,
                "identityKey",
                "docs",
                "Title",
                "https://ignored.example",
                mapOf("document_id" to "doc-1", "url" to "https://metadata.example")
            )
        )
        assertEquals(
            "https://metadata.example",
            invoke<String>(
                gateway,
                "identityKey",
                "docs",
                "Title",
                "https://ignored.example",
                mapOf("url" to "https://metadata.example")
            )
        )
        assertEquals(
            "https://link.example",
            invoke<String>(
                gateway,
                "identityKey",
                "docs",
                "Title",
                "",
                mapOf("link" to "https://link.example")
            )
        )
        assertEquals(
            "https://docs.example/item",
            invoke<String>(
                gateway,
                "identityKey",
                "docs",
                "Title",
                "https://docs.example/item",
                emptyMap<String, String>()
            )
        )
        assertEquals(
            "docs::Title",
            invoke<String>(
                gateway,
                "identityKey",
                "docs",
                "Title",
                "https://bookstack.example/books/item",
                emptyMap<String, String>()
            )
        )

        assertEquals("alpha beta v2", invoke<String>(gateway, "normalizeSearchText", "  Alpha/Beta_v2!!  "))
        assertTrue(invoke(gateway, "isHttpUrl", "https://example.com") as Boolean)
        assertFalse(invoke(gateway, "isHttpUrl", null) as Boolean)
        assertTrue(invoke(gateway, "isSkippedBookStackUrl", "skipped://source-filter/rss") as Boolean)
        assertTrue(invoke(gateway, "isBookStackUrl", "https://docs.example/books/item") as Boolean)
        assertFalse(invoke(gateway, "isBookStackUrl", "books/item") as Boolean)
        assertTrue(invoke(gateway, "isGrafanaUrl", "https://grafana.example/d/test") as Boolean)
        assertTrue(invoke(gateway, "isGrafanaUrl", "https://dashboards.example/explore?left=x") as Boolean)
        assertFalse(invoke(gateway, "isGrafanaUrl", "https://docs.example/item") as Boolean)
    }

    @Test
    fun `derive grafana URL supports explicit values path and dashboard metadata`() {
        val gateway = gateway()

        assertEquals(
            "https://grafana.example/d/direct",
            invoke<String>(
                gateway,
                "deriveGrafanaUrl",
                mapOf(PresentationMetadataKeys.GRAFANA_URL to "https://grafana.example/d/direct")
            )
        )
        assertEquals(
            "https://grafana.example/d/from-presentation",
            invoke<String>(
                gateway,
                "deriveGrafanaUrl",
                mapOf(PresentationMetadataKeys.URL to "https://grafana.example/d/from-presentation")
            )
        )
        assertEquals(
            "https://grafana.webservices.net/d/ops",
            invoke<String>(
                gateway,
                "deriveGrafanaUrl",
                mapOf(PresentationMetadataKeys.GRAFANA_PATH to "d/ops")
            )
        )
        assertEquals(
            "https://grafana.example/d/absolute",
            invoke<String>(
                gateway,
                "deriveGrafanaUrl",
                mapOf(PresentationMetadataKeys.GRAFANA_PATH to "https://grafana.example/d/absolute")
            )
        )

        val dashboardUrl = invoke<String>(
            gateway,
            "deriveGrafanaUrl",
            mapOf(
                PresentationMetadataKeys.GRAFANA_DASHBOARD_UID to "ops-home",
                PresentationMetadataKeys.GRAFANA_DASHBOARD_SLUG to "ops home",
                PresentationMetadataKeys.GRAFANA_ORG_ID to "1",
                PresentationMetadataKeys.GRAFANA_FROM to "now-6h",
                PresentationMetadataKeys.GRAFANA_TO to "now",
                PresentationMetadataKeys.GRAFANA_PANEL_ID to "7"
            )
        )
        assertNotNull(dashboardUrl)
        assertTrue(dashboardUrl!!.startsWith("https://grafana.webservices.net/d/ops-home/ops home?"))
        assertTrue(dashboardUrl.contains("orgId=1"))
        assertTrue(dashboardUrl.contains("from=now-6h"))
        assertTrue(dashboardUrl.contains("to=now"))
        assertTrue(dashboardUrl.contains("viewPanel=7"))

        assertEquals(null, invoke<String?>(gateway, "deriveGrafanaUrl", emptyMap<String, String>()))
    }

    @Test
    fun `metadata enrichment derives wikipedia URLs and fallback titles`() {
        val gateway = gateway()

        val wikiMetadata = invoke<Map<String, String>>(
            gateway,
            "enrichMetadata",
            "wikipedia",
            mapOf("title" to "Open Search"),
            "",
            ""
        )
        assertEquals("Open Search", wikiMetadata["title"])
        assertEquals("https://en.wikipedia.org/wiki/Open_Search", wikiMetadata["url"])

        val fallback = invoke<String>(
            gateway,
            "titleOrFallback",
            mapOf("cveId" to "CVE-2026-0001")
        )
        assertEquals("CVE-2026-0001", fallback)
    }

    @Test
    fun `merge results falls back through candidate and existing URLs`() {
        val gateway = gateway()

        val candidateBookStack = invoke<SearchResult>(
            gateway,
            "mergeResults",
            result(url = "", title = "A", snippet = "old", metadata = emptyMap()),
            result(url = "https://bookstack.example/books/a", title = "AA", snippet = "new", metadata = emptyMap())
        )
        assertEquals("https://bookstack.example/books/a", candidateBookStack.url)
        assertEquals("AA", candidateBookStack.title)
        assertEquals("new", candidateBookStack.snippet)

        val existingBookStack = invoke<SearchResult>(
            gateway,
            "mergeResults",
            result(url = "https://bookstack.example/books/existing", title = "A", snippet = "existing", metadata = emptyMap()),
            result(url = "", title = "B", snippet = "new", metadata = emptyMap())
        )
        assertEquals("https://bookstack.example/books/existing", existingBookStack.url)

        val candidateUrl = invoke<SearchResult>(
            gateway,
            "mergeResults",
            result(url = "", title = "A", snippet = "existing", metadata = emptyMap()),
            result(url = "https://docs.example/candidate", title = "B", snippet = "new", metadata = emptyMap())
        )
        assertEquals("https://docs.example/candidate", candidateUrl.url)

        val existingUrl = invoke<SearchResult>(
            gateway,
            "mergeResults",
            result(url = "https://docs.example/existing", title = "A", snippet = "existing", metadata = emptyMap()),
            result(url = "", title = "", snippet = "", metadata = emptyMap())
        )
        assertEquals("https://docs.example/existing", existingUrl.url)
    }

    @Test
    fun `rerank deduplicates matches merges metadata and boosts exact title match`() {
        val gateway = gateway()

        val vectorResults = listOf(
            result(
                url = "",
                title = "Alpha Document",
                snippet = "short",
                metadata = mapOf("document_id" to "1", "url" to "https://docs.example/alpha")
            ),
            result(
                url = "https://docs.example/guide",
                title = "The Alpha Document Guide",
                snippet = "guide",
                metadata = mapOf("document_id" to "2")
            )
        )
        val fulltextResults = listOf(
            result(
                url = "https://bookstack.example/books/alpha",
                title = "Alpha",
                snippet = "much longer snippet for alpha document",
                metadata = mapOf("document_id" to "1", "bookstack_url" to "https://bookstack.example/books/alpha")
            ),
            result(
                url = "https://docs.example/guide",
                title = "The Alpha Document Guide",
                snippet = "guide snippet",
                metadata = mapOf("document_id" to "2")
            )
        )

        val reranked = invoke<List<SearchResult>>(
            gateway,
            "rerank",
            "Alpha Document",
            vectorResults,
            fulltextResults,
            10
        )

        assertEquals(2, reranked.size)
        assertEquals("1", reranked[0].id)
        assertEquals("https://bookstack.example/books/alpha", reranked[0].url)
        assertEquals("Alpha Document", reranked[0].title)
        assertEquals("much longer snippet for alpha document", reranked[0].snippet)
        assertEquals("bookstack", reranked[0].contentType)
        assertTrue(reranked[0].score > reranked[1].score)
    }

    private fun gateway(
        qdrantUrl: String = "127.0.0.1:6553",
        qdrantApiKey: String = "",
        postgresJdbcUrl: String = "jdbc:postgresql://localhost:1/webservices?connectTimeout=1&socketTimeout=1",
        embeddingServiceUrl: String = "http://127.0.0.1:6554"
    ): SearchGateway {
        return SearchGateway(
            qdrantUrl = qdrantUrl,
            qdrantApiKey = qdrantApiKey,
            postgresJdbcUrl = postgresJdbcUrl,
            embeddingServiceUrl = embeddingServiceUrl
        )
    }

    private fun server(): MockWebServer {
        return MockWebServer().also {
            it.start()
            servers += it
        }
    }

    private fun result(
        url: String,
        title: String,
        snippet: String,
        metadata: Map<String, String>,
        source: String = "docs"
    ): SearchResult {
        return SearchResult(
            url = url,
            title = title,
            snippet = snippet,
            score = 1.0,
            source = source,
            metadata = metadata,
            contentType = SearchResult.inferContentType(source, url, metadata),
            capabilities = SearchResult.inferCapabilities(source, url, metadata)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invoke(target: Any, methodName: String, vararg args: Any?): T {
        val method = target.javaClass.declaredMethods.first {
            !it.isSynthetic && it.name == methodName && it.parameterCount == args.size
        }
        method.isAccessible = true
        return method.invoke(target, *args) as T
    }
}
