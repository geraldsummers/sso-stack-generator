package org.webservices.testrunner

import org.webservices.testrunner.framework.TestRunner
import org.webservices.testrunner.suites.AGENT_ENV_SUITE_NAME
import org.webservices.testrunner.suites.AGENT_EXPAND_SUITE_NAME
import org.webservices.testrunner.suites.AGENT_FIXTURES_SUITE_NAME
import org.webservices.testrunner.suites.AGENT_LAB_SUITE_NAME
import org.webservices.testrunner.suites.AGENT_RUNTIME_SUITE_NAME
import org.webservices.testrunner.suites.STACK_APPS_SUITE_NAME
import org.webservices.testrunner.suites.STACK_AUTH_SUITE_NAME
import org.webservices.testrunner.suites.STACK_CONTRACT_SUITE_NAME
import org.webservices.testrunner.suites.STACK_CORE_SUITE_NAME
import org.webservices.testrunner.suites.STACK_FULL_SUITE_NAME
import org.webservices.testrunner.suites.STACK_LIVE_INGESTION_SUITE_NAME
import org.webservices.testrunner.suites.STACK_RECOVERY_SUITE_NAME
import org.webservices.testrunner.suites.agentCapabilityContractTests
import org.webservices.testrunner.suites.agentCapabilityTests
import org.webservices.testrunner.suites.agentEnvTests
import org.webservices.testrunner.suites.agentExpandTests
import org.webservices.testrunner.suites.agentFixtureTests
import org.webservices.testrunner.suites.agentLabTests
import org.webservices.testrunner.suites.agentLlmQualityTests
import org.webservices.testrunner.suites.agentRuntimeTests
import org.webservices.testrunner.suites.agentSecurityTests
import org.webservices.testrunner.suites.stackAppTests
import org.webservices.testrunner.suites.stackAuthTests
import org.webservices.testrunner.suites.stackContractTests
import org.webservices.testrunner.suites.stackCoreTests
import org.webservices.testrunner.suites.stackFullTests
import org.webservices.testrunner.suites.stackLiveIngestionTests
import org.webservices.testrunner.suites.stackLlmCapabilityTests
import org.webservices.testrunner.suites.recoveryTests

internal const val LEGACY_DEFAULT_SUITE = "webservices"
internal const val LEGACY_ALL_SUITE = "all"
internal const val AGENT_SECURITY_SUITE = "agent-security"
internal const val AGENT_CAPABILITY_SUITE = "agent-capability"
internal const val AGENT_ADVISORY_SUITE = "agent-advisory"
internal const val KOTLIN_ALL_SUITE = "kotlin-all"
internal const val DEFAULT_SUITE = STACK_CONTRACT_SUITE_NAME

internal enum class SuiteSemantics {
    BLOCKING,
    ADVISORY
}

internal data class SuiteDefinition(
    val name: String,
    val description: String,
    val semantics: SuiteSemantics = SuiteSemantics.BLOCKING,
    val run: suspend TestRunner.() -> Unit
)

internal object SuiteCatalog {
    private val canonicalSuites = listOf(
        SuiteDefinition(
            name = STACK_CORE_SUITE_NAME,
            description = "Core platform contracts and infrastructure checks"
        ) { stackCoreTests() },
        SuiteDefinition(
            name = STACK_AUTH_SUITE_NAME,
            description = "Authentication, session, and protected-operation checks"
        ) { stackAuthTests() },
        SuiteDefinition(
            name = STACK_APPS_SUITE_NAME,
            description = "Application-surface contract checks"
        ) { stackAppTests() },
        SuiteDefinition(
            name = STACK_CONTRACT_SUITE_NAME,
            description = "Default blocking platform contract suite"
        ) { stackContractTests() },
        SuiteDefinition(
            name = STACK_LIVE_INGESTION_SUITE_NAME,
            description = "Live ingestion, search corpus, and publication checks"
        ) { stackLiveIngestionTests() },
        SuiteDefinition(
            name = STACK_RECOVERY_SUITE_NAME,
            description = "Disposable testdev backup and recovery drills"
        ) { recoveryTests() },
        SuiteDefinition(
            name = STACK_FULL_SUITE_NAME,
            description = "Full stack run including live-ingestion checks"
        ) { stackFullTests() },
        SuiteDefinition(
            name = AGENT_SECURITY_SUITE,
            description = "Deterministic agent security boundary tests"
        ) { agentSecurityTests() },
        SuiteDefinition(
            name = AGENT_CAPABILITY_SUITE,
            description = "Deterministic agent capability contract tests"
        ) { agentCapabilityContractTests() },
        SuiteDefinition(
            name = AGENT_ADVISORY_SUITE,
            description = "Advisory agent reliability and LLM drift checks",
            semantics = SuiteSemantics.ADVISORY
        ) {
            agentCapabilityTests()
            agentLlmQualityTests()
            stackLlmCapabilityTests()
        },
        SuiteDefinition(
            name = AGENT_ENV_SUITE_NAME,
            description = "Base agent workspace toolchain checks"
        ) { agentEnvTests() },
        SuiteDefinition(
            name = AGENT_EXPAND_SUITE_NAME,
            description = "Optional agent workspace profile installation checks"
        ) { agentExpandTests() },
        SuiteDefinition(
            name = AGENT_FIXTURES_SUITE_NAME,
            description = "Deterministic polyglot build fixtures"
        ) { agentFixtureTests() },
        SuiteDefinition(
            name = AGENT_RUNTIME_SUITE_NAME,
            description = "Aider runtime checks",
            semantics = SuiteSemantics.ADVISORY
        ) { agentRuntimeTests() },
        SuiteDefinition(
            name = AGENT_LAB_SUITE_NAME,
            description = "Owned workspace-provisioner integration checks"
        ) { agentLabTests() },
        SuiteDefinition(
            name = KOTLIN_ALL_SUITE,
            description = "Every Kotlin managed test, with duplicate composed suites expanded once"
        ) {
            stackFullTests()
            agentSecurityTests()
            agentCapabilityContractTests()
            agentCapabilityTests()
            agentLlmQualityTests()
            agentRuntimeTests()
            stackLlmCapabilityTests()
            agentEnvTests()
            agentExpandTests()
            agentFixtureTests()
            agentLabTests()
        },
    )

    private val suiteByName = canonicalSuites.associateBy { it.name }
    private val aliases = mapOf(
        LEGACY_DEFAULT_SUITE to DEFAULT_SUITE,
        LEGACY_ALL_SUITE to KOTLIN_ALL_SUITE
    )

    fun availableSuiteNames(): List<String> = canonicalSuites.map { it.name }

    fun resolve(requestedName: String): SuiteDefinition? {
        val canonicalName = aliases[requestedName] ?: requestedName
        return suiteByName[canonicalName]
    }
}
