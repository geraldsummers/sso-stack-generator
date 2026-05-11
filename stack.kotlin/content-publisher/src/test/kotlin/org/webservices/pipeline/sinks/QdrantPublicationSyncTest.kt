package org.webservices.pipeline.sinks

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.webservices.pipeline.storage.EmbeddingStatus
import org.webservices.pipeline.storage.StagedDocument
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QdrantPublicationSyncTest {
    private lateinit var server: MockWebServer
    private val gson = Gson()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `markPublished pushes canonical publication payload to qdrant`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val sync = QdrantPublicationSync(server.url("/").toString(), apiKey = "test-key")
        val doc = StagedDocument(
            id = "wikipedia-doc-1",
            source = "wikipedia",
            collection = "wikipedia",
            text = "Sherbrooke Canadiens content",
            metadata = mapOf("title" to "Sherbrooke Canadiens"),
            embeddingStatus = EmbeddingStatus.COMPLETED
        )

        sync.markPublished(doc, "https://bookstack.example.test/books/wikipedia-articles/page/sherbrooke-canadiens")

        val indexRequest = server.takeRequest()
        assertEquals("/collections/wikipedia/index", indexRequest.path)
        assertEquals("PUT", indexRequest.method)
        assertEquals("test-key", indexRequest.getHeader("api-key"))

        val request = server.takeRequest()
        assertEquals("/collections/wikipedia/points/payload", request.path)
        assertEquals("POST", request.method)
        assertEquals("test-key", request.getHeader("api-key"))

        val json = gson.fromJson(request.body.readUtf8(), Map::class.java)
        assertNotNull(json["payload"])
        val payload = json["payload"] as Map<*, *>
        assertEquals("https://bookstack.example.test/books/wikipedia-articles/page/sherbrooke-canadiens", payload["bookstack_url"])
        assertEquals("bookstack", payload["presentation_target"])
        assertEquals("https://bookstack.example.test/books/wikipedia-articles/page/sherbrooke-canadiens", payload["presentation_url"])
        assertEquals(true, payload["search_ready"])
        assertEquals(true, payload["published"])
        assertEquals("wikipedia-doc-1", payload["document_id"])

        val points = json["points"] as List<*>
        assertEquals(1, points.size)
        assertTrue(points.first().toString().isNotBlank())
    }
}
