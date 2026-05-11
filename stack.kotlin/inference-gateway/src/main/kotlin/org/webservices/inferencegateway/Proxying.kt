package org.webservices.inferencegateway

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID

internal data class ProxyTrace(
    val clientRequestId: String,
    val requestId: String
)

private val proxyJson = Json { ignoreUnknownKeys = true }

internal data class NormalizedRequestBody(
    val bytes: ByteArray,
    val removedToolCount: Int = 0
)

internal data class NormalizedResponseBody(
    val bytes: ByteArray,
    val rewrittenToolCallCount: Int = 0
)

private data class ResponsesTranslation(
    val requestBytes: ByteArray,
    val responseId: String,
    val stream: Boolean,
    val requestedModel: String?
)

internal fun extractRequestedModel(body: ByteArray): String? {
    return runCatching {
        proxyJson.parseToJsonElement(body.decodeToString())
            .jsonObject["model"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

internal fun fetchAggregatedModels(
    targets: List<ResolvedGatewayTarget>,
    proxyClient: OkHttpClient
): JsonObject {
    val modelsById = LinkedHashMap<String, JsonObject>()

    targets.forEach { target ->
        val request = Request.Builder()
            .url(target.baseUrl.trimEnd('/') + "/v1/models")
            .get()
            .build()
        runCatching {
            proxyClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use
                }
                val body = response.body?.string().orEmpty()
                val payload = proxyJson.parseToJsonElement(body).jsonObject
                val data = payload["data"]?.jsonArray ?: JsonArray(emptyList())
                data.forEach { modelElement ->
                    val model = runCatching { modelElement.jsonObject }.getOrNull() ?: return@forEach
                    val id = model["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (id.isBlank() || modelsById.containsKey(id)) {
                        return@forEach
                    }
                    modelsById[id] = model.withGatewayOwnership(target.serviceName)
                }
            }
        }
    }

    return buildJsonObject {
        put("object", JsonPrimitive("list"))
        put("data", JsonArray(modelsById.values.toList()))
    }
}

private fun JsonObject.withGatewayOwnership(serviceName: String): JsonObject {
    if (this["owned_by"] != null) {
        return this
    }
    return buildJsonObject {
        this@withGatewayOwnership.forEach { (key, value) -> put(key, value) }
        put("owned_by", JsonPrimitive(serviceName))
    }
}

@Suppress("DEPRECATION")
internal suspend fun ApplicationCall.proxyRequest(
    requestUri: String,
    baseUrl: String,
    proxyClient: OkHttpClient
) {
    val upstreamUrl = baseUrl.trimEnd('/') + if (requestUri.startsWith('/')) requestUri else "/$requestUri"
    val (requestBuilder, trace) = prepareProxyRequest(upstreamUrl)

    val method = request.httpMethod.value
    val requestBody = if (method in setOf("GET", "HEAD")) {
        null
    } else {
        val mediaType = request.headers["Content-Type"]?.toMediaTypeOrNull() ?: "application/json".toMediaTypeOrNull()
        streamedRequestBody(
            input = receiveStream(),
            mediaType = mediaType,
            contentLength = request.headers["Content-Length"]?.toLongOrNull()
        )
    }
    requestBuilder.method(method, requestBody)

    try {
        proxyClient.newCall(requestBuilder.build()).execute().use { response ->
            copyProxyResponseHeaders(response.headers.names(), response::headers, trace)
            val upstreamRequestId = response.header("x-request-id")
            application.log.info(
                "proxy request completed method={} upstream={} client_request_id={} request_id={} upstream_request_id={} status={}",
                method,
                upstreamUrl,
                trace.clientRequestId,
                trace.requestId,
                upstreamRequestId ?: "<missing>",
                response.code
            )
            val contentType = response.body?.contentType()?.toString()?.let(ContentType::parse)
            respondOutputStream(
                contentType = contentType ?: ContentType.Application.Json,
                status = HttpStatusCode.fromValue(response.code)
            ) {
                response.body?.byteStream()?.use { input -> input.copyTo(this) }
            }
        }
    } catch (error: Exception) {
        application.log.warn(
            "proxy request failed method={} upstream={} client_request_id={} request_id={} message={}",
            method,
            upstreamUrl,
            trace.clientRequestId,
            trace.requestId,
            error.message ?: "unknown"
        )
        respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf(
                "error" to "upstream_unavailable",
                "message" to (error.message ?: "request to $upstreamUrl failed")
            )
        )
    }
}

@Suppress("DEPRECATION")
internal suspend fun ApplicationCall.proxyNormalizedJsonRequest(
    requestUri: String,
    baseUrl: String,
    proxyClient: OkHttpClient,
    normalize: (ByteArray) -> NormalizedRequestBody
) {
    val upstreamUrl = baseUrl.trimEnd('/') + if (requestUri.startsWith('/')) requestUri else "/$requestUri"
    val (requestBuilder, trace) = prepareProxyRequest(upstreamUrl)

    val method = request.httpMethod.value
    val requestBody = if (method in setOf("GET", "HEAD")) {
        null
    } else {
        val mediaType = request.headers["Content-Type"]?.toMediaTypeOrNull() ?: "application/json".toMediaTypeOrNull()
        val normalized = normalize(receiveStream().readBytes())
        if (normalized.removedToolCount > 0) {
            application.log.info(
                "normalized upstream request method={} upstream={} client_request_id={} request_id={} removed_non_function_tools={}",
                method,
                upstreamUrl,
                trace.clientRequestId,
                trace.requestId,
                normalized.removedToolCount
            )
        }
        RequestBody.create(mediaType, normalized.bytes)
    }
    requestBuilder.method(method, requestBody)

    try {
        proxyClient.newCall(requestBuilder.build()).execute().use { response ->
            copyProxyResponseHeaders(response.headers.names(), response::headers, trace)
            val upstreamRequestId = response.header("x-request-id")
            application.log.info(
                "proxy request completed method={} upstream={} client_request_id={} request_id={} upstream_request_id={} status={}",
                method,
                upstreamUrl,
                trace.clientRequestId,
                trace.requestId,
                upstreamRequestId ?: "<missing>",
                response.code
            )
            val contentType = response.body?.contentType()?.toString()?.let(ContentType::parse)
            respondOutputStream(
                contentType = contentType ?: ContentType.Application.Json,
                status = HttpStatusCode.fromValue(response.code)
            ) {
                response.body?.byteStream()?.use { input -> input.copyTo(this) }
            }
        }
    } catch (error: Exception) {
        application.log.warn(
            "proxy request failed method={} upstream={} client_request_id={} request_id={} message={}",
            method,
            upstreamUrl,
            trace.clientRequestId,
            trace.requestId,
            error.message ?: "unknown"
        )
        respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf(
                "error" to "upstream_unavailable",
                "message" to (error.message ?: "request to $upstreamUrl failed")
            )
        )
    }
}

@Suppress("DEPRECATION")
internal suspend fun ApplicationCall.proxyBufferedRequest(
    requestUri: String,
    baseUrl: String,
    proxyClient: OkHttpClient,
    bodyBytes: ByteArray?,
    normalizeResponse: ((contentType: String?, body: ByteArray) -> NormalizedResponseBody)? = null
) {
    val upstreamUrl = baseUrl.trimEnd('/') + if (requestUri.startsWith('/')) requestUri else "/$requestUri"
    val (requestBuilder, trace) = prepareProxyRequest(upstreamUrl)

    val method = request.httpMethod.value
    val requestBody = if (method in setOf("GET", "HEAD")) {
        null
    } else {
        val mediaType = request.headers["Content-Type"]?.toMediaTypeOrNull() ?: "application/json".toMediaTypeOrNull()
        RequestBody.create(mediaType, bodyBytes ?: ByteArray(0))
    }
    requestBuilder.method(method, requestBody)

    try {
        proxyClient.newCall(requestBuilder.build()).execute().use { response ->
            copyProxyResponseHeaders(response.headers.names(), response::headers, trace)
            val upstreamRequestId = response.header("x-request-id")
            val contentTypeHeader = response.body?.contentType()?.toString()
            val responseBytes = response.body?.bytes() ?: ByteArray(0)
            val normalizedResponse = normalizeResponse?.invoke(contentTypeHeader, responseBytes)
                ?: NormalizedResponseBody(responseBytes)
            if (normalizedResponse.rewrittenToolCallCount > 0) {
                application.log.info(
                    "normalized upstream response method={} upstream={} client_request_id={} request_id={} rewritten_tool_calls={}",
                    method,
                    upstreamUrl,
                    trace.clientRequestId,
                    trace.requestId,
                    normalizedResponse.rewrittenToolCallCount
                )
            }
            application.log.info(
                "proxy request completed method={} upstream={} client_request_id={} request_id={} upstream_request_id={} status={}",
                method,
                upstreamUrl,
                trace.clientRequestId,
                trace.requestId,
                upstreamRequestId ?: "<missing>",
                response.code
            )
            val contentType = contentTypeHeader?.let(ContentType::parse)
            respondOutputStream(
                contentType = contentType ?: ContentType.Application.Json,
                status = HttpStatusCode.fromValue(response.code)
            ) {
                write(normalizedResponse.bytes)
            }
        }
    } catch (error: Exception) {
        application.log.warn(
            "proxy request failed method={} upstream={} client_request_id={} request_id={} message={}",
            method,
            upstreamUrl,
            trace.clientRequestId,
            trace.requestId,
            error.message ?: "unknown"
        )
        respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf(
                "error" to "upstream_unavailable",
                "message" to (error.message ?: "request to $upstreamUrl failed")
            )
        )
    }
}

@Suppress("DEPRECATION")
internal suspend fun ApplicationCall.proxyResponsesViaChatCompletions(
    baseUrl: String,
    proxyClient: OkHttpClient,
    bodyBytes: ByteArray
) {
    val upstreamUrl = baseUrl.trimEnd('/') + "/v1/chat/completions"
    val (requestBuilder, trace) = prepareProxyRequest(upstreamUrl)
    val translation = translateResponsesRequest(bodyBytes)
    requestBuilder.method(
        request.httpMethod.value,
        RequestBody.create("application/json".toMediaTypeOrNull(), translation.requestBytes)
    )

    try {
        proxyClient.newCall(requestBuilder.build()).execute().use { response ->
            copyProxyResponseHeaders(response.headers.names(), response::headers, trace)
            val upstreamRequestId = response.header("x-request-id")
            val responseBytes = response.body?.bytes() ?: ByteArray(0)
            application.log.info(
                "proxy request completed method={} upstream={} client_request_id={} request_id={} upstream_request_id={} status={}",
                request.httpMethod.value,
                upstreamUrl,
                trace.clientRequestId,
                trace.requestId,
                upstreamRequestId ?: "<missing>",
                response.code
            )

            if (!response.isSuccessful) {
                val contentType = response.body?.contentType()?.toString()?.let(ContentType::parse)
                respondOutputStream(
                    contentType = contentType ?: ContentType.Application.Json,
                    status = HttpStatusCode.fromValue(response.code)
                ) {
                    write(responseBytes)
                }
                return@use
            }

            if (translation.stream) {
                val eventStream = translateChatCompletionToResponsesSse(
                    responseBytes = responseBytes,
                    responseId = translation.responseId,
                    requestedModel = translation.requestedModel
                )
                respondOutputStream(
                    contentType = ContentType.Text.EventStream,
                    status = HttpStatusCode.OK
                ) {
                    write(eventStream)
                }
            } else {
                val translatedResponse = translateChatCompletionToResponsesJson(
                    responseBytes = responseBytes,
                    responseId = translation.responseId,
                    requestedModel = translation.requestedModel
                )
                respondOutputStream(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK
                ) {
                    write(translatedResponse)
                }
            }
        }
    } catch (error: Exception) {
        application.log.warn(
            "proxy request failed method={} upstream={} client_request_id={} request_id={} message={}",
            request.httpMethod.value,
            upstreamUrl,
            trace.clientRequestId,
            trace.requestId,
            error.message ?: "unknown"
        )
        respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf(
                "error" to "upstream_unavailable",
                "message" to (error.message ?: "request to $upstreamUrl failed")
            )
        )
    }
}

internal fun streamedRequestBody(
    input: InputStream,
    mediaType: MediaType?,
    contentLength: Long?
): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = contentLength ?: -1L

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        input.use { stream ->
            val output = sink.outputStream()
            stream.copyTo(output)
            output.flush()
        }
    }
}

internal fun normalizeResponsesToolsForLlamaCpp(body: ByteArray): NormalizedRequestBody {
    val parsed = runCatching {
        proxyJson.parseToJsonElement(body.decodeToString()).jsonObject
    }.getOrNull() ?: return NormalizedRequestBody(body)

    val tools = parsed["tools"]?.jsonArray ?: return NormalizedRequestBody(body)
    val functionTools = tools.filter { tool ->
        runCatching {
            tool.jsonObject["type"]?.jsonPrimitive?.content == "function"
        }.getOrDefault(false)
    }
    val removedToolCount = tools.size - functionTools.size
    if (removedToolCount == 0) {
        return NormalizedRequestBody(body)
    }

    val normalized = buildJsonObject {
        parsed.forEach { (key, value) ->
            if (key == "tools") {
                if (functionTools.isNotEmpty()) {
                    put("tools", JsonArray(functionTools))
                }
            } else {
                put(key, value)
            }
        }
    }
    return NormalizedRequestBody(
        bytes = proxyJson.encodeToString(JsonObject.serializer(), normalized).toByteArray(),
        removedToolCount = removedToolCount
    )
}

private fun translateResponsesRequest(bodyBytes: ByteArray): ResponsesTranslation {
    val payload = runCatching { proxyJson.parseToJsonElement(bodyBytes.decodeToString()).jsonObject }.getOrNull()
        ?: return ResponsesTranslation(
            requestBytes = bodyBytes,
            responseId = "resp_${UUID.randomUUID()}",
            stream = false,
            requestedModel = extractRequestedModel(bodyBytes)
        )

    val stream = payload["stream"]?.jsonPrimitive?.booleanOrNull == true
    val responseId = payload["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "resp_${UUID.randomUUID()}" }
    val requestedModel = payload["model"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    val translated = buildJsonObject {
        requestedModel?.let { put("model", JsonPrimitive(it)) }
        put("stream", JsonPrimitive(false))
        put("messages", translateResponsesInputToMessages(payload))

        payload["temperature"]?.let { put("temperature", it) }
        payload["top_p"]?.let { put("top_p", it) }
        payload["tools"]?.let { put("tools", it) }
        payload["tool_choice"]?.let { put("tool_choice", it) }
        payload["parallel_tool_calls"]?.let { put("parallel_tool_calls", it) }
        payload["max_output_tokens"]?.let { put("max_tokens", it) }
    }

    return ResponsesTranslation(
        requestBytes = proxyJson.encodeToString(JsonObject.serializer(), translated).toByteArray(),
        responseId = responseId,
        stream = stream,
        requestedModel = requestedModel
    )
}

private fun translateResponsesInputToMessages(payload: JsonObject): JsonArray {
    payload["messages"]?.let { messages ->
        return runCatching { messages.jsonArray }.getOrDefault(JsonArray(emptyList()))
    }

    val instructions = payload["instructions"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val input = payload["input"]
    val messages = buildJsonArray {
        if (instructions.isNotBlank()) {
            addChatMessage(role = "system", text = instructions)
        }
        when (input) {
            null, JsonNull -> {
            }
            is JsonPrimitive -> {
                input.contentOrNull?.takeIf { it.isNotBlank() }?.let { addChatMessage(role = "user", text = it) }
            }
            is JsonArray -> {
                input.forEach { item ->
                    val message = runCatching { item.jsonObject }.getOrNull() ?: return@forEach
                    val role = message["role"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "user" }
                    extractResponsesContentText(message["content"])?.takeIf { it.isNotBlank() }?.let { text ->
                        addChatMessage(role = role, text = text)
                    }
                }
            }
            is JsonObject -> {
                extractResponsesContentText(input["content"])?.takeIf { it.isNotBlank() }?.let { text ->
                    addChatMessage(
                        role = input["role"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "user" },
                        text = text
                    )
                }
            }
        }
    }
    return messages
}

private fun JsonArrayBuilder.addChatMessage(role: String, text: String) {
    add(
        buildJsonObject {
            put("role", JsonPrimitive(role))
            put("content", JsonPrimitive(text))
        }
    )
}

private fun extractResponsesContentText(content: JsonElement?): String? {
    return when (content) {
        null, JsonNull -> null
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { item ->
            val entry = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            when (entry["type"]?.jsonPrimitive?.contentOrNull) {
                "input_text", "output_text", "text" -> entry["text"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.joinToString(separator = "\n").ifBlank { null }
        is JsonObject -> content["text"]?.jsonPrimitive?.contentOrNull
        else -> null
    }
}

private fun translateChatCompletionToResponsesJson(
    responseBytes: ByteArray,
    responseId: String,
    requestedModel: String?
): ByteArray {
    val payload = runCatching { proxyJson.parseToJsonElement(responseBytes.decodeToString()).jsonObject }.getOrNull()
        ?: return responseBytes
    val response = buildResponsesEnvelope(payload, responseId, requestedModel, usageOverride = null)
    return proxyJson.encodeToString(JsonObject.serializer(), response).toByteArray()
}

private fun translateChatCompletionToResponsesSse(
    responseBytes: ByteArray,
    responseId: String,
    requestedModel: String?
): ByteArray {
    val payload = runCatching { proxyJson.parseToJsonElement(responseBytes.decodeToString()).jsonObject }.getOrNull()
        ?: return responseBytes
    val completed = buildResponsesEnvelope(payload, responseId, requestedModel, usageOverride = null)
    val completedResponse = completed.getValue("response").jsonObject
    val outputItem = completedResponse.getValue("output").jsonArray.firstOrNull()?.jsonObject ?: buildAssistantMessage("")
    val text = outputItem.getValue("content").jsonArray.firstOrNull()?.jsonObject
        ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
    val created = buildJsonObject {
        put("response", buildResponsesEnvelope(payload, responseId, requestedModel, usageOverride = JsonNull).getValue("response"))
    }
    val outputTextDelta = buildJsonObject {
        put("output_index", JsonPrimitive(0))
        put("content_index", JsonPrimitive(0))
        put("delta", JsonPrimitive(text))
    }
    val outputItemDone = buildJsonObject {
        put("output_index", JsonPrimitive(0))
        put("item", outputItem)
    }
    val blocks = listOf(
        sseEvent("response.created", created),
        sseEvent("response.output_text.delta", outputTextDelta),
        sseEvent("response.output_item.done", outputItemDone),
        sseEvent("response.completed", buildJsonObject { put("response", completedResponse) }),
        "data: [DONE]"
    )
    return blocks.joinToString(separator = "\n\n", postfix = "\n\n").toByteArray()
}

private fun sseEvent(name: String, payload: JsonObject): String =
    "event: $name\ndata: ${proxyJson.encodeToString(JsonObject.serializer(), payload)}"

private fun buildResponsesEnvelope(
    chatCompletion: JsonObject,
    responseId: String,
    requestedModel: String?,
    usageOverride: JsonElement?
): JsonObject {
    val assistantText = extractAssistantText(chatCompletion)
    val usage = usageOverride ?: translateUsage(chatCompletion, assistantText)
    val responseModel = requestedModel
        ?: chatCompletion["model"]?.jsonPrimitive?.contentOrNull
        ?: "unknown"
    val responseStatus = if (usageOverride is JsonNull) "in_progress" else "completed"
    val output = if (usageOverride is JsonNull) JsonArray(emptyList()) else JsonArray(listOf(buildAssistantMessage(assistantText)))
    return buildJsonObject {
        put(
            "response",
            buildJsonObject {
                put("id", JsonPrimitive(responseId))
                put("object", JsonPrimitive("response"))
                put("created_at", JsonPrimitive(Instant.now().toString()))
                put("status", JsonPrimitive(responseStatus))
                put("model", JsonPrimitive(responseModel))
                put("output", output)
                if (usageOverride !is JsonNull) {
                    put("usage", usage)
                }
            }
        )
    }
}

private fun buildAssistantMessage(text: String): JsonObject = buildJsonObject {
    put("id", JsonPrimitive("msg_${UUID.randomUUID()}"))
    put("type", JsonPrimitive("message"))
    put("role", JsonPrimitive("assistant"))
    put("status", JsonPrimitive("completed"))
    put(
        "content",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("output_text"))
                    put("text", JsonPrimitive(text))
                    put("annotations", JsonArray(emptyList()))
                }
            )
        }
    )
}

private fun extractAssistantText(chatCompletion: JsonObject): String {
    val choice = chatCompletion["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
    val message = choice["message"]?.jsonObject
    val content = message?.get("content")
    return when (content) {
        null, JsonNull -> ""
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString(separator = "\n") { item ->
            runCatching { item.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
        }
        else -> ""
    }
}

private fun translateUsage(chatCompletion: JsonObject, assistantText: String): JsonObject {
    val usage = chatCompletion["usage"]?.jsonObject
    val inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
    val outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: if (assistantText.isBlank()) 0 else 1
    val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: (inputTokens + outputTokens)
    return buildJsonObject {
        put("input_tokens", JsonPrimitive(inputTokens))
        put("output_tokens", JsonPrimitive(outputTokens))
        put("total_tokens", JsonPrimitive(totalTokens))
        put(
            "input_tokens_details",
            buildJsonObject {
                put("cached_tokens", JsonPrimitive(0))
            }
        )
        put(
            "output_tokens_details",
            buildJsonObject {
                put("reasoning_tokens", JsonPrimitive(0))
            }
        )
    }
}

internal fun normalizeResponsesToolCallsForVllm(
    contentType: String?,
    body: ByteArray
): NormalizedResponseBody {
    val normalizedContentType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when (normalizedContentType) {
        "text/event-stream" -> normalizeResponsesToolCallsForVllmSse(body)
        "application/json" -> normalizeResponsesToolCallsForVllmJson(body)
        else -> NormalizedResponseBody(body)
    }
}

internal fun normalizeResponsesToolCallsForVllmJson(body: ByteArray): NormalizedResponseBody {
    val payload = runCatching { proxyJson.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()
        ?: return NormalizedResponseBody(body)
    val (normalizedPayload, rewrittenToolCalls) = normalizeResponsesPayload(payload)
    if (rewrittenToolCalls == 0) {
        return NormalizedResponseBody(body)
    }
    return NormalizedResponseBody(
        bytes = proxyJson.encodeToString(JsonObject.serializer(), normalizedPayload).toByteArray(),
        rewrittenToolCallCount = rewrittenToolCalls
    )
}

internal fun normalizeResponsesToolCallsForVllmSse(body: ByteArray): NormalizedResponseBody {
    val blocks = body.decodeToString().split("\n\n")
    var rewrittenToolCalls = 0
    val normalizedBlocks = blocks.map { block ->
        val trimmed = block.trimEnd()
        if (trimmed.isBlank()) {
            return@map block
        }
        val lines = trimmed.lines()
        val eventName = lines.firstOrNull { it.startsWith("event:") }?.substringAfter("event:")?.trim()
        val dataLines = lines.filter { it.startsWith("data:") }.map { it.substringAfter("data:").trimStart() }
        if (eventName == null || dataLines.isEmpty()) {
            return@map block
        }
        val payload = runCatching { proxyJson.parseToJsonElement(dataLines.joinToString("\n")).jsonObject }.getOrNull()
            ?: return@map block
        val (normalizedPayload, rewrittenInBlock) = normalizeResponsesPayload(payload)
        if (rewrittenInBlock == 0) {
            return@map block
        }
        rewrittenToolCalls += rewrittenInBlock
        buildString {
            append("event: ")
            append(eventName)
            append('\n')
            append("data: ")
            append(proxyJson.encodeToString(JsonObject.serializer(), normalizedPayload))
        }
    }
    if (rewrittenToolCalls == 0) {
        return NormalizedResponseBody(body)
    }
    return NormalizedResponseBody(
        bytes = normalizedBlocks.joinToString("\n\n").toByteArray(),
        rewrittenToolCallCount = rewrittenToolCalls
    )
}

private fun normalizeResponsesPayload(payload: JsonObject): Pair<JsonObject, Int> {
    var rewrittenToolCalls = 0
    val normalizedPayload = buildJsonObject {
        payload.forEach { (key, value) ->
            when (key) {
                "output" -> {
                    val normalizedOutput = normalizePotentialToolCallOutput(value)
                    rewrittenToolCalls += normalizedOutput.second
                    put(key, normalizedOutput.first)
                }
                "item" -> {
                    val normalizedItem = normalizePotentialToolCallItem(value)
                    rewrittenToolCalls += normalizedItem.second
                    put(key, normalizedItem.first)
                }
                "response" -> {
                    val normalizedResponse = normalizePotentialToolCallResponse(value)
                    rewrittenToolCalls += normalizedResponse.second
                    put(key, normalizedResponse.first)
                }
                else -> put(key, value)
            }
        }
    }
    return normalizedPayload to rewrittenToolCalls
}

private fun normalizePotentialToolCallOutput(value: JsonElement): Pair<JsonElement, Int> {
    val output = runCatching { value.jsonArray }.getOrNull() ?: return value to 0
    var rewrittenToolCalls = 0
    val normalizedOutput = output.map { item ->
        val normalizedItem = normalizePotentialToolCallItem(item)
        rewrittenToolCalls += normalizedItem.second
        normalizedItem.first
    }
    return JsonArray(normalizedOutput) to rewrittenToolCalls
}

private fun normalizePotentialToolCallResponse(value: JsonElement): Pair<JsonElement, Int> {
    val responseObject = runCatching { value.jsonObject }.getOrNull() ?: return value to 0
    var rewrittenToolCalls = 0
    val normalizedResponse = buildJsonObject {
        responseObject.forEach { (key, nestedValue) ->
            if (key == "output") {
                val normalizedOutput = normalizePotentialToolCallOutput(nestedValue)
                rewrittenToolCalls += normalizedOutput.second
                put(key, normalizedOutput.first)
            } else {
                put(key, nestedValue)
            }
        }
    }
    return normalizedResponse to rewrittenToolCalls
}

private fun normalizePotentialToolCallItem(value: JsonElement): Pair<JsonElement, Int> {
    val item = runCatching { value.jsonObject }.getOrNull() ?: return value to 0
    if (item["type"]?.jsonPrimitive?.contentOrNull != "message") {
        return value to 0
    }
    val text = extractSingleAssistantText(item) ?: return value to 0
    val toolCall = parseToolCallFromAssistantText(text) ?: return value to 0
    val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: "fc_${UUID.randomUUID()}"
    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: "call_$itemId"
    val normalizedItem = buildJsonObject {
        put("id", JsonPrimitive(itemId))
        put("type", JsonPrimitive("function_call"))
        put("status", JsonPrimitive(item["status"]?.jsonPrimitive?.contentOrNull ?: "completed"))
        put("call_id", JsonPrimitive(callId))
        put("name", JsonPrimitive(toolCall.name))
        put("arguments", JsonPrimitive(toolCall.argumentsJson))
    }
    return normalizedItem to 1
}

private fun extractSingleAssistantText(item: JsonObject): String? {
    if (item["role"]?.jsonPrimitive?.contentOrNull != "assistant") {
        return null
    }
    val content = item["content"]?.jsonArray ?: return null
    if (content.size != 1) {
        return null
    }
    val contentItem = runCatching { content[0].jsonObject }.getOrNull() ?: return null
    if (contentItem["type"]?.jsonPrimitive?.contentOrNull != "output_text") {
        return null
    }
    return contentItem["text"]?.jsonPrimitive?.contentOrNull
}

private data class ParsedToolCall(
    val name: String,
    val argumentsJson: String
)

private fun parseToolCallFromAssistantText(text: String): ParsedToolCall? {
    return toolCallJsonCandidates(text).firstNotNullOfOrNull(::parseToolCallCandidate)
}

private fun parseToolCallCandidate(candidate: String): ParsedToolCall? {
    val stripped = candidate.trim()
    if (stripped.isBlank()) {
        return null
    }
    val parsed = runCatching { proxyJson.parseToJsonElement(stripped).jsonObject }.getOrNull() ?: return null
    val name = parsed["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (name.isBlank()) {
        return null
    }
    val argumentsElement = parsed["arguments"] ?: JsonObject(emptyMap())
    val argumentsJson = when (argumentsElement) {
        is JsonObject -> proxyJson.encodeToString(JsonObject.serializer(), argumentsElement)
        is JsonPrimitive -> argumentsElement.contentOrNull ?: return null
        else -> return null
    }
    return ParsedToolCall(name = name, argumentsJson = argumentsJson)
}

private fun toolCallJsonCandidates(text: String): Sequence<String> = sequence {
    val seen = LinkedHashSet<String>()
    val ordered = mutableListOf<String>()

    fun offer(candidate: String?) {
        val normalized = candidate?.trim().orEmpty()
        if (normalized.isBlank() || !seen.add(normalized)) {
            return
        }
        ordered += normalized
    }

    val trimmed = text.trim()
    offer(trimmed)

    Regex("""(?s)<tool_call>\s*(.*?)\s*</tool_call>""")
        .findAll(text)
        .forEach { match -> offer(match.groupValues.getOrNull(1)) }

    Regex("""(?s)```(?:json)?\s*(.*?)\s*```""")
        .findAll(text)
        .forEach { match -> offer(match.groupValues.getOrNull(1)) }

    balancedJsonObjects(text).forEach(::offer)
    yieldAll(ordered)
}

private fun balancedJsonObjects(text: String): Sequence<String> = sequence {
    var depth = 0
    var startIndex = -1
    var inString = false
    var escaping = false

    text.forEachIndexed { index, char ->
        if (escaping) {
            escaping = false
            return@forEachIndexed
        }

        when (char) {
            '\\' -> if (inString) {
                escaping = true
            }
            '"' -> inString = !inString
            '{' -> if (!inString) {
                if (depth == 0) {
                    startIndex = index
                }
                depth += 1
            }
            '}' -> if (!inString && depth > 0) {
                depth -= 1
                if (depth == 0 && startIndex >= 0) {
                    yield(text.substring(startIndex, index + 1))
                    startIndex = -1
                }
            }
        }
    }
}

internal fun ApplicationCall.prepareProxyRequest(upstreamUrl: String): Pair<Request.Builder, ProxyTrace> {
    val incomingClientRequestId = request.headers["X-Client-Request-Id"]?.trim().orEmpty().ifEmpty { null }
    val incomingRequestId = request.headers["X-Request-Id"]?.trim().orEmpty().ifEmpty { null }
    val correlatedId = incomingClientRequestId ?: incomingRequestId ?: UUID.randomUUID().toString()
    val trace = ProxyTrace(
        clientRequestId = incomingClientRequestId ?: correlatedId,
        requestId = incomingRequestId ?: correlatedId
    )
    val requestBuilder = Request.Builder().url(upstreamUrl)
    request.headers.forEach { key, values ->
        if (key.equals("host", ignoreCase = true) ||
            key.equals("content-length", ignoreCase = true) ||
            key.equals("x-internal-api-token", ignoreCase = true) ||
            key.equals("x-trusted-proxy-secret", ignoreCase = true)
        ) {
            return@forEach
        }
        values.forEach { value -> requestBuilder.addHeader(key, value) }
    }
    requestBuilder.header("X-Client-Request-Id", trace.clientRequestId)
    requestBuilder.header("X-Request-Id", trace.requestId)
    return requestBuilder to trace
}

internal fun ApplicationCall.copyProxyResponseHeaders(
    headerNames: Set<String>,
    valuesForHeader: (String) -> List<String>,
    trace: ProxyTrace
) {
    response.header("X-Client-Request-Id", trace.clientRequestId)
    response.header("X-Request-Id", trace.requestId)
    headerNames
        .filterNot {
            it.equals("transfer-encoding", ignoreCase = true) ||
                it.equals("content-length", ignoreCase = true) ||
                it.equals("connection", ignoreCase = true)
        }
        .forEach { name ->
            valuesForHeader(name).forEach { value -> response.header(name, value) }
        }
    response.header("X-Upstream-Request-Id", valuesForHeader("x-request-id").firstOrNull() ?: trace.requestId)
}
