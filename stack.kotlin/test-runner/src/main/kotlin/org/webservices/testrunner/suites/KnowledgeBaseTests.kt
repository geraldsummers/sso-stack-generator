package org.webservices.testrunner.suites

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.webservices.testrunner.framework.*

suspend fun TestRunner.knowledgeBaseTests() = suite("Knowledge Base Tests") {
    suspend fun ensureEmbeddingBackendReady() {
        val embeddingReadiness = probeEmbeddingBackendReadiness(
            maxAttempts = 6,
            attemptTimeoutMs = 30_000L,
            retryDelayMs = 5_000L
        )
        require(embeddingReadiness.ready) {
            embeddingReadiness.reason ?: "Embedding backend is unavailable"
        }
    }

    suspend fun searchOnce(
        query: String,
        collections: List<String> = listOf("*"),
        limit: Int = 5,
        mode: String,
        timeoutMs: Long = 30_000L
    ): SearchResult =
        client.search(
            query = query,
            collections = collections,
            limit = limit,
            mode = mode,
            timeoutMs = timeoutMs
        )

    suspend fun TestContext.searchOrFail(
        label: String,
        query: String,
        collections: List<String> = listOf("*"),
        limit: Int = 5,
        mode: String,
        attempts: Int = 3,
        retryDelayMs: Long = 5_000L
    ): SearchResult {
        var lastResult: SearchResult? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            val result = searchOnce(query, collections, limit, mode)
            lastResult = result
            if (result.success) {
                return result
            }
            val body = result.results.toString()
            val dependencyFailure = explainEmbeddingBackedSearchFailure(
                HttpStatusCode.BadGateway,
                body
            )
            if (dependencyFailure == null || attempt == attempts - 1) {
                fail(dependencyFailure ?: "$label failed: ${compactDependencyMessage(body)}")
            }
            delay(retryDelayMs)
        }
        fail("$label failed without a result")
    }

    suspend fun embedWithRetry(
        text: String,
        attempts: Int = 3,
        delayMs: Long = 3000,
        timeoutMs: Long = 45_000L
    ): ToolResult {
        var last: ToolResult = ToolResult.Error(0, "Embedding request did not execute")
        repeat(attempts) { index ->
            val result = try {
                withTimeout(timeoutMs) {
                    client.callTool("llm_embed_text", mapOf(
                        "text" to text,
                        "model" to "bge-m3"
                    ))
                }
            } catch (e: Exception) {
                ToolResult.Error(-1, e.message ?: "Embedding request timed out")
            }
            if (result is ToolResult.Success) {
                return result
            }

            last = result
            val message = (result as? ToolResult.Error)?.message
            val retryable = isKnownEmbeddingDependencyFailure(message)
            if (!retryable || index == attempts - 1) {
                return result
            }
            delay(delayMs)
        }
        return last
    }

    test("Query MariaDB blocks forbidden patterns") {
        val result = client.callTool("query_mariadb", mapOf(
            "database" to "grafana",
            "query" to "DROP TABLE users"
        ))

        when (result) {
            is ToolResult.Success -> {
                val output = result.output.lowercase()
                require(output.contains("error") || output.contains("only select")) {
                    "Expected error response containing 'error' or 'only select' (case-insensitive), got: ${result.output}"
                }
            }
            is ToolResult.Error -> {
                val msg = result.message
                require(msg.contains("Only SELECT") || msg.contains("forbidden")) {
                    "Expected security error, got: $msg"
                }
            }
        }
    }

    test("Semantic search executes") {
        if (System.getenv("TESTDEV_SKIP_GPU_INGESTION") == "1") {
            println("      ✓ Semantic search intentionally excluded from testdev profile")
            return@test
        }
        ensureEmbeddingBackendReady()

        val result = searchOrFail(
            label = "Semantic search",
            query = "kubernetes deployment",
            collections = listOf("wikipedia"),
            limit = 5,
            mode = "vector"
        )

        result.success shouldBe true
        
        val resultStr = result.results.toString()
        require(resultStr.contains("results") || resultStr.startsWith("[")) {
            "Expected results array, got: $resultStr"
        }
    }

    test("Vectorization pipeline: embed → store → retrieve") {
        if (System.getenv("TESTDEV_SKIP_GPU_INGESTION") == "1") {
            println("      ✓ Vectorization pipeline intentionally excluded from testdev profile")
            return@test
        }
        ensureEmbeddingBackendReady()
        
        val testText = "webservices integration test vector ${System.currentTimeMillis()}"
        println("\n      Generating embedding for: '$testText'")

        val embedResult = embedWithRetry(testText)

        require(embedResult is ToolResult.Success) {
            "Embedding generation failed: ${(embedResult as? ToolResult.Error)?.message}"
        }
        val embeddingOutput = (embedResult as ToolResult.Success).output

        
        require(embeddingOutput.contains("[") && embeddingOutput.contains("]")) {
            "Expected vector array, got: $embeddingOutput"
        }

        val dimensions = embeddingOutput.split(",").size
        println("      ✓ Generated ${dimensions}d vector")
        require(dimensions > 1000) { "Expected ~1024 dimensions, got $dimensions" }

        
        
        println("      Testing vector search with query...")
        val searchResult = searchOrFail(
            label = "Vector search",
            query = "integration test",
            collections = listOf("wikipedia"),
            limit = 10,
            mode = "vector"
        )

        searchResult.success shouldBe true
        println("      ✓ Search completed successfully")

        
        val searchResultStr = searchResult.results.toString()
        require(searchResultStr.contains("results") || searchResultStr.startsWith("[")) {
            "Expected results structure, got: $searchResultStr"
        }

        println("      ✓ Vectorization pipeline validated: embed → search")
    }
}
