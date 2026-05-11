package org.webservices.pipeline.monitoring

import org.webservices.pipeline.scheduling.RunMetadata
import org.webservices.pipeline.scheduling.RunType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class SourceRuntimeTracker(
    private val clock: Clock = Clock.systemUTC(),
    private val stallThreshold: Duration = Duration.ofMinutes(15)
) {
    private val states = ConcurrentHashMap<String, MutableRuntimeState>()

    fun registerSource(source: String) {
        states.computeIfAbsent(source) {
            MutableRuntimeState(
                source = source,
                runState = "awaiting_first_run",
                phase = "awaiting_first_run"
            )
        }
    }

    fun snapshot(source: String): SourceRuntimeSnapshot {
        val state = states.computeIfAbsent(source) {
            MutableRuntimeState(source = source, runState = "awaiting_first_run", phase = "awaiting_first_run")
        }
        return synchronized(state) { state.toSnapshot() }
    }

    fun snapshotAll(): List<SourceRuntimeSnapshot> {
        return states.keys.sorted().map(::snapshot)
    }

    fun isStalled(snapshot: SourceRuntimeSnapshot): Boolean {
        if (!snapshot.activeRun) {
            return false
        }
        val progressAt = snapshot.lastProgressAt ?: snapshot.fetchStartedAt ?: snapshot.currentRunStartedAt ?: return false
        return runCatching {
            Duration.between(Instant.parse(progressAt), Instant.now(clock)) > stallThreshold
        }.getOrDefault(false)
    }

    fun markRunStarting(source: String, metadata: RunMetadata) {
        val now = nowString()
        val state = stateFor(source)
        synchronized(state) {
            state.activeRun = true
            state.runState = "running"
            state.phase = metadata.runType.name.lowercase()
            state.currentRunType = metadata.runType.name.lowercase()
            state.currentRunStartedAt = now
            state.fetchStartedAt = now
            state.fetchCompletedAt = null
            state.lastProgressAt = now
            state.lastError = null
            state.stagedCurrentRun = 0
            state.deduplicatedCurrentRun = 0
            state.failedCurrentRun = 0
        }
    }

    fun markFetchProgress(source: String) {
        val state = stateFor(source)
        synchronized(state) {
            state.lastProgressAt = nowString()
        }
    }

    fun markDocumentsStaged(source: String, count: Int) {
        val state = stateFor(source)
        synchronized(state) {
            state.phase = "staging"
            state.lastProgressAt = nowString()
            state.stagedCurrentRun += count.toLong()
        }
    }

    fun markDeduplicated(source: String, count: Int = 1) {
        val state = stateFor(source)
        synchronized(state) {
            state.lastProgressAt = nowString()
            state.deduplicatedCurrentRun += count.toLong()
        }
    }

    fun markItemFailure(source: String, errorMessage: String?) {
        val state = stateFor(source)
        synchronized(state) {
            state.lastProgressAt = nowString()
            state.failedCurrentRun += 1
            if (!errorMessage.isNullOrBlank()) {
                state.lastError = errorMessage
            }
        }
    }

    fun markFetchCompleted(source: String) {
        val state = stateFor(source)
        synchronized(state) {
            val now = nowString()
            state.phase = "finalizing"
            state.fetchCompletedAt = now
            state.lastProgressAt = now
        }
    }

    fun markRunSucceeded(source: String) {
        val state = stateFor(source)
        synchronized(state) {
            val now = nowString()
            state.activeRun = false
            state.runState = "idle"
            state.phase = "idle"
            state.fetchCompletedAt = state.fetchCompletedAt ?: now
            state.lastProgressAt = now
            state.lastCompletedAt = now
            if (state.currentRunType == RunType.INITIAL_PULL.name.lowercase()) {
                state.completedInitialPull = true
            }
        }
    }

    fun markRunFailed(source: String, errorMessage: String?) {
        val state = stateFor(source)
        synchronized(state) {
            val now = nowString()
            state.activeRun = false
            state.runState = "failed"
            state.phase = "failed"
            state.lastProgressAt = now
            state.lastFailedAt = now
            if (!errorMessage.isNullOrBlank()) {
                state.lastError = errorMessage
            }
        }
    }

    private fun stateFor(source: String): MutableRuntimeState {
        return states.computeIfAbsent(source) {
            MutableRuntimeState(source = source, runState = "awaiting_first_run", phase = "awaiting_first_run")
        }
    }

    private fun nowString(): String = Instant.now(clock).toString()
}

private data class MutableRuntimeState(
    val source: String,
    var activeRun: Boolean = false,
    var runState: String,
    var phase: String,
    var completedInitialPull: Boolean = false,
    var currentRunType: String? = null,
    var currentRunStartedAt: String? = null,
    var fetchStartedAt: String? = null,
    var fetchCompletedAt: String? = null,
    var lastProgressAt: String? = null,
    var lastCompletedAt: String? = null,
    var lastFailedAt: String? = null,
    var lastError: String? = null,
    var stagedCurrentRun: Long = 0,
    var deduplicatedCurrentRun: Long = 0,
    var failedCurrentRun: Long = 0
) {
    fun toSnapshot(): SourceRuntimeSnapshot {
        return SourceRuntimeSnapshot(
            source = source,
            activeRun = activeRun,
            runState = runState,
            phase = phase,
            completedInitialPull = completedInitialPull,
            currentRunType = currentRunType,
            currentRunStartedAt = currentRunStartedAt,
            fetchStartedAt = fetchStartedAt,
            fetchCompletedAt = fetchCompletedAt,
            lastProgressAt = lastProgressAt,
            lastCompletedAt = lastCompletedAt,
            lastFailedAt = lastFailedAt,
            lastError = lastError,
            stagedCurrentRun = stagedCurrentRun,
            deduplicatedCurrentRun = deduplicatedCurrentRun,
            failedCurrentRun = failedCurrentRun
        )
    }
}
