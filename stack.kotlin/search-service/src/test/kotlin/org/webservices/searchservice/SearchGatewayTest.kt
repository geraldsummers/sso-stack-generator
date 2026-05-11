package org.webservices.searchservice

import org.webservices.pipeline.core.PresentationMetadataKeys
import org.webservices.pipeline.core.PresentationTargets
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test


class SearchGatewayTest {

    @Test
    fun `SearchResult inferContentType identifies articles`() {
        val type = SearchResult.inferContentType("rss_feeds", "http://news.com", emptyMap())
        assertEquals("article", type)
    }

    @Test
    fun `SearchResult inferContentType identifies wikipedia`() {
        val type = SearchResult.inferContentType("wiki", "http://example.com", emptyMap())
        assertEquals("documentation", type)
    }

    @Test
    fun `SearchResult inferContentType identifies CVE`() {
        val type = SearchResult.inferContentType("cve_database", "http://example.com", emptyMap())
        assertEquals("vulnerability", type)
    }

    @Test
    fun `SearchResult inferContentType identifies legal`() {
        val type = SearchResult.inferContentType("legal_docs", "http://example.com", emptyMap())
        assertEquals("legal", type)
    }

    @Test
    fun `SearchResult inferContentType identifies code`() {
        val type = SearchResult.inferContentType("code_repo", "http://github.com", emptyMap())
        assertEquals("code", type)
    }

    @Test
    fun `SearchResult inferContentType defaults to document`() {
        val type = SearchResult.inferContentType("unknown_source", "http://example.com", emptyMap())
        assertEquals("document", type)
    }

    @Test
    fun `SearchResult inferContentType identifies grafana presentation targets`() {
        val type = SearchResult.inferContentType(
            "market_data",
            "https://grafana.webservices.net/d/market-home/market-home",
            mapOf(PresentationMetadataKeys.TARGET to PresentationTargets.GRAFANA)
        )
        assertEquals("grafana", type)
    }

    @Test
    fun `SearchResult inferCapabilities identifies time series grafana results`() {
        val caps = SearchResult.inferCapabilities(
            "market_data",
            "https://grafana.webservices.net/d/market-home/market-home",
            mapOf(PresentationMetadataKeys.TARGET to PresentationTargets.GRAFANA)
        )

        assertTrue(caps["hasTimeSeries"] == true)
        assertTrue(caps["isInteractive"] == true)
        assertTrue(caps["humanFriendly"] == true)
    }

    @Test
    fun `SearchResult inferCapabilities identifies BookStack results as interactive`() {
        val caps = SearchResult.inferCapabilities(
            "wikipedia",
            "https://bookstack.webservices.net/books/wikipedia/page/example",
            mapOf("bookstack_url" to "https://bookstack.webservices.net/books/wikipedia/page/example")
        )

        assertTrue(caps["humanFriendly"] == true)
        assertTrue(caps["agentFriendly"] == true)
        assertTrue(caps["hasRichContent"] == true)
        assertTrue(caps["isInteractive"] == true)
        assertTrue(caps["isStructured"] == true)
        assertTrue(caps["hasTimeSeries"] == false)
    }

    @Test
    fun `SearchResult inferCapabilities for URL with hasRichContent`() {
        val caps = SearchResult.inferCapabilities("test", "http://example.com", emptyMap())

        assertTrue(caps["hasRichContent"] == true)
    }

    @Test
    fun `SearchResult inferCapabilities for URL without URL`() {
        val caps = SearchResult.inferCapabilities("test", "", emptyMap())

        assertTrue(caps["hasRichContent"] == false)
    }

    @Test
    fun `SearchResult inferCapabilities includes humanFriendly for articles`() {
        val caps = SearchResult.inferCapabilities("rss_feeds", "", emptyMap())

        assertTrue(caps["humanFriendly"] == true)
    }

    @Test
    fun `SearchResult inferCapabilities includes agentFriendly for code`() {
        val caps = SearchResult.inferCapabilities("test", "https://github.com/test", emptyMap())

        assertTrue(caps["agentFriendly"] == true)
    }

    @Test
    fun `SearchResult inferCapabilities treats vulnerabilities as human-usable structured links`() {
        val caps = SearchResult.inferCapabilities(
            "cve",
            "https://nvd.nist.gov/vuln/detail/CVE-2026-0001",
            mapOf("cveId" to "CVE-2026-0001")
        )

        assertTrue(caps["humanFriendly"] == true)
        assertTrue(caps["agentFriendly"] == true)
        assertTrue(caps["hasRichContent"] == true)
        assertTrue(caps["isInteractive"] == true)
        assertTrue(caps["isStructured"] == true)
    }

    @Test
    fun `SearchResult data class holds correct values`() {
        val result = SearchResult(
            source = "test_source",
            title = "Test Title",
            url = "https://example.com",
            snippet = "Test snippet",
            score = 0.95,
            metadata = mapOf("key" to "value", "document_id" to "doc-123"),
            contentType = "document",
            capabilities = mapOf("linkable" to true)
        )

        assertEquals("doc-123", result.id)
        assertEquals("test_source", result.source)
        assertEquals("Test Title", result.title)
        assertEquals("https://example.com", result.url)
        assertEquals(0.95, result.score)
        assertEquals("value", result.metadata["key"])
        assertEquals("document", result.contentType)
    }

    @Test
    fun `SearchResult handles empty metadata`() {
        val result = SearchResult(
            source = "test",
            title = "Title",
            url = "https://example.com",
            snippet = "Snippet",
            score = 1.0,
            metadata = emptyMap(),
            contentType = "document",
            capabilities = mapOf("linkable" to true)
        )

        assertTrue(result.metadata.isEmpty())
    }

    @Test
    fun `SearchResult handles special characters in fields`() {
        val result = SearchResult(
            source = "test",
            title = "Title with <html> & 'quotes'",
            url = "https://example.com?param=value&other=test",
            snippet = "Snippet with \"quotes\" and <tags>",
            score = 0.5,
            metadata = mapOf("key" to "value with 'quotes'"),
            contentType = "document",
            capabilities = mapOf("linkable" to true)
        )

        assertTrue(result.title.contains("<html>"))
        assertTrue(result.snippet.contains("\"quotes\""))
        assertTrue(result.metadata["key"]!!.contains("'quotes'"))
    }

    @Test
    fun `redactSearchQueryForLog omits raw query text`() {
        val redacted = redactSearchQueryForLog("password reset for alice@example.test")

        assertTrue(redacted.startsWith("sha256="))
        assertTrue(redacted.contains("length=37"))
        assertFalse(redacted.contains("alice@example.test"))
        assertFalse(redacted.contains("password reset"))
    }

    @Test
    fun `validateSearchRequest rejects empty collection list`() {
        val error = validateSearchRequest(
            SearchRequest(query = "docs", collections = emptyList(), limit = 5)
        )

        assertEquals("collections", error?.field)
    }

    @Test
    fun `validateSearchRequest rejects unsafe collection names`() {
        val error = validateSearchRequest(
            SearchRequest(query = "docs", collections = listOf("../postgres"), limit = 5)
        )

        assertEquals("collections", error?.field)
    }

    @Test
    fun `validateSearchRequest rejects excessive collection fanout`() {
        val error = validateSearchRequest(
            SearchRequest(
                query = "docs",
                collections = (1..50).map { "collection_$it" },
                limit = 101
            )
        )

        assertEquals("limit", error?.field)
    }

    @Test
    fun `validateSearchRequest allows wildcard and safe collection names`() {
        val error = validateSearchRequest(
            SearchRequest(query = "docs", collections = listOf("*", "linux_docs", "test-cve"), limit = 5)
        )

        assertNull(error)
    }
}
