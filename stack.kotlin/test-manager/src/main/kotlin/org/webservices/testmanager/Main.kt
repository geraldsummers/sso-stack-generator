package org.webservices.testmanager

import io.github.oshai.kotlinlogging.KotlinLogging
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
import org.webservices.testmanager.config.loadConfig
import org.webservices.testmanager.service.TestManagerService
import org.webservices.testmanager.storage.TestManagerStore

private val logger = KotlinLogging.logger {}

fun main() {
    val config = loadConfig()
    val store = TestManagerStore(config.databasePath)
    val service = TestManagerService(config, store)
    service.start()

    val server = embeddedServer(Netty, port = config.port) {
        configureServer(service, config.apiKey)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Stopping test-manager" }
        kotlinx.coroutines.runBlocking { service.stop() }
        store.close()
        server.stop(1000, 5000)
    })

    logger.info { "Starting test-manager on port ${config.port}" }
    server.start(wait = true)
}

fun Application.configureServer(service: TestManagerService, apiKey: String) {
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            val html = this::class.java.classLoader.getResource("static/index.html")?.readText()
            if (html == null) {
                call.respondText("test-manager", ContentType.Text.Plain)
            } else {
                call.respondText(html, ContentType.Text.Html)
            }
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/api/overview") {
            if (!requireApiKey(call, apiKey)) return@get
            call.respond(service.overview())
        }

        get("/api/release-info") {
            if (!requireApiKey(call, apiKey)) return@get
            val releaseInfo = service.releaseInfo()
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Release info not found"))
            call.respond(releaseInfo)
        }

        get("/api/known-good") {
            if (!requireApiKey(call, apiKey)) return@get
            val baseline = service.knownGoodBaseline()
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Known-good baseline not found"))
            call.respond(baseline)
        }

        get("/api/known-good/log") {
            if (!requireApiKey(call, apiKey)) return@get
            val log = service.getKnownGoodBaselineLog()
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Known-good baseline log not found"))
            call.respondText(log, ContentType.Text.Plain)
        }

        get("/api/suites") {
            if (!requireApiKey(call, apiKey)) return@get
            call.respond(service.listSuites())
        }

        get("/api/runs") {
            if (!requireApiKey(call, apiKey)) return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            call.respond(service.listRuns(limit))
        }

        get("/api/runs/{id}") {
            if (!requireApiKey(call, apiKey)) return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid run id"))
            val run = service.getRun(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Run not found"))
            call.respond(run)
        }

        get("/api/runs/{id}/log") {
            if (!requireApiKey(call, apiKey)) return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid run id"))
            val log = service.getRunLog(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Log not found"))
            call.respondText(log, ContentType.Text.Plain)
        }

        post("/api/suites/{name}/runs") {
            if (!requireApiKey(call, apiKey)) return@post
            val name = call.parameters["name"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Suite name required"))
            val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: false
            val run = service.queueManualRun(name, force)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Suite not found"))
            call.respond(HttpStatusCode.Accepted, run)
        }
    }
}

private suspend fun requireApiKey(call: io.ktor.server.application.ApplicationCall, apiKey: String): Boolean {
    val provided = call.request.headers["X-API-Key"]?.trim()
    if (provided == apiKey) {
        return true
    }
    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "API key required"))
    return false
}
