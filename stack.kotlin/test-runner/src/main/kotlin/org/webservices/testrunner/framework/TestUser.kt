package org.webservices.testrunner.framework

data class TestUser(
    val username: String,
    val password: String,
    val groups: List<String>,
    val distinguishedName: String? = null,
    val email: String? = null,
    val totpSecret: String? = null,
    val ownerUsername: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
