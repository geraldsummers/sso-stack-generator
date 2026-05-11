package org.webservices.testmanager.service

import com.charleskorn.kaml.Yaml
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.webservices.pipeline.monitoring.PipelineReadinessResponse
import org.webservices.testmanager.config.TestManagerConfig
import org.webservices.testmanager.model.OverviewResponse
import org.webservices.testmanager.model.KnownGoodBaselineView
import org.webservices.testmanager.model.ReleaseInfo
import org.webservices.testmanager.model.RunView
import org.webservices.testmanager.model.SourcePrerequisite
import org.webservices.testmanager.model.SuiteManifest
import org.webservices.testmanager.model.SuiteManifestFile
import org.webservices.testmanager.model.SuiteView
import org.webservices.testmanager.storage.RunRecord
import org.webservices.testmanager.storage.SuiteStateRecord
import org.webservices.testmanager.storage.TestManagerStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}
private val yaml = Yaml.default
private val json = Json { ignoreUnknownKeys = true }
private val safeCommandTokenRegex = Regex("^[A-Za-z0-9._/:=+\\-]+$")

class TestManagerService(
    private val config: TestManagerConfig,
    private val store: TestManagerStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    fun start() {
        config.dataRoot.createDirectories()
        config.logRoot.createDirectories()
        scope.launch { evaluationLoop() }
        scope.launch { workerLoop() }
    }

    suspend fun stop() {
        scope.cancel()
        httpClient.close()
    }

    suspend fun overview(): OverviewResponse {
        val manifests = loadSuites()
        val views = resolveSuiteViews(manifests)
        val fingerprints = currentFingerprints(manifests)
        return OverviewResponse(
            generatedAt = Instant.now().toString(),
            releaseFingerprint = fingerprints.releaseFingerprint,
            domainFingerprint = fingerprints.domainFingerprint,
            releaseInfo = loadReleaseInfo(),
            knownGoodBaseline = loadKnownGoodBaseline(),
            suites = views,
            recentRuns = store.listRuns(limit = 20).map { it.toView() }
        )
    }

    suspend fun listSuites(): List<SuiteView> = resolveSuiteViews(loadSuites())

    fun listRuns(limit: Int = 50): List<RunView> = store.listRuns(limit).map { it.toView() }

    fun getRun(runId: Long): RunView? = store.getRun(runId)?.toView()

    fun getRunLog(runId: Long): String? {
        val run = store.getRun(runId) ?: return null
        val logPath = run.logPath ?: return run.logTail?.let(::redactSensitiveOutput)
        val path = Path.of(logPath)
        return if (isPathInside(path, config.logRoot) && path.exists() && path.isRegularFile()) {
            redactSensitiveOutput(path.readText())
        } else {
            run.logTail?.let(::redactSensitiveOutput)
        }
    }

    fun releaseInfo(): ReleaseInfo? = loadReleaseInfo()

    fun knownGoodBaseline(): KnownGoodBaselineView? = loadKnownGoodBaseline()

    fun getKnownGoodBaselineLog(): String? {
        val baseline = loadKnownGoodBaseline() ?: return null
        val path = baseline.logPath?.let(Path::of) ?: return null
        return if (isPathInside(path, config.resultsRoot) && path.exists() && path.isRegularFile()) {
            redactSensitiveOutput(path.readText())
        } else {
            null
        }
    }

    suspend fun queueManualRun(suiteName: String, force: Boolean, requestedBy: String = "manual"): RunView? {
        val manifest = loadSuites().find { it.name == suiteName } ?: return null
        if (!force && store.hasQueuedOrRunningRun(suiteName)) {
            return store.listRuns(limit = 1).find { it.suiteName == suiteName }?.toView()
        }

        val fingerprints = currentFingerprints(listOf(manifest))
        val runId = store.queueRun(
            suiteName = manifest.name,
            title = manifest.title,
            command = manifest.command,
            triggerReason = if (force) "manual_force" else "manual",
            requestedBy = requestedBy,
            releaseFingerprint = fingerprints.releaseFingerprint,
            domainFingerprint = fingerprints.domainFingerprint,
            watchFingerprint = fingerprints.watchFingerprints[manifest.name]
        )
        val existing = store.getSuiteState(manifest.name)
        store.upsertSuiteState(
            SuiteStateRecord(
                suiteName = manifest.name,
                title = manifest.title,
                command = manifest.command,
                state = "queued",
                blockers = existing?.blockers ?: emptyList(),
                prerequisitesMet = existing?.prerequisitesMet ?: force,
                fresh = false,
                lastTriggerReason = if (force) "manual_force" else "manual",
                lastEvaluatedAt = Instant.now().toString(),
                lastRunId = runId,
                consumedReleaseFingerprint = fingerprints.releaseFingerprint,
                consumedDomainFingerprint = fingerprints.domainFingerprint,
                consumedWatchFingerprint = fingerprints.watchFingerprints[manifest.name],
                lastQueuedAt = Instant.now().toString()
            )
        )
        return store.getRun(runId)?.toView()
    }

    private suspend fun evaluationLoop() {
        while (scope.isActive) {
            runCatching { evaluateSuitesOnce() }
                .onFailure { error -> logger.error(error) { "Suite evaluation failed: ${error.message}" } }
            delay(config.evaluationIntervalSeconds * 1000)
        }
    }

    private suspend fun workerLoop() {
        while (scope.isActive) {
            val run = runCatching { store.claimNextQueuedRun() }
                .onFailure { error -> logger.error(error) { "Failed to claim queued run: ${error.message}" } }
                .getOrNull()
            if (run == null) {
                delay(config.queuePollIntervalSeconds * 1000)
                continue
            }
            executeRun(run)
        }
    }

    private suspend fun evaluateSuitesOnce() {
        val manifests = loadSuites()
        val readiness = fetchReadiness()
        val latestRuns = store.latestRunsBySuite()
        val requiredContainers = manifests.flatMap { it.prerequisites.containersHealthy }.toSortedSet()
        val containerHealth = inspectContainers(requiredContainers)
        val fingerprints = currentFingerprints(manifests)
        val now = Instant.now()

        manifests.forEach { manifest ->
            val existing = store.getSuiteState(manifest.name)
            val latestRun = latestRuns[manifest.name]
            val queuedOrRunning = store.hasQueuedOrRunningRun(manifest.name)
            val blockers = evaluateBlockers(manifest, readiness, containerHealth)
            val prereqsMet = blockers.isEmpty()
            val fresh = isFresh(latestRun, manifest, now)
            val trigger = determineTrigger(manifest, existing, latestRun, queuedOrRunning, prereqsMet, fingerprints, now)

            var lastRunId = existing?.lastRunId ?: latestRun?.id
            var consumedRelease = existing?.consumedReleaseFingerprint
            var consumedDomain = existing?.consumedDomainFingerprint
            var consumedWatch = existing?.consumedWatchFingerprint
            var lastQueuedAt = existing?.lastQueuedAt
            var state = computeState(latestRun, queuedOrRunning, prereqsMet, fresh)
            var triggerReason = existing?.lastTriggerReason

            if (trigger != null) {
                val runId = store.queueRun(
                    suiteName = manifest.name,
                    title = manifest.title,
                    command = manifest.command,
                    triggerReason = trigger.reason,
                    requestedBy = "scheduler",
                    releaseFingerprint = fingerprints.releaseFingerprint,
                    domainFingerprint = fingerprints.domainFingerprint,
                    watchFingerprint = fingerprints.watchFingerprints[manifest.name]
                )
                lastRunId = runId
                consumedRelease = fingerprints.releaseFingerprint
                consumedDomain = fingerprints.domainFingerprint
                consumedWatch = fingerprints.watchFingerprints[manifest.name]
                lastQueuedAt = now.toString()
                state = "queued"
                triggerReason = trigger.reason
            }

            store.upsertSuiteState(
                SuiteStateRecord(
                    suiteName = manifest.name,
                    title = manifest.title,
                    command = manifest.command,
                    state = state,
                    blockers = blockers,
                    prerequisitesMet = prereqsMet,
                    fresh = fresh,
                    lastTriggerReason = triggerReason,
                    lastEvaluatedAt = now.toString(),
                    lastRunId = lastRunId,
                    consumedReleaseFingerprint = consumedRelease,
                    consumedDomainFingerprint = consumedDomain,
                    consumedWatchFingerprint = consumedWatch,
                    lastQueuedAt = lastQueuedAt
                )
            )
        }
    }

    private suspend fun executeRun(run: RunRecord) {
        logger.info { "Executing ${run.suiteName} via ${redactSensitiveOutput(run.command)}" }
        val existing = store.getSuiteState(run.suiteName)
        store.upsertSuiteState(
            SuiteStateRecord(
                suiteName = run.suiteName,
                title = run.title,
                command = run.command,
                state = "running",
                blockers = existing?.blockers ?: emptyList(),
                prerequisitesMet = existing?.prerequisitesMet ?: true,
                fresh = false,
                lastTriggerReason = run.triggerReason,
                lastEvaluatedAt = Instant.now().toString(),
                lastRunId = run.id,
                consumedReleaseFingerprint = run.releaseFingerprint,
                consumedDomainFingerprint = run.domainFingerprint,
                consumedWatchFingerprint = run.watchFingerprint,
                lastQueuedAt = existing?.lastQueuedAt ?: run.requestedAt
            )
        )

        val logPath = config.logRoot.resolve("${run.id}-${safeLogFileName(run.suiteName)}.log")
        val result = runCommand(run.command, logPath)
        val finalStatus = if (result.exitCode == 0) "passed" else "failed"
        store.completeRun(
            id = run.id,
            status = finalStatus,
            exitCode = result.exitCode,
            logPath = logPath.toAbsolutePath().toString(),
            logTail = result.logTail
        )
        store.upsertSuiteState(
            SuiteStateRecord(
                suiteName = run.suiteName,
                title = run.title,
                command = run.command,
                state = if (result.exitCode == 0) "passing" else "failing",
                blockers = emptyList(),
                prerequisitesMet = true,
                fresh = result.exitCode == 0,
                lastTriggerReason = run.triggerReason,
                lastEvaluatedAt = Instant.now().toString(),
                lastRunId = run.id,
                consumedReleaseFingerprint = run.releaseFingerprint,
                consumedDomainFingerprint = run.domainFingerprint,
                consumedWatchFingerprint = run.watchFingerprint,
                lastQueuedAt = run.requestedAt
            )
        )
        logger.info { "Completed ${run.suiteName} with status $finalStatus" }
    }

    private suspend fun loadSuites(): List<SuiteManifest> {
        if (!config.suitesPath.exists()) {
            logger.warn { "Suite manifest not found: ${config.suitesPath}" }
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            yaml.decodeFromString(SuiteManifestFile.serializer(), config.suitesPath.readText()).suites.sortedBy { it.name }
        }
    }

    private suspend fun fetchReadiness(): PipelineReadinessResponse? {
        return runCatching {
            httpClient.get(config.pipelineReadinessUrl) {
                config.pipelineApiKey?.let { header("X-API-Key", it) }
            }.body<PipelineReadinessResponse>()
        }.onFailure { error ->
            logger.warn { "Pipeline readiness unavailable: ${error.message}" }
        }.getOrNull()
    }

    private fun inspectContainers(containers: Set<String>): Map<String, String> {
        return containers.associateWith { inspectContainer(it) }
    }

    private fun inspectContainer(containerName: String): String {
        return try {
            val process = ProcessBuilder(
                "docker",
                "--host",
                config.dockerHost,
                "inspect",
                containerName,
                "--format",
                "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}"
            ).start()
            val stdout = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8).trim()
            val stderr = process.errorStream.readAllBytes().toString(StandardCharsets.UTF_8).trim()
            val exit = process.waitFor()
            if (exit == 0 && stdout.isNotBlank()) stdout else if (stderr.contains("No such object")) "missing" else "unknown"
        } catch (error: Exception) {
            logger.warn { "Container inspect failed for $containerName: ${error.message}" }
            "unknown"
        }
    }

    private fun evaluateBlockers(
        manifest: SuiteManifest,
        readiness: PipelineReadinessResponse?,
        containerHealth: Map<String, String>
    ): List<String> {
        val blockers = mutableListOf<String>()
        manifest.prerequisites.containersHealthy.forEach { container ->
            val status = containerHealth[container] ?: "missing"
            if (status != "healthy" && status != "running") {
                blockers += "container $container is $status"
            }
        }

        if (manifest.prerequisites.sources.isNotEmpty() && readiness == null) {
            blockers += "pipeline readiness unavailable"
            return blockers
        }

        manifest.prerequisites.sources.forEach { prerequisite ->
            blockers += evaluateSourcePrerequisite(prerequisite, readiness)
        }
        return blockers
    }

    private fun evaluateSourcePrerequisite(
        prerequisite: SourcePrerequisite,
        readiness: PipelineReadinessResponse?
    ): List<String> {
        val source = readiness?.sources?.find { it.source == prerequisite.source }
            ?: return listOf("source ${prerequisite.source} not reported by pipeline readiness")
        val blockers = mutableListOf<String>()

        if (!source.enabled) {
            blockers += "source ${prerequisite.source} disabled"
        }
        if (prerequisite.completedInitialPull && !source.completedInitialPull) {
            blockers += "source ${prerequisite.source} has not completed an initial pull since startup"
        }
        if (prerequisite.requireIdle && source.activeRun) {
            blockers += "source ${prerequisite.source} is still ingesting"
        }
        if (source.searchableDocuments < prerequisite.minSearchableDocuments) {
            blockers += "source ${prerequisite.source} searchable docs ${source.searchableDocuments} < ${prerequisite.minSearchableDocuments}"
        }
        if (source.publishedDocuments < prerequisite.minPublishedDocuments) {
            blockers += "source ${prerequisite.source} published docs ${source.publishedDocuments} < ${prerequisite.minPublishedDocuments}"
        }
        if (prerequisite.maxPendingEmbedding != null && source.pendingEmbedding > prerequisite.maxPendingEmbedding) {
            blockers += "source ${prerequisite.source} pending embeddings ${source.pendingEmbedding} > ${prerequisite.maxPendingEmbedding}"
        }
        if (prerequisite.maxPendingPublication != null && source.pendingPublication > prerequisite.maxPendingPublication) {
            blockers += "source ${prerequisite.source} pending publications ${source.pendingPublication} > ${prerequisite.maxPendingPublication}"
        }
        return blockers
    }

    private fun determineTrigger(
        manifest: SuiteManifest,
        existing: SuiteStateRecord?,
        latestRun: RunRecord?,
        queuedOrRunning: Boolean,
        prerequisitesMet: Boolean,
        fingerprints: FingerprintSnapshot,
        now: Instant
    ): TriggerDecision? {
        if (queuedOrRunning || !prerequisitesMet) {
            return null
        }

        if (latestRun == null && manifest.triggers.runOnFirstSeen) {
            return TriggerDecision("initial")
        }
        if (manifest.triggers.onReleaseChange && existing?.consumedReleaseFingerprint != null && existing.consumedReleaseFingerprint != fingerprints.releaseFingerprint) {
            return TriggerDecision("release_change")
        }
        if (manifest.triggers.onDomainChange && existing?.consumedDomainFingerprint != null && existing.consumedDomainFingerprint != fingerprints.domainFingerprint) {
            return TriggerDecision("domain_change")
        }
        val watchFingerprint = fingerprints.watchFingerprints[manifest.name]
        if (manifest.triggers.onWatchPathsChange && !watchFingerprint.isNullOrBlank() && existing?.consumedWatchFingerprint != null && existing.consumedWatchFingerprint != watchFingerprint) {
            return TriggerDecision("watch_change")
        }
        if (manifest.triggers.onPrereqReady && existing != null && !existing.prerequisitesMet) {
            return TriggerDecision("prereq_ready")
        }
        if (latestRun != null && shouldRunOnCadence(latestRun, manifest, now)) {
            return TriggerDecision("cadence")
        }

        return null
    }

    private fun shouldRunOnCadence(latestRun: RunRecord, manifest: SuiteManifest, now: Instant): Boolean {
        val baseline = latestRun.finishedAt ?: latestRun.requestedAt
        return runCatching {
            Duration.between(Instant.parse(baseline), now) >= Duration.ofMinutes(manifest.cadenceMinutes)
        }.getOrDefault(false)
    }

    private fun isFresh(latestRun: RunRecord?, manifest: SuiteManifest, now: Instant): Boolean {
        if (latestRun == null) {
            return false
        }
        val baseline = latestRun.finishedAt ?: latestRun.requestedAt
        return runCatching {
            Duration.between(Instant.parse(baseline), now) <= Duration.ofMinutes(manifest.freshnessMinutes)
        }.getOrDefault(false)
    }

    private fun computeState(latestRun: RunRecord?, queuedOrRunning: Boolean, prerequisitesMet: Boolean, fresh: Boolean): String {
        if (queuedOrRunning) {
            return when (latestRun?.status) {
                "running" -> "running"
                else -> "queued"
            }
        }
        if (!prerequisitesMet) {
            return "blocked"
        }
        if (latestRun == null) {
            return "waiting"
        }
        if (latestRun.status == "failed") {
            return "failing"
        }
        if (!fresh) {
            return "stale"
        }
        return if (latestRun.status == "passed") "passing" else latestRun.status
    }

    private suspend fun resolveSuiteViews(manifests: List<SuiteManifest>): List<SuiteView> {
        val latestRuns = store.latestRunsBySuite()
        val states = manifests.associate { manifest -> manifest.name to store.getSuiteState(manifest.name) }
        return manifests.map { manifest ->
            val state = states[manifest.name]
            SuiteView(
                name = manifest.name,
                title = manifest.title,
                command = manifest.command,
                state = state?.state ?: "waiting",
                blockers = state?.blockers ?: emptyList(),
                fresh = state?.fresh ?: false,
                cadenceMinutes = manifest.cadenceMinutes,
                freshnessMinutes = manifest.freshnessMinutes,
                lastTriggerReason = state?.lastTriggerReason,
                lastEvaluatedAt = state?.lastEvaluatedAt,
                latestRun = latestRuns[manifest.name]?.toView()
            )
        }
    }

    private fun currentFingerprints(manifests: List<SuiteManifest>): FingerprintSnapshot {
        return FingerprintSnapshot(
            releaseFingerprint = fingerprintFile(config.releaseInfoPath),
            domainFingerprint = fingerprintFile(config.domainConfigPath),
            watchFingerprints = manifests.associate { manifest ->
                manifest.name to fingerprintWatchPaths(manifest.watchPaths)
            }
        )
    }

    private fun loadKnownGoodBaseline(): KnownGoodBaselineView? {
        val resultsRoot = config.resultsRoot
        if (!resultsRoot.exists() || !resultsRoot.isDirectory()) {
            return null
        }

        return Files.list(resultsRoot).use { stream ->
            stream.filter { it.isDirectory() }
                .toList()
                .mapNotNull(::parseKnownGoodBaseline)
                .filter { it.failed == 0 && it.status.equals("PASSED", ignoreCase = true) }
                .maxWithOrNull(
                    compareBy<KnownGoodBaselineView> { runCatching { Instant.parse(it.recordedAt) }.getOrNull() ?: Instant.EPOCH }
                        .thenBy { it.resultsPath }
                )
        }
    }

    private fun parseKnownGoodBaseline(resultsDir: Path): KnownGoodBaselineView? {
        val summaryPath = resultsDir.resolve("summary.txt")
        if (!summaryPath.exists() || !summaryPath.isRegularFile()) {
            return null
        }

        val summaryValues = summaryPath.readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(":") }
            .associate { line ->
                val (key, value) = line.split(":", limit = 2)
                key.trim() to value.trim()
            }

        val suite = parseSuiteName(resultsDir.name, resultsDir.resolve("metadata.txt"))
        val recordedAt = parseRecordedAt(resultsDir.name, resultsDir.resolve("metadata.txt"))

        return KnownGoodBaselineView(
            suite = suite,
            status = summaryValues["Status"] ?: "UNKNOWN",
            total = summaryValues["Total Tests"]?.toIntOrNull() ?: return null,
            passed = summaryValues["Passed"]?.toIntOrNull() ?: return null,
            failed = summaryValues["Failed"]?.toIntOrNull() ?: return null,
            durationMs = summaryValues["Duration"]?.substringBefore("ms")?.trim()?.toLongOrNull(),
            recordedAt = recordedAt,
            resultsPath = resultsDir.toAbsolutePath().toString(),
            summaryPath = summaryPath.toAbsolutePath().toString(),
            logPath = resultsDir.resolve("detailed.log")
                .takeIf { it.exists() && it.isRegularFile() }
                ?.toAbsolutePath()
                ?.toString()
        )
    }

    private fun parseSuiteName(resultsDirName: String, metadataPath: Path): String {
        if (metadataPath.exists() && metadataPath.isRegularFile()) {
            metadataPath.readText()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Suite:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return resultsDirName.substringAfter('-', missingDelimiterValue = resultsDirName)
    }

    private fun parseRecordedAt(resultsDirName: String, metadataPath: Path): String? {
        if (metadataPath.exists() && metadataPath.isRegularFile()) {
            val rawTimestamp = metadataPath.readText()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Timestamp:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
            if (!rawTimestamp.isNullOrBlank()) {
                runCatching {
                    LocalDateTime.parse(rawTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toString()
                }.getOrNull()?.let { return it }
            }
        }

        return runCatching {
            LocalDateTime.parse(
                resultsDirName.substringBefore('-'),
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            ).atZone(ZoneId.systemDefault()).toInstant().toString()
        }.getOrNull()
    }

    private fun fingerprintFile(path: Path): String {
        if (!path.exists() || !path.isRegularFile()) {
            return "missing:${path.fileName}"
        }
        return sha256(path.fileName.toString().toByteArray(StandardCharsets.UTF_8), Files.readAllBytes(path))
    }

    private fun loadReleaseInfo(): ReleaseInfo? {
        val path = config.releaseInfoPath
        if (!path.exists() || !path.isRegularFile()) {
            return null
        }
        val raw = path.readText().trim()
        if (raw.isBlank()) {
            return null
        }
        return if (raw.startsWith("{")) {
            runCatching { json.decodeFromString<ReleaseInfo>(raw) }.getOrNull()
        } else {
            parseLegacyReleaseInfo(raw)
        }
    }

    private fun parseLegacyReleaseInfo(raw: String): ReleaseInfo? {
        val values = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(":") }
            .associate { line ->
                val (key, value) = line.split(":", limit = 2)
                key.trim() to value.trim()
            }
        if (values.isEmpty()) {
            return null
        }
        val version = values["version"]
        val gitShortSha = values["git_short_sha"] ?: version
        return ReleaseInfo(
            version = version,
            gitSha = values["git_sha"] ?: version,
            gitShortSha = gitShortSha,
            gitBranch = values["git_branch"],
            gitDirty = values["git_dirty"]?.toBooleanStrictOrNull(),
            sourceBuiltAt = values["source_built_at"] ?: values["built_at"],
            renderedAt = values["rendered_at"],
            builtBy = values["built_by"],
            renderedBy = values["rendered_by"],
            siteName = values["site_name"],
            buildSystem = values["build_system"]
        )
    }

    private fun fingerprintWatchPaths(patterns: List<String>): String? {
        if (patterns.isEmpty()) {
            return null
        }
        val matched = linkedSetOf<Path>()
        patterns.forEach { pattern ->
            val direct = config.workspaceRoot.resolve(pattern).normalize()
            when {
                direct.exists() && direct.isRegularFile() -> matched.add(direct)
                direct.exists() && direct.isDirectory() -> {
                    Files.walk(direct).use { stream ->
                        stream.filter { Files.isRegularFile(it) }.forEach { matched.add(it) }
                    }
                }
                else -> {
                    val matcher = config.workspaceRoot.fileSystem.getPathMatcher("glob:$pattern")
                    Files.walk(config.workspaceRoot).use { stream ->
                        stream.filter { Files.isRegularFile(it) }
                            .filter { matcher.matches(it.relativeTo(config.workspaceRoot)) }
                            .forEach { matched.add(it) }
                    }
                }
            }
        }
        if (matched.isEmpty()) {
            return "none"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        matched.sortedBy { it.toString() }.forEach { path ->
            digest.update(path.relativeTo(config.workspaceRoot).toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(Files.readAllBytes(path))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun runCommand(command: String, logPath: Path): CommandResult {
        return withContext(Dispatchers.IO) {
            logPath.parent.createDirectories()
            val args = parseSafeCommand(command)
            val process = ProcessBuilder(args)
                .directory(config.workspaceRoot.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["DOCKER_HOST"] = config.dockerHost
                    environment()["DIST_DIR"] = config.workspaceRoot.toString()
                    environment()["COMPOSE_PROJECT_NAME"] = environment()["COMPOSE_PROJECT_NAME"] ?: "webservices"
                }
                .start()

            val tail = StringBuilder()
            logPath.toFile().bufferedWriter().use { writer ->
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val redactedLine = redactSensitiveOutput(line)
                        writer.appendLine(redactedLine)
                        trimAndAppend(tail, redactedLine + "\n")
                    }
                }
            }
            val exitCode = process.waitFor()
            CommandResult(exitCode = exitCode, logTail = tail.toString())
        }
    }

    private fun parseSafeCommand(command: String): List<String> {
        val trimmed = command.trim()
        require(trimmed.isNotEmpty()) { "empty command is not allowed" }
        val parts = trimmed.split(Regex("\\s+"))
        require(parts.isNotEmpty()) { "empty command is not allowed" }
        val executable = parts.first()
        require(config.allowedCommandPrefixes.any { it == executable }) {
            "command executable '$executable' is not in allowed prefixes: ${config.allowedCommandPrefixes.joinToString(",")}"
        }
        parts.forEach { token ->
            require(safeCommandTokenRegex.matches(token)) {
                "command contains unsupported token"
            }
        }
        return parts
    }

    private fun safeLogFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "suite" }

    private fun isPathInside(path: Path, root: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        return normalizedPath.startsWith(normalizedRoot)
    }

    private fun trimAndAppend(buffer: StringBuilder, addition: String) {
        buffer.append(addition)
        if (buffer.length > config.maxLogTailChars) {
            buffer.delete(0, buffer.length - config.maxLogTailChars)
        }
    }

    private fun sha256(vararg chunks: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEach(digest::update)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private data class FingerprintSnapshot(
    val releaseFingerprint: String,
    val domainFingerprint: String,
    val watchFingerprints: Map<String, String?>
)

private data class TriggerDecision(val reason: String)
private data class CommandResult(val exitCode: Int, val logTail: String)

internal fun redactSensitiveOutput(value: String): String {
    var redacted = value
    listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s]+"),
        Regex("(?i)((?:x-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password)\\s*[:=]\\s*)[^\\s]+"),
        Regex("(?i)([?&](?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password)=)[^&\\s]+")
    ).forEach { pattern ->
        redacted = pattern.replace(redacted) { match -> match.groupValues[1] + "[REDACTED]" }
    }
    return redacted
}
