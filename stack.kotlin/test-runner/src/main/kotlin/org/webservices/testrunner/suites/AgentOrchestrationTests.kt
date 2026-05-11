package org.webservices.testrunner.suites

import org.webservices.testrunner.framework.TestRunner

suspend fun TestRunner.agentOrchestrationTests() {
    println("\n▶ Agent Orchestration Tests (Advisory)")
    println("  Model-context orchestration was removed; advisory coverage now lives in workspace and LLM direct-path suites")
}
