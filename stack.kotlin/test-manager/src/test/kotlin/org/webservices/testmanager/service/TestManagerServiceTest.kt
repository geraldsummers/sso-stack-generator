package org.webservices.testmanager.service

import kotlinx.coroutines.runBlocking
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.isAccessible
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.webservices.pipeline.monitoring.PipelineReadinessResponse
import org.webservices.pipeline.monitoring.SourceReadiness
import org.webservices.testmanager.config.TestManagerConfig
import org.webservices.testmanager.model.SourcePrerequisite
import org.webservices.testmanager.model.SuiteManifest
import org.webservices.testmanager.model.SuitePrerequisites
import org.webservices.testmanager.storage.RunRecord
import org.webservices.testmanager.storage.SuiteStateRecord
import org.webservices.testmanager.storage.TestManagerStore
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

class TestManagerServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val stores = mutableListOf<TestManagerStore>()
    private val services = mutableListOf<TestManagerService>()

    @AfterEach
    fun tearDown() {
        services.reversed().forEach { service -> runBlocking { service.stop() } }
        stores.reversed().forEach(TestManagerStore::close)
    }

    @Test
    fun `overview and queueManualRun use manifests state and fingerprints`() = runBlocking {
        val workspace = tempDir.resolve("workspace").createDirectories()
        val dataRoot = tempDir.resolve("data")
        val suitesPath = workspace.resolve("runtime/configs/test-manager/suites.yaml")
        suitesPath.parent.createDirectories()
        Files.writeString(
            suitesPath,
            """
            suites:
              - name: auth-smoke
                title: Auth Smoke
                command: ./run-tests.sh ts-e2e-smoke
                cadenceMinutes: 60
                freshnessMinutes: 180
                watchPaths:
                  - watched.txt
            """.trimIndent()
        )
        Files.writeString(workspace.resolve(".build-info"), "release-a")
        Files.writeString(
            workspace.resolve("build-info.json"),
            """
            {
              "version": "c8810d9df282",
              "gitSha": "c8810d9df282a5860fccfb99e0c1e14ee526e6e4",
              "gitShortSha": "c8810d9df282",
              "gitBranch": "main",
              "gitDirty": false,
              "sourceBuiltAt": "2026-04-07T00:00:00Z",
              "renderedAt": "2026-04-07T00:01:00Z",
              "siteName": "latium"
            }
            """.trimIndent()
        )
        workspace.resolve("runtime/configs/caddy").createDirectories()
        Files.writeString(workspace.resolve("runtime/configs/caddy/Caddyfile"), "quality.datamancy.net")
        Files.writeString(workspace.resolve("watched.txt"), "alpha")

        val store = store(dataRoot.resolve("test-manager.db"))
        store.upsertSuiteState(
            SuiteStateRecord(
                suiteName = "auth-smoke",
                title = "Auth Smoke",
                command = "./run-tests.sh ts-e2e-smoke",
                state = "blocked",
                blockers = listOf("pipeline readiness unavailable"),
                prerequisitesMet = false,
                fresh = false
            )
        )
        val service = service(config(workspace, dataRoot, suitesPath, workspace.resolve("build-info.json")))

        val firstRun = service.queueManualRun("auth-smoke", force = false, requestedBy = "gerald")
        assertNotNull(firstRun)
        assertEquals("queued", store.getRun(firstRun!!.id)?.status)
        assertEquals("manual", firstRun.triggerReason)
        assertFalse(store.getSuiteState("auth-smoke")!!.prerequisitesMet)

        val duplicate = service.queueManualRun("auth-smoke", force = false, requestedBy = "gerald")
        assertEquals(firstRun.id, duplicate?.id)

        val forced = service.queueManualRun("auth-smoke", force = true, requestedBy = "gerald")
        assertNotNull(forced)
        assertEquals("manual_force", forced!!.triggerReason)
        assertTrue(store.getSuiteState("auth-smoke")!!.lastTriggerReason == "manual_force")

        val overview = service.overview()
        assertEquals(1, overview.suites.size)
        assertTrue(overview.releaseFingerprint.isNotBlank())
        assertTrue(overview.domainFingerprint.isNotBlank())
        assertEquals("c8810d9df282a5860fccfb99e0c1e14ee526e6e4", overview.releaseInfo?.gitSha)
        assertEquals("main", overview.releaseInfo?.gitBranch)
        assertEquals(2, overview.recentRuns.size)

        val suite = service.listSuites().single()
        assertEquals("queued", suite.state)
        assertEquals("Auth Smoke", suite.title)
        assertEquals(forced.id, suite.latestRun?.id)
        assertNull(service.queueManualRun("missing", force = false))
    }

    @Test
    fun `getRunLog prefers file contents and falls back to tail`() = runBlocking {
        val workspace = tempDir.resolve("workspace").createDirectories()
        val dataRoot = tempDir.resolve("data")
        val suitesPath = workspace.resolve("runtime/configs/test-manager/suites.yaml")
        suitesPath.parent.createDirectories()
        Files.writeString(suitesPath, "suites: []")

        val store = store(dataRoot.resolve("test-manager.db"))
        val service = service(config(workspace, dataRoot, suitesPath))

        val runId = store.queueRun(
            suiteName = "auth-smoke",
            title = "Auth Smoke",
            command = "./run-tests.sh ts-e2e-smoke",
            triggerReason = "manual",
            requestedBy = "gerald",
            releaseFingerprint = "release",
            domainFingerprint = "domain",
            watchFingerprint = null
        )
        store.completeRun(runId, "passed", 0, null, "tail output")

        assertEquals("tail output", service.getRunLog(runId))

        val logPath = dataRoot.resolve("runs/$runId-auth-smoke.log")
        logPath.parent.createDirectories()
        Files.writeString(logPath, "full log")
        store.completeRun(runId, "passed", 0, logPath.toString(), "tail output")

        assertEquals("full log", service.getRunLog(runId))
        assertNull(service.getRunLog(999_999))
    }

    @Test
    fun `getRunLog refuses paths outside log root and redacts sensitive output`() = runBlocking {
        val workspace = tempDir.resolve("workspace").createDirectories()
        val dataRoot = tempDir.resolve("data")
        val suitesPath = workspace.resolve("runtime/configs/test-manager/suites.yaml")
        suitesPath.parent.createDirectories()
        Files.writeString(suitesPath, "suites: []")

        val store = store(dataRoot.resolve("test-manager.db"))
        val service = service(config(workspace, dataRoot, suitesPath))
        val runId = store.queueRun(
            suiteName = "auth-smoke",
            title = "Auth Smoke",
            command = "./run-tests.sh ts-e2e-smoke",
            triggerReason = "manual",
            requestedBy = "gerald",
            releaseFingerprint = "release",
            domainFingerprint = "domain",
            watchFingerprint = null
        )
        val outsideLog = tempDir.resolve("outside.log")
        Files.writeString(outsideLog, "outside secret=leaked")
        store.completeRun(runId, "failed", 1, outsideLog.toString(), "Authorization: Bearer topsecret")

        assertEquals("Authorization: Bearer [REDACTED]", service.getRunLog(runId))

        val insideLog = dataRoot.resolve("runs/$runId-auth-smoke.log")
        insideLog.parent.createDirectories()
        Files.writeString(insideLog, "api_key=abc123\nplain")
        store.completeRun(runId, "failed", 1, insideLog.toString(), "tail")

        assertEquals("api_key=[REDACTED]\nplain", service.getRunLog(runId))
    }

    @Test
    fun `start creates data directories`() = runBlocking {
        val workspace = tempDir.resolve("workspace").createDirectories()
        val dataRoot = tempDir.resolve("data")
        val suitesPath = workspace.resolve("runtime/configs/test-manager/suites.yaml")
        suitesPath.parent.createDirectories()
        Files.writeString(suitesPath, "suites: []")

        val service = service(config(workspace, dataRoot, suitesPath))
        service.start()
        service.stop()

        assertTrue(dataRoot.resolve("runs").toFile().exists())
    }

    @Test
    fun `private helpers cover blocker state fingerprint and trimming logic`() = runBlocking {
        val workspace = tempDir.resolve("workspace").createDirectories()
        val dataRoot = tempDir.resolve("data")
        val suitesPath = workspace.resolve("runtime/configs/test-manager/suites.yaml")
        suitesPath.parent.createDirectories()
        Files.writeString(suitesPath, "suites: []")
        val service = service(config(workspace, dataRoot, suitesPath))

        val prerequisite = SourcePrerequisite(
            source = "rss",
            completedInitialPull = true,
            requireIdle = true,
            minSearchableDocuments = 10,
            minPublishedDocuments = 5,
            maxPendingEmbedding = 2,
            maxPendingPublication = 1
        )
        val readiness = PipelineReadinessResponse(
            generatedAt = Instant.now().toString(),
            sources = listOf(
                SourceReadiness(
                    source = "rss",
                    name = "RSS",
                    enabled = false,
                    runState = "running",
                    phase = "fetching",
                    activeRun = true,
                    completedInitialPull = false,
                    initialPullComplete = false,
                    searchableDocuments = 3,
                    pendingEmbedding = 4,
                    pendingPublication = 2,
                    publishedDocuments = 1
                )
            )
        )

        val sourceBlockers = invoke<List<String>>(
            service,
            "evaluateSourcePrerequisite",
            prerequisite,
            readiness
        )
        assertTrue(sourceBlockers.any { it.contains("disabled") })
        assertTrue(sourceBlockers.any { it.contains("initial pull") })
        assertTrue(sourceBlockers.any { it.contains("still ingesting") })
        assertTrue(sourceBlockers.any { it.contains("searchable docs 3 < 10") })
        assertTrue(sourceBlockers.any { it.contains("published docs 1 < 5") })
        assertTrue(sourceBlockers.any { it.contains("pending embeddings 4 > 2") })
        assertTrue(sourceBlockers.any { it.contains("pending publications 2 > 1") })

        val suite = SuiteManifest(
            name = "auth-smoke",
            title = "Auth Smoke",
            command = "./run-tests.sh ts-e2e-smoke",
            cadenceMinutes = 60,
            freshnessMinutes = 120,
            prerequisites = SuitePrerequisites(
                containersHealthy = listOf("caddy", "keycloak"),
                sources = listOf(prerequisite)
            ),
            watchPaths = listOf("watched.txt")
        )
        val blocked = invoke<List<String>>(
            service,
            "evaluateBlockers",
            suite,
            null,
            mapOf("caddy" to "healthy", "keycloak" to "missing")
        )
        assertTrue(blocked.contains("container keycloak is missing"))
        assertTrue(blocked.contains("pipeline readiness unavailable"))

        val waitingState = invoke<String>(service, "computeState", null, false, true, false)
        val runningState = invoke<String>(
            service,
            "computeState",
            RunRecord(1, "suite", "Suite", "cmd", "running", "manual", "gerald", Instant.now().toString()),
            true,
            true,
            false
        )
        val failingState = invoke<String>(
            service,
            "computeState",
            RunRecord(1, "suite", "Suite", "cmd", "failed", "manual", "gerald", Instant.now().toString()),
            false,
            true,
            false
        )
        assertEquals("waiting", waitingState)
        assertEquals("running", runningState)
        assertEquals("failing", failingState)

        Files.writeString(workspace.resolve("watched.txt"), "alpha")
        val watchHashA = invoke<String?>(service, "fingerprintWatchPaths", listOf("watched.txt"))
        Files.writeString(workspace.resolve("watched.txt"), "beta")
        val watchHashB = invoke<String?>(service, "fingerprintWatchPaths", listOf("watched.txt"))
        assertNotNull(watchHashA)
        assertNotEquals(watchHashA, watchHashB)
        assertEquals("none", invoke<String?>(service, "fingerprintWatchPaths", listOf("missing-glob/**")))
        assertNull(invoke<String?>(service, "fingerprintWatchPaths", emptyList<String>()))

        val missingFingerprint = invoke<String>(service, "fingerprintFile", workspace.resolve("missing.txt"))
        assertEquals("missing:missing.txt", missingFingerprint)

        val releaseInfo = workspace.resolve(".build-info")
        Files.writeString(releaseInfo, "release-a")
        val fingerprint = invoke<String>(service, "fingerprintFile", releaseInfo)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))

        val buffer = StringBuilder("12345")
        invoke<Unit>(service, "trimAndAppend", buffer, "67890")
        assertEquals("67890", buffer.toString())
    }

    @Test
    fun `releaseInfo supports json and legacy key value formats`() = runBlocking {
        val workspace = tempDir.resolve("workspace").createDirectories()
        val dataRoot = tempDir.resolve("data")
        val suitesPath = workspace.resolve("runtime/configs/test-manager/suites.yaml")
        suitesPath.parent.createDirectories()
        Files.writeString(suitesPath, "suites: []")

        val jsonPath = workspace.resolve("build-info.json")
        Files.writeString(
            jsonPath,
            """
            {
              "version": "c8810d9df282",
              "gitSha": "c8810d9df282a5860fccfb99e0c1e14ee526e6e4",
              "gitShortSha": "c8810d9df282",
              "gitBranch": "main",
              "gitDirty": true
            }
            """.trimIndent()
        )
        val jsonService = service(config(workspace, dataRoot, suitesPath, jsonPath))
        val jsonInfo = jsonService.releaseInfo()
        assertEquals("c8810d9df282", jsonInfo?.version)
        assertEquals(true, jsonInfo?.gitDirty)

        val legacyPath = workspace.resolve(".build-info")
        Files.writeString(
            legacyPath,
            """
            version: 9871d5a
            git_sha: c8810d9df282a5860fccfb99e0c1e14ee526e6e4
            git_short_sha: c8810d9df282
            git_branch: main
            git_dirty: false
            source_built_at: 2026-04-07T00:00:00Z
            rendered_at: 2026-04-07T00:01:00Z
            built_by: gerald
            rendered_by: gerald
            site_name: latium
            build_system: bazel+shell+sops
            """.trimIndent()
        )
        val legacyService = service(config(workspace, dataRoot, suitesPath, legacyPath))
        val legacyInfo = legacyService.releaseInfo()
        assertEquals("main", legacyInfo?.gitBranch)
        assertEquals("gerald", legacyInfo?.builtBy)
        assertEquals(false, legacyInfo?.gitDirty)
    }

    @Test
    fun `evaluateSuitesOnce and executeRun cover scheduler paths`() = runBlocking {
        val workspace = tempDir.resolve("workspace").createDirectories()
        val dataRoot = tempDir.resolve("data")
        val suitesPath = workspace.resolve("runtime/configs/test-manager/suites.yaml")
        suitesPath.parent.createDirectories()
        Files.writeString(
            suitesPath,
            """
            suites:
              - name: auth-smoke
                title: Auth Smoke
                command: /usr/bin/printf hello
                cadenceMinutes: 1
                freshnessMinutes: 60
                watchPaths:
                  - watched.txt
            """.trimIndent()
        )
        workspace.resolve("runtime/configs/caddy").createDirectories()
        Files.writeString(workspace.resolve(".build-info"), "release-a")
        Files.writeString(workspace.resolve("runtime/configs/caddy/Caddyfile"), "quality.datamancy.net")
        Files.writeString(workspace.resolve("watched.txt"), "alpha")

        val store = store(dataRoot.resolve("test-manager.db"))
        val service = service(config(workspace, dataRoot, suitesPath))

        callSuspend(service, "evaluateSuitesOnce")
        val initialState = store.getSuiteState("auth-smoke")
        val initialRun = store.listRuns(limit = 10).single()
        assertEquals("queued", initialState?.state)
        assertEquals("initial", initialRun.triggerReason)

        callSuspend(service, "executeRun", initialRun)
        val executed = store.getRun(initialRun.id)
        assertEquals("passed", executed?.status)
        assertEquals("passing", store.getSuiteState("auth-smoke")?.state)
        val logPath = Path.of(executed!!.logPath!!)
        assertTrue(logPath.readText().contains("hello"))
        assertTrue(executed.logTail!!.contains("ello"))

        val previousRequestedAt = Instant.parse(executed.requestedAt).minusSeconds(7200).toString()
        val previousFinishedAt = Instant.parse(executed.finishedAt).minusSeconds(7200).toString()
        store.completeRun(executed.id, "passed", 0, executed.logPath, executed.logTail)
        store.upsertSuiteState(
            SuiteStateRecord(
                suiteName = "auth-smoke",
                title = "Auth Smoke",
                command = "/usr/bin/printf hello",
                state = "passing",
                blockers = emptyList(),
                prerequisitesMet = true,
                fresh = true,
                lastTriggerReason = "initial",
                lastRunId = executed.id,
                consumedReleaseFingerprint = "old-release",
                consumedDomainFingerprint = "old-domain",
                consumedWatchFingerprint = "old-watch"
            )
        )
        forceRunTimestamps(store, executed.id, previousRequestedAt, previousFinishedAt)

        Files.writeString(workspace.resolve(".build-info"), "release-b")
        Files.writeString(workspace.resolve("runtime/configs/caddy/Caddyfile"), "quality.changed.net")
        Files.writeString(workspace.resolve("watched.txt"), "beta")

        callSuspend(service, "evaluateSuitesOnce")
        val latest = store.listRuns(limit = 10).first()
        assertEquals("release_change", latest.triggerReason)
    }

    private fun config(
        workspace: Path,
        dataRoot: Path,
        suitesPath: Path,
        releaseInfoPath: Path = workspace.resolve(".build-info")
    ): TestManagerConfig = TestManagerConfig(
        port = 8105,
        workspaceRoot = workspace,
        dataRoot = dataRoot,
        databasePath = dataRoot.resolve("test-manager.db"),
        logRoot = dataRoot.resolve("runs"),
        resultsRoot = workspace.resolve("test-results"),
        suitesPath = suitesPath,
        releaseInfoPath = releaseInfoPath,
        domainConfigPath = workspace.resolve("runtime/configs/caddy/Caddyfile"),
        pipelineReadinessUrl = "http://127.0.0.1:1/readiness",
        pipelineApiKey = null,
        apiKey = "test-api-key",
        allowedCommandPrefixes = listOf("./run-tests.sh", "/usr/bin/printf"),
        dockerHost = "unix:///var/run/docker.sock",
        evaluationIntervalSeconds = 3600,
        queuePollIntervalSeconds = 3600,
        maxLogTailChars = 5
    )

    private fun store(path: Path): TestManagerStore =
        TestManagerStore(path).also(stores::add)

    private fun service(config: TestManagerConfig): TestManagerService =
        TestManagerService(config, store(config.databasePath)).also(services::add)

    private suspend fun callSuspend(target: Any, methodName: String, vararg args: Any?): Any? {
        val function = target::class.declaredFunctions.first { it.name == methodName && it.parameters.size == args.size + 1 }
        function.isAccessible = true
        return function.callSuspend(target, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invoke(target: Any, methodName: String, vararg args: Any?): T {
        val method: Method = target.javaClass.declaredMethods
            .first { it.name == methodName && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(target, *args) as T
    }

    private fun forceRunTimestamps(store: TestManagerStore, id: Long, requestedAt: String, finishedAt: String) {
        val jdbcUrlField = TestManagerStore::class.java.getDeclaredField("jdbcUrl").apply { isAccessible = true }
        val jdbcUrl = jdbcUrlField.get(store) as String
        java.sql.DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.prepareStatement("UPDATE runs SET requested_at = ?, finished_at = ? WHERE id = ?").use { statement ->
                statement.setString(1, requestedAt)
                statement.setString(2, finishedAt)
                statement.setLong(3, id)
                statement.executeUpdate()
            }
        }
    }
}
