package org.webservices.progression

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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.io.path.createDirectories

fun main(args: Array<String>) {
    val config = loadConfig()
    config.runtimeDir.createDirectories()
    val registry = RegistryLoader().load(config)
    val store = StateStore(config.runtimeDir)

    if (args.firstOrNull() == "serve") {
        val server = embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
            configureServer(config, registry, store)
        }
        server.start(wait = true)
    } else {
        kotlin.system.exitProcess(ProgressionCli(config, registry, store).run(args))
    }
}

fun Application.configureServer(
    config: ProgressionConfig,
    registry: Registry,
    store: StateStore
) {
    install(ContentNegotiation) {
        json(progressionJson)
    }
    val engine = ProgressionEngine(registry, store)
    val scanners = StackScanners(config, store)

    routing {
        get("/") {
            val html = this::class.java.classLoader.getResource("static/index.html")?.readText()
                ?: "progression"
            call.respondText(html, ContentType.Text.Html)
        }
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        route("/api") {
            get("/progress") {
                scanners.scan()
                call.respond(engine.view())
            }
            get("/progress/next") {
                scanners.scan()
                call.respond(engine.view().primaryNextTask ?: mapOf("status" to "complete"))
            }
            get("/tasks/{id}") {
                scanners.scan()
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing task id"))
                call.respond(engine.task(id) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "task not found")))
            }
            get("/dashboards") {
                scanners.scan()
                call.respond(engine.view().dashboards)
            }
            get("/dashboards/{id}") {
                scanners.scan()
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing dashboard id"))
                call.respond(engine.dashboard(id) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "dashboard not found")))
            }
            get("/services/{id}") {
                scanners.scan()
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing service id"))
                if (id != "bookstack") {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "service not implemented in progression MVP"))
                    return@get
                }
                call.respond(engine.bookstackService())
            }
            get("/evidence/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing evidence id"))
                call.respond(store.readEvidence(id) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "evidence not found")))
            }
            post("/scan") {
                call.respond(scanners.scan())
            }
        }
    }
}
