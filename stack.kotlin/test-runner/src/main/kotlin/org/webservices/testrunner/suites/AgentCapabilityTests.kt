package org.webservices.testrunner.suites

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.webservices.testrunner.framework.LatencyTestResult
import org.webservices.testrunner.framework.ProbabilisticTestResultSuccess
import org.webservices.testrunner.framework.ProbabilisticTestRunner
import org.webservices.testrunner.framework.TestRunner
import org.webservices.testrunner.framework.ThroughputTestResult
import org.webservices.testrunner.framework.ToolResult

suspend fun TestRunner.agentCapabilityTests() {
    if (isDiscoveringTests || hasExplicitTestSelection) return

    val probRunner = ProbabilisticTestRunner(environment, client, httpClient)

    println("\n▶ Agent Capability Reliability Tests (Advisory)")

    probRunner.probabilisticTest(
        name = "Workspace provisioner health checks stay reliable under repetition",
        trials = 20,
        acceptableFailureRate = 0.05
    ) {
        val response = client.getRawResponse("http://workspace-provisioner:8120/health")
        response.status == HttpStatusCode.OK
    }

    probRunner.latencyTest(
        name = "OIDC discovery latency",
        trials = 40,
        maxMedianLatency = 250,
        maxP95Latency = 800
    ) {
        val start = System.currentTimeMillis()
        client.getRawResponse("http://workspace-provisioner:8120/api/oidc/discovery")
        System.currentTimeMillis() - start
    }

    probRunner.latencyTest(
        name = "normalize_whitespace helper latency",
        trials = 40,
        maxMedianLatency = 50,
        maxP95Latency = 150
    ) {
        val start = System.currentTimeMillis()
        client.callTool("normalize_whitespace", mapOf("text" to "  hello   world  "))
        System.currentTimeMillis() - start
    }

    probRunner.throughputTest(
        name = "uuid_generate helper throughput",
        durationSeconds = 10,
        minOpsPerSecond = 20.0
    ) {
        client.callTool("uuid_generate", emptyMap<String, Any>())
    }

    probRunner.probabilisticTest(
        name = "Helper layer handles concurrent requests",
        trials = 20,
        acceptableFailureRate = 0.15
    ) {
        val responses = coroutineScope {
            (1..5).map {
                async {
                    client.callTool("uuid_generate", emptyMap<String, Any>())
                }
            }.awaitAll()
        }
        responses.all { it is ToolResult.Success }
    }

    val summary = probRunner.summary()
    summary.results.forEach { recordProbabilisticResult(it) }

    println("\n" + "=".repeat(80))
    println("ADVISORY RELIABILITY TEST SUMMARY")
    println("=".repeat(80))
    println("Total Tests: ${summary.total}")
    println("  ✓ Passed: ${summary.passed}")
    println("  ✗ Failed: ${summary.failed}")

    if (summary.failed > 0) {
        println("\n⚠️  Advisory failures detected:")
        summary.results.filter { !it.passed }.forEach { result ->
            when (result) {
                is ProbabilisticTestResultSuccess -> {
                    println("  • ${result.name}")
                    println("    Success rate: ${result.successCount}/${result.trials} (${(result.actualFailureRate * 100).toInt()}% failure)")
                }
                is LatencyTestResult -> {
                    println("  • ${result.name}")
                    println("    Median: ${result.medianMs}ms (max ${result.maxMedianLatency}ms), P95: ${result.p95Ms}ms (max ${result.maxP95Latency}ms)")
                }
                is ThroughputTestResult -> {
                    println("  • ${result.name}")
                    println("    Throughput: ${String.format("%.2f", result.opsPerSecond)}ops/s (min ${result.minOpsPerSecond}ops/s)")
                }
            }
        }
    }

    println("=".repeat(80))
}
