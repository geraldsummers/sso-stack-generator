package org.webservices.pipeline.sources.standardized

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webservices.pipeline.core.Chunkable
import org.webservices.pipeline.core.PresentationMetadataKeys
import org.webservices.pipeline.core.StandardizedSource
import org.webservices.pipeline.scheduling.BackfillStrategy
import org.webservices.pipeline.scheduling.ResyncStrategy
import org.webservices.pipeline.scheduling.RunMetadata
import java.time.Duration
import java.util.concurrent.TimeUnit

private val poeNinjaLogger = KotlinLogging.logger {}

data class PoeNinjaPriceDocument(
    val league: String,
    val category: String,
    val type: String,
    val itemId: String,
    val name: String,
    val baseType: String?,
    val variant: String?,
    val chaosValue: Double?,
    val divineValue: Double?,
    val primaryValue: Double?,
    val listingCount: Int?,
    val count: Int?,
    val totalChangePercent: Double?,
    val detailsId: String?
) : Chunkable {
    override fun toText(): String = buildString {
        appendLine("Path of Exile poe.ninja price: $name")
        appendLine("League: $league")
        appendLine("Category: $category")
        appendLine("Type: $type")
        baseType?.let { appendLine("Base type: $it") }
        variant?.let { appendLine("Variant: $it") }
        chaosValue?.let { appendLine("Chaos value: $it") }
        divineValue?.let { appendLine("Divine value: $it") }
        primaryValue?.let { appendLine("Primary value: $it") }
        listingCount?.let { appendLine("Listings: $it") }
        count?.let { appendLine("Sample count: $it") }
        totalChangePercent?.let { appendLine("Seven day change percent: $it") }
    }

    override fun getId(): String =
        "poe_ninja_${league.normalizedKey()}_${category.normalizedKey()}_${type.normalizedKey()}_${itemId.normalizedKey()}"

    override fun getMetadata(): Map<String, String> = buildMap {
        val url = poeNinjaOverviewUrl(league, type)
        put("source", "poe_ninja_prices")
        put("title", "$name price in $league")
        put("name", name)
        put("url", url)
        put(PresentationMetadataKeys.URL, url)
        put("content_type", "market_data")
        put("audience", "both")
        put("league", league)
        put("category", category)
        put("type", type)
        put("item_id", itemId)
        baseType?.let { put("base_type", it) }
        variant?.let { put("variant", it) }
        chaosValue?.let { put("chaos_value", it.toString()) }
        divineValue?.let { put("divine_value", it.toString()) }
        primaryValue?.let { put("primary_value", it.toString()) }
        listingCount?.let { put("listing_count", it.toString()) }
        count?.let { put("count", it.toString()) }
        totalChangePercent?.let { put("seven_day_change_percent", it.toString()) }
        detailsId?.let { put("details_id", it) }
    }
}

class PoeNinjaPriceStandardizedSource(
    private val baseUrl: String = "https://poe.ninja",
    private val leagues: List<String> = listOf("Standard"),
    private val currencyTypes: List<String> = listOf("Currency"),
    private val itemTypes: List<String> = DEFAULT_ITEM_TYPES,
    private val maxEntriesPerType: Int = 1000,
    private val requestDelayMs: Long = 500L,
    private val scheduleMinutes: Int = 360,
    private val client: OkHttpClient = defaultPoeNinjaHttpClient()
) : StandardizedSource<PoeNinjaPriceDocument> {
    override val name = "poe_ninja_prices"

    override fun resyncStrategy(): ResyncStrategy =
        ResyncStrategy.FixedInterval(Duration.ofMinutes(scheduleMinutes.coerceAtLeast(15).toLong()))

    override fun backfillStrategy(): BackfillStrategy = BackfillStrategy.NoBackfill

    override fun needsChunking(): Boolean = false

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<PoeNinjaPriceDocument> {
        val documents = mutableListOf<PoeNinjaPriceDocument>()
        val normalizedLeagues = leagues.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
        val normalizedCurrencyTypes = currencyTypes.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
        val normalizedItemTypes = itemTypes.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()

        for (league in normalizedLeagues) {
            for (type in normalizedCurrencyTypes) {
                documents += fetchCurrencyOverview(league, type)
                delayBetweenRequests()
            }
            for (type in normalizedItemTypes) {
                documents += fetchItemOverview(league, type)
                delayBetweenRequests()
            }
        }

        return documents.asFlow()
    }

    private suspend fun fetchCurrencyOverview(league: String, type: String): List<PoeNinjaPriceDocument> {
        val json = fetchJson(
            pathSegments = listOf("poe1", "api", "economy", "exchange", "current", "overview"),
            query = mapOf("league" to league, "type" to type)
        )
        val coreItems = json.getAsJsonObject("core")
            ?.getAsJsonArray("items")
            ?.mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject }
            ?.associateBy { item ->
                item.stringOrNull("id") ?: item.stringOrNull("name").orEmpty()
            }
            ?: emptyMap()

        return json.getAsJsonArray("lines")
            ?.mapNotNull { line -> line.takeIf { it.isJsonObject }?.asJsonObject }
            ?.take(maxEntriesPerType.coerceAtLeast(1))
            ?.mapNotNull { line ->
                val id = line.stringOrNull("id") ?: return@mapNotNull null
                val item = coreItems[id]
                val name = item?.stringOrNull("name") ?: id
                PoeNinjaPriceDocument(
                    league = league,
                    category = "currency",
                    type = type,
                    itemId = id,
                    name = name,
                    baseType = null,
                    variant = null,
                    chaosValue = line.doubleOrNull("primaryValue"),
                    divineValue = null,
                    primaryValue = line.doubleOrNull("primaryValue"),
                    listingCount = null,
                    count = null,
                    totalChangePercent = line.getAsJsonObject("sparkline")?.doubleOrNull("totalChange"),
                    detailsId = item?.stringOrNull("detailsId")
                )
            }
            ?: emptyList()
    }

    private suspend fun fetchItemOverview(league: String, type: String): List<PoeNinjaPriceDocument> {
        val json = fetchJson(
            pathSegments = listOf("poe1", "api", "economy", "stash", "current", "item", "overview"),
            query = mapOf("league" to league, "type" to type)
        )

        return json.getAsJsonArray("lines")
            ?.mapNotNull { line -> line.takeIf { it.isJsonObject }?.asJsonObject }
            ?.take(maxEntriesPerType.coerceAtLeast(1))
            ?.mapNotNull { line ->
                val name = line.stringOrNull("name") ?: return@mapNotNull null
                val id = line.stringOrNull("id")
                    ?: line.stringOrNull("detailsId")
                    ?: listOfNotNull(name, line.stringOrNull("baseType"), line.stringOrNull("variant")).joinToString("-")
                PoeNinjaPriceDocument(
                    league = league,
                    category = "item",
                    type = type,
                    itemId = id,
                    name = name,
                    baseType = line.stringOrNull("baseType"),
                    variant = line.stringOrNull("variant"),
                    chaosValue = line.doubleOrNull("chaosValue"),
                    divineValue = line.doubleOrNull("divineValue"),
                    primaryValue = line.doubleOrNull("chaosValue"),
                    listingCount = line.intOrNull("listingCount"),
                    count = line.intOrNull("count"),
                    totalChangePercent = line.getAsJsonObject("sparkLine")?.doubleOrNull("totalChange"),
                    detailsId = line.stringOrNull("detailsId")
                )
            }
            ?: emptyList()
    }

    private suspend fun fetchJson(pathSegments: List<String>, query: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val builder = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        pathSegments.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val url = builder.build()
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "webservices-knowledge-ingestion/1.0")
                .build()
        ).execute()

        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("poe.ninja request failed for $url: HTTP ${it.code}")
            }
            JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject
        }
    }

    private suspend fun delayBetweenRequests() {
        if (requestDelayMs > 0) {
            delay(requestDelayMs)
        }
    }

    private fun JsonObject.stringOrNull(field: String): String? {
        val value = get(field) ?: return null
        return value.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.doubleOrNull(field: String): Double? {
        val value = get(field) ?: return null
        return value.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    }

    private fun JsonObject.intOrNull(field: String): Int? {
        val value = get(field) ?: return null
        return value.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    }

    companion object {
        val DEFAULT_ITEM_TYPES = listOf(
            "UniqueWeapon",
            "UniqueArmour",
            "UniqueAccessory",
            "UniqueFlask",
            "UniqueJewel",
            "SkillGem",
            "DivinationCard",
            "Map"
        )
    }
}

private fun defaultPoeNinjaHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

private fun String.normalizedKey(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "unknown" }

private fun poeNinjaOverviewUrl(league: String, type: String): String {
    val leagueSlug = league.normalizedKey().replace('_', '-')
    val typeSlug = POE_NINJA_TYPE_URLS[type] ?: type
        .replace(Regex("([a-z])([A-Z])"), "$1-$2")
        .lowercase()
    return "https://poe.ninja/poe1/economy/$leagueSlug/$typeSlug"
}

private val POE_NINJA_TYPE_URLS = mapOf(
    "Currency" to "currency",
    "UniqueWeapon" to "unique-weapons",
    "UniqueArmour" to "unique-armours",
    "UniqueAccessory" to "unique-accessories",
    "UniqueFlask" to "unique-flasks",
    "UniqueJewel" to "unique-jewels",
    "SkillGem" to "skill-gems",
    "DivinationCard" to "divination-cards",
    "Map" to "maps"
)
