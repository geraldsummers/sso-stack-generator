package org.webservices.inferencecontroller

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
private data class HealthResponse(
    val status: String,
    val ready: Boolean,
    val mode: String,
    val targetsReady: Boolean,
    val transitioning: Boolean
)

fun main() {
    val config = loadConfig()
    val service = InferenceControllerService(
        config = config,
        systemd = BusctlSystemdController(config.dbusSessionBusAddress),
        stateStore = InferenceControllerStateStore(config.statePath)
    )
    service.start()

    val server = embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        configureServer(
            service = service,
            apiToken = config.apiToken,
            allowUnauthenticatedInternalApi = config.allowUnauthenticatedInternalApi
        )
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        service.stop()
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

fun Application.configureServer(
    service: InferenceControllerService,
    apiToken: String? = null,
    allowUnauthenticatedInternalApi: Boolean = false
) {
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            call.respondText("inference-controller", ContentType.Text.Plain)
        }

        get("/health") {
            val status = service.currentStatus()
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    status = if (status.ready) "ok" else "degraded",
                    ready = status.ready,
                    mode = status.mode.name.lowercase(),
                    targetsReady = status.targetsReady,
                    transitioning = status.transitioning
                )
            )
        }

        get("/ready") {
            val status = service.currentStatus()
            call.respond(if (status.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, status)
        }

        get("/api/status") {
            if (!call.requireApiToken(apiToken, allowUnauthenticatedInternalApi)) return@get
            call.respond(service.currentStatus())
        }

        post("/api/actions/reconcile") {
            if (!call.requireApiToken(apiToken, allowUnauthenticatedInternalApi)) return@post
            call.respond(HttpStatusCode.Accepted, service.reconcileNow())
        }

        put("/api/mode") {
            if (!call.requireApiToken(apiToken, allowUnauthenticatedInternalApi)) return@put
            val request = call.receive<SetModeRequest>()
            val mode = runCatching { InferenceMode.valueOf(request.mode.trim().uppercase()) }
                .getOrElse {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_mode", "message" to request.mode))
                    return@put
                }
            call.respond(HttpStatusCode.Accepted, service.setMode(mode, request.note))
        }
    }
}

private suspend fun ApplicationCall.requireApiToken(
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
        ?: request.headers["Authorization"]?.trim()?.removePrefix("Bearer ")?.trim()
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
