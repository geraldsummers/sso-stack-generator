package org.webservices.testrunner.suites

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webservices.testrunner.framework.ProbabilisticTestRunner
import org.webservices.testrunner.framework.TestRunner
import kotlin.system.measureTimeMillis

/**
 * Helper to extract agent response content from /call-tool JSON response.
 * Handles both new agent format: {"result": {"content": "...", "iterations": N}}
 * and old direct format for backward compatibility.
 */
private fun extractAgentContent(body: String): String {
    return try {
        val json = Json.parseToJsonElement(body).jsonObject
        val result = json["result"]?.jsonObject
        result?.get("content")?.jsonPrimitive?.content ?: body
    } catch (_: Exception) {
        body
    }
}

private fun advisoryLlmChecksEnabled(): Boolean = System.getenv("RUN_ADVISORY_LLM_TESTS") == "1"

suspend fun TestRunner.agentLlmQualityTests() {
    if (isDiscoveringTests || hasExplicitTestSelection) return

    println("\n▶ Agent LLM Smoke Tests (Non-Blocking)")
    if (!advisoryLlmChecksEnabled()) {
        println("  Advisory LLM smoke tests disabled by default; set RUN_ADVISORY_LLM_TESTS=1 to enable")
        return
    }

    val probRunner = ProbabilisticTestRunner(environment, client, httpClient)

    probRunner.probabilisticTest(
        name = "LLM: Generates coherent responses to simple questions",
        trials = 20,
        acceptableFailureRate = 0.2
    ) {
        val questions = listOf(
            "What is 2+2?",
            "What color is the sky?",
            "Is water wet?",
            "How many days in a week?"
        )

        val question = questions.random()
        val response = client.callToolRequest(
            """
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[{"role":"user","content":"$question"}],
                        "temperature":0.3,
                        "max_tokens":50
                    }
                }
            """.trimIndent()
        )

        if (response.status == HttpStatusCode.OK) {
            val content = extractAgentContent(response.bodyAsText())
            content.length > 10 && !content.contains("error", ignoreCase = true)
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "LLM: Handles conversation context correctly",
        trials = 15,
        acceptableFailureRate = 0.3
    ) {
        val response = client.callToolRequest(
            """
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"user","content":"My name is Alice"},
                            {"role":"assistant","content":"Hello Alice! Nice to meet you."},
                            {"role":"user","content":"What is my name?"}
                        ],
                        "temperature":0.2,
                        "max_tokens":50
                    }
                }
            """.trimIndent()
        )

        if (response.status == HttpStatusCode.OK) {
            val content = extractAgentContent(response.bodyAsText())
            content.contains("Alice", ignoreCase = true)
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "LLM: Respects temperature parameter for determinism",
        trials = 10,
        acceptableFailureRate = 0.3
    ) {
        val prompt = "Say exactly: 'Hello World'"
        val responses = mutableSetOf<String>()

        repeat(3) {
            val response = client.callToolRequest(
                """
                    {
                        "tool":"llm_chat_completion",
                        "input":{
                            "messages":[{"role":"user","content":"$prompt"}],
                            "temperature":0.0,
                            "max_tokens":20
                        }
                    }
                """.trimIndent()
            )
            if (response.status == HttpStatusCode.OK) {
                responses.add(extractAgentContent(response.bodyAsText()))
            }
        }

        responses.size <= 2
    }

    probRunner.latencyTest(
        name = "LLM: Completion latency for short prompts",
        trials = 30,
        maxMedianLatency = 5000,
        maxP95Latency = 15000
    ) {
        measureTimeMillis {
            client.callToolRequest(
                """
                    {
                        "tool":"llm_chat_completion",
                        "input":{
                            "messages":[{"role":"user","content":"Hello"}],
                            "max_tokens":50
                        }
                    }
                """.trimIndent()
            )
        }
    }

    probRunner.probabilisticTest(
        name = "LLM: Handles max_tokens limit correctly",
        trials = 15,
        acceptableFailureRate = 0.2
    ) {
        val response = client.callToolRequest(
            """
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[{"role":"user","content":"Write a long story"}],
                        "max_tokens":10
                    }
                }
            """.trimIndent()
        )

        if (response.status == HttpStatusCode.OK) {
            val content = extractAgentContent(response.bodyAsText())
            content.length < 500
        } else {
            false
        }
    }

    val summary = probRunner.summary()
    summary.results.forEach { recordProbabilisticResult(it) }
    println("\n" + "=".repeat(80))
    println("LLM SMOKE TEST SUMMARY (NON-BLOCKING)")
    println("=".repeat(80))
    println("Total Tests: ${summary.total}")
    println("  ✓ Passed: ${summary.passed}")
    println("  ✗ Failed: ${summary.failed}")
    if (summary.failed > 0) {
        println("⚠️  LLM smoke checks are advisory only. Deterministic agent capability tests remain the blocking gate.")
    }
    println("=".repeat(80))
}
