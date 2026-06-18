package org.webservices.workspaceprovisioner

import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path

data class WorkspaceProvisionerConfig(
    val port: Int,
    val dataDir: Path,
    val databasePath: Path,
    val oidcBaseUrl: String,
    val oidcPublicUrl: String,
    val publicBaseUrl: String,
    val searchServiceBaseUrl: String,
    val searchServiceUsername: String?,
    val searchServicePassword: String?,
    val searchServiceToken: String?,
    val trustedProxySecret: String?,
    val allowedBearerGroups: Set<String>,
    val agentTokenSecret: String,
    val agentTokenTtlSeconds: Long,
    val workspaceClientId: String,
    val workspaceCliRedirectUri: String,
    val runtimePublicHost: String,
    val runtimeHttpBindAddress: String,
    val runtimeSshPortStart: Int,
    val runtimeSshPortEnd: Int,
    val runtimeNotebookPortStart: Int,
    val runtimeNotebookPortEnd: Int,
    val runtimeTtydPortStart: Int,
    val runtimeTtydPortEnd: Int,
    val workspaceImage: String,
    val workspaceContext: Path,
    val notebookImage: String,
    val notebookContext: Path,
    val workspaceUser: String,
    val workspaceSshUser: String,
    val workspaceSshPortInternal: Int,
    val workspaceNotebookPortInternal: Int,
    val workspaceTtydPortInternal: Int,
    val workspaceCertTtl: String,
    val workspaceLeaseDays: Int,
    val stepPath: Path,
    val caConfigPath: Path,
    val caProvisioner: String,
    val caProvisionerPasswordFile: Path,
    val caUserPublicKeyPath: Path
)

fun loadConfig(): WorkspaceProvisionerConfig = WorkspaceProvisionerConfig(
    port = envInt("WORKSPACE_PROVISIONER_PORT", 8120),
    dataDir = Path(env("WORKSPACE_PROVISIONER_DATA_DIR", "/data")),
    databasePath = Path(env("WORKSPACE_PROVISIONER_DB_PATH", "/data/db/workspaces.sqlite")),
    oidcBaseUrl = env("WORKSPACE_PROVISIONER_OIDC_BASE_URL", "http://keycloak:8080/realms/webservices").trimEnd('/'),
    oidcPublicUrl = env("WORKSPACE_PROVISIONER_OIDC_PUBLIC_URL", "https://keycloak.example.test/realms/webservices").trimEnd('/'),
    publicBaseUrl = env("WORKSPACE_PROVISIONER_PUBLIC_BASE_URL", "https://workspaces.example.test").trimEnd('/'),
    searchServiceBaseUrl = validateSearchServiceBaseUrl(
        env(
            "WORKSPACE_PROVISIONER_SEARCH_SERVICE_BASE_URL",
            "${env("WORKSPACE_PROVISIONER_OPENSEARCH_URL", "http://opensearch:9200").trimEnd('/')}/${env("WORKSPACE_PROVISIONER_OPENSEARCH_INDEX", "knowledge")}"
        )
    ),
    searchServiceUsername = System.getenv("WORKSPACE_PROVISIONER_OPENSEARCH_USERNAME")?.takeIf { it.isNotBlank() },
    searchServicePassword = System.getenv("WORKSPACE_PROVISIONER_OPENSEARCH_PASSWORD")?.takeIf { it.isNotBlank() },
    searchServiceToken = System.getenv("WORKSPACE_PROVISIONER_SEARCH_SERVICE_TOKEN")?.takeIf { it.isNotBlank() },
    trustedProxySecret = System.getenv("WORKSPACE_PROVISIONER_TRUSTED_PROXY_SECRET")?.takeIf { it.isNotBlank() },
    allowedBearerGroups = envList("WORKSPACE_PROVISIONER_ALLOWED_BEARER_GROUPS", "admins,operators,agents").toSet(),
    agentTokenSecret = envRequired("WORKSPACE_PROVISIONER_AGENT_TOKEN_SECRET"),
    agentTokenTtlSeconds = envLong("WORKSPACE_PROVISIONER_AGENT_TOKEN_TTL_SECONDS", 86_400L),
    workspaceClientId = env("WORKSPACE_PROVISIONER_WORKSPACE_CLIENT_ID", "workspace-cli"),
    workspaceCliRedirectUri = env("WORKSPACE_PROVISIONER_WORKSPACE_CLI_REDIRECT_URI", "http://127.0.0.1:38080/callback"),
    runtimePublicHost = env("WORKSPACE_PROVISIONER_RUNTIME_PUBLIC_HOST", "labware.local"),
    runtimeHttpBindAddress = env("WORKSPACE_PROVISIONER_RUNTIME_HTTP_BIND_ADDRESS", "127.0.0.1"),
    runtimeSshPortStart = envInt("WORKSPACE_PROVISIONER_RUNTIME_SSH_PORT_START", 47000),
    runtimeSshPortEnd = envInt("WORKSPACE_PROVISIONER_RUNTIME_SSH_PORT_END", 47999),
    runtimeNotebookPortStart = envInt("WORKSPACE_PROVISIONER_RUNTIME_NOTEBOOK_PORT_START", 48000),
    runtimeNotebookPortEnd = envInt("WORKSPACE_PROVISIONER_RUNTIME_NOTEBOOK_PORT_END", 48999),
    runtimeTtydPortStart = envInt("WORKSPACE_PROVISIONER_RUNTIME_TTYD_PORT_START", 49000),
    runtimeTtydPortEnd = envInt("WORKSPACE_PROVISIONER_RUNTIME_TTYD_PORT_END", 49999),
    workspaceImage = env("WORKSPACE_PROVISIONER_WORKSPACE_IMAGE", "webservices/agent-workspace:workspace-build"),
    workspaceContext = Path(env("WORKSPACE_PROVISIONER_WORKSPACE_CONTEXT", "/workspace/stack.containers/agent-workspace")),
    notebookImage = env("WORKSPACE_PROVISIONER_NOTEBOOK_IMAGE", "webservices/agent-workspace-notebook:workspace-build"),
    notebookContext = Path(env("WORKSPACE_PROVISIONER_NOTEBOOK_CONTEXT", "/workspace/stack.containers/agent-workspace-notebook")),
    workspaceUser = env("WORKSPACE_PROVISIONER_WORKSPACE_USER", "agent"),
    workspaceSshUser = env("WORKSPACE_PROVISIONER_WORKSPACE_SSH_USER", "agent"),
    workspaceSshPortInternal = envInt("WORKSPACE_PROVISIONER_WORKSPACE_SSH_PORT_INTERNAL", 2222),
    workspaceNotebookPortInternal = envInt("WORKSPACE_PROVISIONER_WORKSPACE_NOTEBOOK_PORT_INTERNAL", 8888),
    workspaceTtydPortInternal = envInt("WORKSPACE_PROVISIONER_WORKSPACE_TTYD_PORT_INTERNAL", 7681),
    workspaceCertTtl = env("WORKSPACE_PROVISIONER_WORKSPACE_CERT_TTL", "12h"),
    workspaceLeaseDays = envInt("WORKSPACE_PROVISIONER_WORKSPACE_LEASE_DAYS", 14),
    stepPath = Path(env("WORKSPACE_PROVISIONER_STEPPATH", "/data/step")),
    caConfigPath = Path(env("WORKSPACE_PROVISIONER_CA_CONFIG", "/data/step/config/ca.json")),
    caProvisioner = env("WORKSPACE_PROVISIONER_CA_PROVISIONER", "workspace-provisioner"),
    caProvisionerPasswordFile = Path(env("WORKSPACE_PROVISIONER_CA_PROVISIONER_PASSWORD_FILE", "/data/step/secrets/provisioner-password.txt")),
    caUserPublicKeyPath = Path(env("WORKSPACE_PROVISIONER_CA_USER_PUBLIC_KEY_PATH", "/data/step/certs/ssh_user_ca_key.pub"))
)

private fun env(name: String, defaultValue: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: defaultValue
private fun envList(name: String, defaultValue: String): List<String> =
    env(name, defaultValue)
        .split(',', ';', ' ')
        .map { it.trim() }
        .filter { it.isNotBlank() }
private fun envRequired(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name must be set")

private fun envInt(name: String, defaultValue: Int): Int = System.getenv(name)?.toIntOrNull() ?: defaultValue

private fun envLong(name: String, defaultValue: Long): Long = System.getenv(name)?.toLongOrNull() ?: defaultValue

internal fun validateSearchServiceBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val uri = runCatching { URI(trimmed) }.getOrElse {
        throw IllegalArgumentException("WORKSPACE_PROVISIONER_SEARCH_SERVICE_BASE_URL must be a valid URI")
    }
    require(uri.scheme == "http" || uri.scheme == "https") {
        "WORKSPACE_PROVISIONER_SEARCH_SERVICE_BASE_URL must use http or https"
    }
    require(!uri.host.isNullOrBlank()) {
        "WORKSPACE_PROVISIONER_SEARCH_SERVICE_BASE_URL must include a host"
    }
    require(uri.userInfo.isNullOrBlank()) {
        "WORKSPACE_PROVISIONER_SEARCH_SERVICE_BASE_URL must not include credentials"
    }
    require(uri.query.isNullOrBlank() && uri.fragment.isNullOrBlank()) {
        "WORKSPACE_PROVISIONER_SEARCH_SERVICE_BASE_URL must not include query or fragment"
    }
    return trimmed
}
