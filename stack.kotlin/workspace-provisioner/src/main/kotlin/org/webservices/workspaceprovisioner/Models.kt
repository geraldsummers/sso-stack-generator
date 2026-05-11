package org.webservices.workspaceprovisioner

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class PrincipalIdentity(
    val username: String,
    val email: String? = null,
    val groups: List<String> = emptyList(),
    val source: String,
    val subjectKind: String = "user",
    val workspaceId: String? = null,
    val scopes: List<String> = emptyList()
)

@Serializable
data class WorkspaceAgentAccessView(
    val controllerUrl: String,
    val workspaceId: String,
    val searchPath: String,
    val documentPathPrefix: String,
    val tokenExpiresAt: String,
    val scopes: List<String>
)

@Serializable
data class NotebookSessionView(
    val status: String,
    val url: String,
    val basePath: String,
    val port: Int? = null,
    val lastError: String? = null
)

@Serializable
data class TtydSessionView(
    val status: String,
    val url: String,
    val basePath: String,
    val port: Int? = null,
    val lastError: String? = null
)

@Serializable
data class WorkspaceProfileView(
    val name: String,
    val tier: String,
    val summary: String,
    val status: String,
    val source: String,
    val lastAppliedAt: String? = null
)

@Serializable
data class WorkspaceSummary(
    val id: String,
    val displayName: String,
    val ownerUsername: String,
    val status: String,
    val sshHost: String,
    val sshPort: Int,
    val sshUser: String,
    val leaseExpiresAt: String,
    val createdAt: String,
    val updatedAt: String,
    val delegates: List<String>,
    val notebook: NotebookSessionView,
    val shell: TtydSessionView,
    val agentAccess: WorkspaceAgentAccessView,
    val profiles: List<WorkspaceProfileView>,
    val lastError: String? = null
)

@Serializable
data class SshKeyView(
    val id: String,
    val name: String,
    val fingerprint: String,
    val createdAt: String
)

@Serializable
data class AuditEventView(
    val id: Long,
    val username: String,
    val action: String,
    val workspaceId: String? = null,
    val createdAt: String,
    val detailsJson: String
)

@Serializable
data class CreateWorkspaceRequest(
    val displayName: String,
    val initialDelegate: String? = null
)

@Serializable
data class RenewLeaseRequest(
    val days: Int = 14
)

@Serializable
data class AddSshKeyRequest(
    val name: String,
    val publicKey: String
)

@Serializable
data class AddDelegationRequest(
    val principalUsername: String
)

@Serializable
data class SetCodexTokenRequest(
    val token: String
)

@Serializable
data class WorkspaceSecretStatusResponse(
    val workspaceId: String,
    val configured: Boolean
)

@Serializable
data class IssueCertificateRequest(
    val keyName: String
)

@Serializable
data class IssueCertificateResponse(
    val workspaceId: String,
    val displayName: String,
    val sshHost: String,
    val sshPort: Int,
    val sshUser: String,
    val principal: String,
    val certificate: String,
    val knownHostsEntry: String,
    val caPublicKey: String,
    val hostPublicKey: String,
    val expiresAt: String
)

@Serializable
data class OidcDiscoveryResponse(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("redirect_uri") val redirectUri: String
)

@Serializable
data class HealthResponse(
    val status: String
)

@Serializable
data class ReadyResponse(
    val status: String,
    val workspaces: Int
)

@Serializable
data class DeleteWorkspaceResponse(
    val deleted: Boolean,
    val workspaceId: String
)

data class WorkspaceRecord(
    val id: String,
    val displayName: String,
    val ownerUsername: String,
    val status: String,
    val containerName: String,
    val volumeName: String,
    val sshPort: Int,
    val sshUser: String,
    val notebookContainerName: String?,
    val notebookPort: Int?,
    val notebookStatus: String,
    val ttydPort: Int?,
    val ttydStatus: String,
    val createdAt: String,
    val updatedAt: String,
    val leaseExpiresAt: String,
    val notebookLastError: String? = null,
    val ttydLastError: String? = null,
    val lastError: String? = null
)

data class SshKeyRecord(
    val id: String,
    val username: String,
    val name: String,
    val publicKey: String,
    val fingerprint: String,
    val createdAt: String
)

data class AuditEventRecord(
    val id: Long,
    val username: String,
    val action: String,
    val workspaceId: String?,
    val detailsJson: String,
    val createdAt: String
)

data class WorkspaceProfileRecord(
    val name: String,
    val tier: String,
    val summary: String,
    val status: String,
    val source: String,
    val lastAppliedAt: String?
)
