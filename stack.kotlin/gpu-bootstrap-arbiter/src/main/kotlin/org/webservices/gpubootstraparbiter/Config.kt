package org.webservices.gpubootstraparbiter

import java.nio.file.Path
import kotlin.io.path.Path

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
private fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
private fun envLong(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default
private fun envLongOrNull(name: String): Long? = System.getenv(name)?.toLongOrNull()
private fun envBooleanOrNull(name: String): Boolean? =
    when (System.getenv(name)?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }
private fun envOptional(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

fun loadConfig(): ArbiterConfig {
    val workspaceRoot = Path(env("GPU_ARBITER_WORKSPACE_ROOT", "/workspace"))
    val dataRoot = Path(env("GPU_ARBITER_DATA_ROOT", "/data"))

    val pendingHighThreshold = envLong(
        "GPU_ARBITER_EMBEDDING_PRIORITY_PENDING_HIGH_THRESHOLD",
        envLong("GPU_ARBITER_PROMOTION_PENDING_THRESHOLD", 5000)
    )
    val pendingLowThreshold = envLongOrNull("GPU_ARBITER_EMBEDDING_PRIORITY_PENDING_LOW_THRESHOLD")
        ?.coerceAtMost(pendingHighThreshold)
        ?: (pendingHighThreshold / 4).coerceAtLeast(0)

    val inProgressHighThreshold = envLong(
        "GPU_ARBITER_EMBEDDING_PRIORITY_IN_PROGRESS_HIGH_THRESHOLD",
        envLong("GPU_ARBITER_PROMOTION_IN_PROGRESS_THRESHOLD", 256)
    )
    val inProgressLowThreshold = envLongOrNull("GPU_ARBITER_EMBEDDING_PRIORITY_IN_PROGRESS_LOW_THRESHOLD")
        ?.coerceAtMost(inProgressHighThreshold)
        ?: (inProgressHighThreshold / 4).coerceAtLeast(0)

    val requireInitialPullCompleteForLlmPriority =
        envBooleanOrNull("GPU_ARBITER_REQUIRE_INITIAL_PULL_COMPLETE_FOR_LLM_PRIORITY")
            ?: envBooleanOrNull("GPU_ARBITER_REQUIRE_INITIAL_PULL_COMPLETE")
            ?: false

    return ArbiterConfig(
        port = envInt("GPU_ARBITER_PORT", 8110),
        workspaceRoot = workspaceRoot,
        dataRoot = dataRoot,
        statePath = Path(env("GPU_ARBITER_STATE_PATH", dataRoot.resolve("arbiter-state.json").toString())),
        dockerHost = env("GPU_ARBITER_DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "tcp://docker-socket-proxy:2375"),
        composeProjectName = env("GPU_ARBITER_COMPOSE_PROJECT_NAME", "webservices"),
        composeFile = Path(env("GPU_ARBITER_COMPOSE_FILE", workspaceRoot.resolve("docker-compose.yml").toString())),
        composeEnvFile = Path(env("GPU_ARBITER_COMPOSE_ENV_FILE", workspaceRoot.resolve("runtime/stack.env").toString())),
        signalUrl = env(
            "GPU_ARBITER_SIGNAL_URL",
            env("GPU_ARBITER_READINESS_URL", "http://gpu-workload-monitor:8112/api/signal")
        ),
        signalApiKey = System.getenv("GPU_ARBITER_SIGNAL_API_KEY")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GPU_ARBITER_READINESS_API_KEY")?.takeIf { it.isNotBlank() },
        apiToken = envOptional("GPU_ARBITER_API_TOKEN")
            ?: envOptional("MODEL_CONTEXT_PROXY_AUTH_SECRET"),
        allowUnauthenticatedInternalApi = envBooleanOrNull("GPU_ARBITER_ALLOW_UNAUTHENTICATED_INTERNAL_API") ?: false,
        signalTimeoutMs = envLong("GPU_ARBITER_SIGNAL_TIMEOUT_MS", 10000),
        evaluationIntervalSeconds = envLong("GPU_ARBITER_EVALUATION_INTERVAL_SECONDS", 30),
        embeddingPriorityPendingHighThreshold = pendingHighThreshold,
        embeddingPriorityPendingLowThreshold = pendingLowThreshold,
        embeddingPriorityInProgressHighThreshold = inProgressHighThreshold,
        embeddingPriorityInProgressLowThreshold = inProgressLowThreshold,
        requireInitialPullCompleteForLlmPriority = requireInitialPullCompleteForLlmPriority
    )
}

data class ArbiterConfig(
    val port: Int,
    val workspaceRoot: Path,
    val dataRoot: Path,
    val statePath: Path,
    val dockerHost: String,
    val composeProjectName: String,
    val composeFile: Path,
    val composeEnvFile: Path,
    val signalUrl: String,
    val signalApiKey: String?,
    val apiToken: String?,
    val allowUnauthenticatedInternalApi: Boolean = false,
    val signalTimeoutMs: Long,
    val evaluationIntervalSeconds: Long,
    val embeddingPriorityPendingHighThreshold: Long,
    val embeddingPriorityPendingLowThreshold: Long,
    val embeddingPriorityInProgressHighThreshold: Long,
    val embeddingPriorityInProgressLowThreshold: Long,
    val requireInitialPullCompleteForLlmPriority: Boolean
)
