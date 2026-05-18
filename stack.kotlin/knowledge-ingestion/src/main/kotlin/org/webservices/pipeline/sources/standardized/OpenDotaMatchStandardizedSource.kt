package org.webservices.pipeline.sources.standardized

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webservices.pipeline.core.Chunkable
import org.webservices.pipeline.core.PresentationMetadataKeys
import org.webservices.pipeline.core.StandardizedSource
import org.webservices.pipeline.scheduling.BackfillStrategy
import org.webservices.pipeline.scheduling.ResyncStrategy
import org.webservices.pipeline.scheduling.RunMetadata
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

private val openDotaLogger = KotlinLogging.logger {}

data class OpenDotaMatchDocument(
    val matchId: Long,
    val matchSeqNum: Long?,
    val radiantWin: Boolean?,
    val startTime: Long?,
    val durationSeconds: Int?,
    val lobbyType: Int?,
    val gameMode: Int?,
    val averageRankTier: Int?,
    val cluster: Int?,
    val radiantTeam: List<Int>,
    val direTeam: List<Int>,
    val detailJson: String? = null
) : Chunkable {
    override fun toText(): String = buildString {
        appendLine("OpenDota match $matchId")
        startTime?.let { appendLine("Started: ${Instant.ofEpochSecond(it)}") }
        durationSeconds?.let { appendLine("Duration seconds: $it") }
        radiantWin?.let { appendLine("Winner: ${if (it) "Radiant" else "Dire"}") }
        lobbyType?.let { appendLine("Lobby type: $it") }
        gameMode?.let { appendLine("Game mode: $it") }
        averageRankTier?.let { appendLine("Average rank tier: $it") }
        cluster?.let { appendLine("Cluster: $it") }
        if (radiantTeam.isNotEmpty()) appendLine("Radiant heroes: ${radiantTeam.joinToString(", ")}")
        if (direTeam.isNotEmpty()) appendLine("Dire heroes: ${direTeam.joinToString(", ")}")
        if (!detailJson.isNullOrBlank()) {
            appendLine()
            appendLine("Match detail JSON:")
            appendLine(detailJson)
        }
    }

    override fun getId(): String = "opendota_match_$matchId"

    override fun getMetadata(): Map<String, String> = buildMap {
        val url = "https://www.opendota.com/matches/$matchId"
        put("source", "opendota_matches")
        put("title", "OpenDota match $matchId")
        put("url", url)
        put(PresentationMetadataKeys.URL, url)
        put("content_type", "dataset")
        put("audience", "both")
        put("match_id", matchId.toString())
        matchSeqNum?.let { put("match_seq_num", it.toString()) }
        radiantWin?.let { put("radiant_win", it.toString()) }
        startTime?.let {
            put("start_time", it.toString())
            put("started_at", Instant.ofEpochSecond(it).toString())
        }
        durationSeconds?.let { put("duration_seconds", it.toString()) }
        lobbyType?.let { put("lobby_type", it.toString()) }
        gameMode?.let { put("game_mode", it.toString()) }
        averageRankTier?.let { put("avg_rank_tier", it.toString()) }
        cluster?.let { put("cluster", it.toString()) }
        if (radiantTeam.isNotEmpty()) put("radiant_team", radiantTeam.joinToString(","))
        if (direTeam.isNotEmpty()) put("dire_team", direTeam.joinToString(","))
    }
}

class OpenDotaMatchStandardizedSource(
    private val baseUrl: String = "https://api.opendota.com/api",
    private val maxMatches: Int = 100,
    private val fetchMatchDetails: Boolean = false,
    private val scheduleMinutes: Int = 60,
    private val client: OkHttpClient = defaultOpenDotaHttpClient()
) : StandardizedSource<OpenDotaMatchDocument> {
    override val name = "opendota_matches"

    override fun resyncStrategy(): ResyncStrategy =
        ResyncStrategy.FixedInterval(Duration.ofMinutes(scheduleMinutes.coerceAtLeast(5).toLong()))

    override fun backfillStrategy(): BackfillStrategy = BackfillStrategy.NoBackfill

    override fun needsChunking(): Boolean = fetchMatchDetails

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<OpenDotaMatchDocument> {
        val matches = fetchPublicMatches().take(maxMatches.coerceAtLeast(1)).mapNotNull { match ->
            try {
                val matchId = match.get("match_id")?.asLong ?: return@mapNotNull null
                val detailJson = if (fetchMatchDetails) fetchMatchDetail(matchId) else null
                OpenDotaMatchDocument(
                    matchId = matchId,
                    matchSeqNum = match.get("match_seq_num")?.takeIf { !it.isJsonNull }?.asLong,
                    radiantWin = match.get("radiant_win")?.takeIf { !it.isJsonNull }?.asBoolean,
                    startTime = match.get("start_time")?.takeIf { !it.isJsonNull }?.asLong,
                    durationSeconds = match.get("duration")?.takeIf { !it.isJsonNull }?.asInt,
                    lobbyType = match.get("lobby_type")?.takeIf { !it.isJsonNull }?.asInt,
                    gameMode = match.get("game_mode")?.takeIf { !it.isJsonNull }?.asInt,
                    averageRankTier = match.get("avg_rank_tier")?.takeIf { !it.isJsonNull }?.asInt,
                    cluster = match.get("cluster")?.takeIf { !it.isJsonNull }?.asInt,
                    radiantTeam = match.heroList("radiant_team"),
                    direTeam = match.heroList("dire_team"),
                    detailJson = detailJson
                )
            } catch (e: Exception) {
                openDotaLogger.warn(e) { "Skipping malformed OpenDota match record" }
                null
            }
        }
        return matches.asFlow()
    }

    private suspend fun fetchPublicMatches(): List<JsonObject> = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/publicMatches")
                .header("User-Agent", "webservices-knowledge-ingestion/1.0")
                .build()
        ).execute()

        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("OpenDota publicMatches failed: HTTP ${it.code}")
            }
            val body = it.body?.string().orEmpty()
            JsonParser.parseString(body).asJsonArray
                .mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
        }
    }

    private suspend fun fetchMatchDetail(matchId: Long): String? = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/matches/$matchId")
                .header("User-Agent", "webservices-knowledge-ingestion/1.0")
                .build()
        ).execute()

        response.use {
            if (!it.isSuccessful) {
                openDotaLogger.warn { "OpenDota match detail $matchId failed: HTTP ${it.code}" }
                return@withContext null
            }
            it.body?.string()
        }
    }

    private fun JsonObject.heroList(field: String): List<Int> {
        val element = get(field) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { hero ->
            hero.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        }
    }
}

private fun defaultOpenDotaHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
