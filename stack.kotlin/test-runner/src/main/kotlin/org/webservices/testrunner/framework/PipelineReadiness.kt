package org.webservices.testrunner.framework

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class PipelineSourceReadiness(
    val source: String,
    val enabled: Boolean,
    val status: String?,
    val completedInitialPull: Boolean,
    val initialPullComplete: Boolean,
    val activeRun: Boolean,
    val searchableDocuments: Long,
    val pendingEmbedding: Long,
    val pendingPublication: Long,
    val publishedDocuments: Long,
    val totalProcessed: Long,
    val totalFailed: Long,
    val checkpointData: JsonObject?,
    val blockers: List<String>
) {
    fun isSearchReady(minSearchableDocuments: Long = 1): Boolean =
        enabled && searchableDocuments >= minSearchableDocuments

    fun isPublicationReady(minPublishedDocuments: Long = 1): Boolean =
        enabled && publishedDocuments >= minPublishedDocuments

    fun searchReadinessReason(minSearchableDocuments: Long = 1): String = buildList {
        if (!enabled) add("source disabled")
        if (searchableDocuments < minSearchableDocuments) {
            add("searchableDocuments=$searchableDocuments < $minSearchableDocuments")
        }
        if (!initialPullComplete && !completedInitialPull) add("initial pull not complete")
        if (pendingEmbedding > 0) add("pendingEmbedding=$pendingEmbedding")
        if (activeRun) add("source still ingesting")
        if (status != null) add("status=$status")
        if (blockers.isNotEmpty()) add("blockers=${blockers.joinToString(";")}")
    }.joinToString(", ").ifBlank { summary() }

    fun publicationReadinessReason(minPublishedDocuments: Long = 1): String = buildList {
        if (!enabled) add("source disabled")
        if (publishedDocuments < minPublishedDocuments) {
            add("publishedDocuments=$publishedDocuments < $minPublishedDocuments")
        }
        if (!initialPullComplete && !completedInitialPull) add("initial pull not complete")
        if (pendingPublication > 0) add("pendingPublication=$pendingPublication")
        if (activeRun) add("source still ingesting")
        if (status != null) add("status=$status")
        if (blockers.isNotEmpty()) add("blockers=${blockers.joinToString(";")}")
    }.joinToString(", ").ifBlank { summary() }

    fun summary(): String =
        "source=$source enabled=$enabled completedInitialPull=$completedInitialPull initialPullComplete=$initialPullComplete " +
            "searchableDocuments=$searchableDocuments publishedDocuments=$publishedDocuments " +
            "pendingEmbedding=$pendingEmbedding pendingPublication=$pendingPublication " +
            "totalProcessed=$totalProcessed totalFailed=$totalFailed" +
            if (status.isNullOrBlank()) "" else " status=$status"
}

suspend fun TestRunner.getPipelineResponse(path: String): HttpResponse? {
    val suffix = if (path.startsWith("/")) path else "/$path"
    val candidateBases = buildList {
        add(endpoints.pipeline.trimEnd('/'))
        add("http://airflow-webserver:8080")
        add("http://ingestion-runner:8090")
    }.distinct()

    for (base in candidateBases) {
        val response = runCatching { client.getRawResponse("$base$suffix") }.getOrNull() ?: continue
        if (response.status != HttpStatusCode.NotFound && response.status != HttpStatusCode.BadGateway) {
            return response
        }
    }
    return null
}

suspend fun TestRunner.getPipelineSourceReadiness(
    sourceName: String,
    retryOnUnavailableStats: Boolean = true
): PipelineSourceReadiness? {
    repeat(6) { attempt ->
        val response = getPipelineResponse("/readiness/$sourceName") ?: return@repeat
        if (response.status != HttpStatusCode.OK) {
            return null
        }

        val source = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val readiness = PipelineSourceReadiness(
            source = source["source"]?.jsonPrimitive?.contentOrNull ?: sourceName,
            enabled = source["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            status = source["runState"]?.jsonPrimitive?.contentOrNull ?: source["status"]?.jsonPrimitive?.contentOrNull,
            completedInitialPull = source["completedInitialPull"]?.jsonPrimitive?.booleanOrNull ?: false,
            initialPullComplete = source["initialPullComplete"]?.jsonPrimitive?.booleanOrNull ?: false,
            activeRun = source["activeRun"]?.jsonPrimitive?.booleanOrNull ?: false,
            searchableDocuments = source["searchableDocuments"]?.jsonPrimitive?.longOrNull ?: 0L,
            pendingEmbedding = source["pendingEmbedding"]?.jsonPrimitive?.longOrNull ?: 0L,
            pendingPublication = source["pendingPublication"]?.jsonPrimitive?.longOrNull ?: 0L,
            publishedDocuments = source["publishedDocuments"]?.jsonPrimitive?.longOrNull ?: 0L,
            totalProcessed = source["stagedCurrentRun"]?.jsonPrimitive?.longOrNull ?: 0L,
            totalFailed = source["failedCurrentRun"]?.jsonPrimitive?.longOrNull ?: 0L,
            checkpointData = source["checkpointData"]?.jsonObject,
            blockers = source["blockers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        )

        val waitingOnStats = readiness.blockers.any {
            it.contains("stats unavailable", ignoreCase = true)
        }
        if (!retryOnUnavailableStats || !waitingOnStats || attempt == 5) {
            return readiness
        }

        delay(5_000)
    }
    return null
}

private fun readinessWaitMs(envName: String, defaultSeconds: Long): Long =
    (System.getenv(envName)?.toLongOrNull() ?: defaultSeconds).coerceAtLeast(0L) * 1000L

private suspend fun TestRunner.cachedSourceReadiness(
    cache: MutableMap<String, PipelineSourceReadiness?>,
    sourceName: String,
    retryOnUnavailableStats: Boolean = false
): PipelineSourceReadiness? {
    return if (cache.containsKey(sourceName)) {
        cache[sourceName]
    } else {
        getPipelineSourceReadiness(sourceName, retryOnUnavailableStats = retryOnUnavailableStats)
            .also { cache[sourceName] = it }
    }
}

suspend fun TestContext.requireSourceSearchReady(
    runner: TestRunner,
    cache: MutableMap<String, PipelineSourceReadiness?>,
    sourceName: String,
    label: String,
    minSearchableDocuments: Long = 1
): PipelineSourceReadiness {
    val readiness = runner.cachedSourceReadiness(cache, sourceName)
        ?: throw AssertionError("$label readiness is unavailable")
    require(readiness.isSearchReady(minSearchableDocuments)) {
        "$label is still warming up: ${readiness.searchReadinessReason(minSearchableDocuments)}"
    }
    return readiness
}

suspend fun TestContext.requireSourcePublicationReady(
    runner: TestRunner,
    cache: MutableMap<String, PipelineSourceReadiness?>,
    sourceName: String,
    label: String,
    minPublishedDocuments: Long = 1
): PipelineSourceReadiness {
    val readiness = runner.cachedSourceReadiness(cache, sourceName)
        ?: throw AssertionError("$label readiness is unavailable")
    require(readiness.isPublicationReady(minPublishedDocuments)) {
        "$label is still warming up: ${readiness.publicationReadinessReason(minPublishedDocuments)}"
    }
    return readiness
}

suspend fun TestRunner.awaitSearchReady(
    sourceName: String,
    maxWaitMs: Long = readinessWaitMs("TEST_SOURCE_SEARCH_READY_MAX_WAIT_SECONDS", 300),
    pollIntervalMs: Long = 5000L
): PipelineSourceReadiness? {
    val deadline = System.currentTimeMillis() + maxWaitMs
    var lastReadiness: PipelineSourceReadiness? = null

    while (true) {
        val readiness = getPipelineSourceReadiness(sourceName)
        if (readiness == null) {
            return lastReadiness
        }

        lastReadiness = readiness
        if (readiness.isSearchReady()) {
            return readiness
        }

        if (System.currentTimeMillis() >= deadline) {
            return readiness
        }

        val shouldContinueWaiting =
            readiness.activeRun ||
                !readiness.initialPullComplete ||
                readiness.blockers.any {
                    it.contains("stats unavailable", ignoreCase = true) ||
                        it.contains("no searchable documents", ignoreCase = true)
                }

        if (!shouldContinueWaiting) {
            return readiness
        }

        delay(pollIntervalMs)
    }
}

suspend fun TestRunner.awaitPublicationReady(
    sourceName: String,
    maxWaitMs: Long = readinessWaitMs("TEST_SOURCE_PUBLICATION_READY_MAX_WAIT_SECONDS", 300),
    pollIntervalMs: Long = 5000L
): PipelineSourceReadiness? {
    val deadline = System.currentTimeMillis() + maxWaitMs
    var lastReadiness: PipelineSourceReadiness? = null

    while (true) {
        val readiness = getPipelineSourceReadiness(sourceName)
        if (readiness == null) {
            return lastReadiness
        }

        lastReadiness = readiness
        if (readiness.isPublicationReady()) {
            return readiness
        }

        if (System.currentTimeMillis() >= deadline) {
            return readiness
        }

        val shouldContinueWaiting =
            readiness.activeRun ||
                !readiness.initialPullComplete ||
                readiness.blockers.any {
                    it.contains("stats unavailable", ignoreCase = true) ||
                        it.contains("no searchable documents", ignoreCase = true)
                }

        if (!shouldContinueWaiting) {
            return readiness
        }

        delay(pollIntervalMs)
    }
}
