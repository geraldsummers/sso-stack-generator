package org.webservices.testrunner.suites

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.webservices.testrunner.framework.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.UUID

suspend fun TestRunner.llmTests() = suite("LLM Integration Tests") {
    data class LlmReadiness(val ready: Boolean, val reason: String? = null)

    fun isLlmErrorPayload(text: String): Boolean {
        return try {
            fun hasErrorKey(element: JsonElement): Boolean = when (element) {
                is JsonObject -> {
                    element.containsKey("error") ||
                        element["status_code"]?.jsonPrimitive?.intOrNull?.let { it >= 400 } == true ||
                        element.values.any { hasErrorKey(it) }
                }
                is JsonArray -> element.any { hasErrorKey(it) }
                else -> false
            }
            hasErrorKey(Json.parseToJsonElement(text))
        } catch (_: Exception) {
            val lowered = text.lowercase()
            lowered.contains("llm_http_error") ||
                lowered.contains("internalservererror") ||
                lowered.contains("connection error")
        }
    }

    fun extractLlmErrorCode(text: String): String? {
        return try {
            fun findErrorCode(element: JsonElement): String? = when (element) {
                is JsonObject -> {
                    element["error"]?.jsonPrimitive?.contentOrNull
                        ?: element.values.asSequence().mapNotNull { findErrorCode(it) }.firstOrNull()
                }
                is JsonArray -> element.asSequence().mapNotNull { findErrorCode(it) }.firstOrNull()
                else -> null
            }
            findErrorCode(Json.parseToJsonElement(text))
        } catch (_: Exception) {
            null
        }
    }

    fun isKnownLlmUnavailable(text: String): Boolean {
        val errorCode = extractLlmErrorCode(text)?.lowercase()
        if (errorCode in setOf("circuit_breaker_open", "service_unavailable", "upstream_unavailable")) {
            return true
        }
        val lowered = text.lowercase()
        return lowered.contains("circuit_breaker_open") ||
            lowered.contains("service unavailable") ||
            lowered.contains("connection error") ||
            lowered.contains("timed out") ||
            lowered.contains("timeout")
    }

    fun describeLlmResult(result: ToolResult): String {
        return when (result) {
            is ToolResult.Success -> result.extractAgentContent()
            is ToolResult.Error -> result.message
        }
    }

    fun parseSseEvents(body: String): List<Pair<String, JsonObject>> {
        return body.trim()
            .split(Regex("\n\\s*\n"))
            .flatMap { chunk ->
                val lines = chunk.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) {
                    return@flatMap emptyList()
                }
                val eventType = lines.firstOrNull { it.startsWith("event:") }?.removePrefix("event:")?.trim()
                val payload = lines.filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").trim() }
                    .takeIf { it.isNotBlank() }
                    ?: return@flatMap emptyList()
                when (val json = Json.parseToJsonElement(payload)) {
                    is JsonObject -> listOf(eventType.orEmpty().ifBlank { json["type"]?.jsonPrimitive?.content.orEmpty() } to json)
                    is JsonArray -> json.mapNotNull { element ->
                        val event = element as? JsonObject ?: return@mapNotNull null
                        (eventType.orEmpty().ifBlank { event["type"]?.jsonPrimitive?.content.orEmpty() }) to event
                    }
                    else -> emptyList()
                }
            }
    }

    suspend fun waitForLlmReady(model: String, maxAttempts: Int = 12, delayMs: Long = 5000): LlmReadiness {
        var lastReason = "LLM readiness probe did not return READY"
        var consecutiveUnavailableSignals = 0

        repeat(maxAttempts) { attempt ->
            val probe = client.callTool("llm_chat_completion", mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to "Reply with READY only.")
                ),
                "temperature" to 0.0,
                "max_tokens" to 8
            ))

            when (probe) {
                is ToolResult.Success -> {
                    val content = probe.extractAgentContent()
                    when {
                        !isLlmErrorPayload(content) && content.contains("READY") -> {
                            return LlmReadiness(ready = true)
                        }
                        isKnownLlmUnavailable(content) -> {
                            consecutiveUnavailableSignals++
                            lastReason = content
                        }
                        else -> {
                            consecutiveUnavailableSignals = 0
                            lastReason = content
                        }
                    }
                }
                is ToolResult.Error -> {
                    val message = probe.message
                    if (isKnownLlmUnavailable(message)) {
                        consecutiveUnavailableSignals++
                    } else {
                        consecutiveUnavailableSignals = 0
                    }
                    lastReason = message
                }
            }

            if (consecutiveUnavailableSignals >= 3) {
                return LlmReadiness(
                    ready = false,
                    reason = "LLM gateway unavailable (circuit breaker or upstream outage): $lastReason"
                )
            }

            if (attempt < maxAttempts - 1) {
                delay(delayMs)
            }
        }

        return LlmReadiness(
            ready = false,
            reason = "LLM readiness probe exhausted after $maxAttempts attempts: $lastReason"
        )
    }

    suspend fun selectedLlmModel(): String {
        val statusBody = awaitStatusBody(
            label = "inference-controller status",
            url = "http://inference-controller:8110/api/status"
        )
        val status = parseStatus(statusBody)
        val llmTarget = status["llmTarget"]?.jsonPrimitive?.contentOrNull
            ?: error("Inference controller status missing llmTarget: $statusBody")
        val backends = status["backends"]?.jsonArray.orEmpty().map { it.jsonObject }
        val backend = backends.firstOrNull { it["serviceName"]?.jsonPrimitive?.contentOrNull == llmTarget }
            ?: error("Inference controller status missing backend '$llmTarget': $statusBody")
        return backend["expectedModel"]?.jsonPrimitive?.contentOrNull
            ?: error("Inference controller backend '$llmTarget' is missing expectedModel: $statusBody")
    }

    suspend fun callLlmWithRetry(
        payload: Map<String, Any>,
        attempts: Int = 24,
        delayMs: Long = 5000,
        isAcceptable: (String) -> Boolean
    ): ToolResult {
        var last: ToolResult = ToolResult.Error(0, "LLM call did not execute")
        repeat(attempts) { index ->
            val result = client.callTool("llm_chat_completion", payload)
            if (result is ToolResult.Success) {
                val content = result.extractAgentContent()
                if (isKnownLlmUnavailable(content)) {
                    return result
                }
                if (!isLlmErrorPayload(content) && isAcceptable(content)) {
                    return result
                }
            } else if (result is ToolResult.Error) {
                if (isKnownLlmUnavailable(result.message) || result.statusCode !in 500..599) {
                    return result
                }
            }
            last = result
            if (index < attempts - 1) {
                delay(delayMs)
            }
        }
        return last
    }

    val llmModel = selectedLlmModel()
    val llmReadiness = waitForLlmReady(model = llmModel)

    suspend fun ensureEmbeddingBackendReady() {
        val embeddingReadiness = probeEmbeddingBackendReadiness(maxAttempts = 4)
        require(embeddingReadiness.ready) {
            embeddingReadiness.reason ?: "Embedding backend is unavailable"
        }
    }

    test("LLM chat completion generates response") {
        require(llmReadiness.ready) {
            llmReadiness.reason ?: "LLM gateway unavailable in this test environment"
        }
        val result = callLlmWithRetry(mapOf(
            "model" to llmModel,
            "messages" to listOf(
                mapOf("role" to "user", "content" to "What is 2+2? Answer with just the number.")
            ),
            "temperature" to 0.1,
            "max_tokens" to 10
        )) { content ->
            content.contains("4")
        }

        require(result is ToolResult.Success, "LLM completion failed: ${describeLlmResult(result)}")
        val output = (result as ToolResult.Success).extractAgentContent()

        require(!isLlmErrorPayload(output)) {
            "LLM call returned error response: $output"
        }

        output shouldContain "4"
    }

    test("LLM completion handles system prompts") {
        require(llmReadiness.ready) {
            llmReadiness.reason ?: "LLM gateway unavailable in this test environment"
        }
        val result = callLlmWithRetry(mapOf(
            "model" to llmModel,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a Kotlin expert. Answer with code only."),
                mapOf("role" to "user", "content" to "Write a function to add two numbers")
            ),
            "temperature" to 0.7,
            "max_tokens" to 100
        )) { content ->
            content.contains("fun") || content.contains("function")
        }

        require(result is ToolResult.Success, "LLM completion failed: ${describeLlmResult(result)}")
        val output = (result as ToolResult.Success).extractAgentContent()
        require(!isLlmErrorPayload(output)) { "LLM call returned error response: $output" }
        require(output.contains("fun") || output.contains("function"), "Expected function definition, got: $output")
    }

    test("LLM streamed responses preserve usage contract") {
        require(llmReadiness.ready) {
            llmReadiness.reason ?: "LLM gateway unavailable in this test environment"
        }
        val requestId = "llm-stream-${UUID.randomUUID()}"
        val responsesEndpoint = "${endpoints.llmGateway.trimEnd('/')}/responses"
        val response = requestHttpClient.post(responsesEndpoint) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Client-Request-Id", requestId)
            setBody(
                """
                {
                  "model": "$llmModel",
                  "stream": true,
                  "input": [
                    {
                      "role": "user",
                      "content": [
                        {"type": "input_text", "text": "Reply with READY only."}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val events = parseSseEvents(response.bodyAsText())
        val created = events.first { it.first == "response.created" }.second.getValue("response").jsonObject
        val outputDone = events.first { it.first == "response.output_item.done" }.second
        val completed = events.first { it.first == "response.completed" }.second.getValue("response").jsonObject
        val text = completed.getValue("output").jsonArray[0].jsonObject
            .getValue("content").jsonArray[0].jsonObject
            .getValue("text").jsonPrimitive.content

        val createdUsage = created["usage"]
        require(createdUsage == null || createdUsage is JsonNull) {
            "response.created usage must be absent or null while streaming: $created"
        }
        val outputIndex = outputDone["output_index"]?.jsonPrimitive?.intOrNull
        require(outputIndex == null || outputIndex == 0) {
            "response.output_item.done output_index must be absent or 0: $outputDone"
        }
        require(outputDone["item"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "message") {
            "response.output_item.done must describe an assistant message: $outputDone"
        }
        require((completed.getValue("usage").jsonObject.getValue("input_tokens").jsonPrimitive.intOrNull ?: -1) >= 0)
        require((completed.getValue("usage").jsonObject.getValue("output_tokens").jsonPrimitive.intOrNull ?: 0) >= 1)
        require((completed.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.intOrNull ?: 0) >= 1)
        text shouldContain "READY"
    }

    test("LLM embed text returns vector") {
        suspend fun callEmbeddingWithRetry(
            attempts: Int = 3,
            timeoutMs: Long = 60_000L,
            delayMs: Long = 5_000L
        ): ToolResult {
            var last: ToolResult = ToolResult.Error(0, "Embedding request did not execute")
            repeat(attempts) { index ->
                val result = try {
                    withTimeout(timeoutMs) {
                        client.callTool("llm_embed_text", mapOf(
                            "text" to "This is a test sentence for embedding.",
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

        ensureEmbeddingBackendReady()

        val result = callEmbeddingWithRetry()

        require(
            result is ToolResult.Success,
            "Embedding failed: ${compactDependencyMessage((result as? ToolResult.Error)?.message)}"
        )
        val output = (result as ToolResult.Success).output
        output shouldContain "["
        output shouldContain "]"

        val dimensions = output.split(",").size
        dimensions shouldBeGreaterThan 1000
    }
}
