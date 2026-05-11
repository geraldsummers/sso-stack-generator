package org.webservices.testrunner.suites

import org.webservices.testrunner.framework.TestRunner

const val STACK_CORE_SUITE_NAME = "stack-core"
const val STACK_AUTH_SUITE_NAME = "stack-auth"
const val STACK_APPS_SUITE_NAME = "stack-apps"
const val STACK_CONTRACT_SUITE_NAME = "stack-contract"
const val STACK_LIVE_INGESTION_SUITE_NAME = "stack-live-ingestion"
const val STACK_FULL_SUITE_NAME = "stack-full"

suspend fun TestRunner.stackCoreTests() {
    foundationTests()
    knowledgeBaseTests()
    microserviceTests()
    infrastructureTests()
    databaseTests()
    securityTests()
    utilityServicesTests()
}

suspend fun TestRunner.stackAuthTests() {
    authenticationTests()
    authenticatedOperationsTests()
    enhancedAuthenticationTests()
}

suspend fun TestRunner.stackAppTests() {
    userInterfaceTests()
    communicationTests()
    collaborationTests()
    productivityTests()
    fileManagementTests()
    monitoringTests()
    backupTests()
    homeAssistantTests()
    emailStackTests()
    cachingLayerTests()
    extendedCommunicationTests()
    extendedProductivityTests()
}

suspend fun TestRunner.stackContractTests() {
    stackCoreTests()
    stackAuthTests()
    stackAppTests()
}

suspend fun TestRunner.stackLiveIngestionTests() {
    dataPipelineTests()
    searchServiceTests()
    bookStackIntegrationTests()
}

suspend fun TestRunner.webServicesTests() {
    stackContractTests()
}

suspend fun TestRunner.stackFullTests() {
    stackContractTests()
    stackLiveIngestionTests()
}
