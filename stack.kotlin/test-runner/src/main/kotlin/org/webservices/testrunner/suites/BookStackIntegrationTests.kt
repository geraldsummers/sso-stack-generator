package org.webservices.testrunner.suites

import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webservices.testrunner.framework.TestRunner
import java.sql.DriverManager

suspend fun TestRunner.bookStackIntegrationTests() = suite("BookStack Integration Tests") {
    fun publicationCount(source: String): Long {
        DriverManager.getConnection(
            endpoints.postgres.jdbcUrl,
            endpoints.postgres.user,
            endpoints.postgres.password
        ).use { conn ->
            conn.prepareStatement(
                """
                SELECT count(*)
                FROM publication_records
                WHERE source_id = ?
                  AND presentation_target = 'bookstack'
                  AND published
                  AND search_ready
                """.trimIndent()
            ).use { stmt ->
                stmt.queryTimeout = 10
                stmt.setString(1, source)
                stmt.executeQuery().use { rs ->
                    require(rs.next()) { "No publication count returned for $source" }
                    return rs.getLong(1)
                }
            }
        }
    }

    suspend fun publish(source: String) {
        val response = client.postRaw("http://ingestion-runner:8090/publish") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("source", source)
                put("publish", true)
            })
        }
        require(response.status == HttpStatusCode.OK) {
            "BookStack publication metadata task failed for $source: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
    }

    test("BookStack API is accessible when credentials are configured") {
        val response = client.getRawResponse("${endpoints.bookstack}/api/books")
        require(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Unauthorized) {
            "BookStack API did not respond on /api/books: HTTP ${response.status.value}"
        }
    }

    test("publication task records BookStack presentation metadata") {
        publish("stack_knowledge")
        require(publicationCount("stack_knowledge") > 0L) {
            "No BookStack publication metadata recorded for stack_knowledge"
        }
    }
}
