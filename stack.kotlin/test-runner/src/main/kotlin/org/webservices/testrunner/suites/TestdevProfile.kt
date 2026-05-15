package org.webservices.testrunner.suites

internal fun isTestdevProfile(): Boolean =
    System.getenv("TESTDEV_SKIP_GPU_INGESTION") == "1"

internal fun testRunnerComposeProjectName(): String =
    System.getenv("TEST_RUNNER_COMPOSE_PROJECT_NAME").orEmpty()
        .ifBlank { System.getenv("COMPOSE_PROJECT_NAME").orEmpty() }
        .ifBlank { "webservices" }

internal fun composeServiceContainerName(serviceName: String): String {
    val project = testRunnerComposeProjectName()
    return if (project.startsWith("webservices_testdev_")) {
        "$project-$serviceName-1"
    } else {
        serviceName
    }
}
