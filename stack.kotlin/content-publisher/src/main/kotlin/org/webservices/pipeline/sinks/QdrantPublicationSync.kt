package org.webservices.pipeline.sinks

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webservices.pipeline.core.PresentationMetadataKeys
import org.webservices.pipeline.core.PresentationTargets
import org.webservices.pipeline.storage.StagedDocument
import java.security.MessageDigest
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private fun String.toDeterministicPointId(): BigInteger {
    val hash = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    val uuid = UUID.nameUUIDFromBytes(hash.copyOf(16))
    return BigInteger(java.lang.Long.toUnsignedString(uuid.mostSignificantBits xor uuid.leastSignificantBits))
}

class QdrantPublicationSync(
    qdrantHttpUrl: String,
    private val apiKey: String = "",
    private val maxConcurrentRequests: Int = 8
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = qdrantHttpUrl.trimEnd('/')
    private val publicationIndexesReady = ConcurrentHashMap.newKeySet<String>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun markPublished(doc: StagedDocument, bookstackUrl: String) {
        ensurePublicationIndex(doc.collection)
        val payload = mapOf(
            "bookstack_url" to bookstackUrl,
            PresentationMetadataKeys.TARGET to PresentationTargets.BOOKSTACK,
            PresentationMetadataKeys.URL to bookstackUrl,
            PresentationMetadataKeys.SEARCH_READY to true,
            "published" to true,
            "document_id" to doc.id
        )
        updatePayload(doc.collection, doc.id.toDeterministicPointId(), payload)
    }

    suspend fun syncPublishedDocuments(docs: List<StagedDocument>) = coroutineScope {
        if (docs.isEmpty()) {
            return@coroutineScope
        }

        docs.map { it.collection }.toSet().forEach { collection ->
            ensurePublicationIndex(collection)
        }

        val semaphore = Semaphore(maxConcurrentRequests.coerceAtLeast(1))
        docs.map { doc ->
            async {
                val bookstackUrl = doc.bookstackUrl
                if (bookstackUrl.isNullOrBlank()) {
                    return@async
                }
                semaphore.withPermit {
                    try {
                        markPublished(doc, bookstackUrl)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to sync Qdrant publication payload for ${doc.collection}/${doc.id}" }
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun ensurePublicationIndex(collection: String) = withContext(Dispatchers.IO) {
        if (!publicationIndexesReady.add(collection)) {
            return@withContext
        }

        val body = gson.toJson(
            mapOf(
                "field_name" to PresentationMetadataKeys.SEARCH_READY,
                "field_schema" to "bool"
            )
        )

        val request = Request.Builder()
            .url("$baseUrl/collections/$collection/index")
            .put(body.toRequestBody(jsonMediaType))
            .apply {
                if (apiKey.isNotBlank()) {
                    header("api-key", apiKey)
                }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(500).orEmpty()
                publicationIndexesReady.remove(collection)
                throw IllegalStateException(
                    "Qdrant publication index setup failed for $collection: HTTP ${response.code} ${response.message} $errorBody"
                )
            }
        }
        logger.info { "Ensured Qdrant search_ready index for $collection" }
    }

    private suspend fun updatePayload(collection: String, pointId: BigInteger, payload: Map<String, Any>) = withContext(Dispatchers.IO) {
        val body = gson.toJson(
            mapOf(
                "payload" to payload,
                "points" to listOf(pointId)
            )
        )

        val request = Request.Builder()
            .url("$baseUrl/collections/$collection/points/payload")
            .post(body.toRequestBody(jsonMediaType))
            .apply {
                if (apiKey.isNotBlank()) {
                    header("api-key", apiKey)
                }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(500).orEmpty()
                throw IllegalStateException(
                    "Qdrant payload sync failed for $collection/$pointId: HTTP ${response.code} ${response.message} $errorBody"
                )
            }
        }
        logger.debug { "Synced Qdrant publication payload for $collection/$pointId" }
    }
}
