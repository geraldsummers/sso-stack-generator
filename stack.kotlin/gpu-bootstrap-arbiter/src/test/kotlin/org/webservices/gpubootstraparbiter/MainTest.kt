package org.webservices.gpubootstraparbiter

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MainTest {

    @Test
    fun `streamedRequestBody streams large payloads without prebuffering`() {
        val payload = "x".repeat(3 * 1024 * 1024).toByteArray()

        val requestBody = streamedRequestBody(
            input = ByteArrayInputStream(payload),
            mediaType = "application/json".toMediaType(),
            contentLength = payload.size.toLong()
        )

        val sink = Buffer()
        requestBody.writeTo(sink)

        assertTrue(requestBody.isOneShot())
        assertEquals(payload.size.toLong(), requestBody.contentLength())
        assertEquals("application/json", requestBody.contentType()?.toString())
        assertEquals(payload.size.toLong(), sink.size)
        assertEquals(payload.decodeToString(), sink.readUtf8())
    }

    @Test
    fun `cpu llm backend uses models endpoint for health`() {
        assertEquals("/api/tags", managedBackend("llm-cpu-fallback").healthPath)
        assertEquals("/api/tags", managedBackend("vllm-gpu").healthPath)
    }

    @Test
    fun `mutating and internal api endpoints fail closed when token is missing outside explicit dev or test mode`() = testApplication {
        application {
            configureServer(
                service = service(),
                apiToken = null,
                allowUnauthenticatedInternalApi = false
            )
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/status").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/actions/reconcile").status)
    }

    @Test
    fun `mutating and internal api endpoints require configured token`() = testApplication {
        application {
            configureServer(
                service = service(),
                apiToken = "shared-secret",
                allowUnauthenticatedInternalApi = false
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/status") {
                header("X-Internal-Api-Token", "shared-secret")
            }.status
        )
    }

    private fun service(): GpuBootstrapArbiterService {
        val tempDir = Files.createTempDirectory("gpu-bootstrap-arbiter-main-test")
        val statePath = tempDir.resolve("state.json")
        ArbiterStateStore(statePath).save(ArbiterState())
        return GpuBootstrapArbiterService(
            config = ArbiterConfig(
                port = 8110,
                workspaceRoot = tempDir,
                dataRoot = tempDir,
                statePath = statePath,
                dockerHost = "tcp://docker-socket-proxy:2375",
                composeProjectName = "webservices",
                composeFile = tempDir.resolve("docker-compose.yml"),
                composeEnvFile = tempDir.resolve("stack.env"),
                signalUrl = "http://gpu-workload-monitor:8112/api/signal",
                signalApiKey = null,
                apiToken = "shared-secret",
                allowUnauthenticatedInternalApi = false,
                signalTimeoutMs = 10000,
                evaluationIntervalSeconds = 3600,
                embeddingPriorityPendingHighThreshold = 10,
                embeddingPriorityPendingLowThreshold = 2,
                embeddingPriorityInProgressHighThreshold = 2,
                embeddingPriorityInProgressLowThreshold = 0,
                requireInitialPullCompleteForLlmPriority = false
            ),
            docker = object : DockerController {
                override suspend fun inspect(serviceName: String): BackendStatus = BackendStatus(
                    serviceName = serviceName,
                    containerName = serviceName,
                    role = managedBackend(serviceName).role,
                    mode = managedBackend(serviceName).mode,
                    state = "running",
                    running = true,
                    healthy = true,
                    managed = true
                )

                override suspend fun ensureRunning(backend: ManagedBackend) = Unit

                override suspend fun ensureStopped(backend: ManagedBackend) = Unit
            },
            signalClient = object : WorkloadSignalClient {
                override suspend fun fetch(): WorkloadSignalSnapshot = WorkloadSignalSnapshot(
                    decisionInputsHealthy = true,
                    initialBuildIncomplete = false,
                    totalPendingEmbedding = 0,
                    totalInProgressEmbedding = 0,
                    incompleteSources = emptyList(),
                    lastEvaluatedAt = "2026-04-08T00:00:00Z",
                    lastError = null
                )
            },
            stateStore = ArbiterStateStore(statePath),
            clock = Clock.fixed(Instant.parse("2026-04-08T00:10:00Z"), ZoneOffset.UTC)
        ).also { runBlocking { it.reconcileNow() } }
    }
}
