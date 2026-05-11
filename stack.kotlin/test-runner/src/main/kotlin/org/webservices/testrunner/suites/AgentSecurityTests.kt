package org.webservices.testrunner.suites

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.webservices.testrunner.framework.TestRunner

suspend fun TestRunner.agentSecurityTests() = suite("Agent Security Boundary Tests") {
    test("Workspace API rejects unauthenticated listing") {
        val response = requestHttpClient.get("http://workspace-provisioner:8120/api/workspaces")
        response.status shouldBe HttpStatusCode.Unauthorized
    }

    test("Workspace API rejects unauthenticated creation") {
        val response = requestHttpClient.post("http://workspace-provisioner:8120/api/workspaces") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"displayName":"unauthorized"}""")
        }
        response.status shouldBe HttpStatusCode.Unauthorized
    }

    test("Workspace OIDC discovery exposes no client secrets") {
        val response = requestHttpClient.get("http://workspace-provisioner:8120/api/oidc/discovery")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "\"client_id\""
        require(!body.contains("client_secret", ignoreCase = true)) {
            "Workspace OIDC discovery must not expose client_secret"
        }
    }
}
