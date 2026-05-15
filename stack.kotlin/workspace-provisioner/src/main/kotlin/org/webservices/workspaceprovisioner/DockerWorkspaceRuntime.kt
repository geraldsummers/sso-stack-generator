package org.webservices.workspaceprovisioner

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.streams.asSequence

data class RuntimeAccessInfo(
    val sshHost: String,
    val sshPort: Int,
    val sshUser: String,
    val hostPublicKey: String
)

data class NotebookAccessInfo(
    val url: String,
    val basePath: String,
    val port: Int
)

data class TtydAccessInfo(
    val url: String,
    val basePath: String,
    val port: Int
)

class DockerWorkspaceRuntime(
    private val config: WorkspaceProvisionerConfig,
    private val sshCa: SmallstepSshCa
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val workspaceTokenCodec = WorkspaceAgentTokenCodec(
        sharedSecret = config.agentTokenSecret,
        ttlSeconds = config.agentTokenTtlSeconds
    )
    private val workspaceBuildFingerprintFile = config.dataDir.resolve("agent-workspace-image.sha256")
    private val notebookBuildFingerprintFile = config.dataDir.resolve("agent-workspace-notebook-image.sha256")

    init {
        validateDockerImage(config.workspaceImage, "workspace image")
        validateDockerImage(config.notebookImage, "notebook image")
        validateLinuxUser(config.workspaceUser, "workspace user")
        validateLinuxUser(config.workspaceSshUser, "workspace ssh user")
    }

    @Synchronized
    fun ensureWorkspaceImage() {
        require(config.workspaceContext.isDirectory()) { "workspace image context not found: ${config.workspaceContext}" }
        ensureImage(
            imageName = config.workspaceImage,
            context = config.workspaceContext,
            fingerprintFile = workspaceBuildFingerprintFile,
            description = "workspace"
        )
    }

    @Synchronized
    fun ensureNotebookImage() {
        require(config.notebookContext.isDirectory()) { "notebook image context not found: ${config.notebookContext}" }
        ensureImage(
            imageName = config.notebookImage,
            context = config.notebookContext,
            fingerprintFile = notebookBuildFingerprintFile,
            description = "notebook"
        )
    }

    fun isHostPortAvailable(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("0.0.0.0", port))
            true
        }
    }.getOrDefault(false)

    fun createWorkspace(record: WorkspaceRecord, principals: List<String>): RuntimeAccessInfo {
        validateWorkspaceRecord(record)
        ensureWorkspaceImage()
        val ttydPort = requireNotNull(record.ttydPort) { "workspace ttyd port is not reserved" }
        docker("rm", "-f", record.containerName)
        docker("volume", "rm", "-f", record.volumeName)
        dockerExpectSuccess(
            "create workspace volume",
            "volume",
            "create",
            "--label", "webservices.workspace.id=${record.id}",
            "--label", "webservices.workspace.owner=${record.ownerUsername}",
            record.volumeName
        )
        val startResult = docker(
            "run",
            "-d",
            "-w", "/",
            "--name", record.containerName,
            "--hostname", record.containerName,
            "--label", "webservices.workspace.id=${record.id}",
            "--label", "webservices.workspace.owner=${record.ownerUsername}",
            "-p", "${record.sshPort}:${config.workspaceSshPortInternal}",
            "-p", runtimeHttpPublish(ttydPort, config.workspaceTtydPortInternal),
            "-v", "${record.volumeName}:/workspace-home",
            "--user", "root",
            config.workspaceImage,
            "bash", "-lc", seedWorkspaceHomeCommand()
        )
        if (startResult.exitCode != 0) {
            if (isHostPortConflict(startResult.stdout, record.sshPort)) {
                throw HostPortUnavailableException(
                    port = record.sshPort,
                    message = "workspace SSH port ${record.sshPort} is already allocated on the host"
                )
            }
            if (isHostPortConflict(startResult.stdout, ttydPort)) {
                throw HostPortUnavailableException(
                    port = ttydPort,
                    message = "workspace ttyd port ${ttydPort} is already allocated on the host"
                )
            }
            error(
                "start workspace container failed: exit=${startResult.exitCode} stdout=${startResult.stdout} stderr=${startResult.stderr}"
            )
        }
        waitForWorkspaceHomeReady(record)
        return configureWorkspace(record, principals)
    }

    fun startWorkspace(record: WorkspaceRecord, principals: List<String>): RuntimeAccessInfo {
        validateWorkspaceRecord(record)
        ensureWorkspaceImage()
        requireNotNull(record.ttydPort) { "workspace ttyd port is not reserved" }
        dockerExpectSuccess("start workspace container", "start", record.containerName)
        waitForWorkspaceHomeReady(record)
        return configureWorkspace(record, principals)
    }

    fun stopWorkspace(record: WorkspaceRecord) {
        validateWorkspaceRecord(record)
        dockerExpectSuccess("stop workspace container", "stop", record.containerName)
    }

    fun startNotebook(record: WorkspaceRecord): NotebookAccessInfo {
        validateWorkspaceRecord(record, requireNotebook = true)
        ensureNotebookImage()
        val notebookPort = requireNotNull(record.notebookPort) { "workspace notebook port is not reserved" }
        val notebookContainerName = requireNotNull(record.notebookContainerName) { "workspace notebook container name is not set" }
        docker("rm", "-f", notebookContainerName)
        val startResult = docker(
            "run",
            "-d",
            "--name", notebookContainerName,
            "--hostname", notebookContainerName,
            "--label", "webservices.workspace.id=${record.id}",
            "--label", "webservices.workspace.owner=${record.ownerUsername}",
            "--label", "webservices.session.kind=notebook",
            "-p", runtimeHttpPublish(notebookPort, config.workspaceNotebookPortInternal),
            "-e", "JUPYTER_ROOT_DIR=/home/${config.workspaceUser}",
            "-e", "JUPYTER_TOKEN=",
            "-e", "NOTEBOOK_BASE_URL=${notebookBasePath(record.id)}",
            "-v", "${record.volumeName}:/home/${config.workspaceUser}",
            config.notebookImage
        )
        if (startResult.exitCode != 0) {
            if (isHostPortConflict(startResult.stdout, notebookPort)) {
                throw HostPortUnavailableException(
                    port = notebookPort,
                    message = "workspace notebook port ${notebookPort} is already allocated on the host"
                )
            }
            error(
                "start notebook container failed: exit=${startResult.exitCode} stdout=${startResult.stdout} stderr=${startResult.stderr}"
            )
        }
        waitForNotebookReady(record)
        return NotebookAccessInfo(
            url = notebookUrl(record.id),
            basePath = notebookBasePath(record.id),
            port = notebookPort
        )
    }

    fun stopNotebook(record: WorkspaceRecord) {
        validateWorkspaceRecord(record)
        val notebookContainerName = record.notebookContainerName ?: return
        dockerExpectSuccess("stop notebook container", "stop", notebookContainerName)
    }

    fun deleteWorkspace(record: WorkspaceRecord) {
        validateWorkspaceRecord(record)
        record.notebookContainerName?.let { docker("rm", "-f", it) }
        docker("rm", "-f", record.containerName)
        docker("volume", "rm", "-f", record.volumeName)
    }

    fun inspectStatus(record: WorkspaceRecord): String? {
        validateWorkspaceRecord(record)
        val result = docker("inspect", "--format", "{{.State.Status}}", record.containerName)
        if (result.exitCode != 0) return null
        return result.stdout.trim().ifBlank { null }
    }

    fun inspectNotebookStatus(record: WorkspaceRecord): String? {
        validateWorkspaceRecord(record)
        val notebookContainerName = record.notebookContainerName ?: return null
        val result = docker("inspect", "--format", "{{.State.Status}}", notebookContainerName)
        if (result.exitCode != 0) return null
        return result.stdout.trim().ifBlank { null }
    }

    fun inspectTtydStatus(record: WorkspaceRecord): String? {
        validateWorkspaceRecord(record)
        record.ttydPort ?: return null
        val result = docker(
            "exec",
            record.containerName,
            "bash",
            "-lc",
            "pgrep -af -- \"^/workspace-home/bin/ttyd .*--base-path ${ttydBasePath(record.id)}\""
        )
        if (result.exitCode != 0) return null
        return "running"
    }

    fun readProfiles(record: WorkspaceRecord): List<WorkspaceProfileRecord> {
        validateWorkspaceRecord(record)
        ensureWorkspaceImage()
        val result = docker(
            "run",
            "--rm",
            "-v", "${record.volumeName}:/workspace-home",
            config.workspaceImage,
            "bash",
            "-lc",
            "cat /workspace-home/.local/share/agent/profile-state/manifest.json 2>/dev/null || true"
        )
        if (result.exitCode != 0) return emptyList()
        val payload = result.stdout.trim()
        if (payload.isBlank()) return emptyList()
        return json.decodeFromString(ProfileManifest.serializer(), payload).profiles.map {
            WorkspaceProfileRecord(
                name = it.name,
                tier = it.tier,
                summary = it.summary,
                status = it.status,
                source = it.source,
                lastAppliedAt = it.lastAppliedAt
            )
        }
    }

    fun updateAuthorizedPrincipals(record: WorkspaceRecord, principals: List<String>) {
        validateWorkspaceRecord(record)
        val principalsFile = principals.joinToString("\n", postfix = "\n")
        writeTextFileInContainer(record.containerName, "/etc/ssh/auth_principals/${config.workspaceUser}", principalsFile)
    }

    fun fetchHostPublicKey(record: WorkspaceRecord): String {
        validateWorkspaceRecord(record)
        return dockerExpectSuccess(
            "read workspace host public key",
            "exec", "-w", "/", record.containerName, "bash", "-lc", "cat /etc/ssh/ssh_host_ed25519_key.pub"
        ).trim()
    }

    fun writeCodexToken(record: WorkspaceRecord, token: String) {
        validateWorkspaceRecord(record)
        require(token.isNotBlank()) { "Codex access token is required" }
        require(!token.contains('\n') && !token.contains('\r')) { "Codex access token must be a single line" }
        writeTextFileInContainer(
            record.containerName,
            "/workspace-home/.config/webservices/codex.env",
            buildString {
                appendLine("# Managed by Disposable Workspaces. This file is write-only from the controller UI.")
                appendLine("export CODEX_API_KEY=${shellSafe(token)}")
            }
        )
        dockerExpectSuccess(
            "secure workspace Codex token",
            "exec", "-w", "/", record.containerName, "bash", "-lc",
            "chown ${config.workspaceUser}:${config.workspaceUser} /workspace-home/.config/webservices/codex.env && chmod 0600 /workspace-home/.config/webservices/codex.env"
        )
    }

    fun clearCodexToken(record: WorkspaceRecord) {
        validateWorkspaceRecord(record)
        dockerExpectSuccess(
            "clear workspace Codex token",
            "exec", "-w", "/", record.containerName, "bash", "-lc",
            "rm -f /workspace-home/.config/webservices/codex.env"
        )
    }

    fun notebookBasePath(workspaceId: String): String {
        validateDockerIdentifier(workspaceId, "workspace id")
        return "/w/${workspaceId}/notebook/"
    }

    fun notebookUrl(workspaceId: String): String = "${config.publicBaseUrl}${notebookBasePath(workspaceId)}lab"

    fun ttydBasePath(workspaceId: String): String {
        validateDockerIdentifier(workspaceId, "workspace id")
        return "/w/${workspaceId}/shell"
    }

    fun ttydUrl(workspaceId: String): String = "${config.publicBaseUrl}${ttydBasePath(workspaceId)}/"

    private fun runtimeHttpPublish(hostPort: Int, containerPort: Int): String =
        "${config.runtimeHttpBindAddress}:${hostPort}:${containerPort}"

    private fun waitForWorkspaceHomeReady(record: WorkspaceRecord) {
        val readinessCommand = "test -f /workspace-home/.workspace-runtime-ready && test -L /home/${config.workspaceUser}"
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadlineNanos) {
            when (inspectStatus(record)) {
                null, "exited", "dead" -> {
                    val logs = docker("logs", "--tail", "80", record.containerName).stdout.trim()
                    error("workspace container exited before becoming ready: ${record.containerName} logs=$logs")
                }

                "created", "restarting" -> {
                    Thread.sleep(200)
                    continue
                }

                "running" -> {
                    val result = docker("exec", "-w", "/", record.containerName, "bash", "-lc", readinessCommand)
                    if (result.exitCode == 0) return
                }
            }
            Thread.sleep(200)
        }

        val logs = docker("logs", "--tail", "80", record.containerName).stdout.trim()
        error("workspace container did not become ready within 30 seconds: ${record.containerName} logs=$logs")
    }

    private fun waitForNotebookReady(record: WorkspaceRecord) {
        val notebookContainerName = requireNotNull(record.notebookContainerName)
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadlineNanos) {
            when (inspectNotebookStatus(record)) {
                null, "exited", "dead" -> {
                    val logs = docker("logs", "--tail", "80", notebookContainerName).stdout.trim()
                    error("notebook container exited before becoming ready: ${record.notebookContainerName} logs=$logs")
                }

                "created", "restarting" -> {
                    Thread.sleep(200)
                    continue
                }

                "running" -> {
                    val result = docker(
                        "exec",
                        notebookContainerName,
                        "python3",
                        "-c",
                        """
                        import sys, urllib.request
                        urllib.request.urlopen("http://127.0.0.1:${config.workspaceNotebookPortInternal}${notebookBasePath(record.id)}lab", timeout=2)
                        sys.stdout.write("ok")
                        """.trimIndent()
                    )
                    if (result.exitCode == 0) return
                }
            }
            Thread.sleep(200)
        }

        val logs = docker("logs", "--tail", "80", notebookContainerName).stdout.trim()
        error("notebook container did not become ready within 30 seconds: $notebookContainerName logs=$logs")
    }

    private fun waitForTtydReady(record: WorkspaceRecord) {
        val ttydPort = requireNotNull(record.ttydPort)
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadlineNanos) {
            val result = docker(
                "exec",
                record.containerName,
                "python3",
                "-c",
                """
                import sys, urllib.request
                urllib.request.urlopen("http://127.0.0.1:${config.workspaceTtydPortInternal}${ttydBasePath(record.id)}/", timeout=2)
                sys.stdout.write("ok")
                """.trimIndent()
            )
            if (result.exitCode == 0) return
            Thread.sleep(200)
        }

        val logs = docker("logs", "--tail", "80", record.containerName).stdout.trim()
        error("ttyd process did not become ready within 30 seconds: ${record.containerName} logs=$logs port=$ttydPort")
    }

    private fun configureWorkspace(record: WorkspaceRecord, principals: List<String>): RuntimeAccessInfo {
        val agentToken = workspaceTokenCodec.issue(record)
        dockerExpectSuccess(
            "prepare sshd directories",
            "exec", "-w", "/", record.containerName, "bash", "-lc",
            """
            mkdir -p /run/sshd /etc/ssh/auth_principals /etc/ssh/sshd_config.d /workspace-home /workspace-home/bin /workspace-home/repositories /workspace-home/.config/webservices
            ssh-keygen -A
            chown -h ${config.workspaceUser}:${config.workspaceUser} /home/${config.workspaceUser}
            chown -R ${config.workspaceUser}:${config.workspaceUser} /workspace-home
            """.trimIndent()
        )
        dockerCp(sshCa.userCaPublicKeyPath.toString(), "${record.containerName}:/etc/ssh/trusted_user_ca_keys.pub")
        writeTextFileInContainer(
            record.containerName,
            "/etc/ssh/auth_principals/${config.workspaceUser}",
            principals.joinToString("\n", postfix = "\n")
        )
        writeTextFileInContainer(
            record.containerName,
            "/etc/ssh/sshd_config.d/10-workspace.conf",
            buildString {
                appendLine("Port ${config.workspaceSshPortInternal}")
                appendLine("ListenAddress 0.0.0.0")
                appendLine("PasswordAuthentication no")
                appendLine("KbdInteractiveAuthentication no")
                appendLine("ChallengeResponseAuthentication no")
                appendLine("PubkeyAuthentication yes")
                appendLine("TrustedUserCAKeys /etc/ssh/trusted_user_ca_keys.pub")
                appendLine("AuthorizedPrincipalsFile /etc/ssh/auth_principals/%u")
                appendLine("PermitRootLogin no")
                appendLine("AllowUsers ${config.workspaceUser}")
                appendLine("PidFile /run/sshd/workspace.pid")
                appendLine("UsePAM no")
                appendLine("Subsystem sftp internal-sftp")
            }
        )
        writeTextFileInContainer(
            record.containerName,
            "/workspace-home/.config/webservices/agent.env",
            buildString {
                appendLine("export STACK_WORKSPACE_ID=${shellSafe(record.id)}")
                appendLine("export STACK_WORKSPACES_URL=${shellSafe(config.publicBaseUrl)}")
                appendLine("export STACK_AGENT_TOKEN=${shellSafe(agentToken.value)}")
                appendLine("export STACK_AGENT_TOKEN_EXPIRES_AT=${shellSafe(agentToken.expiresAt.toString())}")
                appendLine("export STACK_KNOWLEDGE_SEARCH_URL=${shellSafe("${config.publicBaseUrl}/api/workspaces/${record.id}/knowledge/search")}")
                appendLine("export STACK_KNOWLEDGE_DOCUMENT_URL_PREFIX=${shellSafe("${config.publicBaseUrl}/api/workspaces/${record.id}/knowledge/documents/")}")
            }
        )
        dockerExpectSuccess(
            "set workspace helper file ownership",
            "exec", "-w", "/", record.containerName, "bash", "-lc",
            "chown -R ${config.workspaceUser}:${config.workspaceUser} /workspace-home/.config /workspace-home/bin"
        )
        val hostPublicKey = fetchHostPublicKey(record)
        docker("exec", "-w", "/", record.containerName, "bash", "-lc", "pkill -f '/usr/sbin/sshd -D' || true")
        dockerExpectSuccess(
            "start workspace sshd",
            "exec", "-w", "/", "-d", record.containerName, "bash", "-lc", "/usr/sbin/sshd -D -e"
        )
        startTtyd(record)
        return RuntimeAccessInfo(
            sshHost = config.runtimePublicHost,
            sshPort = record.sshPort,
            sshUser = config.workspaceSshUser,
            hostPublicKey = hostPublicKey
        )
    }

    private fun ensureImage(imageName: String, context: Path, fingerprintFile: Path, description: String) {
        val currentFingerprint = contextFingerprint(context)
        val stored = runCatching { Files.readString(fingerprintFile).trim() }.getOrNull()
        val inspect = docker("image", "inspect", imageName)
        if (inspect.exitCode == 0 && stored == currentFingerprint) return
        dockerExpectSuccess("build $description image", "build", "-t", imageName, context.toString())
        Files.createDirectories(config.dataDir)
        Files.writeString(fingerprintFile, currentFingerprint)
    }

    private fun dockerCp(source: String, target: String) {
        dockerExpectSuccess("copy file into workspace container", "cp", source, target)
    }

    fun startTtyd(record: WorkspaceRecord): TtydAccessInfo {
        val ttydPort = requireNotNull(record.ttydPort) { "workspace ttyd port is not reserved" }
        if (inspectTtydStatus(record) == "running") {
            return TtydAccessInfo(
                url = ttydUrl(record.id),
                basePath = ttydBasePath(record.id),
                port = ttydPort
            )
        }
        ensureTtydBinary(record)
        val startResult = docker(
            "exec",
            "-d",
            "-w",
            "/workspace-home/repositories",
            "-u",
            config.workspaceUser,
            record.containerName,
            "bash",
            "-lc",
            """
            exec /workspace-home/bin/ttyd \
              --port ${config.workspaceTtydPortInternal} \
              --interface 0.0.0.0 \
              --writable \
              --check-origin \
              --base-path ${shellSafe(ttydBasePath(record.id))} \
              --cwd /workspace-home/repositories \
              bash -l
            """.trimIndent()
        )
        if (startResult.exitCode != 0) {
            if (isHostPortConflict(startResult.stdout, ttydPort)) {
                throw HostPortUnavailableException(
                    port = ttydPort,
                    message = "workspace ttyd port ${ttydPort} is already allocated on the host"
                )
            }
            error(
                "start ttyd process failed: exit=${startResult.exitCode} stdout=${startResult.stdout} stderr=${startResult.stderr}"
            )
        }
        waitForTtydReady(record)
        return TtydAccessInfo(
            url = ttydUrl(record.id),
            basePath = ttydBasePath(record.id),
            port = ttydPort
        )
    }

    private fun ensureTtydBinary(record: WorkspaceRecord) {
        val localBinary = config.dataDir.resolve("ttyd.bin")
        Files.createDirectories(config.dataDir)
        if (!Files.exists(localBinary)) {
            Files.copy(
                Path.of("/usr/local/bin/ttyd"),
                localBinary,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
        dockerExpectSuccess("copy ttyd binary into workspace container", "cp", localBinary.toString(), "${record.containerName}:/workspace-home/bin/ttyd")
        dockerExpectSuccess(
            "set ttyd binary ownership",
            "exec", record.containerName, "bash", "-lc",
            "chmod 0755 /workspace-home/bin/ttyd && chown ${config.workspaceUser}:${config.workspaceUser} /workspace-home/bin/ttyd"
        )
    }

    private fun isHostPortConflict(output: String, port: Int): Boolean {
        val normalized = output.lowercase()
        return normalized.contains("port is already allocated") ||
            normalized.contains("bind for 0.0.0.0:$port failed") ||
            normalized.contains("failed to bind port 0.0.0.0:$port")
    }

    private fun writeTextFileInContainer(containerName: String, targetPath: String, content: String) {
        validateDockerIdentifier(containerName, "container name")
        val tempFile = Files.createTempFile(config.dataDir, "workspace-text-", ".tmp")
        Files.writeString(tempFile, content)
        try {
            dockerCp(tempFile.toString(), "$containerName:$targetPath")
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun contextFingerprint(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it) }
                .sortedBy { it.toString() }
                .forEach { path ->
                    digest.update(root.relativize(path).toString().toByteArray())
                    digest.update(0.toByte())
                    digest.update(path.readBytes())
                    digest.update(0.toByte())
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun seedWorkspaceHomeCommand(): String = """
        set -euo pipefail
        rm -f /workspace-home/.workspace-runtime-ready
        if [ ! -f /workspace-home/.workspace-seeded ]; then
          mkdir -p /workspace-home /workspace-home/repositories
          cp -a /home/${config.workspaceUser}/. /workspace-home/
          touch /workspace-home/.workspace-seeded
        fi
        rm -rf /home/${config.workspaceUser}
        ln -s /workspace-home /home/${config.workspaceUser}
        mkdir -p /workspace-home/repositories
        chown -R ${config.workspaceUser}:${config.workspaceUser} /workspace-home
        su -s /bin/bash ${config.workspaceUser} -lc 'agent-sync-profile-state seed'
        touch /workspace-home/.workspace-runtime-ready
        exec tail -f /dev/null
    """.trimIndent()

    private fun dockerExpectSuccess(description: String, vararg args: String): String {
        val result = docker(*args)
        require(result.exitCode == 0) {
            "$description failed: exit=${result.exitCode} stdout=${result.stdout} stderr=${result.stderr}"
        }
        return result.stdout
    }

    private fun docker(vararg args: String): CommandResult {
        val process = ProcessBuilder(listOf("docker", *args))
            .redirectErrorStream(true)
            .start()
        val stdout = ByteArrayOutputStream()
        process.inputStream.copyTo(stdout)
        val completed = process.waitFor(10, TimeUnit.MINUTES)
        check(completed) { "docker command timed out: ${args.joinToString(" ")}" }
        return CommandResult(process.exitValue(), stdout.toString(Charsets.UTF_8), "")
    }

    private fun shellSafe(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun validateWorkspaceRecord(record: WorkspaceRecord, requireNotebook: Boolean = false) {
        validateDockerIdentifier(record.id, "workspace id")
        validateDockerIdentifier(record.containerName, "workspace container name")
        validateDockerIdentifier(record.volumeName, "workspace volume name")
        validateLinuxUser(record.sshUser, "workspace record ssh user")
        record.notebookContainerName?.let { validateDockerIdentifier(it, "notebook container name") }
        if (requireNotebook) {
            validateDockerIdentifier(requireNotNull(record.notebookContainerName) { "workspace notebook container name is not set" }, "notebook container name")
        }
    }

    private fun validateDockerIdentifier(value: String, label: String) {
        require(dockerIdentifierRegex.matches(value)) { "invalid $label" }
    }

    private fun validateDockerImage(value: String, label: String) {
        require(dockerImageRegex.matches(value) && !value.contains("..")) { "invalid $label" }
    }

    private fun validateLinuxUser(value: String, label: String) {
        require(linuxUserRegex.matches(value)) { "invalid $label" }
    }
}

private val dockerIdentifierRegex = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
private val dockerImageRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$")
private val linuxUserRegex = Regex("^[a-z_][a-z0-9_-]{0,31}$")

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

class HostPortUnavailableException(
    val port: Int,
    override val message: String
) : IllegalStateException(message)

@Serializable
private data class ProfileManifest(
    val schema: Int = 1,
    @SerialName("updated_at") val updatedAt: String? = null,
    val profiles: List<ProfileManifestEntry> = emptyList()
)

@Serializable
private data class ProfileManifestEntry(
    val name: String,
    val tier: String,
    val summary: String,
    val status: String,
    val source: String,
    @SerialName("last_applied_at") val lastAppliedAt: String? = null
)
