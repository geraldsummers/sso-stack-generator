package org.webservices.gpuworkloadmonitor

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webservices.pipeline.monitoring.SourceReadiness
import java.util.concurrent.TimeUnit

interface SourceReadinessClient {
    suspend fun fetch(sourceId: String): SourceReadiness?
}

class HttpSourceReadinessClient(
    private val config: WorkloadMonitorConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
) : SourceReadinessClient {
    override suspend fun fetch(sourceId: String): SourceReadiness? {
        val request = Request.Builder()
            .url("${config.knowledgeIngestionReadinessBaseUrl}/$sourceId")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val body = response.body?.string() ?: return null
            json.decodeFromString<SourceReadiness>(body)
        }
    }
}
