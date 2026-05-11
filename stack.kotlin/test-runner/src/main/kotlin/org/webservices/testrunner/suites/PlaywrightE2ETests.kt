package org.webservices.testrunner.suites

import org.webservices.testrunner.framework.TestRunner
import java.io.File

/**
 * Playwright fast E2E Tests
 *
 * Orchestrates running Playwright browser automation tests for:
 * - Route-contract coverage
 * - Fast authenticated smoke checks
 *
 * Deep browser flows and visual snapshots remain available through the
 * TypeScript runner entrypoints but are intentionally not part of the default CI path.
 */
suspend fun TestRunner.playwrightE2ETests() {
    suite("Playwright E2E Tests") {
        test("Run Playwright fast E2E suite") {
            val playwrightDir = File("/app/playwright-tests")

            if (!playwrightDir.exists()) {
                throw Exception("Playwright tests directory not found: ${playwrightDir.absolutePath}")
            }

            File("/app/test-results/screenshots").deleteRecursively()

            // Set environment variables for Playwright tests
            val keycloakUrl = this@playwrightE2ETests.env.endpoints.keycloak
            val keycloakInternalUrl = this@playwrightE2ETests.env.endpoints.keycloakInternal
            // Use Caddy internal proxy to avoid NAT hairpin issues (external domain routes through Cloudflare)
            val baseUrl = System.getenv("BASE_URL") ?: "http://caddy"

            val processBuilder = ProcessBuilder(
                "npm", "run", "test:e2e"
            ).apply {
                directory(playwrightDir)
                environment().apply {
                    put("IDENTITY_PROVIDER", System.getenv("IDENTITY_PROVIDER") ?: "keycloak")
                    put("KEYCLOAK_URL", keycloakUrl)
                    put("KEYCLOAK_INTERNAL_URL", keycloakInternalUrl)
                    put("KEYCLOAK_REALM", System.getenv("KEYCLOAK_REALM") ?: "webservices")
                    put("KEYCLOAK_ADMIN_USER", System.getenv("KEYCLOAK_ADMIN_USER") ?: "admin")
                    System.getenv("KEYCLOAK_ADMIN_PASSWORD")?.takeIf { it.isNotBlank() }?.let {
                        put("KEYCLOAK_ADMIN_PASSWORD", it)
                    }
                    put("BASE_URL", baseUrl)
                    put("FORCE_COLOR", "1")  // Enable colored output
                }
                inheritIO()  // Stream output directly to stdout/stderr in real-time
            }

            println("Starting Playwright fast E2E suite...")
            println("  Playwright dir: ${playwrightDir.absolutePath}")
            println("  Identity provider: ${processBuilder.environment()["IDENTITY_PROVIDER"]}")
            println("  Keycloak URL: $keycloakUrl")
            println("  Base URL: $baseUrl")
            println()
            System.out.flush()

            val process = processBuilder.start()
            val exitCode = process.waitFor()

            // Copy Playwright reports to test-results
            val resultsDir = this@playwrightE2ETests.resultsDir
            if (resultsDir != null) {
                val playwrightReportDir = File(playwrightDir, "playwright-report")
                val playwrightResultsDir = File(playwrightDir, "test-results")
                val screenshotsDir = File("/app/test-results/screenshots")
                val targetDir = File(resultsDir, "playwright")
                targetDir.mkdirs()

                if (playwrightReportDir.exists()) {
                    playwrightReportDir.copyRecursively(File(targetDir, "report"), overwrite = true)
                    println("Copied Playwright HTML report to ${targetDir.absolutePath}/report")
                }

                if (playwrightResultsDir.exists()) {
                    playwrightResultsDir.copyRecursively(File(targetDir, "test-results"), overwrite = true)
                    println("Copied Playwright test results to ${targetDir.absolutePath}/test-results")
                }

                if (screenshotsDir.exists()) {
                    screenshotsDir.copyRecursively(File(targetDir, "screenshots"), overwrite = true)
                    println("Copied Playwright screenshots to ${targetDir.absolutePath}/screenshots")
                }
            }

            if (exitCode != 0) {
                throw Exception("Playwright fast E2E suite failed with exit code $exitCode. See output above.")
            }

            println("✓ Playwright fast E2E suite passed")
        }
    }
}
