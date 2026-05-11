package org.webservices.inferencegateway

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface InferenceControllerStatusClient {
    suspend fun fetch(): InferenceControllerStatusPayload?
}

class HttpInferenceControllerStatusClient(
    private val config: InferenceGatewayConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : InferenceControllerStatusClient {
    override suspend fun fetch(): InferenceControllerStatusPayload? {
        val request = Request.Builder()
            .url(config.controllerStatusUrl)
            .get()
            .apply {
                config.controllerApiToken?.let { header("X-Internal-Api-Token", it) }
            }
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val body = response.body?.string() ?: return null
            json.decodeFromString<InferenceControllerStatusPayload>(body)
        }
    }
}
