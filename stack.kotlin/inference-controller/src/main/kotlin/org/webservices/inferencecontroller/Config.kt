package org.webservices.inferencecontroller

import java.nio.file.Path
import kotlin.io.path.Path

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
private fun envOptional(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
private fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
private fun envLong(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default
private fun envBoolean(name: String, default: Boolean = false): Boolean =
    when (System.getenv(name)?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
    }

fun loadConfig(): InferenceControllerConfig {
    val runtimeDir = env("XDG_RUNTIME_DIR", "/host-user-runtime")
    val apiToken = envOptional("INFERENCE_CONTROLLER_API_TOKEN")
        ?: envOptional("MODEL_CONTEXT_PROXY_AUTH_SECRET")
    return InferenceControllerConfig(
        port = envInt("INFERENCE_CONTROLLER_PORT", 8110),
        statePath = Path(env("INFERENCE_CONTROLLER_STATE_PATH", "/data/state.json")),
        reconcileIntervalSeconds = envLong("INFERENCE_CONTROLLER_RECONCILE_INTERVAL_SECONDS", 15),
        dbusSessionBusAddress = env("DBUS_SESSION_BUS_ADDRESS", "unix:path=$runtimeDir/bus"),
        apiToken = apiToken,
        allowUnauthenticatedInternalApi = envBoolean("INFERENCE_CONTROLLER_ALLOW_UNAUTHENTICATED_INTERNAL_API")
    )
}

data class InferenceControllerConfig(
    val port: Int,
    val statePath: Path,
    val reconcileIntervalSeconds: Long,
    val dbusSessionBusAddress: String,
    val apiToken: String?,
    val allowUnauthenticatedInternalApi: Boolean = false
)
