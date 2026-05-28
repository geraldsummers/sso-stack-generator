package org.webservices.gpuworkloadmonitor

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
private fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
private fun envLong(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default

fun loadConfig(): WorkloadMonitorConfig {
    return WorkloadMonitorConfig(
        port = envInt("GPU_WORKLOAD_MONITOR_PORT", 8112),
        postgresJdbcUrl = env("POSTGRES_JDBC_URL", "jdbc:postgresql://postgres-ssd:5432/webservices"),
        postgresUser = env("POSTGRES_USER", "pipeline_user"),
        postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: "",
        sourceIds = env(
            "GPU_WORKLOAD_MONITOR_SOURCE_IDS",
            "rss,cve,wikipedia,australian_laws,linux_docs,debian_wiki,arch_wiki"
        ).split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct(),
        knowledgeIngestionReadinessBaseUrl = env(
            "GPU_WORKLOAD_MONITOR_READINESS_BASE_URL",
            "http://knowledge-ingestion:8090/readiness"
        ).trimEnd('/'),
        evaluationIntervalSeconds = envLong("GPU_WORKLOAD_MONITOR_EVALUATION_INTERVAL_SECONDS", 30),
        sourceQueryTimeoutMs = envLong("GPU_WORKLOAD_MONITOR_SOURCE_QUERY_TIMEOUT_MS", 3000),
        readinessHttpTimeoutMs = envLong("GPU_WORKLOAD_MONITOR_READINESS_HTTP_TIMEOUT_MS", 3000)
    )
}

data class WorkloadMonitorConfig(
    val port: Int,
    val postgresJdbcUrl: String,
    val postgresUser: String,
    val postgresPassword: String,
    val sourceIds: List<String>,
    val knowledgeIngestionReadinessBaseUrl: String,
    val evaluationIntervalSeconds: Long,
    val sourceQueryTimeoutMs: Long,
    val readinessHttpTimeoutMs: Long
)
