package org.webservices.testrunner.framework

import kotlinx.coroutines.*
import java.io.File

/**
 * Core test execution engine and DSL for integration testing.
 *
 * TestRunner provides a structured DSL for writing integration tests that validate
 * cross-service interactions in the webservices stack. It orchestrates test execution,
 * manages authentication helpers, and collects results for reporting.
 *
 * ## Test Structure
 * Tests are organized hierarchically:
 * - **Suite**: Named group of related tests (e.g., "Authentication Tests")
 * - **Test**: Individual test case with setup, execution, and assertions
 * - **Context**: Test execution environment with helper methods and assertions
 *
 * ## Cross-Service Integration Testing
 * TestRunner enables tests that span multiple services:
 * - **Authentication Flows**: Keycloak → edge auth → Services
 * - **Data Pipelines**: Airflow → PostgreSQL checkpoints → Qdrant/OpenSearch → BookStack
 * - **Agent Tools**: Agent-Tool-Server → All Services (LLM-driven workflows)
 * - **SSO Validation**: Single Keycloak session accessing multiple applications
 *
 * ## Helper Orchestration
 * TestRunner pre-configures helpers for common operations:
 * - `auth`: Validate Keycloak edge auth and trusted internal test identity
 * - `tokens`: Acquire service-specific API tokens
 * - `client`: Make HTTP requests to services
 *
 * ## Why This DSL Exists
 * - **Readability**: Tests read like specifications, not HTTP boilerplate
 * - **Isolation**: Each test gets fresh context, preventing state leaks
 * - **Error Handling**: Exceptions converted to test failures with clear messages
 * - **Timing**: Automatic duration tracking for performance analysis
 *
 * @property environment Detected test environment (Container or Localhost)
 * @property client ServiceClient for HTTP requests to webservices services
 * @property httpClient Raw Ktor HTTP client for lower-level operations
 */
class TestRunner(
    val environment: TestEnvironment,
    val client: ServiceClient,
    val httpClient: io.ktor.client.HttpClient,
    val cookieStorage: ResettableCookiesStorage? = null,
    val requestHttpClient: io.ktor.client.HttpClient = httpClient,
    val resultsDir: File? = null,
    private val selection: TestSelection = TestSelection()
) {
    val env get() = environment
    val endpoints get() = environment.endpoints
    val isDiscoveringTests get() = selection.discoverOnly
    val hasExplicitTestSelection get() = selection.ids.isNotEmpty()
    private val testOutputLog = StringBuilder()

    val auth = AuthHelper(
        client = httpClient,
        cookieStorage = cookieStorage,
        requestClient = requestHttpClient,
        protectedServiceUrl = runCatching { "https://${caddyHost("keycloak-whoami")}/" }.getOrNull()
    )
    val tokens = TokenManager(httpClient, environment.endpoints)

    private val results = mutableListOf<TestResult>()
    private val descriptors = mutableListOf<TestDescriptor>()
    private val suiteStack = mutableListOf<String>()

    suspend fun suite(name: String, block: suspend TestSuite.() -> Unit) {
        val message = "\n▶ $name"
        if (!selection.discoverOnly) {
            println(message)
            System.out.flush()
            log(message)
        }
        val suite = TestSuite(name, this)
        suiteStack.add(name)
        try {
            suite.block()
        } catch (e: Throwable) {
            recordFailure("$name: suite execution", e.message ?: "Unknown suite error")
        } finally {
            suiteStack.removeAt(suiteStack.lastIndex)
        }
    }

    suspend fun test(name: String, block: suspend TestContext.() -> Unit): TestResult {
        val suiteName = suiteStack.lastOrNull() ?: "Unscoped Tests"
        val descriptor = TestDescriptor(
            id = testId(suiteName, name),
            suite = suiteName,
            name = name
        )
        descriptors.add(descriptor)

        if (selection.discoverOnly) {
            return TestResult.Skipped(name, "discovery only", 0)
        }

        if (!selection.shouldRun(descriptor.id)) {
            return TestResult.Skipped(name, "not selected", 0)
        }

        print("  [TEST] ${descriptor.id} ... ")
        System.out.flush()
        log("  [TEST] ${descriptor.id} ($name) ... ")
        val startedAt = System.currentTimeMillis()
        val result = try {
            val ctx = TestContext(client, auth, tokens)
            ctx.block()
            val duration = System.currentTimeMillis() - startedAt
            TestResult.Success(descriptor.id, duration).also {
                val message = "✓ OK (${duration}ms)"
                println(message)
                System.out.flush()
                log(message)
            }
        } catch (e: SkippedTestException) {
            val duration = System.currentTimeMillis() - startedAt
            TestResult.Skipped(descriptor.id, e.message ?: "Skipped", duration).also {
                val message1 = "↷ SKIP (${duration}ms)"
                val message2 = "      ${e.message ?: "Skipped"}"
                println(message1)
                println(message2)
                System.out.flush()
                log(message1)
                log(message2)
            }
        } catch (e: AssertionError) {
            val duration = System.currentTimeMillis() - startedAt
            TestResult.Failure(descriptor.id, e.message ?: "Assertion failed", duration).also {
                val message1 = "✗ FAIL (${duration}ms)"
                val message2 = "      ${e.message}"
                println(message1)
                println(message2)
                System.out.flush()
                log(message1)
                log(message2)
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startedAt
            TestResult.Failure(descriptor.id, e.message ?: "Unknown error", duration).also {
                val message1 = "✗ ERROR (${duration}ms)"
                val message2 = "      ${e.message}"
                println(message1)
                println(message2)
                System.out.flush()
                log(message1)
                log(message2)
                if (e.stackTrace.isNotEmpty()) {
                    val message3 = "      at ${e.stackTrace[0]}"
                    println(message3)
                    System.out.flush()
                    log(message3)
                }
            }
        }
        results.add(result)
        return result
    }

    fun skip(name: String, reason: String) {
        val skipped = TestResult.Skipped(name, reason, 0)
        results.add(skipped)
        val message1 = "  [SKIP] $name"
        val message2 = "      $reason"
        println(message1)
        println(message2)
        System.out.flush()
        log(message1)
        log(message2)
    }

    fun recordFailure(name: String, message: String) {
        val failure = TestResult.Failure(name, message, 0)
        results.add(failure)
        val message1 = "✗ SUITE"
        val message2 = "      $name: $message"
        println(message1)
        println(message2)
        System.out.flush()
        log(message1)
        log(message2)
    }

    fun note(message: String) {
        val formatted = "      ℹ  $message"
        println(formatted)
        System.out.flush()
        log(formatted)
    }

    fun recordProbabilisticResult(result: ProbabilisticTestResultBase) {
        when (result) {
            is ProbabilisticTestResultSuccess -> {
                if (result.passed) {
                    results.add(TestResult.Success(result.name, result.totalDurationMs))
                    log("  [ADVISORY] ${result.name}: passed (${result.successCount}/${result.trials})")
                } else {
                    results.add(
                        TestResult.Failure(
                            result.name,
                            "Success rate ${result.successCount}/${result.trials} exceeded the allowed failure budget",
                            result.totalDurationMs
                        )
                    )
                    log("  [ADVISORY] ${result.name}: failed (${result.successCount}/${result.trials})")
                }
            }
            is LatencyTestResult -> {
                if (result.passed) {
                    results.add(TestResult.Success(result.name, 0))
                    log("  [ADVISORY] ${result.name}: passed (median=${result.medianMs}ms p95=${result.p95Ms}ms)")
                } else {
                    results.add(
                        TestResult.Failure(
                            result.name,
                            "Latency thresholds exceeded: median=${result.medianMs}ms (limit ${result.maxMedianLatency}ms), p95=${result.p95Ms}ms (limit ${result.maxP95Latency}ms)",
                            0
                        )
                    )
                    log("  [ADVISORY] ${result.name}: failed (median=${result.medianMs}ms p95=${result.p95Ms}ms)")
                }
            }
            is ThroughputTestResult -> {
                if (result.passed) {
                    results.add(TestResult.Success(result.name, 0))
                    log("  [ADVISORY] ${result.name}: passed (${result.opsPerSecond}ops/s)")
                } else {
                    results.add(
                        TestResult.Failure(
                            result.name,
                            "Throughput ${result.opsPerSecond}ops/s was below the minimum ${result.minOpsPerSecond}ops/s",
                            0
                        )
                    )
                    log("  [ADVISORY] ${result.name}: failed (${result.opsPerSecond}ops/s)")
                }
            }
        }
    }

    fun summary(): TestSummary {
        val passed = results.filterIsInstance<TestResult.Success>()
        val failed = results.filterIsInstance<TestResult.Failure>()
        val skipped = results.filterIsInstance<TestResult.Skipped>()

        // Save detailed log if results directory exists
        resultsDir?.let {
            File(it, "detailed.log").writeText(testOutputLog.toString())
        }

        return TestSummary(
            total = results.size,
            passed = passed.size,
            failed = failed.size,
            skipped = skipped.size,
            duration = results.sumOf {
                when (it) {
                    is TestResult.Success -> it.durationMs
                    is TestResult.Failure -> it.durationMs
                    is TestResult.Skipped -> it.durationMs
                }
            },
            failures = failed
        )
    }

    fun discoveredTests(): List<TestDescriptor> = descriptors.toList()

    fun hasSelectedTests(): Boolean {
        if (selection.ids.isEmpty()) return true
        return descriptors.any { it.id in selection.ids }
    }

    private fun log(message: String) {
        testOutputLog.appendLine(message)
    }
}

data class TestSelection(
    val ids: Set<String> = emptySet(),
    val discoverOnly: Boolean = false
) {
    fun shouldRun(id: String): Boolean = ids.isEmpty() || id in ids
}

data class TestDescriptor(
    val id: String,
    val suite: String,
    val name: String
)

fun testId(suiteName: String, testName: String): String {
    return "${slug(suiteName)}.${slug(testName)}"
}

private fun slug(value: String): String {
    return value.lowercase()
        .replace(Regex("[^a-z0-9]+"), ".")
        .trim('.')
        .replace(Regex("\\.+"), ".")
        .ifBlank { "test" }
}

class TestSuite(val name: String, private val runner: TestRunner) {
    suspend fun test(name: String, block: suspend TestContext.() -> Unit) {
        runner.test(name, block)
    }

    fun skip(name: String, reason: String) {
        runner.skip(name, reason)
    }
}

class TestContext(
    val client: ServiceClient,
    val auth: AuthHelper,
    val tokens: TokenManager
) {
    
    infix fun String.shouldContain(substring: String) {
        if (!this.contains(substring)) {
            throw AssertionError("Expected string to contain '$substring', but got: ${this.take(200)}")
        }
    }

    infix fun String.shouldNotContain(substring: String) {
        if (this.contains(substring)) {
            throw AssertionError("Expected string NOT to contain '$substring', but it was found")
        }
    }

    infix fun Boolean.shouldBe(expected: Boolean) {
        if (this != expected) {
            throw AssertionError("Expected $expected but got $this")
        }
    }

    infix fun Int.shouldBeGreaterThan(threshold: Int) {
        if (this <= threshold) {
            throw AssertionError("Expected $this to be greater than $threshold")
        }
    }

    infix fun Int.shouldBe(expected: Int) {
        if (this != expected) {
            throw AssertionError("Expected $expected but got $this")
        }
    }

    infix fun <T> T.shouldBe(expected: T) {
        if (this != expected) {
            throw AssertionError("Expected $expected but got $this")
        }
    }

    fun <T> T.shouldNotBeNull(): T {
        if (this == null) {
            throw AssertionError("Expected non-null value but got null")
        }
        return this
    }

    fun require(condition: Boolean, message: String = "Requirement failed") {
        if (!condition) {
            throw AssertionError(message)
        }
    }

    fun fail(message: String): Nothing = throw AssertionError(message)

    fun skip(reason: String): Nothing = throw SkippedTestException(reason)

    
    infix fun Int.shouldBeOneOf(values: List<Int>) {
        if (this !in values) {
            throw AssertionError("Expected $this to be one of $values")
        }
    }
}

sealed class TestResult {
    data class Success(val name: String, val durationMs: Long) : TestResult()
    data class Failure(val name: String, val error: String, val durationMs: Long) : TestResult()
    data class Skipped(val name: String, val reason: String, val durationMs: Long) : TestResult()
}

class SkippedTestException(message: String) : RuntimeException(message)

data class TestSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val duration: Long,
    val failures: List<TestResult.Failure>
)
