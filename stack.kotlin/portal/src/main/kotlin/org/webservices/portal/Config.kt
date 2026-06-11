package org.webservices.portal

import java.nio.file.Path
import kotlin.io.path.Path

data class PortalConfig(
    val port: Int,
    val domain: String,
    val contractsPath: Path,
    val lockPath: Path,
    val profilesPath: Path,
    val reportsDir: Path,
    val liveTimeoutMs: Long,
    val refreshTtlMs: Long,
    val progressBaseUrl: String,
    val workspacesBaseUrl: String,
    val chatgptConnectorBaseUrl: String,
    val grafanaBaseUrl: String,
    val kopiaBaseUrl: String,
    val marketingMode: Boolean,
    val excludedComponents: Set<String>
)

fun loadConfig(): PortalConfig = PortalConfig(
    port = envInt("PORTAL_PORT", 8080),
    domain = env("DOMAIN", "example.test"),
    contractsPath = Path(env("PORTAL_CONTRACTS_PATH", "/contracts/service-contracts.json")),
    lockPath = Path(env("PORTAL_COMPONENT_LOCK_PATH", "/contracts/components.lock.json")),
    profilesPath = Path(env("PORTAL_PROFILES_PATH", "/contracts/portal-profiles.json")),
    reportsDir = Path(env("PORTAL_REPORTS_DIR", "/contracts/reports")),
    liveTimeoutMs = envLong("PORTAL_LIVE_TIMEOUT_MS", 1200),
    refreshTtlMs = envLong("PORTAL_REFRESH_TTL_MS", 30_000),
    progressBaseUrl = env("PORTAL_PROGRESS_BASE_URL", "http://progression:8130"),
    workspacesBaseUrl = env("PORTAL_WORKSPACES_BASE_URL", "http://workspace-provisioner:8120"),
    chatgptConnectorBaseUrl = env("PORTAL_CHATGPT_CONNECTOR_BASE_URL", "http://chatgpt-connector:8130"),
    grafanaBaseUrl = env("PORTAL_GRAFANA_BASE_URL", "http://grafana:3000"),
    kopiaBaseUrl = env("PORTAL_KOPIA_BASE_URL", "http://kopia:51515"),
    marketingMode = envBool("PORTAL_MARKETING_MODE", false),
    excludedComponents = envSet("PORTAL_EXCLUDE_COMPONENTS")
)

private fun env(name: String, default: String): String =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default

private fun envInt(name: String, default: Int): Int =
    env(name, default.toString()).toIntOrNull() ?: default

private fun envLong(name: String, default: Long): Long =
    env(name, default.toString()).toLongOrNull() ?: default

private fun envBool(name: String, default: Boolean): Boolean =
    when (env(name, default.toString()).lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
    }

private fun envSet(name: String): Set<String> =
    System.getenv(name)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()
