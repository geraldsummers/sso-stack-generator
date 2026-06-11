package org.webservices.portal

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json as clientJson
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun main() {
    val config = loadConfig()
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { clientJson(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = config.liveTimeoutMs
            connectTimeoutMillis = config.liveTimeoutMs
            socketTimeoutMillis = config.liveTimeoutMs
        }
    }
    val service = PortalService(config, httpClient)
    val server = embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        configureServer(service)
    }
    server.start(wait = true)
}

fun Application.configureServer(service: PortalService) {
    install(ContentNegotiation) {
        json(json)
    }

    routing {
        get("/") {
            val html = this::class.java.classLoader.getResource("static/index.html")?.readText()
                ?: "portal"
            call.respondText(html, ContentType.Text.Html)
        }
        get("/theme.css") {
            val css = this::class.java.classLoader.getResource("static/theme.css")?.readText()
                ?: ""
            call.respondText(css, ContentType.Text.CSS)
        }
        get("/app.js") {
            val script = this::class.java.classLoader.getResource("static/app.js")?.readText()
                ?: ""
            call.respondText(script, ContentType.Application.JavaScript)
        }
        get("/health") {
            call.respond(HealthResponse("ok"))
        }
        route("/api") {
            get("/modules") {
                call.respond(service.modules())
            }
            get("/profiles") {
                call.respond(service.profiles())
            }
            get("/dashboard/{profileId}") {
                val profileId = call.parameters["profileId"]
                if (profileId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing profile id"))
                    return@get
                }
                try {
                    call.respond(service.dashboard(profileId))
                } catch (_: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "profile not found"))
                }
            }
            get("/dashboard/{profileId}/refresh") {
                val profileId = call.parameters["profileId"]
                if (profileId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing profile id"))
                    return@get
                }
                try {
                    call.respond(service.dashboard(profileId))
                } catch (_: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "profile not found"))
                }
            }
            get("/integrations/status") {
                call.respond(service.integrations())
            }
            get("/reports") {
                call.respond(service.reports())
            }
        }
    }
}
