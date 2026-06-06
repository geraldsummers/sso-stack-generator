package org.webservices.testmanager.config

import java.nio.file.Path
import kotlin.io.path.Path

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
private fun envRequired(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name must be set")
private fun envLong(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default
private fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
private fun envCsv(name: String, default: List<String>): List<String> =
    System.getenv(name)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.takeIf { it.isNotEmpty() }
        ?: default

fun loadConfig(): TestManagerConfig {
    val workspaceRoot = Path(env("TEST_MANAGER_WORKSPACE_ROOT", "/workspace"))
    val dataRoot = Path(env("TEST_MANAGER_DATA_ROOT", "/data"))
    return TestManagerConfig(
        port = envInt("TEST_MANAGER_PORT", 8105),
        workspaceRoot = workspaceRoot,
        dataRoot = dataRoot,
        databasePath = Path(env("TEST_MANAGER_DB_PATH", dataRoot.resolve("test-manager.db").toString())),
        logRoot = Path(env("TEST_MANAGER_LOG_ROOT", dataRoot.resolve("runs").toString())),
        resultsRoot = Path(env("TEST_MANAGER_RESULTS_ROOT", workspaceRoot.resolve("test-results").toString())),
        suitesPath = Path(env("TEST_MANAGER_SUITES_PATH", workspaceRoot.resolve("runtime/configs/test-manager/suites.yaml").toString())),
        releaseInfoPath = Path(env("TEST_MANAGER_RELEASE_INFO_PATH", workspaceRoot.resolve(".build-info").toString())),
        domainConfigPath = Path(env("TEST_MANAGER_DOMAIN_CONFIG_PATH", workspaceRoot.resolve("runtime/configs/caddy/Caddyfile").toString())),
        pipelineReadinessUrl = env("TEST_MANAGER_PIPELINE_READINESS_URL", "http://ingestion-runner:8090/health"),
        pipelineApiKey = System.getenv("TEST_MANAGER_PIPELINE_API_KEY")?.takeIf { it.isNotBlank() },
        apiKey = envRequired("TEST_MANAGER_API_KEY"),
        allowedCommandPrefixes = envCsv("TEST_MANAGER_ALLOWED_COMMAND_PREFIXES", listOf("./run-tests.sh")),
        dockerHost = env("TEST_MANAGER_DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "tcp://docker-socket-proxy:2375"),
        evaluationIntervalSeconds = envLong("TEST_MANAGER_EVALUATION_INTERVAL_SECONDS", 30),
        queuePollIntervalSeconds = envLong("TEST_MANAGER_QUEUE_POLL_INTERVAL_SECONDS", 5),
        maxLogTailChars = envInt("TEST_MANAGER_MAX_LOG_TAIL_CHARS", 12000)
    )
}

data class TestManagerConfig(
    val port: Int,
    val workspaceRoot: Path,
    val dataRoot: Path,
    val databasePath: Path,
    val logRoot: Path,
    val resultsRoot: Path,
    val suitesPath: Path,
    val releaseInfoPath: Path,
    val domainConfigPath: Path,
    val pipelineReadinessUrl: String,
    val pipelineApiKey: String?,
    val apiKey: String,
    val allowedCommandPrefixes: List<String>,
    val dockerHost: String,
    val evaluationIntervalSeconds: Long,
    val queuePollIntervalSeconds: Long,
    val maxLogTailChars: Int
)
