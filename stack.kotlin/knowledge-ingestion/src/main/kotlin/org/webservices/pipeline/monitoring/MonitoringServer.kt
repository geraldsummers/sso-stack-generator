package org.webservices.pipeline.monitoring

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.SourceMetadataStore
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}
private const val READINESS_QUERY_TIMEOUT_MS = 20_000L

data class MonitoredSourceDefinition(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)

class MonitoringServer(
    private val port: Int = 8090,
    private val metadataStore: SourceMetadataStore,
    private val sources: List<MonitoredSourceDefinition>,
    private val stagingStore: DocumentStagingStore? = null,
    private val statsCache: PipelineStatsCache? = null,
    private val runtimeTracker: SourceRuntimeTracker = SourceRuntimeTracker(),
    private val apiKey: String? = System.getenv("MONITORING_API_KEY"),
    private val clock: Clock = Clock.systemUTC()
) {
    private val server = AtomicReference<ApplicationEngine?>()

    private suspend fun loadStatsSnapshot(): PipelineStatsSnapshot? =
        statsCache?.snapshot() ?: statsCache?.refreshNow()

    private suspend fun fetchQueueStatsWithTimeout(timeoutMs: Long = 3000): Map<String, Long>? {
        return stagingStore?.getStatsWithQueryTimeout(timeoutMs)
    }

    private suspend fun fetchQueueStatsBySourceWithTimeout(source: String, timeoutMs: Long = 3000): Map<String, Long>? {
        return stagingStore?.getStatsBySourceWithQueryTimeout(source, timeoutMs)
    }

    private suspend fun ApplicationCall.requireAuth(): Boolean {
        if (apiKey.isNullOrBlank()) {
            return true
        }

        val providedKey = request.headers["X-API-Key"] ?: request.headers["Authorization"]?.removePrefix("Bearer ")
        if (providedKey != apiKey) {
            logger.warn { "Unauthorized monitoring access attempt from ${request.local.remoteHost}" }
            respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
            return false
        }
        return true
    }

    fun start() {
        val engine = embeddedServer(Netty, host = "0.0.0.0", port = port, module = { configureMonitoringModule() })

        engine.start(wait = false)
        server.set(engine)
        logger.info { "Monitoring server started on http://0.0.0.0:$port" }
    }

    fun stop() {
        server.get()?.stop(1000, 2000)
    }

    internal fun Application.configureMonitoringModule() {
        install(ContentNegotiation) {
            json()
        }

        routing {
            get("/") {
                if (!call.requireAuth()) return@get
                call.respondText(dashboardHtml(), ContentType.Text.Html)
            }

            get("/health") {
                if (!call.requireAuth()) return@get
                call.respond(HealthResponse(status = "ok", message = "Pipeline service running"))
            }

            get("/actuator/health") {
                if (!call.requireAuth()) return@get
                call.respond(HealthResponse(status = "UP", message = "Pipeline service running"))
            }

            get("/status") {
                if (!call.requireAuth()) return@get
                call.respond(StatusResponse(uptime = System.currentTimeMillis() / 1000, sources = buildSourceStatuses()))
            }

            get("/sources") {
                if (!call.requireAuth()) return@get
                call.respond(SourcesResponse(sources.map { SourceInfo(it.id, it.name, it.description) }))
            }

            get("/queue") {
                if (!call.requireAuth()) return@get
                if (stagingStore == null) {
                    call.respond(QueueStatusResponse(available = false, message = "Queue monitoring not available"))
                    return@get
                }
                val snapshot = loadStatsSnapshot()
                val stats = snapshot?.queueStats ?: fetchQueueStatsWithTimeout()
                if (stats == null) {
                    call.respond(QueueStatusResponse(available = false, message = "Queue monitoring timed out while reading staging stats"))
                    return@get
                }
                call.respond(
                    QueueStatusResponse(
                        available = true,
                        pending = stats["pending"] ?: 0,
                        inProgress = stats["in_progress"] ?: 0,
                        completed = stats["completed"] ?: 0,
                        failed = stats["failed"] ?: 0,
                        message = if (snapshot != null && !snapshot.queueFresh) "Embedding queue status (cached)" else "Embedding queue status"
                    )
                )
            }

            get("/queue/{source}") {
                if (!call.requireAuth()) return@get
                val sourceId = call.parameters["source"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Source parameter required"))
                if (stagingStore == null) {
                    call.respond(SourceQueueStatusResponse(source = sourceId, available = false, message = "Queue monitoring not available"))
                    return@get
                }
                val snapshot = loadStatsSnapshot()
                val stats = snapshot?.queueStatsFor(sourceId) ?: fetchQueueStatsBySourceWithTimeout(sourceId)
                if (stats == null) {
                    call.respond(SourceQueueStatusResponse(source = sourceId, available = false, message = "Queue monitoring timed out while reading source stats"))
                    return@get
                }
                call.respond(
                    SourceQueueStatusResponse(
                        source = sourceId,
                        available = true,
                        pending = stats["pending"] ?: 0,
                        inProgress = stats["in_progress"] ?: 0,
                        completed = stats["completed"] ?: 0,
                        failed = stats["failed"] ?: 0,
                        message = if (snapshot != null && !snapshot.sourceStatsFresh) "Queue status for $sourceId (cached)" else "Queue status for $sourceId"
                    )
                )
            }

            get("/readiness") {
                if (!call.requireAuth()) return@get
                call.respond(buildReadinessResponse())
            }

            get("/readiness/{source}") {
                if (!call.requireAuth()) return@get
                val sourceId = call.parameters["source"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Source parameter required"))
                val sourceDefinition = sources.find { it.id == sourceId }
                if (sourceDefinition == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown source: $sourceId"))
                    return@get
                }
                val readiness = buildReadinessResponse(listOf(sourceDefinition)).sources.firstOrNull()
                if (readiness == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown source: $sourceId"))
                } else {
                    call.respond(readiness)
                }
            }
        }
    }

    private suspend fun buildReadinessResponse(
        requestedSources: List<MonitoredSourceDefinition> = sources
    ): PipelineReadinessResponse {
        val sourceIds = requestedSources.map { it.id }
        val snapshot = loadStatsSnapshot()
        val snapshotSourceStatsAvailable = snapshot?.sourceStatsAvailable == true
        val snapshotPublicationStatsAvailable = snapshot?.publicationStatsAvailable == true
        val statsBySource = when {
            snapshotSourceStatsAvailable -> sourceIds.associateWith { sourceId -> snapshot?.queueStatsFor(sourceId).orEmpty() }
            else -> stagingStore?.getStatsBySourcesWithQueryTimeout(sourceIds, timeoutMs = READINESS_QUERY_TIMEOUT_MS)
        }
        val publicationBySource = when {
            snapshotPublicationStatsAvailable -> sourceIds.associateWith { sourceId -> snapshot?.publicationStatsFor(sourceId) }
            else -> stagingStore?.getBookStackStatsBySourcesWithQueryTimeout(sourceIds, timeoutMs = READINESS_QUERY_TIMEOUT_MS)
        }
        val fallbackReadinessBySource = when {
            sourceIds.isEmpty() -> emptyMap()
            statsBySource != null && publicationBySource != null -> null
            else -> stagingStore?.getReadinessEvidenceBySourcesWithQueryTimeout(sourceIds, timeoutMs = READINESS_QUERY_TIMEOUT_MS)
        }
        val sourceStatsAvailable =
            snapshot?.sourceStatsAvailable ?: (statsBySource != null) || (fallbackReadinessBySource != null)
        val publicationStatsAvailable =
            snapshot?.publicationStatsAvailable ?: (publicationBySource != null) || (fallbackReadinessBySource != null)

        return PipelineReadinessResponse(
            generatedAt = Instant.now(clock).toString(),
            sources = requestedSources.map { definition ->
                val metadata = metadataStore.load(definition.id)
                val runtime = runtimeTracker.snapshot(definition.id)
                val stats = statsBySource?.get(definition.id).orEmpty()
                val publication = publicationBySource?.get(definition.id)
                val fallbackReadiness = fallbackReadinessBySource?.get(definition.id)
                val searchableDocuments = stats["completed"] ?: fallbackReadiness?.searchableDocuments ?: 0L
                val pendingEmbedding = stats["pending"] ?: fallbackReadiness?.pendingEmbedding ?: 0L
                val inProgressEmbedding = stats["in_progress"] ?: fallbackReadiness?.inProgressEmbedding ?: 0L
                val failedEmbedding = stats["failed"] ?: fallbackReadiness?.failedEmbedding ?: 0L
                val pendingPublication = publication?.pending ?: fallbackReadiness?.pendingPublication ?: 0L
                val publishedDocuments = publication?.completed ?: fallbackReadiness?.publishedDocuments ?: 0L
                val failedPublication = publication?.failed ?: fallbackReadiness?.failedPublication ?: 0L
                val skippedPublication = publication?.skipped ?: fallbackReadiness?.skippedPublication ?: 0L
                val stalled = runtimeTracker.isStalled(runtime)
                val initialPullComplete = runtime.completedInitialPull ||
                    metadata.lastSuccessfulRun != null ||
                    searchableDocuments > 0L ||
                    publishedDocuments > 0L
                val blockers = mutableListOf<String>()

                if (!definition.enabled) blockers += "source disabled"
                if (runtime.activeRun) blockers += "active ${runtime.currentRunType ?: "run"}"
                if (stalled) blockers += "run appears stalled"
                if (!initialPullComplete) blockers += "initial pull not yet completed since service start"
                if (stagingStore != null && !sourceStatsAvailable) blockers += "queue stats unavailable"
                if (stagingStore != null && !publicationStatsAvailable) blockers += "publication stats unavailable"
                if (searchableDocuments == 0L) blockers += "no searchable documents staged yet"

                SourceReadiness(
                    source = definition.id,
                    name = definition.name,
                    enabled = definition.enabled,
                    runState = runtime.runState,
                    phase = runtime.phase,
                    activeRun = runtime.activeRun,
                    completedInitialPull = runtime.completedInitialPull,
                    initialPullComplete = initialPullComplete,
                    currentRunType = runtime.currentRunType,
                    currentRunStartedAt = runtime.currentRunStartedAt,
                    fetchStartedAt = runtime.fetchStartedAt,
                    fetchCompletedAt = runtime.fetchCompletedAt,
                    lastProgressAt = runtime.lastProgressAt,
                    lastCompletedAt = runtime.lastCompletedAt,
                    lastFailedAt = runtime.lastFailedAt,
                    lastSuccessfulRun = metadata.lastSuccessfulRun,
                    lastAttemptedRun = metadata.lastAttemptedRun,
                    lastError = runtime.lastError,
                    consecutiveFailures = metadata.consecutiveFailures,
                    stagedCurrentRun = runtime.stagedCurrentRun,
                    deduplicatedCurrentRun = runtime.deduplicatedCurrentRun,
                    failedCurrentRun = runtime.failedCurrentRun,
                    pendingEmbedding = pendingEmbedding,
                    inProgressEmbedding = inProgressEmbedding,
                    searchableDocuments = searchableDocuments,
                    failedEmbedding = failedEmbedding,
                    pendingPublication = pendingPublication,
                    publishedDocuments = publishedDocuments,
                    failedPublication = failedPublication,
                    skippedPublication = skippedPublication,
                    stalled = stalled,
                    blockers = blockers
                )
            }
        )
    }

    private suspend fun buildSourceStatuses(): List<SourceStatus> {
        val readinessBySource = buildReadinessResponse().sources.associateBy { it.source }

        return sources.map { source ->
            val metadata = metadataStore.load(source.id)
            val readiness = readinessBySource[source.id]
            SourceStatus(
                source = source.id,
                enabled = source.enabled,
                totalProcessed = metadata.totalItemsProcessed,
                totalFailed = metadata.totalItemsFailed,
                lastRunTime = metadata.lastSuccessfulRun ?: metadata.lastAttemptedRun ?: "never",
                consecutiveFailures = metadata.consecutiveFailures,
                status = classifySourceStatus(source.enabled, metadata.consecutiveFailures, readiness),
                checkpointData = metadata.checkpointData,
                activeRun = readiness?.activeRun,
                initialPullComplete = readiness?.initialPullComplete,
                searchableDocuments = readiness?.searchableDocuments,
                publishedDocuments = readiness?.publishedDocuments,
                pendingEmbedding = readiness?.pendingEmbedding,
                pendingPublication = readiness?.pendingPublication,
                blockers = readiness?.blockers.orEmpty()
            )
        }
    }

    private fun classifySourceStatus(
        enabled: Boolean,
        consecutiveFailures: Int,
        readiness: SourceReadiness?
    ): String = when {
        !enabled -> "disabled"
        consecutiveFailures > 3 -> "degraded"
        readiness == null -> "degraded"
        readiness.blockers.any {
            it.contains("stats unavailable", ignoreCase = true)
        } -> "degraded"
        readiness.activeRun || !readiness.initialPullComplete -> "warming_up"
        readiness.searchableDocuments <= 0L && readiness.publishedDocuments <= 0L -> "degraded"
        readiness.blockers.isNotEmpty() -> "warming_up"
        else -> "healthy"
    }

    private fun dashboardHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Pipeline Readiness</title>
              <style>
                body { font-family: "IBM Plex Sans", sans-serif; margin: 0; padding: 32px; background: #0c1422; color: #edf4ff; }
                section { max-width: 900px; margin: 0 auto; background: rgba(17, 28, 44, 0.92); border: 1px solid rgba(140, 196, 255, 0.18); border-radius: 18px; padding: 24px; }
                h1 { margin-top: 0; }
                a { color: #78d6ff; }
                ul { line-height: 1.7; }
                code { color: #78d6ff; }
              </style>
            </head>
            <body>
              <section>
                <h1>Pipeline Readiness</h1>
                <p>Use the JSON endpoints for stage-aware automation:</p>
                <ul>
                  <li><a href="/readiness"><code>/readiness</code></a> for per-source gating state</li>
                  <li><a href="/status"><code>/status</code></a> for execution totals</li>
                  <li><a href="/queue"><code>/queue</code></a> for embedding queue totals</li>
                  <li><code>/readiness/{source}</code> and <code>/queue/{source}</code> for source-specific detail</li>
                </ul>
              </section>
            </body>
            </html>
        """.trimIndent()
    }
}

@Serializable
data class HealthResponse(
    val status: String,
    val message: String
)

@Serializable
data class StatusResponse(
    val uptime: Long,
    val sources: List<SourceStatus>
)

@Serializable
data class SourceStatus(
    val source: String,
    val enabled: Boolean,
    val totalProcessed: Long,
    val totalFailed: Long,
    val lastRunTime: String,
    val consecutiveFailures: Int,
    val status: String,
    val checkpointData: Map<String, String> = emptyMap(),
    val activeRun: Boolean? = null,
    val initialPullComplete: Boolean? = null,
    val searchableDocuments: Long? = null,
    val publishedDocuments: Long? = null,
    val pendingEmbedding: Long? = null,
    val pendingPublication: Long? = null,
    val blockers: List<String> = emptyList()
)

@Serializable
data class SourcesResponse(
    val sources: List<SourceInfo>
)

@Serializable
data class SourceInfo(
    val id: String,
    val name: String,
    val description: String
)

@Serializable
data class QueueStatusResponse(
    val available: Boolean,
    val pending: Long = 0,
    val inProgress: Long = 0,
    val completed: Long = 0,
    val failed: Long = 0,
    val message: String
)

@Serializable
data class SourceQueueStatusResponse(
    val source: String,
    val available: Boolean,
    val pending: Long = 0,
    val inProgress: Long = 0,
    val completed: Long = 0,
    val failed: Long = 0,
    val message: String
)
