package org.webservices.portal

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

private val portalJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    explicitNulls = false
}

private val marketingExcludedComponents = setOf(
    "autobattler",
    "tas-dashboard",
    "qbittorrent",
    "homeassistant",
    "jellyfin"
)

class PortalService(
    private val config: PortalConfig,
    private val httpClient: HttpClient
) {
    suspend fun modules(): List<PortalModule> = loadModules()

    suspend fun profiles(): List<ProfileSummary> {
        val modules = loadModules()
        return loadProfiles().profiles.map { profile ->
            val roleModules = modulesForProfile(profile, modules)
            ProfileSummary(
                profile = profile.id,
                name = profile.name,
                defaultView = profile.defaultView,
                purpose = profile.purpose,
                widgets = profile.widgets,
                services = profile.services,
                moduleCount = roleModules.size,
                modules = roleModules.map { it.component }
            )
        }
    }

    suspend fun dashboard(profileId: String): DashboardResponse {
        val profile = loadProfiles().profiles.firstOrNull { it.id == profileId }
            ?: throw NoSuchElementException("profile not found: $profileId")
        val modules = modulesForProfile(profile, loadModules())
        val reports = loadReports()
        val sourceStatuses = sourceStatuses(modules, reports)
        val partialSources = sourceStatuses.filter { it.status != "ok" }.map { it.id }
        val density = densityFor(profile.id)

        return DashboardResponse(
            profile = DashboardProfile(
                id = profile.id,
                name = profile.name,
                purpose = profile.purpose,
                defaultView = profile.defaultView,
                density = density
            ),
            status = DashboardStatus(
                overall = if (partialSources.isEmpty()) "ok" else "partial",
                updatedAt = now(),
                partialSources = partialSources
            ),
            visualLanguage = visualLanguageFor(profile.id),
            kpis = kpisFor(profile, modules, reports, sourceStatuses),
            heroVisuals = heroVisualsFor(profile, modules, sourceStatuses),
            widgets = widgetsFor(profile, modules, reports),
            visualizations = visualizationsFor(profile, modules, reports),
            actions = actionsFor(profile, modules, reports, sourceStatuses),
            modules = modules,
            evidence = evidenceFor(modules, reports),
            sources = sourceStatuses
        )
    }

    suspend fun integrations(): IntegrationStatusResponse {
        val modules = loadModules()
        val reports = loadReports()
        return IntegrationStatusResponse(now(), sourceStatuses(modules, reports))
    }

    fun reports(): RawReportsResponse {
        val reports = loadReports()
        return RawReportsResponse(now(), reports)
    }

    private fun loadContracts(): ServiceContracts =
        readJson(config.contractsPath, ServiceContracts())

    private fun loadLock(): ComponentLock =
        readJson(config.lockPath, ComponentLock())

    private fun loadProfiles(): PortalProfiles =
        readJson(config.profilesPath, PortalProfiles())

    private fun loadModules(): List<PortalModule> {
        val selected = loadLock().components.toSet()
        val excluded = config.excludedComponents + if (config.marketingMode) marketingExcludedComponents else emptySet()
        return loadContracts().components
            .filter { (component, contract) -> component in selected && component !in excluded && contract.portal.visible }
            .toSortedMap()
            .map { (component, contract) ->
                val host = contract.portal.hrefHost.ifBlank { contract.primaryHost.ifBlank { component } }
                val href = if (host == "apex") "/" else "https://$host.${config.domain}/"
                val displayName = if (component == "chatgpt-connector") "AI Connector" else contract.name.ifBlank { component }
                val displayDescription = if (component == "chatgpt-connector") {
                    "Managed AI connector accounts, MCP endpoint status, and agent automation entry points."
                } else {
                    contract.portal.description.ifBlank { contract.description }
                }
                PortalModule(
                    component = component,
                    name = displayName,
                    description = displayDescription,
                    category = contract.portal.category,
                    href = contract.portal.path.takeIf { it.isNotBlank() }?.let { href.trimEnd('/') + it } ?: href,
                    auth = contract.auth.mode,
                    profiles = contract.portal.profiles,
                    evidence = contract.evidence.expectations,
                    screenshots = contract.screenshots,
                    slo = contract.slo,
                    stateStores = contract.state.stores,
                    backupTargets = contract.backup.targets,
                    restoreDrills = contract.restore.drills,
                    owner = contract.access.owner
                )
            }
    }

    private fun modulesForProfile(profile: ProfileDefinition, modules: List<PortalModule>): List<PortalModule> {
        val serviceSet = profile.services.toSet()
        val explicitModules = modules.filter { module -> module.component in serviceSet }
        return explicitModules.ifEmpty {
            modules.filter { module -> profile.id in module.profiles || compatibilityProfile(profile.id) in module.profiles }
        }
    }

    private fun compatibilityProfile(profileId: String): String = when (profileId) {
        "employee", "client" -> "user"
        "platform-operator-security", "ai-data-analyst", "team-lead" -> "operator"
        "business-owner" -> "admin"
        else -> "operator"
    }

    private fun loadReports(): List<ReportDocument> {
        if (!config.reportsDir.exists()) return emptyList()
        return config.reportsDir.listDirectoryEntries("*.json")
            .filter { it.isRegularFile() }
            .sortedBy { it.name }
            .map { path ->
                try {
                    ReportDocument(
                        name = path.name.removeSuffix(".json"),
                        path = path.toString(),
                        payload = portalJson.parseToJsonElement(path.readText()).jsonObject
                    )
                } catch (error: Exception) {
                    ReportDocument(path.name.removeSuffix(".json"), path.toString(), error = error.message ?: "unreadable report")
                }
            }
    }

    private suspend fun sourceStatuses(modules: List<PortalModule>, reports: List<ReportDocument>): List<SourceStatus> = coroutineScope {
        val reportSources = reports.map { report ->
            SourceStatus(
                id = "report.${report.name}",
                label = titleize(report.name),
                status = if (report.error == null) "ok" else "partial",
                updatedAt = now(),
                summary = if (report.error == null) "Generated report loaded" else "Report could not be parsed",
                errors = listOfNotNull(report.error)
            )
        }
        val healthTargets = listOf(
            "progression" to ("Progression" to "${config.progressBaseUrl}/health"),
            "workspace-provisioner" to ("Workspaces" to "${config.workspacesBaseUrl}/health"),
            "chatgpt-connector" to ("AI Connector" to "${config.chatgptConnectorBaseUrl}/health"),
            "observability" to ("Grafana" to "${config.grafanaBaseUrl}/api/health"),
            "kopia" to ("Kopia" to "${config.kopiaBaseUrl}/")
        ).filter { (component, _) -> modules.any { it.component == component || it.component == "observability" && component == "observability" } }

        val liveSources = healthTargets.map { (id, target) ->
            async {
                val (label, url) = target
                probeSource(id, label, url)
            }
        }.map { it.await() }

        reportSources + liveSources
    }

    private suspend fun probeSource(id: String, label: String, url: String): SourceStatus {
        return try {
            val response = withTimeout(config.liveTimeoutMs) { httpClient.get(url) }
            SourceStatus(
                id = "live.$id",
                label = label,
                status = if (response.status.isSuccess()) "ok" else "partial",
                updatedAt = now(),
                summary = "Live endpoint returned ${response.status.value}",
                errors = if (response.status.isSuccess()) emptyList() else listOf(response.bodyAsText().take(160))
            )
        } catch (error: Exception) {
            SourceStatus(
                id = "live.$id",
                label = label,
                status = "unavailable",
                updatedAt = now(),
                summary = "Live endpoint unavailable",
                errors = listOf((error.message ?: error::class.simpleName ?: "unknown error").take(180))
            )
        }
    }

    private fun kpisFor(
        profile: ProfileDefinition,
        modules: List<PortalModule>,
        reports: List<ReportDocument>,
        sources: List<SourceStatus>
    ): List<KpiTile> {
        val evidenceCount = modules.sumOf { it.evidence.size }
        val screenshotCount = modules.sumOf { it.screenshots.size }
        val backupTargets = modules.sumOf { it.backupTargets.size }
        val liveOk = sources.count { it.status == "ok" }
        val liveTotal = sources.size.coerceAtLeast(1)
        val base = listOf(
            KpiTile("modules", "Relevant modules", modules.size.toString(), "Selected from stack contracts", "good"),
            KpiTile("evidence", "Evidence checks", evidenceCount.toString(), "Declared verification points", if (evidenceCount > 0) "good" else "attention"),
            KpiTile("sources", "Live sources", "$liveOk/$liveTotal", "Reports and live endpoints available", if (liveOk == liveTotal) "good" else "attention"),
            KpiTile("backups", "Backup targets", backupTargets.toString(), "Covered by module contracts", if (backupTargets > 0) "good" else "neutral")
        )
        return when (profile.id) {
            "business-owner" -> listOf(
                KpiTile("decisions", "Decisions ready", "4", "Approvals and delegations prepared", "good"),
                KpiTile("drafts", "Briefs drafted", modulePresent(modules, "chatgpt-connector"), "AI connector can prepare first-pass updates", moduleTone(modules, "chatgpt-connector")),
                KpiTile("handoffs", "Handoff tools", "${modules.count { it.component in setOf("erpnext", "planka", "bookstack", "seafile") }}", "Finance, delivery, docs, and files", "good"),
                KpiTile("focus", "Needs owner", "2", "Work items ready to delegate", "attention")
            )
            "platform-operator-security" -> listOf(
                KpiTile("health", "Health sources", "$liveOk/$liveTotal", "Live operational endpoints", if (liveOk == liveTotal) "good" else "attention"),
                KpiTile("routes", "Route-backed modules", modules.count { it.href.startsWith("https://") }.toString(), "Contracted service routes", "good"),
                KpiTile("identity", "Identity surface", modulePresent(modules, "core"), "Keycloak, routes, and policies", moduleTone(modules, "core")),
                KpiTile("access", "Access checks", modules.flatMap { it.evidence }.count { "auth" in it || "access" in it || "route" in it }.toString(), "Access-related evidence", "good")
            )
            "employee" -> listOf(
                KpiTile("start", "Start here", "5 actions", "Reply, review, update, file, hand off", "good"),
                KpiTile("draft", "Draft assist", modulePresent(modules, "chatgpt-connector"), "AI connector available for first drafts", moduleTone(modules, "chatgpt-connector")),
                KpiTile("work", "Work tools", modules.count { it.component in setOf("planka", "donetick", "erpnext") }.toString(), "Tasks and project tools connected", "good"),
                KpiTile("knowledge", "Reference", modules.count { it.component in setOf("bookstack", "seafile") }.toString(), "Docs and shared files connected", "good")
            )
            "client" -> listOf(
                KpiTile("review", "Ready to review", "3 items", "Deliverables waiting on client action", "good"),
                KpiTile("files", "Client packet", modulePresent(modules, "seafile"), "Scoped files and approvals", moduleTone(modules, "seafile")),
                KpiTile("meeting", "Meeting prep", modulePresent(modules, "sogo"), "Agenda and follow-ups in context", moduleTone(modules, "sogo")),
                KpiTile("billing", "Billing context", modulePresent(modules, "erpnext"), "Invoices and account notes available", moduleTone(modules, "erpnext"))
            )
            "team-lead" -> listOf(
                KpiTile("unblocks", "Unblocks ready", "3", "People and decisions needing a lead", "attention"),
                KpiTile("handoffs", "Handoffs", "4", "Packets ready to assign or send", "good"),
                KpiTile("prompts", "Owner prompts", "4", "Alice, Bob, Charlie, and Damian have next steps", "good"),
                KpiTile("updates", "Updates drafted", modulePresent(modules, "chatgpt-connector"), "AI connector can draft status updates", moduleTone(modules, "chatgpt-connector"))
            )
            "ai-data-analyst" -> listOf(
                KpiTile("workspace", "Workspace launch", modulePresent(modules, "workspace-provisioner"), "Disposable workbench with context", moduleTone(modules, "workspace-provisioner")),
                KpiTile("notebook", "Notebook run", modulePresent(modules, "jupyterhub"), "Analysis environment linked", moduleTone(modules, "jupyterhub")),
                KpiTile("drafts", "Report draft", modulePresent(modules, "chatgpt-connector"), "AI connector can draft findings", moduleTone(modules, "chatgpt-connector")),
                KpiTile("sources", "Source packets", modules.count { it.component in setOf("seafile", "bookstack", "forgejo") }.toString(), "Files, docs, and repo context attached", "good")
            )
            else -> base.take(if (densityFor(profile.id) == DashboardDensity.EXECUTIVE) 3 else 4)
        }
    }

    private fun widgetsFor(profile: ProfileDefinition, modules: List<PortalModule>, reports: List<ReportDocument>): List<DashboardWidget> =
        profile.widgets.map { widgetId ->
            val spec = widgetSpec(widgetId)
            DashboardWidget(
                id = widgetId,
                title = spec.title,
                type = spec.type,
                summary = spec.summary,
                tone = spec.tone,
                items = widgetItems(widgetId, modules, reports),
                href = preferredHref(widgetId, modules)
            )
        }

    private fun visualizationsFor(profile: ProfileDefinition, modules: List<PortalModule>, reports: List<ReportDocument>): List<DashboardVisualization> {
        val categoryPoints = modules.groupingBy { it.category }.eachCount()
            .map { (category, count) -> VisualizationPoint(category, count.toDouble(), toneForCategory(category)) }
        val evidencePoints = modules.take(8)
            .map { VisualizationPoint(it.name, it.evidence.size.toDouble(), if (it.evidence.isEmpty()) "attention" else "good") }
        val sourcePoints = listOf(
            VisualizationPoint("Modules", modules.size.toDouble(), "good"),
            VisualizationPoint("Evidence", modules.sumOf { it.evidence.size }.toDouble(), "good"),
            VisualizationPoint("Screenshots", modules.sumOf { it.screenshots.size }.toDouble(), "neutral"),
            VisualizationPoint("Reports", reports.size.toDouble(), "neutral")
        )
        return when (densityFor(profile.id)) {
            DashboardDensity.EXECUTIVE -> listOf(
                DashboardVisualization("capability_mix", "Capability mix", "bar", "Role-relevant services by category.", categoryPoints),
                DashboardVisualization("risk_surface", "Proof and coverage", "bullet", "Evidence, screenshots, and reports available for drill-through.", sourcePoints)
            )
            DashboardDensity.COCKPIT -> listOf(
                DashboardVisualization("evidence_matrix", "Evidence matrix", "matrix", "Declared checks per role-relevant module.", evidencePoints),
                DashboardVisualization("capability_mix", "Capability mix", "bar", "Operational surfaces by category.", categoryPoints)
            )
            DashboardDensity.OPERATIONAL -> listOf(
                DashboardVisualization("work_surface", "Work surface", "bar", "Role-relevant module categories.", categoryPoints),
                DashboardVisualization("coverage", "Evidence coverage", "bullet", "Proof artifacts available for this dashboard.", sourcePoints)
            )
        }
    }

    private fun visualLanguageFor(profileId: String): VisualLanguage = when (profileId) {
        "employee" -> VisualLanguage("#37d3b7", "employee action home", "start-draft-file-handoff", "calm")
        "client" -> VisualLanguage("#76a9fa", "customer action portal", "review-approve-download", "focused")
        "team-lead" -> VisualLanguage("#76a9fa", "delivery accelerator", "unblock-handoff-coordinate", "operational")
        "business-owner" -> VisualLanguage("#81d67a", "executive decision desk", "decide-delegate-follow-up", "sparse")
        "ai-data-analyst" -> VisualLanguage("#b48cff", "analysis workbench", "launch-query-draft-automate", "dense")
        "platform-operator-security" -> VisualLanguage("#37d3b7", "ops and access cockpit", "health-policy-alerts", "cockpit")
        else -> VisualLanguage("#37d3b7", "stack dashboard", "coverage-grid", "operational")
    }

    private fun heroVisualsFor(
        profile: ProfileDefinition,
        modules: List<PortalModule>,
        sources: List<SourceStatus>
    ): List<HeroVisual> {
        val proofOk = sources.count { it.status == "ok" }
        val proofTotal = sources.size.coerceAtLeast(1)
        return when (profile.id) {
            "employee" -> listOf(
                timeline("start_plan", "Work path", "Reply to handoff without app hunting", "Reply" to 30.0, "Draft" to 48.0, "Update" to 62.0, "File" to 38.0),
                lanes("tool_path", "Tool path", "The cockpit opens the right service", "Mail" to "reply", "AI" to "draft", "Tasks" to "update", "Files" to "file"),
                bars("next_actions", "Action mix", "Useful work waiting now", "Replies" to 3.0, "Reviews" to 4.0, "Updates" to 2.0, "Handoffs" to 1.0)
            )
            "client" -> listOf(
                timeline("review_path", "Review path", "Open, comment, approve, and keep a copy", "Open" to 82.0, "Comment" to 58.0, "Approve" to 44.0, "Archive" to 28.0),
                lanes("client_pack", "Client packet", "Scoped tools in one place", "Files" to "open", "Docs" to "read", "Meeting" to "join", "Invoice" to "view"),
                bars("client_actions", "Action mix", "Customer-facing work ready now", "Approve" to 2.0, "Answer" to 3.0, "Download" to 4.0, "Pay" to 1.0)
            )
            "team-lead" -> listOf(
                bars("handoff_queue", "Handoff queue", "Prepared team actions", "Assign" to 4.0, "Unblock" to 3.0, "Review" to 5.0, "Update" to 2.0),
                lanes("team_prompts", "Team prompts", "Useful nudges by person", "Alice" to "review", "Bob" to "handoff", "Charlie" to "ship", "Damian" to "unblock"),
                heatmap("unblock_map", "Unblock map", "Where one action helps", "Client" to 4.0, "API" to 2.0, "Docs" to 1.0, "Ops" to 1.0)
            )
            "business-owner" -> listOf(
                funnel("decision_flow", "Decision flow", "Signal to delegated action", "Briefs" to 12.0, "Decisions" to 6.0, "Delegated" to 4.0, "Done" to 2.0),
                lanes("delegate_map", "Delegate map", "Prepared owners for next steps", "Finance" to "Alice", "Delivery" to "Bob", "Sales" to "Charlie", "Ops" to "Damian"),
                spark("decision_value", "Decision value", "Choices prepared over time", 48.0, 52.0, 58.0, 55.0, 64.0, 71.0)
            )
            "ai-data-analyst" -> listOf(
                timeline("workbench_steps", "Workbench path", "Question to reviewed output", "Collect" to 34.0, "Query" to 50.0, "Analyze" to 72.0, "Draft" to 44.0),
                lanes("agent_workbench", "Agent workbench", "Launchable automation", "Prompt" to "open", "MCP" to "ready", "Workspace" to "launch", "Notebook" to "run"),
                spark("draft_outputs", "Draft outputs", "Reports prepared for review", 3.0, 4.0, 6.0, 5.0, 9.0, 8.0)
            )
            "platform-operator-security" -> listOf(
                heatmap("service_policy_matrix", "Service + policy matrix", "Health and access surfaces", "Routes" to 1.0, "Auth" to 2.0, "Backups" to 2.0, "Security" to 3.0),
                bars("coverage", "Proof coverage", "Operations and security proof", "Routes" to modules.count { it.href.startsWith("https://") }.toDouble(), "Backups" to modules.sumOf { it.backupTargets.size }.toDouble(), "Checks" to modules.sumOf { it.evidence.size }.toDouble(), "Sources" to proofOk.toDouble()),
                lanes("security_queue", "Alert and access queue", "Safe summary only", "Critical" to "0", "Warn" to "${proofTotal - proofOk}", "Vault" to "sealed", "Stale users" to "2")
            )
            else -> listOf(
                bars("coverage", "Coverage", "Stack surfaces", "Modules" to modules.size.toDouble(), "Evidence" to modules.sumOf { it.evidence.size }.toDouble()),
                ring("sources", "Sources", "Loaded reports", ((proofOk.toDouble() / proofTotal) * 100.0), "%", "good")
            )
        }
    }

    private fun actionsFor(
        profile: ProfileDefinition,
        modules: List<PortalModule>,
        reports: List<ReportDocument>,
        sources: List<SourceStatus>
    ): List<ActionItem> {
        val partial = sources.filter { it.status != "ok" }.take(2).map {
            ActionItem("Review ${it.label}", it.summary, "high")
        }
        val roleActions = when (profile.id) {
            "platform-operator-security" -> listOf(
                ActionItem("Open service health", "Check dashboards, logs, route state, and alert summaries.", "high", moduleHref(modules, "observability")),
                ActionItem("Review access policy", "Open identity and route policy before changing exposure.", "high", moduleHref(modules, "core")),
                ActionItem("Review backup proof", "Confirm snapshots and restore evidence are current.", "normal", moduleHref(modules, "kopia")),
                ActionItem("Open vault entry points", "Check operator-only secret access paths without exposing values.", "normal", moduleHref(modules, "vaultwarden"))
            )
            "ai-data-analyst" -> listOf(
                ActionItem("Launch analysis workspace", "Start a disposable workspace with docs, files, and repo context mounted.", "high", moduleHref(modules, "workspace-provisioner")),
                ActionItem("Open notebook run", "Continue the churn analysis notebook and capture the next chart.", "normal", moduleHref(modules, "jupyterhub")),
                ActionItem("Draft report from evidence", "Use the AI connector to turn findings into a reviewed first draft.", "normal", moduleHref(modules, "chatgpt-connector")),
                ActionItem("Open source files", "Review the shared CSV and notes before publishing results.", "normal", moduleHref(modules, "seafile")),
                ActionItem("Update analysis runbook", "Record inputs, assumptions, and the repeatable workflow.", "normal", moduleHref(modules, "bookstack"))
            )
            "business-owner" -> listOf(
                ActionItem("Approve proposal packet", "Review the prepared scope, price, and delivery note before sending.", "high", moduleHref(modules, "erpnext")),
                ActionItem("Delegate finance follow-up", "Assign Alice the overdue invoice note with account context attached.", "normal", moduleHref(modules, "erpnext")),
                ActionItem("Open executive brief", "Read the short decision memo and supporting project evidence.", "normal", moduleHref(modules, "bookstack")),
                ActionItem("Send client update", "Use the drafted message and attach the current delivery packet.", "normal", moduleHref(modules, "sogo")),
                ActionItem("Ask for a board summary", "Generate a one-page summary from projects, docs, and finance context.", "normal", moduleHref(modules, "chatgpt-connector"))
            )
            "employee" -> listOf(
                ActionItem("Reply to Alice's client note", "Open the mail thread with the project file and suggested reply ready.", "high", moduleHref(modules, "sogo")),
                ActionItem("Draft weekly update", "Use the AI connector with tasks, docs, and recent file context.", "normal", moduleHref(modules, "chatgpt-connector")),
                ActionItem("Update onboarding task", "Move the checklist card after adding today’s evidence.", "normal", moduleHref(modules, "planka")),
                ActionItem("Review handoff file", "Open the shared Seafile packet and mark the delivery note reviewed.", "normal", moduleHref(modules, "seafile")),
                ActionItem("Find the runbook answer", "Open the BookStack page linked to the current task.", "normal", moduleHref(modules, "bookstack"))
            )
            "client" -> listOf(
                ActionItem("Review delivery packet", "Open the scoped files, change note, and approval checklist.", "high", moduleHref(modules, "seafile")),
                ActionItem("Approve phase-two brief", "Read the concise scope page and confirm the next milestone.", "normal", moduleHref(modules, "bookstack")),
                ActionItem("Answer Charlie's question", "Open the customer thread with the required decision highlighted.", "normal", moduleHref(modules, "sogo")),
                ActionItem("Download invoice pack", "Open invoice, receipt, and account context in one place.", "normal", moduleHref(modules, "erpnext")),
                ActionItem("Join project room", "Open the scoped room for follow-up questions and meeting notes.", "normal", moduleHref(modules, "matrix"))
            )
            "team-lead" -> listOf(
                ActionItem("Unblock Damian", "Open the blocked task with the decision log and owner handoff attached.", "high", moduleHref(modules, "planka")),
                ActionItem("Assign review to Alice", "Move the client packet into review and notify the owner.", "normal", moduleHref(modules, "planka")),
                ActionItem("Send client status update", "Use the drafted update with files, tasks, and meeting notes attached.", "normal", moduleHref(modules, "sogo")),
                ActionItem("Open handoff packet", "Review the delivery files before handing work to Bob.", "normal", moduleHref(modules, "seafile")),
                ActionItem("Update decision log", "Record what changed and link the task board for the team.", "normal", moduleHref(modules, "bookstack"))
            )
            else -> listOf(
                ActionItem("Open primary module", "Drill into the most relevant live service for this role.", "normal", modules.firstOrNull()?.href),
                ActionItem("Review evidence", "Use dashboard proof and source health before acting.", "normal")
            )
        }
        val missingReports = if (reports.isEmpty() && profile.id == "platform-operator-security") {
            listOf(ActionItem("Generate contract reports", "No generated reports were mounted for dashboard enrichment.", "normal"))
        } else {
            emptyList()
        }
        val prefix = if (profile.id == "platform-operator-security") partial else emptyList()
        return (prefix + roleActions + missingReports).distinctBy { it.label }.take(6)
    }

    private fun evidenceFor(modules: List<PortalModule>, reports: List<ReportDocument>): List<EvidenceItem> {
        val moduleEvidence = modules.flatMap { module ->
            module.evidence.take(3).map {
                EvidenceItem(
                    label = it,
                    status = "declared",
                    source = module.name,
                    detail = "Declared in service contract",
                    href = module.href
                )
            }
        }
        val reportEvidence = reports.take(6).map {
            EvidenceItem(
                label = titleize(it.name),
                status = if (it.error == null) "ok" else "partial",
                source = "Generated report",
                detail = it.error ?: "Loaded from build report artifacts"
            )
        }
        return (reportEvidence + moduleEvidence).take(18)
    }

    private fun widgetItems(widgetId: String, modules: List<PortalModule>, reports: List<ReportDocument>): List<WidgetItem> {
        roleWorkflowItems(widgetId, modules)?.let { return it }
        val reportItems = reports.take(3).map { WidgetItem(titleize(it.name), if (it.error == null) "loaded" else "partial", it.error ?: "Generated report available", if (it.error == null) "good" else "attention") }
        return when (widgetId) {
            "service_health", "route_health", "auth_health" -> modules.take(6).map {
                WidgetItem(it.name, it.auth, "${it.evidence.size} checks · ${it.slo.availability.ifBlank { "SLO pending" }}", if (it.evidence.isEmpty()) "attention" else "good", it.href)
            }
            "backup_status", "backup_proof", "restore_drills" -> modules.filter { it.backupTargets.isNotEmpty() || it.restoreDrills.isNotEmpty() }.take(6).map {
                WidgetItem(it.name, "${it.backupTargets.size} targets", it.restoreDrills.joinToString(", ").ifBlank { "Restore drill pending" }, "good", it.href)
            }
            "test_results", "screenshots" -> reportItems + modules.filter { it.screenshots.isNotEmpty() }.take(3).map {
                WidgetItem(it.name, "${it.screenshots.size} screenshots", it.screenshots.joinToString(", "), "neutral", it.href)
            }
            "users", "groups", "failed_logins", "service_policies", "stale_accounts" -> modules.filter { it.auth.contains("oidc") || it.auth.contains("keycloak") || it.component == "core" }.take(6).map {
                WidgetItem(it.name, it.auth, "Owner: ${it.owner.ifBlank { "platform" }}", "attention", it.href)
            }
            "datasets", "recent_notebooks", "workspace_launcher", "ingestion_jobs", "analysis_prompts", "search_coverage", "agent_runs" -> modules.filter { it.category in setOf("AI", "Knowledge", "Development") }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "good", it.href)
            }
            "invoices", "unpaid_invoices", "overdue_payments", "payables", "purchase_orders", "receivables", "revenue", "cash_runway", "client_health" -> modules.filter { it.component == "erpnext" || it.component == "seafile" || it.component == "bookstack" || it.component == "grafana" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "neutral", it.href)
            }
            "recent_files", "client_files", "approval_docs", "approval_queue", "campaign_files", "shared_files", "client_docs", "support_links" -> modules.filter { it.component == "seafile" || it.component == "onlyoffice" || it.component == "bookstack" || it.component == "erpnext" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "neutral", it.href)
            }
            "open_tasks", "assigned_tasks", "my_tasks", "active_work", "blocked_tasks", "overdue_work", "client_tasks", "client_requests" -> modules.filter { it.component == "planka" || it.component == "donetick" || it.component == "erpnext" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "good", it.href)
            }
            "mailbox" -> modules.filter { it.component == "sogo" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "neutral", it.href)
            }
            "team_rooms" -> modules.filter { it.component == "matrix" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "neutral", it.href)
            }
            "meetings", "meeting_history", "last_contact", "next_follow_up", "mentions" -> modules.filter { it.component == "sogo" || it.component == "matrix" || it.component == "mastodon" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "neutral", it.href)
            }
            else -> modules.take(5).map {
                WidgetItem(it.name, it.category, it.description, "neutral", it.href)
            }
        }.ifEmpty { reportItems }
    }

    private fun roleWorkflowItems(widgetId: String, modules: List<PortalModule>): List<WidgetItem>? {
        fun item(label: String, value: String, detail: String, component: String, tone: String = "good") =
            WidgetItem(label, value, detail, tone, moduleHref(modules, component) ?: preferredHref(widgetId, modules))

        return when (widgetId) {
            "mailbox" -> listOf(
                item("Alice Morgan", "reply", "Client note needs a concise answer and project link.", "sogo", "attention"),
                item("Bob Chen", "draft", "Weekly progress update has task context attached.", "chatgpt-connector"),
                item("Charlie Reed", "schedule", "Confirm Thursday review window from the calendar.", "sogo")
            )
            "team_rooms" -> listOf(
                item("Delivery room", "join", "Two unread handoff notes from Alice and Damian.", "matrix", "attention"),
                item("Client room", "answer", "Charlie asked for the latest packet link.", "matrix"),
                item("Ops room", "read", "Maintenance note is pinned for Friday.", "matrix")
            )
            "my_tasks" -> listOf(
                item("Update onboarding checklist", "today", "Move the Planka card after adding evidence.", "planka", "attention"),
                item("Review handoff file", "15 min", "Open Seafile packet and confirm the summary page.", "seafile"),
                item("File meeting notes", "ready", "Attach notes to the project record.", "erpnext")
            )
            "recent_docs" -> listOf(
                item("Client update runbook", "open", "Use the approved structure for outbound updates.", "bookstack"),
                item("Delivery handoff template", "copy", "Start the next packet from the standard page.", "bookstack"),
                item("Support escalation notes", "read", "Answer the current customer question safely.", "bookstack")
            )
            "shared_files", "approval_queue" -> listOf(
                item("Northstar delivery packet", "review", "Three files ready for final check.", "seafile", "attention"),
                item("Phase-two proposal", "approve", "Scope PDF and pricing sheet are attached.", "erpnext"),
                item("Onboarding evidence", "file", "Archive the signed checklist in the shared folder.", "seafile")
            )
            "ai_next_actions" -> listOf(
                item("Draft weekly update", "draft", "Use tasks, docs, and files as source context.", "chatgpt-connector"),
                item("Summarize client thread", "summarize", "Turn the last five messages into next steps.", "chatgpt-connector"),
                item("Prepare handoff note", "compose", "Create a first pass for Bob to review.", "chatgpt-connector")
            )
            "client_requests" -> listOf(
                item("Approve phase-two scope", "approve", "Scope summary and open questions are ready.", "bookstack", "attention"),
                item("Answer access question", "answer", "Charlie needs one owner decision.", "sogo"),
                item("Choose review time", "pick", "Two meeting windows are available.", "sogo")
            )
            "deliverables", "client_files", "client_docs", "support_links" -> listOf(
                item("Delivery packet", "open", "Files, summary, and approval checklist.", "seafile", "attention"),
                item("Project summary", "read", "Plain-language status and decisions needed.", "bookstack"),
                item("Support guide", "open", "How to request a change or report an issue.", "bookstack")
            )
            "meeting_history" -> listOf(
                item("Thursday review", "notes", "Actions for Alice, Bob, and Charlie are captured.", "sogo"),
                item("Kickoff call", "recap", "Decision log and file packet are linked.", "bookstack"),
                item("Follow-up room", "join", "Open questions are collected in one room.", "matrix")
            )
            "invoices" -> listOf(
                item("Invoice INV-1042", "view", "PDF, receipt status, and account note.", "erpnext"),
                item("Retainer receipt", "download", "Current billing packet for records.", "erpnext"),
                item("Payment question", "reply", "Finance thread is attached.", "sogo")
            )
            "active_work", "blocked_tasks", "overdue_work", "team_workload" -> listOf(
                item("Damian blocked on API wording", "unblock", "Decision log and draft response are attached.", "planka", "attention"),
                item("Alice review queue", "assign", "Client packet needs owner confirmation.", "planka"),
                item("Bob handoff packet", "send", "Files are ready for delivery review.", "seafile"),
                item("Charlie release note", "review", "Draft is waiting in the docs workspace.", "bookstack")
            )
            "recent_decisions" -> listOf(
                item("Scope boundary", "record", "Capture final wording from the client call.", "bookstack"),
                item("Release timing", "confirm", "Attach the decision to the project board.", "planka"),
                item("Owner handoff", "publish", "Send summary to the delivery room.", "matrix")
            )
            "revenue", "cash_runway", "receivables", "pipeline", "delivery_health", "client_health", "executive_brief" -> listOf(
                item("Approve Northstar proposal", "decide", "Price, margin, and delivery note are prepared.", "erpnext", "attention"),
                item("Delegate overdue invoice", "delegate", "Alice has the account context and draft note.", "erpnext"),
                item("Read executive brief", "review", "Three decisions and two follow-ups are summarized.", "bookstack"),
                item("Send client confidence update", "send", "Draft message includes delivery evidence.", "sogo")
            )
            "datasets", "recent_notebooks", "workspace_launcher", "ingestion_jobs", "analysis_prompts", "search_coverage", "agent_runs" -> listOf(
                item("Launch churn workspace", "launch", "Disposable workspace with data and docs mounted.", "workspace-provisioner", "attention"),
                item("Run cohort notebook", "run", "Notebook is ready with the latest CSV.", "jupyterhub"),
                item("Draft findings report", "draft", "AI connector has the evidence packet.", "chatgpt-connector"),
                item("Publish analysis runbook", "publish", "Inputs and assumptions are ready to record.", "bookstack")
            )
            else -> null
        }
    }

    private fun widgetSpec(id: String): WidgetSpec {
        val title = when (id) {
            "mailbox" -> "Inbox actions"
            "team_rooms" -> "Room actions"
            "my_tasks" -> "My work queue"
            "recent_docs" -> "Useful docs"
            "shared_files" -> "Shared file actions"
            "approval_queue" -> "Approvals"
            "ai_next_actions" -> "AI draft assists"
            "client_requests" -> "Client requests"
            "deliverables" -> "Deliverables"
            "client_files" -> "Client files"
            "client_docs" -> "Client docs"
            "meeting_history" -> "Meeting follow-ups"
            "invoices" -> "Billing actions"
            "active_work" -> "Active work"
            "blocked_tasks" -> "Blockers"
            "overdue_work" -> "Needs attention"
            "team_workload" -> "Team prompts"
            "recent_decisions" -> "Decision log"
            "revenue" -> "Revenue decisions"
            "cash_runway" -> "Cash decisions"
            "receivables" -> "Receivables"
            "pipeline" -> "Pipeline actions"
            "delivery_health" -> "Delivery decisions"
            "client_health" -> "Client decisions"
            "executive_brief" -> "Executive brief"
            "datasets" -> "Datasets"
            "recent_notebooks" -> "Notebook runs"
            "workspace_launcher" -> "Workspace launcher"
            "ingestion_jobs" -> "Ingestion jobs"
            "analysis_prompts" -> "Analysis prompts"
            "search_coverage" -> "Knowledge coverage"
            "agent_runs" -> "Agent runs"
            else -> titleize(id)
        }
        return when (id) {
            "service_health", "route_health", "auth_health", "backup_status", "alerts", "test_results" ->
                WidgetSpec(title, "status_matrix", "Live operational signal and proof state.", "attention")
            "revenue", "cash_runway", "receivables", "pipeline", "delivery_health", "executive_brief", "client_health" ->
                WidgetSpec(title, "decision_card", "Prepared business action with drill-through.", "neutral")
            "datasets", "recent_notebooks", "workspace_launcher", "ingestion_jobs", "analysis_prompts", "search_coverage", "agent_runs" ->
                WidgetSpec(title, "workbench_action", "Launch analysis work with source context attached.", "good")
            "users", "groups", "failed_logins", "service_policies", "stale_accounts", "vault_entry_points" ->
                WidgetSpec(title, "risk_queue", "Access, identity, and security review surface.", "attention")
            "open_tasks", "assigned_tasks", "my_tasks", "active_work", "blocked_tasks", "overdue_work", "client_tasks", "client_requests" ->
                WidgetSpec(title, "action_queue", "Prepared work items linked to the source tool.", "good")
            "mailbox", "team_rooms", "meetings", "meeting_history" ->
                WidgetSpec(title, "message_action", "Conversation context ready for reply or follow-up.", "good")
            "recent_files", "client_files", "shared_files", "approval_docs", "approval_queue", "campaign_files", "deliverables", "client_docs", "support_links" ->
                WidgetSpec(title, "packet_action", "Open, review, approve, or hand off shared work.", "neutral")
            else -> WidgetSpec(title, "workflow", "Connected tool action with safe drill-through.", "neutral")
        }
    }

    private fun preferredHref(widgetId: String, modules: List<PortalModule>): String? = when (widgetId) {
        "service_health", "alerts", "route_health" -> moduleHref(modules, "observability")
        "backup_status", "backup_proof", "restore_drills" -> moduleHref(modules, "kopia")
        "workspace_launcher", "workspace_launch" -> moduleHref(modules, "workspace-provisioner")
        "datasets", "search_gaps", "search_coverage" -> moduleHref(modules, "jupyterhub") ?: moduleHref(modules, "seafile")
        "users", "groups", "auth_model" -> moduleHref(modules, "core")
        "vault_entry_points" -> moduleHref(modules, "vaultwarden")
        "invoices", "unpaid_invoices", "revenue", "receivables" -> moduleHref(modules, "erpnext")
        "recent_docs", "client_docs", "public_docs" -> moduleHref(modules, "bookstack")
        "recent_files", "client_files", "shared_files", "deliverables" -> moduleHref(modules, "seafile")
        "mailbox", "meetings", "meeting_history" -> moduleHref(modules, "sogo")
        "team_rooms" -> moduleHref(modules, "matrix")
        "my_tasks", "active_work", "blocked_tasks", "overdue_work", "team_workload" -> moduleHref(modules, "planka")
        "ai_next_actions", "analysis_prompts", "agent_runs" -> moduleHref(modules, "chatgpt-connector")
        "pipeline", "delivery_health", "client_health", "executive_brief" -> moduleHref(modules, "bookstack") ?: moduleHref(modules, "erpnext")
        else -> modules.firstOrNull()?.href
    }

    private fun bars(id: String, title: String, summary: String, vararg points: Pair<String, Double>): HeroVisual =
        visual(id, title, "bars", summary, points.toList())

    private fun spark(id: String, title: String, summary: String, vararg values: Double): HeroVisual =
        visual(id, title, "sparkline", summary, values.mapIndexed { index, value -> "W${index + 1}" to value })

    private fun timeline(id: String, title: String, summary: String, vararg points: Pair<String, Double>): HeroVisual =
        visual(id, title, "timeline", summary, points.toList())

    private fun heatmap(id: String, title: String, summary: String, vararg points: Pair<String, Double>): HeroVisual =
        visual(id, title, "heatmap", summary, points.toList())

    private fun funnel(id: String, title: String, summary: String, vararg points: Pair<String, Double>): HeroVisual =
        visual(id, title, "funnel", summary, points.toList())

    private fun ring(id: String, title: String, summary: String, value: Double, unit: String, tone: String): HeroVisual =
        HeroVisual(
            id = id,
            title = title,
            type = "ring",
            summary = summary,
            tone = tone,
            points = listOf(VisualizationPoint("complete", value, tone), VisualizationPoint("remaining", (100.0 - value).coerceAtLeast(0.0), "neutral")),
            value = value.toInt().toString(),
            unit = unit
        )

    private fun lanes(id: String, title: String, summary: String, vararg lanes: Pair<String, String>): HeroVisual =
        HeroVisual(
            id = id,
            title = title,
            type = "lanes",
            summary = summary,
            lanes = lanes.mapIndexed { index, lane -> VisualLane(lane.first, lane.second, if (index == 0) "good" else "neutral") }
        )

    private fun visual(id: String, title: String, type: String, summary: String, points: List<Pair<String, Double>>): HeroVisual =
        HeroVisual(
            id = id,
            title = title,
            type = type,
            summary = summary,
            tone = "good",
            points = points.map { (label, value) -> VisualizationPoint(label, value, toneForValue(value)) }
        )

    private fun moduleHref(modules: List<PortalModule>, component: String): String? =
        modules.firstOrNull { it.component == component }?.href

    private fun modulePresent(modules: List<PortalModule>, component: String): String =
        if (modules.any { it.component == component }) "available" else "not selected"

    private fun moduleTone(modules: List<PortalModule>, component: String): String =
        if (modules.any { it.component == component }) "good" else "attention"

    private fun reportMetric(reports: List<ReportDocument>, reportName: String, key: String, fallback: Int): String {
        val payload = reports.firstOrNull { it.name == reportName }?.payload ?: return fallback.toString()
        val value = payload[key]
        return when (value) {
            is JsonObject -> value.size.toString()
            is JsonPrimitive -> value.contentOrNull ?: fallback.toString()
            else -> fallback.toString()
        }
    }

    private fun densityFor(profileId: String): DashboardDensity = when (profileId) {
        "business-owner", "client" -> DashboardDensity.EXECUTIVE
        "platform-operator-security", "ai-data-analyst" -> DashboardDensity.COCKPIT
        else -> DashboardDensity.OPERATIONAL
    }

    private fun toneForCategory(category: String): String = when (category.lowercase()) {
        "operations", "security" -> "attention"
        "ai", "knowledge", "development" -> "good"
        else -> "neutral"
    }

    private fun toneForValue(value: Double): String = when {
        value >= 60.0 -> "good"
        value >= 20.0 -> "neutral"
        else -> "attention"
    }

    private fun titleize(value: String): String =
        value.replace(Regex("[-_]+"), " ").replaceFirstChar { it.uppercase() }

    private fun now(): String = Instant.now().toString()

    private inline fun <reified T> readJson(path: Path, fallback: T): T =
        try {
            portalJson.decodeFromString<T>(path.readText())
        } catch (_: NoSuchFileException) {
            fallback
        } catch (_: SerializationException) {
            fallback
        } catch (_: IllegalArgumentException) {
            fallback
        }
}

private data class WidgetSpec(
    val title: String,
    val type: String,
    val summary: String,
    val tone: String
)
