package org.webservices.testmanager

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.webservices.testmanager.model.OverviewResponse
import org.webservices.testmanager.model.ReleaseInfo
import org.webservices.testmanager.model.RunView
import org.webservices.testmanager.model.SuiteView
import org.webservices.testmanager.service.TestManagerService

class MainTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiKey = "test-api-key"

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `serves health summary and run endpoints`() = testApplication {
        val service = mockk<TestManagerService>()
        val run = RunView(
            id = 7,
            suiteName = "auth-smoke",
            title = "Auth Smoke",
            command = "./run-tests.sh ts-e2e-smoke",
            status = "passed",
            triggerReason = "manual",
            requestedBy = "gerald",
            requestedAt = "2026-04-07T00:00:00Z",
            finishedAt = "2026-04-07T00:01:00Z",
            logTail = "ok"
        )
        val overview = OverviewResponse(
            generatedAt = "2026-04-07T00:02:00Z",
            releaseFingerprint = "release-123",
            domainFingerprint = "domain-123",
            releaseInfo = ReleaseInfo(
                version = "c8810d9df282",
                gitSha = "c8810d9df282a5860fccfb99e0c1e14ee526e6e4",
                gitShortSha = "c8810d9df282",
                gitBranch = "main",
                sourceBuiltAt = "2026-04-07T00:00:00Z",
                renderedAt = "2026-04-07T00:01:00Z"
            ),
            suites = listOf(
                SuiteView(
                    name = "auth-smoke",
                    title = "Auth Smoke",
                    command = "./run-tests.sh ts-e2e-smoke",
                    state = "passing",
                    blockers = emptyList(),
                    fresh = true,
                    cadenceMinutes = 60,
                    freshnessMinutes = 180,
                    lastTriggerReason = "manual",
                    latestRun = run
                )
            ),
            recentRuns = listOf(run)
        )

        coEvery { service.overview() } returns overview
        every { service.releaseInfo() } returns overview.releaseInfo
        coEvery { service.listSuites() } returns overview.suites
        every { service.listRuns(200) } returns listOf(run)
        every { service.listRuns(50) } returns listOf(run)
        every { service.getRun(7) } returns run
        every { service.getRunLog(7) } returns "run log"
        coEvery { service.queueManualRun("auth-smoke", true, any()) } returns run.copy(triggerReason = "manual_force")
        coEvery { service.queueManualRun("missing", false, any()) } returns null

        application {
            configureServer(service, apiKey)
        }

        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.bodyAsText().contains("\"ok\""))

        val root = client.get("/")
        assertEquals(HttpStatusCode.OK, root.status)
        assertTrue(root.bodyAsText().contains("Quality Control"))

        val overviewResponse = apiGet("/api/overview")
        assertEquals(HttpStatusCode.OK, overviewResponse.status)
        assertTrue(overviewResponse.bodyAsText().contains("release-123"))
        assertTrue(overviewResponse.bodyAsText().contains("c8810d9df282"))

        val releaseInfoResponse = apiGet("/api/release-info")
        assertEquals(HttpStatusCode.OK, releaseInfoResponse.status)
        assertTrue(releaseInfoResponse.bodyAsText().contains("\"gitBranch\":\"main\""))

        val suitesResponse = apiGet("/api/suites")
        assertEquals(HttpStatusCode.OK, suitesResponse.status)
        assertTrue(suitesResponse.bodyAsText().contains("Auth Smoke"))

        val runsResponse = apiGet("/api/runs?limit=999")
        assertEquals(HttpStatusCode.OK, runsResponse.status)
        val runs = json.decodeFromString<List<RunView>>(runsResponse.bodyAsText())
        assertEquals(1, runs.size)
        assertEquals(7, runs.first().id)

        val runResponse = apiGet("/api/runs/7")
        assertEquals(HttpStatusCode.OK, runResponse.status)
        assertTrue(runResponse.bodyAsText().contains("\"suiteName\":\"auth-smoke\""))

        val logResponse = apiGet("/api/runs/7/log")
        assertEquals(HttpStatusCode.OK, logResponse.status)
        assertEquals("run log", logResponse.bodyAsText())

        val queueResponse = apiPost("/api/suites/auth-smoke/runs?force=true")
        assertEquals(HttpStatusCode.Accepted, queueResponse.status)
        assertTrue(queueResponse.bodyAsText().contains("manual_force"))
    }

    @Test
    fun `returns structured bad requests and not found responses`() = testApplication {
        val service = mockk<TestManagerService>()
        every { service.getRun(any()) } returns null
        every { service.getRunLog(any()) } returns null
        every { service.releaseInfo() } returns null
        every { service.listRuns(50) } returns emptyList()
        coEvery { service.queueManualRun(any(), any(), any()) } returns null
        coEvery { service.overview() } returns OverviewResponse(
            generatedAt = "2026-04-07T00:00:00Z",
            releaseFingerprint = "release",
            domainFingerprint = "domain",
            suites = emptyList(),
            recentRuns = emptyList()
        )
        coEvery { service.listSuites() } returns emptyList()

        application {
            configureServer(service, apiKey)
        }

        assertEquals(HttpStatusCode.BadRequest, apiGet("/api/runs/not-a-number").status)
        assertEquals(HttpStatusCode.NotFound, apiGet("/api/release-info").status)
        assertEquals(HttpStatusCode.NotFound, apiGet("/api/runs/999").status)
        assertEquals(HttpStatusCode.BadRequest, apiGet("/api/runs/nope/log").status)
        assertEquals(HttpStatusCode.NotFound, apiGet("/api/runs/999/log").status)
        assertEquals(HttpStatusCode.NotFound, apiPost("/api/suites/missing/runs").status)
    }

    @Test
    fun `rejects missing api key`() = testApplication {
        val service = mockk<TestManagerService>()
        application {
            configureServer(service, apiKey)
        }
        val response = client.get("/api/overview")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.apiGet(path: String) =
        client.get(path) { headers.append("X-API-Key", apiKey) }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.apiPost(path: String) =
        client.post(path) { headers.append("X-API-Key", apiKey) }
}
