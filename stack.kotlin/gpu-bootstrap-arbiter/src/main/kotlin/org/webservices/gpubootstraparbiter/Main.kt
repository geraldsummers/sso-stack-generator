package org.webservices.gpubootstraparbiter

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Clock

@Serializable
private data class HealthResponse(
    val status: String,
    val ready: Boolean,
    val mode: String,
    val targetsReady: Boolean,
    val decisionInputsHealthy: Boolean
)

@Serializable
private data class ReadinessResponse(
    val status: String,
    val mode: String,
    val targetsReady: Boolean,
    val decisionInputsHealthy: Boolean
)

fun main() {
    val config = loadConfig()
    val service = GpuBootstrapArbiterService(
        config = config,
        docker = CliDockerController(config),
        signalClient = HttpWorkloadSignalClient(config),
        stateStore = ArbiterStateStore(config.statePath),
        clock = Clock.systemUTC()
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
    service: GpuBootstrapArbiterService,
    apiToken: String? = null,
    allowUnauthenticatedInternalApi: Boolean = false
) {
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            val html = this::class.java.classLoader.getResource("static/index.html")?.readText()
            call.respondText(html ?: "gpu-bootstrap-arbiter", ContentType.Text.Html)
        }

        get("/health") {
            val status = service.currentStatus()
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    status = "ok",
                    ready = status.ready,
                    mode = status.mode.name.lowercase(),
                    targetsReady = status.targetsReady,
                    decisionInputsHealthy = status.decisionInputsHealthy
                )
            )
        }

        get("/ready") {
            val status = service.currentStatus()
            val code = if (status.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(
                code,
                ReadinessResponse(
                    status = if (status.ready) "ready" else "not_ready",
                    mode = status.mode.name.lowercase(),
                    targetsReady = status.targetsReady,
                    decisionInputsHealthy = status.decisionInputsHealthy
                )
            )
        }

        get("/api/status") {
            if (!call.requireInternalApiToken(apiToken, allowUnauthenticatedInternalApi)) return@get
            call.respond(service.currentStatus())
        }

        post("/api/actions/reconcile") {
            if (!call.requireInternalApiToken(apiToken, allowUnauthenticatedInternalApi)) return@post
            service.reconcileNow()
            call.respond(HttpStatusCode.Accepted, service.currentStatus())
        }

        post("/api/actions/promote") {
            if (!call.requireInternalApiToken(apiToken, allowUnauthenticatedInternalApi)) return@post
            service.forcePromote()
            call.respond(HttpStatusCode.Accepted, service.currentStatus())
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.requireInternalApiToken(
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
