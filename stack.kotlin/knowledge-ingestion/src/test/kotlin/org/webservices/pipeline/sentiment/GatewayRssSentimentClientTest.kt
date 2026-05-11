package org.webservices.pipeline.sentiment

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.webservices.pipeline.storage.EmbeddingStatus
import org.webservices.pipeline.storage.StagedDocument
import java.net.InetSocketAddress

class GatewayRssSentimentClientTest {
    @Test
    fun `infer parses chat completion payload and clamps score and confidence`() {
        val payload = """
            {
              "choices": [
                {
                  "message": {
                    "content": "```json\n{\"score\":1.4,\"confidence\":2.0,\"explanation\":\"Strong rally\"}\n```"
                  }
                }
              ]
            }
        """.trimIndent()

        httpServer(200) { payload }.use { server ->
            val client = GatewayRssSentimentClient(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                modelName = "sentiment-model",
                apiKey = "secret"
            )

            val inference = client.infer(stagedDoc("doc-1"), "Bitcoin rally")

            assertEquals(1.0, inference!!.score)
            assertEquals(1.0, inference.confidence)
            assertEquals("bullish", inference.label)
            assertEquals("inference-gateway", inference.provider)
            assertTrue(inference.rawPayload!!.contains("\"score\":1.4"))
        }
    }

    @Test
    fun `infer returns null for http errors blank responses and malformed payloads`() {
        httpServer(500) { "nope" }.use { server ->
            val client = GatewayRssSentimentClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                modelName = "sentiment-model",
                apiKey = null
            )
            assertNull(client.infer(stagedDoc("doc-2"), "Bitcoin rally"))
        }

        httpServer(200) { "" }.use { server ->
            val client = GatewayRssSentimentClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                modelName = "sentiment-model",
                apiKey = null
            )
            assertNull(client.infer(stagedDoc("doc-3"), "Bitcoin rally"))
        }

        httpServer(200) { """{"choices":[{"message":{"content":"not-json"}}]}""" }.use { server ->
            val client = GatewayRssSentimentClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                modelName = "sentiment-model",
                apiKey = null
            )
            assertNull(client.infer(stagedDoc("doc-4"), "Bitcoin rally"))
        }
    }

    @Test
    fun `private parsing helpers derive labels escape json and normalize endpoints`() {
        val client = GatewayRssSentimentClient(
            baseUrl = "http://localhost:4000",
            modelName = "sentiment-model",
            apiKey = null
        )

        val bearish = invoke<SentimentInference?>(
            client,
            "parseInference",
            """{"choices":[{"message":{"content":"{\"score\":-0.3,\"confidence\":0.2}"}}]}"""
        )
        assertEquals("bearish", bearish!!.label)

        val neutral = invoke<SentimentInference?>(
            client,
            "parseInference",
            """{"choices":[{"message":{"content":"{\"score\":0.1,\"confidence\":0.2}"}}]}"""
        )
        assertEquals("neutral", neutral!!.label)

        assertEquals("line\\n\\\"quoted\\\"", invoke<String>(client, "escapeJson", "line\n\"quoted\""))
        assertEquals("http://host/v1/chat/completions", invoke<String>(client, "resolveChatCompletionsUrl", "http://host"))
        assertEquals("http://host/v1/chat/completions", invoke<String>(client, "resolveChatCompletionsUrl", "http://host/v1"))
        assertEquals(
            "http://host/v1/chat/completions",
            invoke<String>(client, "resolveChatCompletionsUrl", "http://host/v1/chat/completions")
        )
    }

    private fun stagedDoc(id: String) = StagedDocument(
        id = id,
        source = "rss",
        collection = "rss_feeds",
        text = "Body",
        metadata = mapOf("title" to "Title", "link" to "https://example.com"),
        embeddingStatus = EmbeddingStatus.PENDING
    )

    private fun httpServer(status: Int, responder: () -> String): HttpServer {
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = responder().toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            start()
        }
    }

    private fun HttpServer.use(block: (HttpServer) -> Unit) {
        try {
            block(this)
        } finally {
            stop(0)
        }
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
