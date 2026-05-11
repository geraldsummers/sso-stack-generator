package org.webservices.testmanager.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webservices.testmanager.model.RunView
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createDirectories

private val json = Json { ignoreUnknownKeys = true }

data class SuiteStateRecord(
    val suiteName: String,
    val title: String,
    val command: String,
    val state: String,
    val blockers: List<String>,
    val prerequisitesMet: Boolean,
    val fresh: Boolean,
    val lastTriggerReason: String? = null,
    val lastEvaluatedAt: String? = null,
    val lastRunId: Long? = null,
    val consumedReleaseFingerprint: String? = null,
    val consumedDomainFingerprint: String? = null,
    val consumedWatchFingerprint: String? = null,
    val lastQueuedAt: String? = null
)

data class RunRecord(
    val id: Long,
    val suiteName: String,
    val title: String,
    val command: String,
    val status: String,
    val triggerReason: String,
    val requestedBy: String,
    val requestedAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val exitCode: Int? = null,
    val logPath: String? = null,
    val logTail: String? = null,
    val releaseFingerprint: String? = null,
    val domainFingerprint: String? = null,
    val watchFingerprint: String? = null
) {
    fun toView(): RunView = RunView(
        id = id,
        suiteName = suiteName,
        title = title,
        command = command,
        status = status,
        triggerReason = triggerReason,
        requestedBy = requestedBy,
        requestedAt = requestedAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
        exitCode = exitCode,
        logPath = logPath,
        logTail = logTail
    )
}

class TestManagerStore(databasePath: Path) : AutoCloseable {
    private val jdbcUrl = "jdbc:sqlite:${databasePath.toAbsolutePath()}"

    init {
        databasePath.parent?.createDirectories()
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS suite_state (
                      suite_name TEXT PRIMARY KEY,
                      title TEXT NOT NULL,
                      command TEXT NOT NULL,
                      state TEXT NOT NULL,
                      blockers_json TEXT NOT NULL,
                      prerequisites_met INTEGER NOT NULL,
                      fresh INTEGER NOT NULL,
                      last_trigger_reason TEXT,
                      last_evaluated_at TEXT,
                      last_run_id INTEGER,
                      consumed_release_fingerprint TEXT,
                      consumed_domain_fingerprint TEXT,
                      consumed_watch_fingerprint TEXT,
                      last_queued_at TEXT
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS runs (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      suite_name TEXT NOT NULL,
                      title TEXT NOT NULL,
                      command TEXT NOT NULL,
                      status TEXT NOT NULL,
                      trigger_reason TEXT NOT NULL,
                      requested_by TEXT NOT NULL,
                      requested_at TEXT NOT NULL,
                      started_at TEXT,
                      finished_at TEXT,
                      exit_code INTEGER,
                      log_path TEXT,
                      log_tail TEXT,
                      release_fingerprint TEXT,
                      domain_fingerprint TEXT,
                      watch_fingerprint TEXT
                    )
                    """.trimIndent()
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_runs_suite_requested ON runs(suite_name, requested_at DESC)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_runs_status_requested ON runs(status, requested_at ASC)")
            }
        }
    }

    fun getSuiteState(suiteName: String): SuiteStateRecord? {
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT suite_name, title, command, state, blockers_json, prerequisites_met, fresh,
                       last_trigger_reason, last_evaluated_at, last_run_id,
                       consumed_release_fingerprint, consumed_domain_fingerprint,
                       consumed_watch_fingerprint, last_queued_at
                FROM suite_state WHERE suite_name = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, suiteName)
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    result.toSuiteStateRecord()
                }
            }
        }
    }

    fun upsertSuiteState(record: SuiteStateRecord) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO suite_state(
                  suite_name, title, command, state, blockers_json, prerequisites_met, fresh,
                  last_trigger_reason, last_evaluated_at, last_run_id,
                  consumed_release_fingerprint, consumed_domain_fingerprint,
                  consumed_watch_fingerprint, last_queued_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(suite_name) DO UPDATE SET
                  title = excluded.title,
                  command = excluded.command,
                  state = excluded.state,
                  blockers_json = excluded.blockers_json,
                  prerequisites_met = excluded.prerequisites_met,
                  fresh = excluded.fresh,
                  last_trigger_reason = excluded.last_trigger_reason,
                  last_evaluated_at = excluded.last_evaluated_at,
                  last_run_id = excluded.last_run_id,
                  consumed_release_fingerprint = excluded.consumed_release_fingerprint,
                  consumed_domain_fingerprint = excluded.consumed_domain_fingerprint,
                  consumed_watch_fingerprint = excluded.consumed_watch_fingerprint,
                  last_queued_at = excluded.last_queued_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, record.suiteName)
                statement.setString(2, record.title)
                statement.setString(3, record.command)
                statement.setString(4, record.state)
                statement.setString(5, json.encodeToString(record.blockers))
                statement.setInt(6, if (record.prerequisitesMet) 1 else 0)
                statement.setInt(7, if (record.fresh) 1 else 0)
                statement.setString(8, record.lastTriggerReason)
                statement.setString(9, record.lastEvaluatedAt)
                setNullableLong(statement, 10, record.lastRunId)
                statement.setString(11, record.consumedReleaseFingerprint)
                statement.setString(12, record.consumedDomainFingerprint)
                statement.setString(13, record.consumedWatchFingerprint)
                statement.setString(14, record.lastQueuedAt)
                statement.executeUpdate()
            }
        }
    }

    fun hasQueuedOrRunningRun(suiteName: String): Boolean {
        return connection().use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM runs WHERE suite_name = ? AND status IN ('queued', 'running') LIMIT 1"
            ).use { statement ->
                statement.setString(1, suiteName)
                statement.executeQuery().use { result -> result.next() }
            }
        }
    }

    fun queueRun(
        suiteName: String,
        title: String,
        command: String,
        triggerReason: String,
        requestedBy: String,
        releaseFingerprint: String,
        domainFingerprint: String,
        watchFingerprint: String?
    ): Long {
        val now = Instant.now().toString()
        return connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO runs(
                  suite_name, title, command, status, trigger_reason, requested_by, requested_at,
                  release_fingerprint, domain_fingerprint, watch_fingerprint
                ) VALUES (?, ?, ?, 'queued', ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, suiteName)
                statement.setString(2, title)
                statement.setString(3, command)
                statement.setString(4, triggerReason)
                statement.setString(5, requestedBy)
                statement.setString(6, now)
                statement.setString(7, releaseFingerprint)
                statement.setString(8, domainFingerprint)
                statement.setString(9, watchFingerprint)
                statement.executeUpdate()
            }
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT last_insert_rowid()").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    fun claimNextQueuedRun(): RunRecord? {
        val connection = connection()
        return try {
            connection.autoCommit = false
            val queuedId = connection.prepareStatement(
                "SELECT id FROM runs WHERE status = 'queued' ORDER BY requested_at ASC LIMIT 1"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    result.getLong("id")
                }
            }
            val startedAt = Instant.now().toString()
            connection.prepareStatement(
                "UPDATE runs SET status = 'running', started_at = ? WHERE id = ? AND status = 'queued'"
            ).use { statement ->
                statement.setString(1, startedAt)
                statement.setLong(2, queuedId)
                val updated = statement.executeUpdate()
                if (updated == 0) {
                    connection.rollback()
                    return null
                }
            }
            connection.commit()
            getRun(queuedId)
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
            connection.close()
        }
    }

    fun completeRun(id: Long, status: String, exitCode: Int?, logPath: String?, logTail: String?) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE runs SET status = ?, finished_at = ?, exit_code = ?, log_path = ?, log_tail = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, status)
                statement.setString(2, Instant.now().toString())
                setNullableInt(statement, 3, exitCode)
                statement.setString(4, logPath)
                statement.setString(5, logTail)
                statement.setLong(6, id)
                statement.executeUpdate()
            }
        }
    }

    fun getRun(id: Long): RunRecord? {
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, suite_name, title, command, status, trigger_reason, requested_by, requested_at,
                       started_at, finished_at, exit_code, log_path, log_tail,
                       release_fingerprint, domain_fingerprint, watch_fingerprint
                FROM runs WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    result.toRunRecord()
                }
            }
        }
    }

    fun listRuns(limit: Int = 25): List<RunRecord> {
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, suite_name, title, command, status, trigger_reason, requested_by, requested_at,
                       started_at, finished_at, exit_code, log_path, log_tail,
                       release_fingerprint, domain_fingerprint, watch_fingerprint
                FROM runs ORDER BY requested_at DESC LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.toRunRecord())
                        }
                    }
                }
            }
        }
    }

    fun latestRunsBySuite(): Map<String, RunRecord> {
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT r.id, r.suite_name, r.title, r.command, r.status, r.trigger_reason, r.requested_by,
                       r.requested_at, r.started_at, r.finished_at, r.exit_code, r.log_path, r.log_tail,
                       r.release_fingerprint, r.domain_fingerprint, r.watch_fingerprint
                FROM runs r
                INNER JOIN (
                    SELECT suite_name, MAX(requested_at) AS requested_at
                    FROM runs
                    GROUP BY suite_name
                ) latest ON latest.suite_name = r.suite_name AND latest.requested_at = r.requested_at
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            val record = result.toRunRecord()
                            put(record.suiteName, record)
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        // no pooled resources
    }

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl)
}

private fun java.sql.ResultSet.toSuiteStateRecord(): SuiteStateRecord {
    val blockers = json.decodeFromString<List<String>>(getString("blockers_json"))
    return SuiteStateRecord(
        suiteName = getString("suite_name"),
        title = getString("title"),
        command = getString("command"),
        state = getString("state"),
        blockers = blockers,
        prerequisitesMet = getInt("prerequisites_met") == 1,
        fresh = getInt("fresh") == 1,
        lastTriggerReason = getString("last_trigger_reason"),
        lastEvaluatedAt = getString("last_evaluated_at"),
        lastRunId = getLongOrNull("last_run_id"),
        consumedReleaseFingerprint = getString("consumed_release_fingerprint"),
        consumedDomainFingerprint = getString("consumed_domain_fingerprint"),
        consumedWatchFingerprint = getString("consumed_watch_fingerprint"),
        lastQueuedAt = getString("last_queued_at")
    )
}

private fun java.sql.ResultSet.toRunRecord(): RunRecord {
    return RunRecord(
        id = getLong("id"),
        suiteName = getString("suite_name"),
        title = getString("title"),
        command = getString("command"),
        status = getString("status"),
        triggerReason = getString("trigger_reason"),
        requestedBy = getString("requested_by"),
        requestedAt = getString("requested_at"),
        startedAt = getString("started_at"),
        finishedAt = getString("finished_at"),
        exitCode = getIntOrNull("exit_code"),
        logPath = getString("log_path"),
        logTail = getString("log_tail"),
        releaseFingerprint = getString("release_fingerprint"),
        domainFingerprint = getString("domain_fingerprint"),
        watchFingerprint = getString("watch_fingerprint")
    )
}

private fun setNullableLong(statement: java.sql.PreparedStatement, index: Int, value: Long?) {
    if (value == null) {
        statement.setNull(index, java.sql.Types.BIGINT)
    } else {
        statement.setLong(index, value)
    }
}

private fun setNullableInt(statement: java.sql.PreparedStatement, index: Int, value: Int?) {
    if (value == null) {
        statement.setNull(index, java.sql.Types.INTEGER)
    } else {
        statement.setInt(index, value)
    }
}

private fun java.sql.ResultSet.getLongOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun java.sql.ResultSet.getIntOrNull(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}
