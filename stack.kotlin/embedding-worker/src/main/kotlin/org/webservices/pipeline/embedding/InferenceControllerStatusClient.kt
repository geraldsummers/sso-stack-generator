package org.webservices.pipeline.embedding

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class InferenceControllerStatusClient(
    private val statusUrl: String,
    timeoutMs: Long = 2_000,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
) {
    suspend fun fetchEmbeddingTarget(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(statusUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Inference controller status returned ${response.code}")
            }

            val body = response.body?.string()
                ?: throw IllegalStateException("Inference controller status response was empty")
            val json = JsonParser.parseString(body).asJsonObject
            json.get("embeddingTarget")
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Inference controller status did not include embeddingTarget")
        }
    }
}
