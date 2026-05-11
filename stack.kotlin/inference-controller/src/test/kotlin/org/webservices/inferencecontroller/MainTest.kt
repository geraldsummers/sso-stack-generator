package org.webservices.inferencecontroller

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MainTest {
    @Test
    fun `api status fails closed when token is missing outside explicit dev or test mode`() = testApplication {
        application {
            configureServer(service(), apiToken = null)
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/status").status)
    }

    @Test
    fun `api status may run without token when explicit unauthenticated mode is enabled`() = testApplication {
        application {
            configureServer(
                service(),
                apiToken = null,
                allowUnauthenticatedInternalApi = true
            )
        }

        assertEquals(HttpStatusCode.OK, client.get("/api/status").status)
    }

    @Test
    fun `api status requires configured internal token`() = testApplication {
        application {
            configureServer(service(), apiToken = "shared-secret")
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/status") {
                header("X-Internal-Api-Token", "shared-secret")
            }.status
        )
    }

    private fun service() = InferenceControllerService(
        config = InferenceControllerConfig(
            port = 8110,
            statePath = Files.createTempDirectory("inference-controller-test").resolve("state.json"),
            reconcileIntervalSeconds = 60,
            dbusSessionBusAddress = "unix:path=/tmp/test-bus",
            apiToken = "shared-secret"
        ),
        systemd = object : SystemdController {
            override suspend fun startUnit(unitName: String) = Unit
            override suspend fun stopUnit(unitName: String) = Unit
            override suspend fun inspectUnit(unitName: String): UnitStatus = UnitStatus("active", "running")
        },
        stateStore = InferenceControllerStateStore(Files.createTempDirectory("inference-controller-state").resolve("state.json")),
        healthClient = object : HttpHealthClient() {
            override fun isHealthy(backend: ManagedBackend): Boolean = true
        }
    ).also { runBlocking { it.reconcileNow() } }
}
