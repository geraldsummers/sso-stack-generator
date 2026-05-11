package org.webservices.gpubootstraparbiter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private val dockerLogger = KotlinLogging.logger {}

interface DockerController {
    suspend fun inspect(serviceName: String): BackendStatus?
    suspend fun ensureRunning(backend: ManagedBackend)
    suspend fun ensureStopped(backend: ManagedBackend)
}

class CliDockerController(
    private val config: ArbiterConfig,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : DockerController {
    private val managedByService = managedBackends.associateBy { it.serviceName }
    private val healthClient = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    override suspend fun inspect(serviceName: String): BackendStatus? {
        val backend = managedByService.getValue(serviceName)
        val result = runDocker(listOf("inspect", backend.containerName), allowFailure = true)
        if (result.exitCode != 0) {
            return null
        }
        val inspected = json.decodeFromString<List<DockerInspectResponse>>(result.stdout).firstOrNull() ?: return null
        val labels = inspected.config?.labels.orEmpty()
        val managed = labels["webservices.gpu-arbiter.managed"] == "true"
        val health = inspected.state?.health?.status
        val running = inspected.state?.running ?: false
        val dockerHealthy = (health == null && running) || health == "healthy"
        val httpHealthy = if (!dockerHealthy && running) probeBackendHealth(backend) else false
        return BackendStatus(
            serviceName = backend.serviceName,
            containerName = backend.containerName,
            role = backend.role,
            mode = backend.mode,
            state = inspected.state?.status ?: "missing",
            running = running,
            healthy = dockerHealthy || httpHealthy,
            managed = managed,
            error = if (!managed) "container is not marked as arbiter-managed" else null
        )
    }

    private fun probeBackendHealth(backend: ManagedBackend): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url("${backend.baseUrl.trimEnd('/')}${backend.healthPath}")
                .get()
                .build()
            healthClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrElse { false }
    }

    override suspend fun ensureRunning(backend: ManagedBackend) {
        val status = inspect(backend.serviceName)
        if (status == null) {
            composeUp(backend)
            return
        }
        require(status.managed) { "Refusing to manage ${backend.serviceName}: ${status.error}" }
        if (!status.running) {
            runDocker(listOf("start", backend.containerName)).requireSuccess("start ${backend.serviceName}")
        }
    }

    override suspend fun ensureStopped(backend: ManagedBackend) {
        val status = inspect(backend.serviceName) ?: return
        require(status.managed) { "Refusing to manage ${backend.serviceName}: ${status.error}" }
        if (status.running) {
            runDocker(listOf("stop", backend.containerName)).requireSuccess("stop ${backend.serviceName}")
        }
    }

    private suspend fun composeUp(backend: ManagedBackend) {
        val args = mutableListOf<String>()
        args += "compose"
        args += "--project-name"
        args += config.composeProjectName
        args += "--env-file"
        args += config.composeEnvFile.toAbsolutePath().toString()
        args += "-f"
        args += config.composeFile.toAbsolutePath().toString()
        backend.profiles.forEach { profile ->
            args += "--profile"
            args += profile
        }
        args += listOf("up", "-d", "--no-build", "--no-deps", backend.serviceName)
        runDocker(args).requireSuccess("compose up ${backend.serviceName}")
        val status = inspect(backend.serviceName)
        require(status?.managed == true) { "Refusing to manage ${backend.serviceName}: created container is not labelled as arbiter-managed" }
    }

    private suspend fun runDocker(args: List<String>, allowFailure: Boolean = false): CommandResult = withContext(Dispatchers.IO) {
        val command = listOf("docker", "--host", config.dockerHost) + args
        dockerLogger.info { "docker> ${command.joinToString(" ")}" }
        val process = ProcessBuilder(command)
            .directory(config.workspaceRoot.toFile())
            .redirectErrorStream(false)
            .start()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        process.inputStream.use { it.copyTo(stdout) }
        process.errorStream.use { it.copyTo(stderr) }
        val exitCode = process.waitFor()
        val result = CommandResult(
            exitCode = exitCode,
            stdout = stdout.toString(Charsets.UTF_8),
            stderr = stderr.toString(Charsets.UTF_8)
        )
        if (!allowFailure && exitCode != 0) {
            error("docker command failed (${command.joinToString(" ")}): ${result.stderr.ifBlank { result.stdout }}")
        }
        result
    }
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    fun requireSuccess(action: String) {
        require(exitCode == 0) { "$action failed: ${stderr.ifBlank { stdout }}" }
    }
}

@Serializable
private data class DockerInspectResponse(
    @SerialName("State") val state: DockerState? = null,
    @SerialName("Config") val config: DockerConfig? = null
)

@Serializable
private data class DockerState(
    @SerialName("Status") val status: String? = null,
    @SerialName("Running") val running: Boolean? = null,
    @SerialName("Health") val health: DockerHealth? = null
)

@Serializable
private data class DockerHealth(
    @SerialName("Status") val status: String? = null
)

@Serializable
private data class DockerConfig(
    @SerialName("Labels") val labels: Map<String, String>? = null
)
