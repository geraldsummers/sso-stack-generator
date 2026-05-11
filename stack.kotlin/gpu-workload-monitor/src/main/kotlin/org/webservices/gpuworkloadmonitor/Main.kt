package org.webservices.gpuworkloadmonitor

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.webservices.pipeline.storage.DocumentStagingStore

fun main() {
    val config = loadConfig()
    val stagingStore = DocumentStagingStore(
        jdbcUrl = config.postgresJdbcUrl,
        user = config.postgresUser,
        dbPassword = config.postgresPassword
    )
    val service = GpuWorkloadMonitorService(
        config = config,
        stagingStore = stagingStore,
        sourceReadinessClient = HttpSourceReadinessClient(config)
    )
    service.start()

    val server = embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        configureServer(service)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        service.stop()
        stagingStore.close()
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

fun Application.configureServer(service: GpuWorkloadMonitorService) {
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
        get("/ready") {
            val status = service.currentStatus()
            call.respond(if (status.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, status)
        }
        get("/api/status") {
            call.respond(service.currentStatus())
        }
        get("/api/signal") {
            call.respond(service.currentStatus().signal)
        }
    }
}
