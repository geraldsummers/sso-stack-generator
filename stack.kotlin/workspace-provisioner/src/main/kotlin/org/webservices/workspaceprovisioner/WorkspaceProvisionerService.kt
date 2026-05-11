package org.webservices.workspaceprovisioner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class WorkspaceProvisionerService(
    private val config: WorkspaceProvisionerConfig,
    private val store: WorkspaceStore,
    private val runtime: DockerWorkspaceRuntime,
    private val sshCa: SmallstepSshCa,
    private val knowledgeGateway: WorkspaceKnowledgeGateway
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createLock = Any()
    private val workspaceTokenCodec = WorkspaceAgentTokenCodec(
        sharedSecret = config.agentTokenSecret,
        ttlSeconds = config.agentTokenTtlSeconds
    )

    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { expireLeases() }
                runCatching { reconcileStatuses() }
                delay(30_000)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    fun currentStatus(): ReadyResponse = ReadyResponse(
        status = "ok",
        workspaces = store.listAllWorkspaces().size
    )

    fun listWorkspaces(principal: PrincipalIdentity): List<WorkspaceSummary> =
        store.listWorkspacesForPrincipal(principal.username).map { record ->
            toSummary(record)
        }

    fun createWorkspace(principal: PrincipalIdentity, request: CreateWorkspaceRequest): WorkspaceSummary {
        require(request.displayName.isNotBlank()) { "displayName is required" }
        val displayName = request.displayName.trim()
        val attemptedSshPorts = mutableSetOf<Int>()
        val attemptedNotebookPorts = mutableSetOf<Int>()
        val attemptedTtydPorts = mutableSetOf<Int>()
        val maxAttempts = minOf(
            config.runtimeSshPortEnd - config.runtimeSshPortStart + 1,
            config.runtimeNotebookPortEnd - config.runtimeNotebookPortStart + 1,
            config.runtimeTtydPortEnd - config.runtimeTtydPortStart + 1
        )

        repeat(maxAttempts) {
            val record = reserveWorkspaceRecord(principal.username, displayName, attemptedSshPorts, attemptedNotebookPorts, attemptedTtydPorts)
            try {
                request.initialDelegate?.takeIf { it.isNotBlank() }?.let {
                    store.addDelegation(record.id, it.trim(), principal.username)
                }
                val principals = workspacePrincipals(record)
                runtime.createWorkspace(record, principals)
                store.updateWorkspaceStatus(record.id, "running")
                store.updateTtydStatus(record.id, "running")
                store.appendAudit(
                    principal.username,
                    "workspace_created",
                    record.id,
                    mapOf(
                        "displayName" to record.displayName,
                        "sshPort" to record.sshPort,
                        "notebookPort" to record.notebookPort
                    )
                )
                return toSummary(store.getWorkspace(record.id)!!)
            } catch (e: HostPortUnavailableException) {
                attemptedSshPorts.add(record.sshPort)
                record.notebookPort?.let(attemptedNotebookPorts::add)
                record.ttydPort?.let(attemptedTtydPorts::add)
                runCatching { runtime.deleteWorkspace(record) }
                store.deleteWorkspace(record.id)
                store.appendAudit(
                    principal.username,
                    "workspace_create_retried",
                    record.id,
                    mapOf(
                        "displayName" to record.displayName,
                        "sshPort" to record.sshPort,
                        "notebookPort" to record.notebookPort,
                        "error" to e.message
                    )
                )
            } catch (e: Exception) {
                store.updateWorkspaceStatus(record.id, "error", e.message)
                store.appendAudit(principal.username, "workspace_create_failed", record.id, mapOf("error" to (e.message ?: "unknown")))
                throw e
            }
        }
        error("no free workspace runtime ports in configured range")
    }

    fun deleteWorkspace(principal: PrincipalIdentity, workspaceId: String): DeleteWorkspaceResponse {
        require(store.isOwner(workspaceId, principal.username)) { "only owners can delete workspaces" }
        val record = store.getWorkspace(workspaceId) ?: error("workspace not found")
        runtime.deleteWorkspace(record)
        store.deleteWorkspace(workspaceId)
        store.appendAudit(principal.username, "workspace_deleted", workspaceId)
        return DeleteWorkspaceResponse(deleted = true, workspaceId = workspaceId)
    }

    fun renewLease(principal: PrincipalIdentity, workspaceId: String, days: Int): WorkspaceSummary {
        require(store.isOwner(workspaceId, principal.username)) { "only owners can renew leases" }
        require(days in 1..30) { "renewal must be between 1 and 30 days" }
        val record = store.renewLease(workspaceId, days)
        store.appendAudit(principal.username, "workspace_lease_renewed", workspaceId, mapOf("days" to days))
        return toSummary(record)
    }

    fun getWorkspace(principal: PrincipalIdentity, workspaceId: String): WorkspaceSummary {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val record = store.getWorkspace(workspaceId) ?: error("workspace not found")
        return toSummary(record)
    }

    fun startWorkspace(principal: PrincipalIdentity, workspaceId: String): WorkspaceSummary {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val record = ensureTtydReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        runtime.startWorkspace(record, workspacePrincipals(record))
        store.updateWorkspaceStatus(workspaceId, "running")
        store.updateTtydStatus(workspaceId, "running")
        store.appendAudit(principal.username, "workspace_started", workspaceId)
        return toSummary(store.getWorkspace(workspaceId)!!)
    }

    fun stopWorkspace(principal: PrincipalIdentity, workspaceId: String): WorkspaceSummary {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val record = store.getWorkspace(workspaceId) ?: error("workspace not found")
        runtime.stopWorkspace(record)
        store.updateWorkspaceStatus(workspaceId, "stopped")
        store.updateTtydStatus(workspaceId, "stopped")
        store.appendAudit(principal.username, "workspace_stopped", workspaceId)
        return toSummary(store.getWorkspace(workspaceId)!!)
    }

    fun startNotebook(principal: PrincipalIdentity, workspaceId: String): WorkspaceSummary {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val attemptedPorts = mutableSetOf<Int>()
        var record = ensureNotebookReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        val maxAttempts = config.runtimeNotebookPortEnd - config.runtimeNotebookPortStart + 1

        repeat(maxAttempts) {
            try {
                runtime.startNotebook(record)
                store.updateNotebookStatus(workspaceId, "running")
                store.appendAudit(principal.username, "workspace_notebook_started", workspaceId, mapOf("notebookPort" to record.notebookPort))
                return toSummary(store.getWorkspace(workspaceId)!!)
            } catch (e: HostPortUnavailableException) {
                record.notebookPort?.let(attemptedPorts::add)
                record = reassignNotebookReservation(record, attemptedPorts)
            } catch (e: Exception) {
                store.updateNotebookStatus(workspaceId, "error", e.message)
                store.appendAudit(principal.username, "workspace_notebook_start_failed", workspaceId, mapOf("error" to (e.message ?: "unknown")))
                throw e
            }
        }
        error("no free workspace notebook ports in configured range")
    }

    fun stopNotebook(principal: PrincipalIdentity, workspaceId: String): WorkspaceSummary {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val record = ensureNotebookReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        runtime.stopNotebook(record)
        store.updateNotebookStatus(workspaceId, "stopped")
        store.appendAudit(principal.username, "workspace_notebook_stopped", workspaceId)
        return toSummary(store.getWorkspace(workspaceId)!!)
    }

    fun notebookAccess(principal: PrincipalIdentity, workspaceId: String): NotebookSessionView {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val record = ensureNotebookReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        require(record.notebookStatus == "running") { "notebook session is not running" }
        return notebookView(record)
    }

    fun ttydAccess(principal: PrincipalIdentity, workspaceId: String): TtydSessionView {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        var record = ensureTtydReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        if (record.status != "running") {
            runtime.startWorkspace(record, workspacePrincipals(record))
            store.updateWorkspaceStatus(workspaceId, "running")
            store.updateTtydStatus(workspaceId, "running")
            record = ensureTtydReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        }
        runtime.startTtyd(record)
        store.updateTtydStatus(workspaceId, "running")
        record = store.getWorkspace(workspaceId) ?: error("workspace not found")
        store.appendAudit(principal.username, "workspace_ttyd_accessed", workspaceId, mapOf("ttydPort" to record.ttydPort))
        return ttydView(record)
    }

    fun agentAccess(principal: PrincipalIdentity, workspaceId: String): WorkspaceAgentAccessView {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val record = store.getWorkspace(workspaceId) ?: error("workspace not found")
        return agentAccessView(record)
    }

    suspend fun knowledgeSearch(
        principal: PrincipalIdentity,
        workspaceId: String,
        request: WorkspaceKnowledgeSearchRequest
    ): JsonProxyResponse {
        requireKnowledgeAccess(principal, workspaceId, "knowledge:search")
        return knowledgeGateway.search(request)
    }

    suspend fun knowledgeDocument(
        principal: PrincipalIdentity,
        workspaceId: String,
        documentId: String,
        collection: String?
    ): JsonProxyResponse {
        requireKnowledgeAccess(principal, workspaceId, "knowledge:document")
        return knowledgeGateway.document(documentId, collection)
    }

    fun addDelegation(principal: PrincipalIdentity, workspaceId: String, delegatedUsername: String): WorkspaceSummary {
        require(store.isOwner(workspaceId, principal.username)) { "only owners can delegate" }
        store.addDelegation(workspaceId, delegatedUsername, principal.username)
        val record = store.getWorkspace(workspaceId) ?: error("workspace not found")
        if (record.status == "running") {
            runtime.updateAuthorizedPrincipals(record, workspacePrincipals(record))
        }
        store.appendAudit(principal.username, "workspace_delegated", workspaceId, mapOf("delegate" to delegatedUsername))
        return toSummary(record)
    }

    fun setCodexToken(principal: PrincipalIdentity, workspaceId: String, token: String): WorkspaceSecretStatusResponse {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val trimmed = token.trim()
        require(trimmed.length >= 20) { "Codex access token is too short" }
        require(!trimmed.contains('\n') && !trimmed.contains('\r')) { "Codex access token must be a single line" }
        var record = ensureTtydReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        if (record.status != "running") {
            runtime.startWorkspace(record, workspacePrincipals(record))
            store.updateWorkspaceStatus(workspaceId, "running")
            store.updateTtydStatus(workspaceId, "running")
            record = ensureTtydReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        }
        runtime.writeCodexToken(record, trimmed)
        store.appendAudit(principal.username, "workspace_codex_token_set", workspaceId)
        return WorkspaceSecretStatusResponse(workspaceId = workspaceId, configured = true)
    }

    fun clearCodexToken(principal: PrincipalIdentity, workspaceId: String): WorkspaceSecretStatusResponse {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        var record = ensureTtydReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        if (record.status != "running") {
            runtime.startWorkspace(record, workspacePrincipals(record))
            store.updateWorkspaceStatus(workspaceId, "running")
            store.updateTtydStatus(workspaceId, "running")
            record = ensureTtydReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        }
        runtime.clearCodexToken(record)
        store.appendAudit(principal.username, "workspace_codex_token_cleared", workspaceId)
        return WorkspaceSecretStatusResponse(workspaceId = workspaceId, configured = false)
    }

    fun addSshKey(principal: PrincipalIdentity, request: AddSshKeyRequest): SshKeyView {
        val fingerprint = sshCa.fingerprint(request.publicKey)
        val record = store.upsertSshKey(principal.username, request.name.trim(), request.publicKey.trim(), fingerprint)
        store.appendAudit(principal.username, "ssh_key_upserted", details = mapOf("keyName" to record.name, "fingerprint" to fingerprint))
        return record.toView()
    }

    fun listSshKeys(principal: PrincipalIdentity): List<SshKeyView> = store.listSshKeys(principal.username).map { it.toView() }

    fun issueSshCertificate(principal: PrincipalIdentity, workspaceId: String, keyName: String): IssueCertificateResponse {
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
        val record = ensureTtydReservation(store.getWorkspace(workspaceId) ?: error("workspace not found"))
        if (record.status != "running") {
            runtime.startWorkspace(record, workspacePrincipals(record))
            store.updateWorkspaceStatus(workspaceId, "running")
            store.updateTtydStatus(workspaceId, "running")
        }
        val key = store.getSshKey(principal.username, keyName) ?: error("ssh key not found: $keyName")
        val principalName = certificatePrincipal(workspaceId, principal.username)
        val cert = sshCa.issueUserCertificate(key.publicKey, principalName)
        val hostPublicKey = runtime.fetchHostPublicKey(record)
        store.appendAudit(principal.username, "ssh_certificate_issued", workspaceId, mapOf("keyName" to keyName, "principal" to principalName))
        return IssueCertificateResponse(
            workspaceId = workspaceId,
            displayName = record.displayName,
            sshHost = config.runtimePublicHost,
            sshPort = record.sshPort,
            sshUser = record.sshUser,
            principal = principalName,
            certificate = cert.certificate,
            knownHostsEntry = "[${config.runtimePublicHost}]:${record.sshPort} $hostPublicKey",
            caPublicKey = sshCa.userCaPublicKey(),
            hostPublicKey = hostPublicKey,
            expiresAt = cert.expiresAt
        )
    }

    fun me(principal: PrincipalIdentity): PrincipalIdentity = principal

    fun oidcDiscovery(): OidcDiscoveryResponse = OidcDiscoveryResponse(
        issuer = config.oidcPublicUrl,
        authorizationEndpoint = "${config.oidcPublicUrl}/protocol/openid-connect/auth",
        tokenEndpoint = "${config.oidcPublicUrl}/protocol/openid-connect/token",
        clientId = config.workspaceClientId,
        redirectUri = config.workspaceCliRedirectUri
    )

    fun auditEvents(): List<AuditEventView> = store.listAuditEvents().map {
        AuditEventView(it.id, it.username, it.action, it.workspaceId, it.createdAt, it.detailsJson)
    }

    private fun reserveWorkspaceRecord(
        ownerUsername: String,
        displayName: String,
        attemptedSshPorts: Set<Int>,
        attemptedNotebookPorts: Set<Int>,
        attemptedTtydPorts: Set<Int>
    ): WorkspaceRecord = synchronized(createLock) {
        val sshPort = allocateSshPort(attemptedSshPorts)
        val notebookPort = allocateNotebookPort(attemptedNotebookPorts)
        val ttydPort = allocateTtydPort(attemptedTtydPorts)
        try {
            store.createWorkspace(
                ownerUsername = ownerUsername,
                displayName = displayName,
                sshPort = sshPort,
                sshUser = config.workspaceSshUser,
                leaseDays = config.workspaceLeaseDays,
                notebookContainerName = notebookContainerName(),
                notebookPort = notebookPort,
                notebookStatus = "stopped",
                ttydPort = ttydPort,
                ttydStatus = "stopped"
            )
        } catch (e: Exception) {
            if (isDuplicatePortReservation(e)) {
                reserveWorkspaceRecord(
                    ownerUsername,
                    displayName,
                    attemptedSshPorts + sshPort,
                    attemptedNotebookPorts + notebookPort,
                    attemptedTtydPorts + ttydPort
                )
            } else {
                throw e
            }
        }
    }

    private fun ensureNotebookReservation(record: WorkspaceRecord): WorkspaceRecord {
        if (record.notebookContainerName != null && record.notebookPort != null) return record
        return synchronized(createLock) {
            val refreshed = store.getWorkspace(record.id) ?: error("workspace not found")
            if (refreshed.notebookContainerName != null && refreshed.notebookPort != null) {
                return@synchronized refreshed
            }
            val port = allocateNotebookPort(emptySet())
            val containerName = refreshed.notebookContainerName ?: notebookContainerName()
            try {
                store.updateNotebookReservation(refreshed.id, containerName, port)
            } catch (e: Exception) {
                if (isDuplicateNotebookPortReservation(e)) {
                    return@synchronized ensureNotebookReservation(refreshed)
                }
                throw e
            }
            store.getWorkspace(refreshed.id) ?: error("workspace not found")
        }
    }

    private fun ensureTtydReservation(record: WorkspaceRecord): WorkspaceRecord {
        if (record.ttydPort != null) return record
        return synchronized(createLock) {
            val refreshed = store.getWorkspace(record.id) ?: error("workspace not found")
            if (refreshed.ttydPort != null) {
                return@synchronized refreshed
            }
            val port = allocateTtydPort(emptySet())
            try {
                store.updateTtydReservation(refreshed.id, port)
            } catch (e: Exception) {
                if (isDuplicateTtydPortReservation(e)) {
                    return@synchronized ensureTtydReservation(refreshed)
                }
                throw e
            }
            store.getWorkspace(refreshed.id) ?: error("workspace not found")
        }
    }

    private fun reassignNotebookReservation(record: WorkspaceRecord, attemptedPorts: Set<Int>): WorkspaceRecord = synchronized(createLock) {
        val nextPort = allocateNotebookPort(attemptedPorts)
        val containerName = record.notebookContainerName ?: notebookContainerName()
        store.updateNotebookReservation(record.id, containerName, nextPort)
        store.getWorkspace(record.id) ?: error("workspace not found")
    }

    private fun allocateSshPort(excludedPorts: Set<Int>): Int {
        val allocated = store.listAllocatedPorts()
        return (config.runtimeSshPortStart..config.runtimeSshPortEnd).firstOrNull { port ->
            port !in excludedPorts &&
                port !in allocated &&
                runtime.isHostPortAvailable(port)
        } ?: error("no free workspace SSH ports in configured range")
    }

    private fun allocateNotebookPort(excludedPorts: Set<Int>): Int {
        val allocated = store.listAllocatedNotebookPorts()
        return (config.runtimeNotebookPortStart..config.runtimeNotebookPortEnd).firstOrNull { port ->
            port !in excludedPorts &&
                port !in allocated &&
                runtime.isHostPortAvailable(port)
        } ?: error("no free workspace notebook ports in configured range")
    }

    private fun allocateTtydPort(excludedPorts: Set<Int>): Int {
        val allocated = store.listAllocatedTtydPorts()
        return (config.runtimeTtydPortStart..config.runtimeTtydPortEnd).firstOrNull { port ->
            port !in excludedPorts &&
                port !in allocated &&
                runtime.isHostPortAvailable(port)
        } ?: error("no free workspace ttyd ports in configured range")
    }

    private fun isDuplicatePortReservation(error: Exception): Boolean {
        val message = error.message ?: return false
        return message.contains("UNIQUE constraint failed: workspaces.ssh_port") ||
            message.contains("UNIQUE constraint failed: workspaces.notebook_port") ||
            message.contains("UNIQUE constraint failed: workspaces.ttyd_port")
    }

    private fun isDuplicateNotebookPortReservation(error: Exception): Boolean {
        val message = error.message ?: return false
        return message.contains("UNIQUE constraint failed: workspaces.notebook_port")
    }

    private fun isDuplicateTtydPortReservation(error: Exception): Boolean {
        val message = error.message ?: return false
        return message.contains("UNIQUE constraint failed: workspaces.ttyd_port")
    }

    private fun workspacePrincipals(record: WorkspaceRecord): List<String> =
        buildList {
            add(certificatePrincipal(record.id, record.ownerUsername))
            store.listDelegates(record.id).forEach { add(certificatePrincipal(record.id, it)) }
        }.distinct().sorted()

    private fun requireKnowledgeAccess(principal: PrincipalIdentity, workspaceId: String, scope: String) {
        if (principal.subjectKind == "workspace_agent") {
            require(principal.workspaceId == workspaceId) { "workspace token does not match requested workspace" }
            require(scope in principal.scopes) { "workspace token does not grant $scope" }
            return
        }
        require(store.hasAccess(workspaceId, principal.username)) { "workspace not accessible" }
    }

    private fun toSummary(record: WorkspaceRecord): WorkspaceSummary {
        val ensured = ensureTtydReservation(ensureNotebookReservation(record))
        return WorkspaceSummary(
            id = ensured.id,
            displayName = ensured.displayName,
            ownerUsername = ensured.ownerUsername,
            status = ensured.status,
            sshHost = config.runtimePublicHost,
            sshPort = ensured.sshPort,
            sshUser = ensured.sshUser,
            leaseExpiresAt = ensured.leaseExpiresAt,
            createdAt = ensured.createdAt,
            updatedAt = ensured.updatedAt,
            delegates = store.listDelegates(ensured.id),
            notebook = notebookView(ensured),
            shell = ttydView(ensured),
            agentAccess = agentAccessView(ensured),
            profiles = runCatching { runtime.readProfiles(ensured) }.getOrDefault(emptyList()).map { profile ->
            WorkspaceProfileView(
                name = profile.name,
                tier = profile.tier,
                summary = profile.summary,
                status = profile.status,
                source = profile.source,
                lastAppliedAt = profile.lastAppliedAt
            )
            },
            lastError = ensured.lastError
        )
    }

    private fun agentAccessView(record: WorkspaceRecord): WorkspaceAgentAccessView {
        val token = workspaceTokenCodec.issue(record)
        return WorkspaceAgentAccessView(
            controllerUrl = config.publicBaseUrl,
            workspaceId = record.id,
            searchPath = "/api/workspaces/${record.id}/knowledge/search",
            documentPathPrefix = "/api/workspaces/${record.id}/knowledge/documents/",
            tokenExpiresAt = token.expiresAt.toString(),
            scopes = token.scopes
        )
    }

    private fun notebookView(record: WorkspaceRecord): NotebookSessionView = NotebookSessionView(
        status = record.notebookStatus,
        url = runtime.notebookUrl(record.id),
        basePath = runtime.notebookBasePath(record.id),
        port = record.notebookPort,
        lastError = record.notebookLastError
    )

    private fun ttydView(record: WorkspaceRecord): TtydSessionView = TtydSessionView(
        status = record.ttydStatus,
        url = runtime.ttydUrl(record.id),
        basePath = runtime.ttydBasePath(record.id),
        port = record.ttydPort,
        lastError = record.ttydLastError
    )

    private fun notebookContainerName(): String = "workspace-notebook-${UUID.randomUUID().toString().substring(0, 12)}"

    private fun expireLeases() {
        store.listExpiredWorkspaceIds().forEach { workspaceId ->
            val record = store.getWorkspace(workspaceId) ?: return@forEach
            runCatching { runtime.deleteWorkspace(record) }
            store.appendAudit(record.ownerUsername, "workspace_expired", workspaceId, mapOf("expiredAt" to Instant.now().toString()))
            store.deleteWorkspace(workspaceId)
        }
    }

    private fun reconcileStatuses() {
        store.listAllWorkspaces().forEach { record ->
            val ensuredRecord = ensureTtydReservation(ensureNotebookReservation(record))
            val status = runtime.inspectStatus(ensuredRecord) ?: "missing"
            when (status) {
                "running" -> if (ensuredRecord.status != "running") store.updateWorkspaceStatus(ensuredRecord.id, "running")
                "created", "exited" -> if (ensuredRecord.status != "stopped") store.updateWorkspaceStatus(ensuredRecord.id, "stopped")
                "missing" -> if (ensuredRecord.status != "missing") store.updateWorkspaceStatus(ensuredRecord.id, "missing", "container missing on runtime")
                else -> if (ensuredRecord.status != status) store.updateWorkspaceStatus(ensuredRecord.id, status)
            }

            val ttydStatus = runtime.inspectTtydStatus(ensuredRecord)
            when (ttydStatus) {
                "running" -> if (ensuredRecord.ttydStatus != "running") store.updateTtydStatus(ensuredRecord.id, "running")
                null -> {
                    if (status == "running") {
                        runCatching {
                            runtime.startTtyd(ensuredRecord)
                            store.updateTtydStatus(ensuredRecord.id, "running")
                        }.onFailure { error ->
                            store.updateTtydStatus(ensuredRecord.id, "error", error.message)
                        }
                    } else if (ensuredRecord.ttydStatus != "stopped") {
                        store.updateTtydStatus(ensuredRecord.id, "stopped")
                    }
                }
            }

            if (ensuredRecord.notebookContainerName != null) {
                val notebookStatus = runtime.inspectNotebookStatus(ensuredRecord) ?: "missing"
                when (notebookStatus) {
                    "running" -> if (ensuredRecord.notebookStatus != "running") store.updateNotebookStatus(ensuredRecord.id, "running")
                    "created", "exited" -> if (ensuredRecord.notebookStatus != "stopped") store.updateNotebookStatus(ensuredRecord.id, "stopped")
                    "missing" -> if (ensuredRecord.notebookStatus != "stopped") store.updateNotebookStatus(ensuredRecord.id, "stopped")
                    else -> if (ensuredRecord.notebookStatus != notebookStatus) store.updateNotebookStatus(ensuredRecord.id, notebookStatus)
                }
            }
        }
    }
}

private fun SshKeyRecord.toView(): SshKeyView = SshKeyView(
    id = id,
    name = name,
    fingerprint = fingerprint,
    createdAt = createdAt
)
