package org.webservices.inferencegateway

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val gatewayJson = Json { ignoreUnknownKeys = true }

fun main() {
    val config = loadConfig()
    val service = InferenceGatewayService(
        config = config,
        controllerStatusClient = HttpInferenceControllerStatusClient(config)
    )

    embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        configureServer(service)
    }.start(wait = true)
}

fun Application.configureServer(service: InferenceGatewayService) {
    install(ContentNegotiation) {
        json()
    }

    val proxyClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, service.currentStatus())
        }

        get("/ready") {
            val status = service.currentStatus()
            call.respond(if (status.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, status)
        }

        get("/api/status") {
            if (!call.requireInternalToken(service.config.internalApiToken, service.config.allowUnauthenticatedInternalApi)) return@get
            call.respond(service.currentStatus())
        }

        get("/embed/health") {
            if (!call.requireInternalToken(service.config.internalApiToken, service.config.allowUnauthenticatedInternalApi)) return@get
            val target = runCatching { service.resolveTarget(InferenceRole.EMBEDDING) }
                .getOrElse { error ->
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "embedding_unavailable", "message" to (error.message ?: "embedding target unavailable")))
                    return@get
                }
            call.proxyRequest(requestUri = target.healthPath, baseUrl = target.baseUrl, proxyClient = proxyClient)
        }

        post("/embed") {
            if (!call.requireInternalToken(service.config.internalApiToken, service.config.allowUnauthenticatedInternalApi)) return@post
            val target = runCatching { service.resolveTarget(InferenceRole.EMBEDDING) }
                .getOrElse { error ->
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "embedding_unavailable", "message" to (error.message ?: "embedding target unavailable")))
                    return@post
                }
            call.proxyRequest(requestUri = "/embed", baseUrl = target.baseUrl, proxyClient = proxyClient)
        }

        get("/llm/health") {
            if (!call.requireInternalToken(service.config.internalApiToken, service.config.allowUnauthenticatedInternalApi)) return@get
            val target = runCatching { service.resolveTarget(InferenceRole.LLM) }
                .getOrElse { error ->
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "llm_unavailable", "message" to (error.message ?: "llm target unavailable")))
                    return@get
                }
            call.proxyRequest(requestUri = target.healthPath, baseUrl = target.baseUrl, proxyClient = proxyClient)
        }

        route("/llm/cpu") {
            installProxyHandlers(
                service = service,
                proxyClient = proxyClient,
                role = InferenceRole.LLM,
                prefixToStrip = "/llm/cpu",
                fixedServiceName = "llm-cpu-fallback"
            )
        }

        route("/llm/gpu") {
            installProxyHandlers(
                service = service,
                proxyClient = proxyClient,
                role = InferenceRole.LLM,
                prefixToStrip = "/llm/gpu",
                fixedServiceName = "vllm-gpu"
            )
        }

        route("/llm/v1") {
            get("/models") {
                val targets = runCatching { service.resolveHealthyTargets(InferenceRole.LLM) }
                    .getOrElse { error ->
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "llm_unavailable", "message" to (error.message ?: "llm targets unavailable"))
                        )
                        return@get
                    }
                call.respond(fetchAggregatedModels(targets, proxyClient))
            }
            installProxyHandlers(service, proxyClient, InferenceRole.LLM, "/llm")
        }
    }
}

private fun Route.installProxyHandlers(
    service: InferenceGatewayService,
    proxyClient: OkHttpClient,
    role: InferenceRole,
    prefixToStrip: String,
    fixedServiceName: String? = null
) {
    val handler: suspend io.ktor.server.routing.RoutingContext.() -> Unit = handler@{
        if (!call.requireInternalToken(service.config.internalApiToken, service.config.allowUnauthenticatedInternalApi)) return@handler
        val requestUri = call.request.uri.removePrefix(prefixToStrip).ifBlank { "/" }
        val isBufferedLlmRequest = role == InferenceRole.LLM && call.request.httpMethod.value !in setOf("GET", "HEAD")

        if (isBufferedLlmRequest) {
            val bodyBytes = call.receiveStream().readBytes()
            val requestedModel = extractRequestedModel(bodyBytes)
            val target = runCatching {
                if (fixedServiceName != null) {
                    service.resolveDirectTarget(fixedServiceName)
                } else {
                    service.resolveTarget(role, requestedModel)
                }
            }.getOrElse { error ->
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "llm_unavailable", "message" to (error.message ?: "target unavailable"))
                )
                return@handler
            }

            if (requestUri == "/v1/responses") {
                call.proxyResponsesViaChatCompletions(
                    baseUrl = target.baseUrl,
                    proxyClient = proxyClient,
                    bodyBytes = rewriteLlmRequestModel(bodyBytes, target)
                )
                return@handler
            }

            call.proxyBufferedRequest(
                requestUri = requestUri,
                baseUrl = target.baseUrl,
                proxyClient = proxyClient,
                bodyBytes = rewriteLlmRequestModel(bodyBytes, target),
                normalizeResponse = null
            )
            return@handler
        }

        val target = runCatching {
            if (fixedServiceName != null) {
                service.resolveDirectTarget(fixedServiceName)
            } else {
                service.resolveTarget(role)
            }
        }.getOrElse { error ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf(
                    "error" to if (role == InferenceRole.LLM) "llm_unavailable" else "embedding_unavailable",
                    "message" to (error.message ?: "target unavailable")
                )
            )
            return@handler
        }
        call.proxyRequest(requestUri = requestUri, baseUrl = target.baseUrl, proxyClient = proxyClient)
    }

    fun Route.installMethodHandlers() {
        get(handler)
        post(handler)
        put(handler)
        patch(handler)
        delete(handler)
        options(handler)
        head(handler)
    }

    installMethodHandlers()
    route("{proxyPath...}") {
        installMethodHandlers()
    }
}

private fun rewriteLlmRequestModel(bodyBytes: ByteArray, target: ResolvedGatewayTarget): ByteArray {
    val defaultModel = target.defaultModel ?: return bodyBytes
    val payload = runCatching {
        gatewayJson.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
    }.getOrNull() ?: return bodyBytes

    val currentModel = payload["model"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (currentModel.isBlank() || currentModel == defaultModel) {
        return bodyBytes
    }

    val rewritten = buildJsonObject {
        payload.forEach { (key, value) ->
            if (key == "model") {
                put("model", JsonPrimitive(defaultModel))
            } else {
                put(key, value)
            }
        }
    }
    return gatewayJson.encodeToString(JsonObject.serializer(), rewritten).toByteArray()
}

private suspend fun io.ktor.server.application.ApplicationCall.requireInternalToken(
    apiToken: String?,
    allowUnauthenticatedInternalApi: Boolean
): Boolean {
    if (apiToken.isNullOrBlank()) {
        if (allowUnauthenticatedInternalApi) {
            return true
        }
        respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf("error" to "internal API token is not configured")
        )
        return false
    }
    val provided = request.headers["X-Internal-Api-Token"]?.trim()
        ?: request.headers["X-Trusted-Proxy-Secret"]?.trim()
    if (secureEquals(provided, apiToken)) {
        return true
    }
    respond(HttpStatusCode.Unauthorized, mapOf("error" to "internal API token required"))
    return false
}

private fun secureEquals(provided: String?, expected: String): Boolean {
    if (provided == null) {
        return false
    }
    return MessageDigest.isEqual(
        provided.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8)
    )
}
