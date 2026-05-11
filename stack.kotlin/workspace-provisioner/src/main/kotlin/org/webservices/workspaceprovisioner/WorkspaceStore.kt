package org.webservices.workspaceprovisioner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.io.path.createDirectories

private val json = Json { ignoreUnknownKeys = true }

class WorkspaceStore(databasePath: Path) : AutoCloseable {
    private val jdbcUrl = "jdbc:sqlite:${databasePath.toAbsolutePath()}"

    init {
        databasePath.parent?.createDirectories()
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS workspaces (
                      id TEXT PRIMARY KEY,
                      display_name TEXT NOT NULL,
                      owner_username TEXT NOT NULL,
                      status TEXT NOT NULL,
                      container_name TEXT NOT NULL,
                      volume_name TEXT NOT NULL,
                      ssh_port INTEGER NOT NULL UNIQUE,
                      ssh_user TEXT NOT NULL,
                      notebook_container_name TEXT,
                      notebook_port INTEGER,
                      notebook_status TEXT NOT NULL DEFAULT 'stopped',
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      lease_expires_at TEXT NOT NULL,
                      notebook_last_error TEXT,
                      ttyd_port INTEGER,
                      ttyd_status TEXT NOT NULL DEFAULT 'stopped',
                      ttyd_last_error TEXT,
                      last_error TEXT
                    )
                    """.trimIndent()
                )
                addColumnIfMissing(statement, "workspaces", "notebook_container_name", "TEXT")
                addColumnIfMissing(statement, "workspaces", "notebook_port", "INTEGER")
                addColumnIfMissing(statement, "workspaces", "notebook_status", "TEXT NOT NULL DEFAULT 'stopped'")
                addColumnIfMissing(statement, "workspaces", "notebook_last_error", "TEXT")
                addColumnIfMissing(statement, "workspaces", "ttyd_port", "INTEGER")
                addColumnIfMissing(statement, "workspaces", "ttyd_status", "TEXT NOT NULL DEFAULT 'stopped'")
                addColumnIfMissing(statement, "workspaces", "ttyd_last_error", "TEXT")
                statement.execute("UPDATE workspaces SET notebook_status = 'stopped' WHERE notebook_status IS NULL OR notebook_status = ''")
                statement.execute("UPDATE workspaces SET ttyd_status = 'stopped' WHERE ttyd_status IS NULL OR ttyd_status = ''")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS workspace_delegations (
                      workspace_id TEXT NOT NULL,
                      principal_username TEXT NOT NULL,
                      granted_by TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      PRIMARY KEY (workspace_id, principal_username),
                      FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ssh_keys (
                      id TEXT PRIMARY KEY,
                      username TEXT NOT NULL,
                      name TEXT NOT NULL,
                      public_key TEXT NOT NULL,
                      fingerprint TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      UNIQUE (username, name),
                      UNIQUE (username, fingerprint)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS audit_events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      username TEXT NOT NULL,
                      action TEXT NOT NULL,
                      workspace_id TEXT,
                      details_json TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_workspaces_owner ON workspaces(owner_username)")
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_workspaces_notebook_port ON workspaces(notebook_port)")
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_workspaces_ttyd_port ON workspaces(ttyd_port)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_delegations_principal ON workspace_delegations(principal_username)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_audit_workspace ON audit_events(workspace_id, created_at DESC)")
            }
        }
    }

    fun listWorkspacesForPrincipal(username: String): List<WorkspaceRecord> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT DISTINCT w.id, w.display_name, w.owner_username, w.status, w.container_name, w.volume_name,
                   w.ssh_port, w.ssh_user, w.notebook_container_name, w.notebook_port, w.notebook_status,
                   w.ttyd_port, w.ttyd_status, w.created_at, w.updated_at, w.lease_expires_at,
                   w.notebook_last_error, w.ttyd_last_error, w.last_error
            FROM workspaces w
            LEFT JOIN workspace_delegations d ON d.workspace_id = w.id
            WHERE w.owner_username = ? OR d.principal_username = ?
            ORDER BY w.created_at DESC
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, username)
            statement.setString(2, username)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.toWorkspaceRecord())
                }
            }
        }
    }

    fun getWorkspace(workspaceId: String): WorkspaceRecord? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT id, display_name, owner_username, status, container_name, volume_name, ssh_port, ssh_user,
                   notebook_container_name, notebook_port, notebook_status, ttyd_port, ttyd_status,
                   created_at, updated_at, lease_expires_at, notebook_last_error, ttyd_last_error, last_error
            FROM workspaces WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, workspaceId)
            statement.executeQuery().use { result -> if (result.next()) result.toWorkspaceRecord() else null }
        }
    }

    fun listAllWorkspaces(): List<WorkspaceRecord> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT id, display_name, owner_username, status, container_name, volume_name, ssh_port, ssh_user,
                   notebook_container_name, notebook_port, notebook_status, ttyd_port, ttyd_status,
                   created_at, updated_at, lease_expires_at, notebook_last_error, ttyd_last_error, last_error
            FROM workspaces
            ORDER BY created_at DESC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.toWorkspaceRecord())
                }
            }
        }
    }

    fun createWorkspace(
        ownerUsername: String,
        displayName: String,
        sshPort: Int,
        sshUser: String,
        leaseDays: Int,
        notebookContainerName: String?,
        notebookPort: Int?,
        notebookStatus: String,
        ttydPort: Int?,
        ttydStatus: String
    ): WorkspaceRecord {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val record = WorkspaceRecord(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            ownerUsername = ownerUsername,
            status = "provisioning",
            containerName = "workspace-${UUID.randomUUID().toString().substring(0, 12)}",
            volumeName = "workspace-home-${UUID.randomUUID().toString().substring(0, 12)}",
            sshPort = sshPort,
            sshUser = sshUser,
            notebookContainerName = notebookContainerName,
            notebookPort = notebookPort,
            notebookStatus = notebookStatus,
            ttydPort = ttydPort,
            ttydStatus = ttydStatus,
            createdAt = now.toString(),
            updatedAt = now.toString(),
            leaseExpiresAt = now.plus(leaseDays.toLong(), ChronoUnit.DAYS).toString(),
            notebookLastError = null,
            ttydLastError = null,
            lastError = null
        )
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO workspaces(
                  id, display_name, owner_username, status, container_name, volume_name, ssh_port,
                  ssh_user, notebook_container_name, notebook_port, notebook_status, ttyd_port, ttyd_status,
                  created_at, updated_at, lease_expires_at, notebook_last_error, ttyd_last_error, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, record.id)
                statement.setString(2, record.displayName)
                statement.setString(3, record.ownerUsername)
                statement.setString(4, record.status)
                statement.setString(5, record.containerName)
                statement.setString(6, record.volumeName)
                statement.setInt(7, record.sshPort)
                statement.setString(8, record.sshUser)
                statement.setString(9, record.notebookContainerName)
                statement.setNullableInt(10, record.notebookPort)
                statement.setString(11, record.notebookStatus)
                statement.setNullableInt(12, record.ttydPort)
                statement.setString(13, record.ttydStatus)
                statement.setString(14, record.createdAt)
                statement.setString(15, record.updatedAt)
                statement.setString(16, record.leaseExpiresAt)
                statement.setString(17, record.notebookLastError)
                statement.setString(18, record.ttydLastError)
                statement.setString(19, record.lastError)
                statement.executeUpdate()
            }
        }
        return record
    }

    fun updateWorkspaceStatus(workspaceId: String, status: String, lastError: String? = null) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE workspaces SET status = ?, updated_at = ?, last_error = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, status)
                statement.setString(2, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                statement.setString(3, lastError)
                statement.setString(4, workspaceId)
                statement.executeUpdate()
            }
        }
    }

    fun updateNotebookReservation(workspaceId: String, notebookContainerName: String, notebookPort: Int) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE workspaces SET notebook_container_name = ?, notebook_port = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, notebookContainerName)
                statement.setInt(2, notebookPort)
                statement.setString(3, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                statement.setString(4, workspaceId)
                statement.executeUpdate()
            }
        }
    }

    fun updateNotebookStatus(workspaceId: String, status: String, lastError: String? = null) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE workspaces SET notebook_status = ?, updated_at = ?, notebook_last_error = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, status)
                statement.setString(2, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                statement.setString(3, lastError)
                statement.setString(4, workspaceId)
                statement.executeUpdate()
            }
        }
    }

    fun updateTtydReservation(workspaceId: String, ttydPort: Int) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE workspaces SET ttyd_port = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setInt(1, ttydPort)
                statement.setString(2, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                statement.setString(3, workspaceId)
                statement.executeUpdate()
            }
        }
    }

    fun updateTtydStatus(workspaceId: String, status: String, lastError: String? = null) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE workspaces SET ttyd_status = ?, updated_at = ?, ttyd_last_error = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, status)
                statement.setString(2, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                statement.setString(3, lastError)
                statement.setString(4, workspaceId)
                statement.executeUpdate()
            }
        }
    }

    fun renewLease(workspaceId: String, days: Int): WorkspaceRecord {
        val current = getWorkspace(workspaceId) ?: error("workspace not found: $workspaceId")
        val base = maxOf(Instant.parse(current.leaseExpiresAt), Instant.now())
        val next = base.plus(days.toLong(), ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE workspaces SET lease_expires_at = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, next.toString())
                statement.setString(2, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                statement.setString(3, workspaceId)
                statement.executeUpdate()
            }
        }
        return getWorkspace(workspaceId)!!
    }

    fun deleteWorkspace(workspaceId: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM workspaces WHERE id = ?").use { statement ->
                statement.setString(1, workspaceId)
                statement.executeUpdate()
            }
        }
    }

    fun listDelegates(workspaceId: String): List<String> = connection().use { connection ->
        connection.prepareStatement(
            "SELECT principal_username FROM workspace_delegations WHERE workspace_id = ? ORDER BY principal_username"
        ).use { statement ->
            statement.setString(1, workspaceId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getString(1))
                }
            }
        }
    }

    fun addDelegation(workspaceId: String, principalUsername: String, grantedBy: String) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO workspace_delegations(workspace_id, principal_username, granted_by, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(workspace_id, principal_username) DO NOTHING
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, workspaceId)
                statement.setString(2, principalUsername)
                statement.setString(3, grantedBy)
                statement.setString(4, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                statement.executeUpdate()
            }
        }
    }

    fun hasAccess(workspaceId: String, username: String): Boolean {
        val workspace = getWorkspace(workspaceId) ?: return false
        if (workspace.ownerUsername == username) return true
        return connection().use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM workspace_delegations WHERE workspace_id = ? AND principal_username = ? LIMIT 1"
            ).use { statement ->
                statement.setString(1, workspaceId)
                statement.setString(2, username)
                statement.executeQuery().use { result -> result.next() }
            }
        }
    }

    fun isOwner(workspaceId: String, username: String): Boolean = getWorkspace(workspaceId)?.ownerUsername == username

    fun listAllocatedPorts(): Set<Int> = connection().use { connection ->
        connection.prepareStatement("SELECT ssh_port FROM workspaces").use { statement ->
            statement.executeQuery().use { result ->
                buildSet {
                    while (result.next()) add(result.getInt(1))
                }
            }
        }
    }

    fun listAllocatedNotebookPorts(): Set<Int> = connection().use { connection ->
        connection.prepareStatement("SELECT notebook_port FROM workspaces WHERE notebook_port IS NOT NULL").use { statement ->
            statement.executeQuery().use { result ->
                buildSet {
                    while (result.next()) add(result.getInt(1))
                }
            }
        }
    }

    fun listAllocatedTtydPorts(): Set<Int> = connection().use { connection ->
        connection.prepareStatement("SELECT ttyd_port FROM workspaces WHERE ttyd_port IS NOT NULL").use { statement ->
            statement.executeQuery().use { result ->
                buildSet {
                    while (result.next()) add(result.getInt(1))
                }
            }
        }
    }

    fun listExpiredWorkspaceIds(now: Instant = Instant.now()): List<String> = connection().use { connection ->
        connection.prepareStatement("SELECT id FROM workspaces WHERE lease_expires_at <= ?").use { statement ->
            statement.setString(1, now.truncatedTo(ChronoUnit.SECONDS).toString())
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
        }
    }

    fun upsertSshKey(username: String, name: String, publicKey: String, fingerprint: String): SshKeyRecord {
        val existing = findKeyByFingerprint(username, fingerprint)
        if (existing != null) {
            connection().use { connection ->
                connection.prepareStatement("UPDATE ssh_keys SET name = ? WHERE id = ?").use { statement ->
                    statement.setString(1, name)
                    statement.setString(2, existing.id)
                    statement.executeUpdate()
                }
            }
            return existing.copy(name = name)
        }
        val existingName = getSshKey(username, name)
        if (existingName != null) {
            connection().use { connection ->
                connection.prepareStatement(
                    "UPDATE ssh_keys SET public_key = ?, fingerprint = ? WHERE id = ?"
                ).use { statement ->
                    statement.setString(1, publicKey)
                    statement.setString(2, fingerprint)
                    statement.setString(3, existingName.id)
                    statement.executeUpdate()
                }
            }
            return existingName.copy(publicKey = publicKey, fingerprint = fingerprint)
        }
        val record = SshKeyRecord(
            id = UUID.randomUUID().toString(),
            username = username,
            name = name,
            publicKey = publicKey,
            fingerprint = fingerprint,
            createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        )
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO ssh_keys(id, username, name, public_key, fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, record.id)
                statement.setString(2, record.username)
                statement.setString(3, record.name)
                statement.setString(4, record.publicKey)
                statement.setString(5, record.fingerprint)
                statement.setString(6, record.createdAt)
                statement.executeUpdate()
            }
        }
        return record
    }

    fun listSshKeys(username: String): List<SshKeyRecord> = connection().use { connection ->
        connection.prepareStatement(
            "SELECT id, username, name, public_key, fingerprint, created_at FROM ssh_keys WHERE username = ? ORDER BY created_at DESC"
        ).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toSshKeyRecord()) } }
        }
    }

    fun getSshKey(username: String, keyName: String): SshKeyRecord? = connection().use { connection ->
        connection.prepareStatement(
            "SELECT id, username, name, public_key, fingerprint, created_at FROM ssh_keys WHERE username = ? AND name = ?"
        ).use { statement ->
            statement.setString(1, username)
            statement.setString(2, keyName)
            statement.executeQuery().use { result -> if (result.next()) result.toSshKeyRecord() else null }
        }
    }

    private fun findKeyByFingerprint(username: String, fingerprint: String): SshKeyRecord? = connection().use { connection ->
        connection.prepareStatement(
            "SELECT id, username, name, public_key, fingerprint, created_at FROM ssh_keys WHERE username = ? AND fingerprint = ?"
        ).use { statement ->
            statement.setString(1, username)
            statement.setString(2, fingerprint)
            statement.executeQuery().use { result -> if (result.next()) result.toSshKeyRecord() else null }
        }
    }

    fun appendAudit(username: String, action: String, workspaceId: String? = null, details: Map<String, Any?> = emptyMap()) {
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO audit_events(username, action, workspace_id, details_json, created_at) VALUES (?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, action)
                statement.setString(3, workspaceId)
                statement.setString(4, encodeAuditDetails(details))
                statement.setString(5, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                statement.executeUpdate()
            }
        }
    }

    fun listAuditEvents(limit: Int = 100): List<AuditEventRecord> = connection().use { connection ->
        connection.prepareStatement(
            "SELECT id, username, action, workspace_id, details_json, created_at FROM audit_events ORDER BY id DESC LIMIT ?"
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toAuditEventRecord()) } }
        }
    }

    override fun close() = Unit

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl).also { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=ON")
        }
    }

    private fun encodeAuditDetails(details: Map<String, Any?>): String = buildJsonObject {
        details.toSortedMap().forEach { (key, value) ->
            when (value) {
                null -> put(key, JsonNull)
                is Number -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }.toString()

    private fun addColumnIfMissing(statement: java.sql.Statement, tableName: String, columnName: String, definition: String) {
        runCatching {
            statement.execute("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
        }
    }
}

private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) {
        setNull(index, java.sql.Types.INTEGER)
    } else {
        setInt(index, value)
    }
}

private fun java.sql.ResultSet.getNullableInt(columnName: String): Int? {
    val value = getInt(columnName)
    return if (wasNull()) null else value
}

private fun java.sql.ResultSet.toWorkspaceRecord(): WorkspaceRecord = WorkspaceRecord(
    id = getString("id"),
    displayName = getString("display_name"),
    ownerUsername = getString("owner_username"),
    status = getString("status"),
    containerName = getString("container_name"),
    volumeName = getString("volume_name"),
    sshPort = getInt("ssh_port"),
    sshUser = getString("ssh_user"),
    notebookContainerName = getString("notebook_container_name"),
    notebookPort = getNullableInt("notebook_port"),
    notebookStatus = getString("notebook_status"),
    ttydPort = getNullableInt("ttyd_port"),
    ttydStatus = getString("ttyd_status"),
    createdAt = getString("created_at"),
    updatedAt = getString("updated_at"),
    leaseExpiresAt = getString("lease_expires_at"),
    notebookLastError = getString("notebook_last_error"),
    ttydLastError = getString("ttyd_last_error"),
    lastError = getString("last_error")
)

private fun java.sql.ResultSet.toSshKeyRecord(): SshKeyRecord = SshKeyRecord(
    id = getString("id"),
    username = getString("username"),
    name = getString("name"),
    publicKey = getString("public_key"),
    fingerprint = getString("fingerprint"),
    createdAt = getString("created_at")
)

private fun java.sql.ResultSet.toAuditEventRecord(): AuditEventRecord = AuditEventRecord(
    id = getLong("id"),
    username = getString("username"),
    action = getString("action"),
    workspaceId = getString("workspace_id"),
    detailsJson = getString("details_json"),
    createdAt = getString("created_at")
)
