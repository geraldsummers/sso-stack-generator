package org.webservices.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.securityTests() = suite("Security Tests") {

    
    test("Vaultwarden server is healthy") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/alive")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Vaultwarden web vault loads") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body.uppercase() shouldContain "<!DOCTYPE HTML>"
    }
}
