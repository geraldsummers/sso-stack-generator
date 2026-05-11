package org.webservices.inferencecontroller

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

open class HttpHealthClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    open fun isHealthy(backend: ManagedBackend): Boolean {
        val request = Request.Builder()
            .url(backend.baseUrl.trimEnd('/') + backend.healthPath)
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return false
                }
                val body = response.body?.string().orEmpty()
                val expectedModel = backend.expectedModel
                expectedModel == null || responseMatchesExpectedModel(body, expectedModel)
            }
        }.getOrElse { false }
    }

    private fun responseMatchesExpectedModel(body: String, expectedModel: String): Boolean {
        if (body.contains(expectedModel)) {
            return true
        }
        val normalizedExpected = expectedModel.substringBefore(':')
        return runCatching {
            val payload = json.parseToJsonElement(body).jsonObject
            val models = payload["models"]?.jsonArray ?: return false
            models.any { element ->
                val name = element.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                name.substringBefore(':') == normalizedExpected
            }
        }.getOrDefault(false)
    }
}
