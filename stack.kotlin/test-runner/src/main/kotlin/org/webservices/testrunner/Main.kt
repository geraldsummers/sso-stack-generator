package org.webservices.testrunner

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.webservices.testrunner.framework.ResettableCookiesStorage
import org.webservices.testrunner.framework.ServiceClient
import org.webservices.testrunner.framework.TestEnvironment
import org.webservices.testrunner.framework.TestSelection
import org.webservices.testrunner.framework.TestRunner
import org.webservices.testrunner.framework.TestSummary
import java.io.File
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    val env = parseEnvironment(args)
    val suite = parseSuite(args)
    val verbose = "--verbose" in args || "-v" in args
    val listTests = "--list-tests" in args
    val planOnly = "--plan" in args
    val selectedTestIds = parseSelectedTestIds(args)

    if ("--help" in args || "-h" in args) {
        printUsage()
        exitProcess(0)
    }

    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())

    val resultsDir = createResultsDir(timestamp, suite)

    println(
        """
        ╔═══════════════════════════════════════════════════════════════════════════╗
        ║  Web Services Integration Test Runner (Kotlin ${KotlinVersion.CURRENT})         ║
        ╚═══════════════════════════════════════════════════════════════════════════╝

        Environment: ${env.name}
        Suite: $suite
        Verbose: $verbose
        Results: ${resultsDir.absolutePath}
        """.trimIndent()
    )
    System.out.flush()

    val cookieStorage = ResettableCookiesStorage()
    val httpClient = createHttpClient(verbose, cookieStorage)
    val requestHttpClient = createHttpClient(verbose)
    val serviceClient = ServiceClient(env.endpoints, httpClient)
    val runner = TestRunner(
        environment = env,
        client = serviceClient,
        httpClient = httpClient,
        cookieStorage = cookieStorage,
        requestHttpClient = requestHttpClient,
        resultsDir = resultsDir,
        selection = TestSelection(
            ids = selectedTestIds,
            discoverOnly = listTests || planOnly
        )
    )

    val startTime = System.currentTimeMillis()
    try {
        runTestSuite(runner, suite)
        if (listTests || planOnly) {
            val tests = runner.discoveredTests()
                .filter { selectedTestIds.isEmpty() || it.id in selectedTestIds }
            if (planOnly) {
                println("Plan: suite=$suite")
                println("Resolved: ${tests.size} Kotlin managed test(s)")
            }
            tests.forEach { descriptor ->
                println("${descriptor.id}\t${descriptor.suite}\t${descriptor.name}")
            }
            if (selectedTestIds.isNotEmpty() && tests.size != selectedTestIds.size) {
                val discoveredIds = tests.map { it.id }.toSet()
                val missing = selectedTestIds - discoveredIds
                println("Missing selected test id(s): ${missing.joinToString(", ")}")
                exitProcess(1)
            }
            exitProcess(0)
        }
        if (!runner.hasSelectedTests()) {
            println("❌ No selected Kotlin managed tests matched: ${selectedTestIds.joinToString(", ")}")
            exitProcess(1)
        }
        val summary = runner.summary()
        val duration = System.currentTimeMillis() - startTime

        val semantics = suiteSemantics(suite)
        val hasBlockingSkips = semantics == SuiteSemantics.BLOCKING && summary.skipped > 0

        printTelemetry(summary, duration, resultsDir, semantics)
        saveResults(summary, resultsDir, env, suite, duration, semantics)

        if (summary.failed > 0 || hasBlockingSkips) {
            exitProcess(1)
        }
    } catch (e: Exception) {
        println("\n❌ Fatal error during test execution:")
        println("   ${e.message}")
        System.out.flush()
        if (verbose) {
            e.printStackTrace()
            System.out.flush()
        }

        resultsDir.mkdirs()
        File(resultsDir, "error.log").writeText(
            """
            Fatal Error: ${e.message}

            Stack Trace:
            ${e.stackTraceToString()}
            """.trimIndent()
        )
        exitProcess(2)
    } finally {
        requestHttpClient.close()
        httpClient.close()
    }
}

private fun createResultsDir(timestamp: String, suite: String): File {
    val primaryDir = File("/app/test-results", "$timestamp-$suite")
    if (primaryDir.mkdirs() || (primaryDir.exists() && primaryDir.canWrite())) {
        return primaryDir
    }

    val fallbackDir = File("/tmp/test-results", "$timestamp-$suite")
    fallbackDir.mkdirs()
    if (!fallbackDir.exists() || !fallbackDir.canWrite()) {
        System.err.println("⚠️  CRITICAL: Cannot create results directory anywhere!")
        exitProcess(3)
    }
    return fallbackDir
}

private fun createHttpClient(
    verbose: Boolean,
    cookieStorage: ResettableCookiesStorage? = null
): HttpClient {
    val disableTlsValidation = System.getenv("DISABLE_TLS_VALIDATION")?.toBoolean() ?: false

    if (disableTlsValidation) {
        println("⚠️  WARNING: TLS certificate validation is DISABLED")
    }

    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = verbose
            })
        }
        if (cookieStorage != null) {
            install(HttpCookies) {
                storage = cookieStorage
            }
        }
        if (verbose) {
            install(Logging) {
                level = LogLevel.INFO
                logger = object : Logger {
                    override fun log(message: String) {
                        println(message)
                    }
                }
            }
        }
        followRedirects = false
        engine {
            requestTimeout = 180_000
            https {
                if (disableTlsValidation) {
                    trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }
                }
            }
        }
    }
}

private suspend fun runTestSuite(runner: TestRunner, suite: String) {
    val definition = SuiteCatalog.resolve(suite)
    if (definition == null) {
        println("❌ Unknown suite: $suite")
        println("Available suites: ${SuiteCatalog.availableSuiteNames().joinToString(", ")}")
        println("Compatibility aliases: $LEGACY_DEFAULT_SUITE -> $DEFAULT_SUITE, $LEGACY_ALL_SUITE -> $KOTLIN_ALL_SUITE")
        exitProcess(1)
    }
    definition.run.invoke(runner)
}

private fun parseEnvironment(args: Array<String>): TestEnvironment {
    return when {
        "--env" in args -> when (val envName = args[args.indexOf("--env") + 1]) {
            "container", "internal" -> TestEnvironment.Container
            "localhost", "local" -> TestEnvironment.Localhost
            else -> {
                println("❌ Unknown environment: $envName")
                println("Available environments: container, internal, localhost, local")
                exitProcess(1)
            }
        }
        else -> TestEnvironment.detect()
    }
}

private fun parseSuite(args: Array<String>): String {
    args.find { it.startsWith("--suite=") }?.substringAfter("=")?.let { return it }
    val suiteFlagIndex = args.indexOfFirst { it == "--suite" }
    if (suiteFlagIndex >= 0) {
        return args.getOrNull(suiteFlagIndex + 1) ?: DEFAULT_SUITE
    }
    return DEFAULT_SUITE
}

private fun parseSelectedTestIds(args: Array<String>): Set<String> {
    val ids = mutableSetOf<String>()
    args.filter { it.startsWith("--test-id=") }
        .mapTo(ids) { it.substringAfter("=") }
    args.forEachIndexed { index, value ->
        if (value == "--test-id") {
            args.getOrNull(index + 1)?.let { ids += it }
        }
    }
    return ids.filter { it.isNotBlank() }.toSet()
}

private fun suiteSemantics(suite: String): SuiteSemantics {
    return SuiteCatalog.resolve(suite)?.semantics ?: SuiteSemantics.BLOCKING
}

private fun printTelemetry(summary: TestSummary, duration: Long, resultsDir: File, semantics: SuiteSemantics) {
    println("\n" + "=".repeat(80))
    println("TEST RESULTS")
    println("=".repeat(80))
    println("Total Tests: ${summary.total}")
    println("  ✓ Passed:  ${summary.passed}")
    println("  ✗ Failed:  ${summary.failed}")
    println("  ↷ Skipped: ${summary.skipped}")
    println("  Duration:  ${duration}ms (${duration / 1000.0}s)")
    println()
    println("Results saved to: ${resultsDir.absolutePath}")
    println("  - summary.txt")
    println("  - detailed.log")
    println("  - failures.log")
    println("  - metadata.txt")
    println("=".repeat(80))
    println(
        when {
            summary.failed > 0 && semantics == SuiteSemantics.ADVISORY ->
                "\n❌ Advisory checks reported failures."
            summary.failed > 0 -> "\n❌ Some tests failed!"
            semantics == SuiteSemantics.ADVISORY && summary.total == 0 -> "\nℹ️  Advisory suite ran no executable checks."
            semantics == SuiteSemantics.ADVISORY -> "\n✅ Advisory suite completed without failures."
            summary.skipped > 0 -> "\n❌ Blocking suite had skips; contract coverage is incomplete."
            else -> "\n✅ Blocking suite passed."
        }
    )
    System.out.flush()
}

private fun saveResults(
    summary: TestSummary,
    resultsDir: File,
    env: TestEnvironment,
    suite: String,
    duration: Long,
    semantics: SuiteSemantics
) {
    val failed = summary.failed > 0 || (semantics == SuiteSemantics.BLOCKING && summary.skipped > 0)
    File(resultsDir, "summary.txt").writeText(
        """
        Total Tests: ${summary.total}
        Passed: ${summary.passed}
        Failed: ${summary.failed}
        Skipped: ${summary.skipped}
        Duration: ${duration}ms (${duration / 1000.0}s)
        Status: ${if (failed) "FAILED" else "PASSED"}
        """.trimIndent()
    )

    if (summary.failures.isNotEmpty()) {
        File(resultsDir, "failures.log").writeText(
            summary.failures.joinToString("\n\n") {
                "Test: ${it.name}\nDuration: ${it.durationMs}ms\nError: ${it.error}"
            }
        )
    }

    File(resultsDir, "metadata.txt").writeText(
        """
        Timestamp: ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now())}
        Environment: ${env.name}
        Suite: $suite
        Kotlin Version: ${KotlinVersion.CURRENT}
        Keycloak: ${env.endpoints.keycloak}
        Search Service: ${env.endpoints.searchService}
        BookStack: ${env.endpoints.bookstack}
        """.trimIndent()
    )
}

private fun printUsage() {
    println(
        """
        Web Services Integration Test Runner

        Usage: test-runner [OPTIONS]

        Options:
          --env <environment>    Values: container, internal, localhost, local
          --suite <suite>        Values: ${SuiteCatalog.availableSuiteNames().joinToString(", ")}
          --list-tests           List generated test ids for the selected suite
          --plan                 Print the selected test execution plan
          --test-id <id>         Run or plan one generated test id; repeatable
          --verbose, -v          Enable verbose logging
          --help, -h             Show this help message

        Examples:
          test-runner
          test-runner --env container --suite $DEFAULT_SUITE
          test-runner --env container --suite ${org.webservices.testrunner.suites.STACK_LIVE_INGESTION_SUITE_NAME}
          test-runner --suite ${org.webservices.testrunner.suites.STACK_FULL_SUITE_NAME} --verbose
        """.trimIndent()
    )
}
