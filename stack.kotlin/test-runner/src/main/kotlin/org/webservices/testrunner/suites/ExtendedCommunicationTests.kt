package org.webservices.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.extendedCommunicationTests() = suite("Extended Communication Tests") {

    // Mastodon tests
    test("Mastodon: Web interface is accessible") {
        val response = getMastodonInternalResponse("/")
        requireOkOrRedirectResponse(response, "Mastodon web interface")
        println("      ✓ Mastodon endpoint returned ${response.status}")
    }

    test("Mastodon: API endpoint responds") {
        val response = getMastodonInternalResponse("/api/v1/instance")
        val body = requireOkResponse(response, "Mastodon instance API")
        body shouldContain "uri"
        println("      ✓ Mastodon instance API accessible")
    }

    test("Mastodon: Streaming API is reachable") {
        val response = client.getRawResponse("${endpoints.mastodonStreaming}/api/v1/streaming/health")
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ Mastodon streaming endpoint responded with ${response.status}")
    }

    test("Mastodon: Public timeline endpoint exists") {
        val response = getMastodonInternalResponse("/api/v1/timelines/public")

        val body = requireOkResponse(response, "Mastodon public timeline")
        val json = Json.parseToJsonElement(body)
        require(json is JsonArray) { "Public timeline should return array" }
        println("      ✓ Public timeline endpoint functional")
    }

    test("Mastodon: OAuth endpoint exists") {
        val response = getMastodonInternalResponse("/oauth/authorize")

        response.status.value shouldBeOneOf listOf(200, 400, 302)
        println("      ✓ OAuth endpoint accessible (${response.status})")
    }

    test("Mastodon: Static assets are served") {
        val response = getMastodonInternalResponse("/manifest.json")
        response.status.value shouldBeOneOf listOf(200, 304)
        println("      ✓ Static assets served")
    }

    test("Mastodon: Federation is configured") {
        val response = getMastodonInternalResponse("/.well-known/webfinger?resource=acct:admin@${System.getenv("MASTODON_HOST_HEADER") ?: "mastodon.${System.getenv("DOMAIN")}"}")

        response.status.value shouldBeOneOf listOf(200, 400, 404)
        println("      ✓ WebFinger endpoint present (${response.status})")
    }

    test("Mastodon: ActivityPub endpoint responds") {
        val response = getMastodonInternalResponse("/.well-known/host-meta")
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ ActivityPub host-meta available")
    }

    test("Mastodon: Media upload API does not allow anonymous upload") {
        val response = getMastodonInternalResponse("/api/v2/media")

        require(response.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
            "Mastodon media upload API should be protected or absent for anonymous GET, got ${response.status}"
        }
        println("      ✓ Media endpoint does not allow anonymous upload (${response.status})")
    }

    test("Mastodon: cached attachment records resolve to files") {
        val ruby = """
            require "json"

            models = ActiveRecord::Base.descendants.select do |model|
              begin
                !model.abstract_class? && model.table_exists?
              rescue
                false
              end
            end

            checked = []
            missing = []
            models.each do |model|
              model.column_names.grep(/_file_name${'$'}/).each do |column|
                attachment = column.sub(/_file_name${'$'}/, "")
                scope = model.where.not(column => [nil, ""])
                records = scope.count
                checked << { model: model.name, attachment: attachment, records: records }

                scope.find_each do |record|
                  file = record.public_send(attachment) rescue nil
                  next unless file&.respond_to?(:path)

                  path = begin
                    file.path(:original)
                  rescue
                    begin
                      file.path
                    rescue
                      nil
                    end
                  end

                  if path.present? && !File.exist?(path)
                    missing << {
                      model: model.name,
                      attachment: attachment,
                      id: record.id,
                      path: path,
                    }
                  end
                end
              end
            end

            payload = { checked: checked, missing: missing.size, missing_sample: missing.first(20) }
            puts JSON.generate(payload)
            exit(missing.empty? ? 0 : 42)
        """.trimIndent()

        val result = DockerCli.run("exec", composeServiceContainerName("mastodon-web"), "bin/rails", "runner", ruby)
        require(result.exitCode == 0) {
            "Mastodon has attachment records with missing cached files: ${result.output}"
        }
        println("      ✓ Mastodon cached attachment records resolve to files: ${result.output}")
    }

    // Ntfy (Notifications) - already has basic coverage in CommunicationTests
    test("Ntfy: Topics can be created") {
        require(endpoints.ntfy != null) { "Ntfy endpoint not configured" }

        val testTopic = "test-topic-${System.currentTimeMillis()}"
        val response = client.getRawResponse("${endpoints.ntfy}/$testTopic")

        response.status shouldBe HttpStatusCode.OK
        println("      ✓ Topic endpoint accessible")
    }

    test("Ntfy: Message publishing endpoint") {
        require(endpoints.ntfy != null) { "Ntfy endpoint not configured" }

        val testTopic = "test-integration-${System.currentTimeMillis()}"

        try {
            val response = httpClient.post("${endpoints.ntfy}/$testTopic") {
                basicAuth(
                    System.getenv("NTFY_USERNAME").orEmpty(),
                    System.getenv("NTFY_PASSWORD").orEmpty()
                )
                setBody("Test message from integration tests")
            }

            response.status shouldBe HttpStatusCode.OK
            println("      ✓ Message publishing successful")
        } catch (e: Exception) {
            fail("Ntfy message publishing failed: ${e.message}")
        }
    }

    test("Ntfy: JSON API endpoint") {
        require(endpoints.ntfy != null) { "Ntfy endpoint not configured" }

        val testTopic = "test-json-${System.currentTimeMillis()}"

        try {
            val response = httpClient.post("${endpoints.ntfy}/$testTopic") {
                basicAuth(
                    System.getenv("NTFY_USERNAME").orEmpty(),
                    System.getenv("NTFY_PASSWORD").orEmpty()
                )
                contentType(ContentType.Application.Json)
                setBody("""{"message":"Test from integration","title":"Integration Test"}""")
            }

            response.status shouldBe HttpStatusCode.OK
            println("      ✓ JSON API functional")
        } catch (e: Exception) {
            fail("Ntfy JSON API check failed: ${e.message}")
        }
    }

}
