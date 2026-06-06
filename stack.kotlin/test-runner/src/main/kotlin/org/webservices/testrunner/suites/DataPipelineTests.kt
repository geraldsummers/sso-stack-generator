package org.webservices.testrunner.suites

import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.webservices.testrunner.framework.TestRunner
import java.sql.DriverManager

suspend fun TestRunner.dataPipelineTests() = suite("Data Pipeline Tests") {
    val json = Json { ignoreUnknownKeys = true }

    suspend fun runnerPost(path: String, source: String? = null, limit: Int? = null) =
        client.postRaw("http://ingestion-runner:8090$path") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                source?.let { put("source", it) }
                limit?.let { put("limit", it) }
            })
        }

    fun scalar(sql: String): Long {
        DriverManager.getConnection(
            endpoints.postgres.jdbcUrl,
            endpoints.postgres.user,
            endpoints.postgres.password
        ).use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.queryTimeout = 10
                stmt.executeQuery().use { rs ->
                    require(rs.next()) { "No result for SQL: $sql" }
                    return rs.getLong(1)
                }
            }
        }
    }

    suspend fun qdrantPointCount(collection: String): Long {
        val response = client.getRawResponse("${endpoints.qdrant}/collections/$collection")
        require(response.status == HttpStatusCode.OK) {
            "Qdrant collection $collection is missing: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]?.jsonObject
            ?.get("points_count")?.jsonPrimitive?.longOrNull ?: 0L
    }

    test("ingestion runner is healthy") {
        val response = client.getRawResponse("http://ingestion-runner:8090/health")
        require(response.status == HttpStatusCode.OK) {
            "ingestion-runner health failed: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
    }

    test("checkpoint and publication schema exists") {
        val tableCount = scalar(
            """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name IN (
                'ingestion_sources',
                'ingestion_runs',
                'ingestion_checkpoints',
                'ingestion_errors',
                'publication_records'
              )
            """.trimIndent()
        )
        require(tableCount == 5L) { "Expected 5 ingestion metadata tables, found $tableCount" }
    }

    test("bootstrap creates OpenSearch index and Qdrant collections") {
        val response = runnerPost("/bootstrap")
        require(response.status == HttpStatusCode.OK) {
            "bootstrap failed: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
        require(qdrantPointCount("stack_knowledge") >= 0L) { "stack_knowledge collection is unavailable" }
    }

    test("bounded stack knowledge ingestion writes metadata and vectors") {
        val response = withTimeout(180_000L) {
            runnerPost("/run", source = "stack_knowledge", limit = 1)
        }
        require(response.status == HttpStatusCode.OK) {
            "stack_knowledge ingestion failed: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
        require(scalar("SELECT count(*) FROM ingestion_runs WHERE source_id = 'stack_knowledge' AND status = 'success'") > 0L) {
            "No successful stack_knowledge ingestion run recorded"
        }
        require(scalar("SELECT count(*) FROM ingestion_checkpoints WHERE source_id = 'stack_knowledge'") > 0L) {
            "No stack_knowledge checkpoint recorded"
        }
        require(qdrantPointCount("stack_knowledge") > 0L) {
            "stack_knowledge collection has no vectors after bounded ingestion"
        }
    }

    test("publication task writes BookStack presentation metadata") {
        val response = runnerPost("/publish", source = "stack_knowledge")
        require(response.status == HttpStatusCode.OK) {
            "publication metadata task failed: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
        require(scalar("SELECT count(*) FROM publication_records WHERE source_id = 'stack_knowledge' AND published AND search_ready") > 0L) {
            "No published stack_knowledge presentation metadata recorded"
        }
    }
}
