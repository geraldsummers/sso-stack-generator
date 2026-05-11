package org.webservices.pipeline.embedding

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InferenceControllerStatusClientTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetchEmbeddingTarget reads embedding target from controller status`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"embeddingTarget":"embedding-gpu"}""")
        )
        server.start()

        val client = InferenceControllerStatusClient(server.url("/api/status").toString())

        val target = client.fetchEmbeddingTarget()

        assertEquals("embedding-gpu", target)
    }

    @Test
    fun `provider falls back to conservative cpu reserve when controller is unavailable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        server.start()

        val provider = InferenceControllerAwareEmbeddingSchedulerLimitsProvider(
            controllerStatusClient = InferenceControllerStatusClient(server.url("/api/status").toString()),
            defaultLimits = EmbeddingSchedulerLimits(
                profile = EmbeddingExecutionProfile.GPU_BULK,
                batchSize = 32,
                maxConcurrentEmbeddings = 8,
                embeddingRequestBatchSize = 16,
                maxConcurrentBatchRequests = 4,
                embeddingRequestMaxBytes = 4096
            ),
            reserveCpuForInteractive = true
        )

        val limits = provider.currentLimits()

        assertEquals(EmbeddingExecutionProfile.CPU_RESERVED, limits.profile)
        assertTrue(limits.pauseBackgroundWork)
        assertEquals(1, limits.batchSize)
    }
}
