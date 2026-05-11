package org.webservices.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.collaborationTests() = suite("Collaboration Tests") {

    
    test("Mastodon web server is healthy") {
        val response = getMastodonInternalResponse("/health")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Mastodon streaming server is healthy") {
        val response = client.getRawResponse("${env.endpoints.mastodonStreaming}/api/v1/streaming/health")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Mastodon can fetch instance info") {
        val response = getMastodonInternalResponse("/api/v1/instance")
        val body = requireOkResponse(response, "Mastodon instance API")
        body shouldContain "uri"
    }

    test("Mastodon public timeline endpoint exists") {
        val response = getMastodonInternalResponse("/api/v1/timelines/public")
        val body = requireOkResponse(response, "Mastodon public timeline API")
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body)
        require(json is kotlinx.serialization.json.JsonArray) { "Mastodon public timeline should return a JSON array" }
    }

    
}
