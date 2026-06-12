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
                KpiTile("pipeline", "Pipeline signal", reportMetric(reports, "contracts", "components", modules.size), "Active stack-backed capabilities", "good"),
                KpiTile("receivables", "Receivables view", modulePresent(modules, "erpnext"), "ERPNext finance source", moduleTone(modules, "erpnext")),
                KpiTile("delivery", "Delivery health", "${modules.count { it.category == "Apps" || it.category == "Knowledge" }} areas", "Work, docs, communication, and proof", "good"),
                KpiTile("risk", "Open risk signal", sources.count { it.status != "ok" }.toString(), "Partial/unavailable sources", if (sources.any { it.status != "ok" }) "attention" else "good")
            )
            "platform-operator-security" -> listOf(
                KpiTile("health", "Health sources", "$liveOk/$liveTotal", "Live operational endpoints", if (liveOk == liveTotal) "good" else "attention"),
                KpiTile("routes", "Route-backed modules", modules.count { it.href.startsWith("https://") }.toString(), "Contracted service routes", "good"),
                KpiTile("identity", "Identity surface", modulePresent(modules, "core"), "Keycloak, routes, and policies", moduleTone(modules, "core")),
                KpiTile("access", "Access checks", modules.flatMap { it.evidence }.count { "auth" in it || "access" in it || "route" in it }.toString(), "Access-related evidence", "good")
            )
            "employee" -> listOf(
                KpiTile("mail", "Inbox signal", modulePresent(modules, "sogo"), "Mail and calendar source", moduleTone(modules, "sogo")),
                KpiTile("rooms", "Team rooms", modulePresent(modules, "matrix"), "Element / Matrix collaboration", moduleTone(modules, "matrix")),
                KpiTile("tasks", "Assigned work", modules.count { it.component in setOf("planka", "donetick", "erpnext") }.toString(), "Task and project surfaces", "good"),
                KpiTile("docs", "Knowledge access", modules.count { it.component in setOf("bookstack", "seafile") }.toString(), "Docs and shared files", "good")
            )
            "client" -> listOf(
                KpiTile("files", "Shared files", modulePresent(modules, "seafile"), "Scoped file delivery", moduleTone(modules, "seafile")),
                KpiTile("deliverables", "Deliverables", modules.count { it.component in setOf("planka", "erpnext", "bookstack") }.toString(), "Project, docs, and approvals", "good"),
                KpiTile("meetings", "Meeting trail", modulePresent(modules, "sogo"), "Calendar and email context", moduleTone(modules, "sogo")),
                KpiTile("invoices", "Billing view", modulePresent(modules, "erpnext"), "Invoice and account status", moduleTone(modules, "erpnext"))
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
        "employee" -> VisualLanguage("#37d3b7", "employee work home", "mail-task-docs-rooms", "calm")
        "client" -> VisualLanguage("#76a9fa", "customer portal", "deliverables-files-approvals", "focused")
        "team-lead" -> VisualLanguage("#76a9fa", "delivery control board", "kanban-heatmap-grid", "operational")
        "business-owner" -> VisualLanguage("#81d67a", "executive pulse", "sparkline-funnel-quadrant", "sparse")
        "ai-data-analyst" -> VisualLanguage("#b48cff", "analysis lab", "pipeline-run-queue", "dense")
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
                timeline("workday_flow", "Workday flow", "Mail, meetings, work, docs", "Inbox" to 34.0, "Meetings" to 22.0, "Tasks" to 48.0, "Docs" to 28.0),
                bars("work_queue", "My work queue", "Assigned work by state", "Due today" to 6.0, "Waiting" to 3.0, "Review" to 4.0, "Done" to 12.0),
                lanes("workspace_surfaces", "Workspace surfaces", "Integrated staff tools", "Mail" to "ready", "Rooms" to "3", "Files" to "18", "AI" to "on")
            )
            "client" -> listOf(
                timeline("deliverables", "Deliverables", "Scoped client work", "Draft" to 85.0, "Review" to 62.0, "Approval" to 35.0, "Done" to 18.0),
                bars("client_progress", "Request progress", "Open customer work", "Open" to 8.0, "Doing" to 4.0, "Waiting" to 2.0, "Done" to 15.0),
                lanes("client_pack", "Client pack", "Shared surfaces", "Files" to "12", "Docs" to "6", "Meetings" to "3", "Invoices" to "2")
            )
            "team-lead" -> listOf(
                bars("delivery_flow", "Delivery flow", "Backlog to done", "Backlog" to 18.0, "Doing" to 9.0, "Review" to 6.0, "Done" to 24.0),
                heatmap("blocked_heat", "Blocked work", "Pressure by lane", "API" to 2.0, "Docs" to 1.0, "Client" to 4.0, "Ops" to 1.0),
                lanes("workload", "Team load", "Synthetic capacity", "Alice" to "82%", "Bob" to "64%", "Charlie" to "91%", "Damian" to "58%")
            )
            "business-owner" -> listOf(
                spark("revenue", "Revenue pulse", "Seeded month trend", 48.0, 52.0, 58.0, 55.0, 64.0, 71.0),
                funnel("pipeline", "Pipeline", "Lead to signed", "Leads" to 44.0, "Qualified" to 24.0, "Proposal" to 12.0, "Won" to 5.0),
                heatmap("risk_quad", "Delivery risk", "Where attention goes", "Finance" to 2.0, "Delivery" to 1.0, "Sales" to 3.0, "Ops" to 1.0)
            )
            "ai-data-analyst" -> listOf(
                bars("data_pipeline", "Data pipeline", "Ingest to report", "Sources" to 18.0, "Indexed" to 15.0, "Notebooks" to 7.0, "Reports" to 4.0),
                spark("notebook_runs", "Notebook runs", "Executed outputs", 3.0, 4.0, 6.0, 5.0, 9.0, 8.0),
                lanes("agent_queue", "Agent queue", "Automation-ready work", "Prompts" to "14", "MCP" to "ok", "Workspaces" to "6")
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
                ActionItem("Review backup proof", "Confirm snapshots and restore evidence are current.", "normal", moduleHref(modules, "kopia"))
            )
            "ai-data-analyst" -> listOf(
                ActionItem("Launch workspace", "Start a disposable analysis workspace for current datasets.", "high", moduleHref(modules, "workspace-provisioner")),
                ActionItem("Check search coverage", "Open indexed knowledge and query status.", "normal", moduleHref(modules, "search"))
            )
            "business-owner" -> listOf(
                ActionItem("Read executive brief", "Review high-level delivery, finance, and risk signals.", "normal", moduleHref(modules, "bookstack")),
                ActionItem("Inspect delivery health", "Open projects and active delivery surface.", "normal", moduleHref(modules, "erpnext"))
            )
            "employee" -> listOf(
                ActionItem("Open work home", "Review mail, rooms, assigned tasks, docs, and shared files.", "normal", moduleHref(modules, "sogo")),
                ActionItem("Draft with AI connector", "Use stack context for a safe first draft or task summary.", "normal", moduleHref(modules, "chatgpt-connector"))
            )
            "client" -> listOf(
                ActionItem("Review deliverables", "Open scoped files, docs, requests, approvals, and invoice state.", "normal", moduleHref(modules, "seafile")),
                ActionItem("Check meeting trail", "Review recent meetings and follow-up context.", "normal", moduleHref(modules, "sogo"))
            )
            else -> listOf(
                ActionItem("Open primary module", "Drill into the most relevant live service for this role.", "normal", modules.firstOrNull()?.href),
                ActionItem("Review evidence", "Use dashboard proof and source health before acting.", "normal")
            )
        }
        val missingReports = if (reports.isEmpty()) listOf(ActionItem("Generate contract reports", "No generated reports were mounted for dashboard enrichment.", "normal")) else emptyList()
        return (partial + roleActions + missingReports).distinctBy { it.label }.take(5)
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

    private fun widgetSpec(id: String): WidgetSpec {
        val title = titleize(id)
        return when (id) {
            "service_health", "route_health", "auth_health", "backup_status", "alerts", "test_results" ->
                WidgetSpec(title, "status_matrix", "Live operational signal and proof state.", "attention")
            "revenue", "cash_runway", "receivables", "pipeline", "delivery_health", "executive_brief" ->
                WidgetSpec(title, "kpi", "High-level business signal with drill-through.", "neutral")
            "datasets", "recent_notebooks", "workspace_launcher", "ingestion_jobs", "analysis_prompts", "search_coverage", "agent_runs" ->
                WidgetSpec(title, "activity_feed", "Analysis workspace and knowledge pipeline context.", "good")
            "users", "groups", "failed_logins", "service_policies", "stale_accounts", "vault_entry_points" ->
                WidgetSpec(title, "risk_queue", "Access, identity, and security review surface.", "attention")
            "open_tasks", "assigned_tasks", "my_tasks", "active_work", "blocked_tasks", "overdue_work", "client_tasks", "client_requests" ->
                WidgetSpec(title, "kanban_summary", "Work queue summarized from project/task services.", "good")
            "mailbox", "team_rooms", "meetings", "meeting_history" ->
                WidgetSpec(title, "message_feed", "Mail, calendar, and team room context.", "neutral")
            "recent_files", "client_files", "shared_files", "approval_docs", "approval_queue", "campaign_files", "deliverables", "client_docs", "support_links" ->
                WidgetSpec(title, "table", "Files, docs, and deliverables from shared work surfaces.", "neutral")
            else -> WidgetSpec(title, "table", "Stack-backed signals with safe drill-through metadata.", "neutral")
        }
    }

    private fun preferredHref(widgetId: String, modules: List<PortalModule>): String? = when (widgetId) {
        "service_health", "alerts", "route_health" -> moduleHref(modules, "observability")
        "backup_status", "backup_proof", "restore_drills" -> moduleHref(modules, "kopia")
        "workspace_launcher", "workspace_launch" -> moduleHref(modules, "workspace-provisioner")
        "datasets", "search_gaps", "search_coverage" -> moduleHref(modules, "search")
        "users", "groups", "auth_model" -> moduleHref(modules, "core")
        "vault_entry_points" -> moduleHref(modules, "vaultwarden")
        "invoices", "unpaid_invoices", "revenue", "receivables" -> moduleHref(modules, "erpnext")
        "recent_docs", "client_docs", "public_docs" -> moduleHref(modules, "bookstack")
        "recent_files", "client_files", "shared_files", "deliverables" -> moduleHref(modules, "seafile")
        "mailbox", "meetings", "meeting_history" -> moduleHref(modules, "sogo")
        "team_rooms" -> moduleHref(modules, "matrix")
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
