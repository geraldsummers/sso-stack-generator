package org.webservices.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*


suspend fun TestRunner.microserviceTests() = suite("Pipeline Tests") {

    suspend fun probePipelineJson(path: String, attempts: Int = 6): JsonObject? {
        val baseCandidates = linkedSetOf(
            "http://ingestion-runner:8090",
            endpoints.pipeline.trimEnd('/'),
            "http://airflow-webserver:8080",
            "http://webservices-ingestion-runner-1:8090"
        )
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            for (base in baseCandidates) {
                val response = runCatching { client.getRawResponse("$base$path") }.getOrNull() ?: continue
                if (response.status == HttpStatusCode.NotFound ||
                    response.status == HttpStatusCode.BadGateway ||
                    response.status == HttpStatusCode.ServiceUnavailable ||
                    response.status == HttpStatusCode.GatewayTimeout
                ) {
                    continue
                }
                if (response.status != HttpStatusCode.OK) {
                    continue
                }
                val body = response.bodyAsText().trim()
                if (body.isBlank()) {
                    continue
                }
                val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                if (parsed != null) {
                    return parsed
                }
            }
            if (attempt < attempts - 1) {
                delay(5_000)
            }
        }
        return null
    }

    test("Pipeline: Health check") {
        if (System.getenv("TESTDEV_SKIP_GPU_INGESTION") == "1") {
            println("      ✓ Pipeline management API intentionally excluded from testdev profile")
            return@test
        }
        val json = probePipelineJson("/health")
        require(json != null) {
            "Pipeline management API did not return JSON from /health on any expected route"
        }
        val status = json["status"]?.jsonPrimitive?.content
        require(status == null || status.equals("ok", ignoreCase = true)) {
            "Pipeline health payload is not OK: $json"
        }
        println("      ✓ Pipeline management API healthy")
    }

    test("Pipeline: List data sources") {
        if (System.getenv("TESTDEV_SKIP_GPU_INGESTION") == "1") {
            println("      ✓ Pipeline source listing intentionally excluded from testdev profile")
            return@test
        }
        val json = probePipelineJson("/sources")
        require(json != null) {
            "Pipeline management API did not return JSON from /sources on any expected route"
        }
        val sources = json["sources"]?.jsonArray

        require(sources != null) { "sources array missing" }
        require(sources.size >= 1) { "Expected at least one source, got ${sources.size}" }

        val sourceNames = sources.map { it.jsonObject["id"]?.jsonPrimitive?.content }

        println("      ✓ Found ${sources.size} data sources: ${sourceNames.joinToString()}")
    }

    test("Pipeline: Check scheduler status") {
        if (System.getenv("TESTDEV_SKIP_GPU_INGESTION") == "1") {
            println("      ✓ Pipeline scheduler status intentionally excluded from testdev profile")
            return@test
        }
        val json = probePipelineJson("/status")
        require(json != null) {
            "Pipeline management API did not return JSON from /status on any expected route"
        }
        val uptime = json["uptime"]?.jsonPrimitive?.longOrNull
        val sources = json["sources"]?.jsonArray

        require(uptime == null || uptime >= 0) { "uptime is invalid: $uptime" }
        require(sources != null) { "sources array missing" }
        require(sources.size >= 1) { "Expected at least one source in status" }

        
        sources.forEach { sourceElement ->
            val source = sourceElement.jsonObject
            require(source["source"]?.jsonPrimitive?.content != null) { "source name missing" }
            require(source["enabled"]?.jsonPrimitive?.booleanOrNull != null) { "enabled field missing" }
            require(source["totalProcessed"]?.jsonPrimitive?.longOrNull != null) { "totalProcessed missing" }
            require(source["status"]?.jsonPrimitive?.content != null) { "status missing" }
        }

        println("      ✓ Pipeline status payload validated (${sources.size} sources)")

        
        sources.forEach { sourceElement ->
            val source = sourceElement.jsonObject
            val name = source["source"]?.jsonPrimitive?.content
            val processed = source["totalProcessed"]?.jsonPrimitive?.long
            val status = source["status"]?.jsonPrimitive?.content
            println("         - $name: $processed processed, status: $status")
        }
    }
}
