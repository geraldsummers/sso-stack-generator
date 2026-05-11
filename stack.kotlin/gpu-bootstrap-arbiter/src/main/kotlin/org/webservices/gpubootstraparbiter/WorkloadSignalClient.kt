package org.webservices.gpubootstraparbiter

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface WorkloadSignalClient {
    suspend fun fetch(): WorkloadSignalSnapshot?
}

class HttpWorkloadSignalClient(
    private val config: ArbiterConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : WorkloadSignalClient {
    override suspend fun fetch(): WorkloadSignalSnapshot? {
        val request = Request.Builder()
            .url(config.signalUrl)
            .apply {
                config.signalApiKey?.let { header("X-API-Key", it) }
            }
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val body = response.body?.string() ?: return null
            json.decodeFromString<WorkloadSignalSnapshot>(body)
        }
    }
}
