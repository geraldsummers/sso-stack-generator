package org.webservices.testrunner.suites

import io.ktor.http.HttpStatusCode
import org.webservices.testrunner.framework.TestRunner
import org.webservices.testrunner.framework.ToolResult
import java.util.UUID

suspend fun TestRunner.agentCapabilityContractTests() = suite("Agent Capability Contract Tests") {
    test("normalize_whitespace helper executes successfully") {
        val result = client.callTool("normalize_whitespace", mapOf("text" to "Hello   world"))
        require(result is ToolResult.Success) { "normalize_whitespace failed: $result" }
        result.output shouldBe "Hello world"
    }

    test("normalize_whitespace helper is idempotent around clean text") {
        val result = client.callTool("normalize_whitespace", mapOf("text" to "already clean"))
        require(result is ToolResult.Success) { "normalize_whitespace failed: $result" }
        result.output shouldBe "already clean"
    }

    test("normalize_whitespace helper trims leading and trailing whitespace") {
        val result = client.callTool("normalize_whitespace", mapOf("text" to "\n\t  hello   world  \n"))
        require(result is ToolResult.Success) { "normalize_whitespace failed: $result" }
        result.output shouldBe "hello world"
    }

    test("uuid_generate helper executes successfully") {
        val result = client.callTool("uuid_generate", emptyMap<String, Any>())
        require(result is ToolResult.Success) { "uuid_generate failed: $result" }
        UUID.fromString(result.output).toString() shouldBe result.output
    }

    test("normalize_whitespace rejects malformed inputs without a server error") {
        when (val result = client.callTool("normalize_whitespace", mapOf("wrong_field" to "test"))) {
            is ToolResult.Success -> false shouldBe true
            is ToolResult.Error -> (result.statusCode != HttpStatusCode.InternalServerError.value) shouldBe true
        }
    }

    test("Unknown helper names return a client-facing error") {
        when (val result = client.callTool("nonexistent_tool_12345", emptyMap<String, Any>())) {
            is ToolResult.Success -> false shouldBe true
            is ToolResult.Error -> (result.statusCode != HttpStatusCode.InternalServerError.value) shouldBe true
        }
    }
}
