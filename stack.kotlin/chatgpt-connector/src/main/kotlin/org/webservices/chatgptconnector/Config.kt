package org.webservices.chatgptconnector

import java.nio.file.Path
import kotlin.io.path.Path

data class ConnectorConfig(
    val port: Int,
    val dataDir: Path,
    val databasePath: Path,
    val oidcBaseUrl: String,
    val trustedProxySecret: String?,
    val searchServiceBaseUrl: String,
    val keycloakRealm: String,
    val keycloakAdminClientId: String,
    val keycloakAdminClientSecret: String,
    val keycloakAdminUsername: String,
    val keycloakAdminPassword: String,
    val keycloakBaseUrl: String,
    val keycloakAgentUserPrefix: String
)

fun loadConfig(): ConnectorConfig = ConnectorConfig(
    port = envInt("CHATGPT_CONNECTOR_PORT", 8130),
    dataDir = Path(env("CHATGPT_CONNECTOR_DATA_DIR", "/data")),
    databasePath = Path(env("CHATGPT_CONNECTOR_DB_PATH", "/data/db/chatgpt_connector.sqlite")),
    oidcBaseUrl = env("CHATGPT_CONNECTOR_OIDC_BASE_URL", "http://keycloak:8080/realms/webservices").trimEnd('/'),
    trustedProxySecret = System.getenv("CHATGPT_CONNECTOR_TRUSTED_PROXY_SECRET")?.takeIf { it.isNotBlank() },
    searchServiceBaseUrl = env("CHATGPT_CONNECTOR_SEARCH_SERVICE_BASE_URL", "http://search-service:8098").trimEnd('/'),
    keycloakRealm = env("CHATGPT_CONNECTOR_KEYCLOAK_REALM", "webservices"),
    keycloakAdminClientId = env("CHATGPT_CONNECTOR_KEYCLOAK_ADMIN_CLIENT_ID", "admin-cli"),
    keycloakAdminClientSecret = env("CHATGPT_CONNECTOR_KEYCLOAK_ADMIN_CLIENT_SECRET", ""),
    keycloakAdminUsername = env("CHATGPT_CONNECTOR_KEYCLOAK_ADMIN_USERNAME", ""),
    keycloakAdminPassword = env("CHATGPT_CONNECTOR_KEYCLOAK_ADMIN_PASSWORD", ""),
    keycloakBaseUrl = env("CHATGPT_CONNECTOR_KEYCLOAK_BASE_URL", "http://keycloak:8080").trimEnd('/'),
    keycloakAgentUserPrefix = env("CHATGPT_CONNECTOR_KEYCLOAK_AGENT_USER_PREFIX", "gpt_agent")
)

private fun env(name: String, defaultValue: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: defaultValue
private fun envInt(name: String, defaultValue: Int): Int = System.getenv(name)?.toIntOrNull() ?: defaultValue
