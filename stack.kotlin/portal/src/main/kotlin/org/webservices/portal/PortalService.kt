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
        return modules.filter { module ->
            module.component in serviceSet || profile.id in module.profiles || compatibilityProfile(profile.id) in module.profiles
        }
    }

    private fun compatibilityProfile(profileId: String): String = when (profileId) {
        "personal", "external-client", "site-ops", "publishing-media" -> "user"
        "platform-operator", "developer-builder", "ai-data-analyst", "knowledge-steward" -> "operator"
        "security-identity-admin", "finance-admin", "business-owner" -> "admin"
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
            "platform-operator" -> listOf(
                KpiTile("health", "Health sources", "$liveOk/$liveTotal", "Live operational endpoints", if (liveOk == liveTotal) "good" else "attention"),
                KpiTile("routes", "Route-backed modules", modules.count { it.href.startsWith("https://") }.toString(), "Contracted service routes", "good"),
                KpiTile("backups", "Backup targets", backupTargets.toString(), "Kopia/report-backed coverage", "good"),
                KpiTile("tests", "Evidence checks", evidenceCount.toString(), "Verification expectations", "good")
            )
            "security-identity-admin" -> listOf(
                KpiTile("auth", "Auth modes", modules.map { it.auth }.toSet().size.toString(), "Distinct access patterns", "attention"),
                KpiTile("users", "Identity surface", modulePresent(modules, "core"), "Keycloak and route policies", moduleTone(modules, "core")),
                KpiTile("vault", "Vault entry point", modulePresent(modules, "vaultwarden"), "No vault contents exposed", moduleTone(modules, "vaultwarden")),
                KpiTile("evidence", "Access checks", modules.flatMap { it.evidence }.count { "auth" in it || "access" in it || "route" in it }.toString(), "Access-related evidence", "good")
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
        "personal" -> VisualLanguage("#37d3b7", "today cockpit", "timeline-ring-pulse", "calm")
        "team-lead" -> VisualLanguage("#76a9fa", "delivery control board", "kanban-heatmap-grid", "operational")
        "project-client-owner" -> VisualLanguage("#8f7cff", "client command center", "timeline-burndown-strip", "operational")
        "business-owner" -> VisualLanguage("#81d67a", "executive pulse", "sparkline-funnel-quadrant", "sparse")
        "finance-admin" -> VisualLanguage("#e6ba5e", "finance operations", "aging-calendar-queue", "precise")
        "sales-relationship" -> VisualLanguage("#f0a15f", "relationship pipeline", "funnel-timeline-stage", "active")
        "knowledge-steward" -> VisualLanguage("#5ad0f0", "knowledge health map", "freshness-coverage-gap", "curatorial")
        "ai-data-analyst" -> VisualLanguage("#b48cff", "analysis lab", "pipeline-run-queue", "dense")
        "developer-builder" -> VisualLanguage("#6dd6a5", "engineering delivery", "flow-runner-release", "dense")
        "platform-operator" -> VisualLanguage("#37d3b7", "ops cockpit", "matrix-coverage-alerts", "cockpit")
        "security-identity-admin" -> VisualLanguage("#ef7d78", "access risk console", "policy-risk-vault", "cockpit")
        "site-ops" -> VisualLanguage("#95d66f", "facilities panel", "sensor-device-calendar", "practical")
        "publishing-media" -> VisualLanguage("#e58bd8", "publishing desk", "calendar-lanes-library", "editorial")
        "external-client" -> VisualLanguage("#76a9fa", "client portal", "deliverable-approval-progress", "focused")
        "reviewer-buyer" -> VisualLanguage("#e6ba5e", "proof dashboard", "catalog-proof-coverage", "evidence")
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
            "personal" -> listOf(
                timeline("today_flow", "Today flow", "Calendar, work, chat, docs", "09:00" to 28.0, "11:30" to 62.0, "14:00" to 38.0, "16:30" to 74.0),
                ring("task_ring", "Task ring", "Done / active / waiting", 72.0, "%", "good"),
                lanes("comm_pulse", "Comms pulse", "Mail and team rooms", "Mail" to "6", "Rooms" to "3", "Mentions" to "2")
            )
            "team-lead" -> listOf(
                bars("delivery_flow", "Delivery flow", "Backlog to done", "Backlog" to 18.0, "Doing" to 9.0, "Review" to 6.0, "Done" to 24.0),
                heatmap("blocked_heat", "Blocked work", "Pressure by lane", "API" to 2.0, "Docs" to 1.0, "Client" to 4.0, "Ops" to 1.0),
                lanes("workload", "Team load", "Synthetic capacity", "Alice" to "82%", "Bob" to "64%", "Charlie" to "91%", "Damian" to "58%")
            )
            "project-client-owner" -> listOf(
                timeline("milestones", "Milestones", "Plan through handoff", "Kickoff" to 100.0, "Build" to 68.0, "Review" to 42.0, "Handoff" to 18.0),
                bars("burndown", "Task burndown", "Open work trend", "Mon" to 31.0, "Tue" to 26.0, "Wed" to 19.0, "Thu" to 14.0),
                lanes("client_assets", "Client assets", "Files, invoices, notes", "Files" to "12", "Invoices" to "3", "Risks" to "2")
            )
            "business-owner" -> listOf(
                spark("revenue", "Revenue pulse", "Seeded month trend", 48.0, 52.0, 58.0, 55.0, 64.0, 71.0),
                funnel("pipeline", "Pipeline", "Lead to signed", "Leads" to 44.0, "Qualified" to 24.0, "Proposal" to 12.0, "Won" to 5.0),
                heatmap("risk_quad", "Delivery risk", "Where attention goes", "Finance" to 2.0, "Delivery" to 1.0, "Sales" to 3.0, "Ops" to 1.0)
            )
            "finance-admin" -> listOf(
                bars("invoice_aging", "Invoice aging", "Receivables by age", "0-7" to 11.0, "8-30" to 7.0, "31-60" to 3.0, "60+" to 1.0),
                timeline("payable_calendar", "Payable calendar", "Upcoming obligations", "Fri" to 3.0, "Mon" to 5.0, "Wed" to 2.0, "Next" to 4.0),
                lanes("approvals", "Approval queue", "Docs needing action", "Purchases" to "4", "Expenses" to "7", "Contracts" to "2")
            )
            "sales-relationship" -> listOf(
                funnel("lead_funnel", "Lead funnel", "Relationship stages", "Leads" to 36.0, "Calls" to 21.0, "Proposals" to 9.0, "Renewals" to 4.0),
                timeline("followups", "Follow-ups", "Contact rhythm", "Today" to 8.0, "Tomorrow" to 5.0, "Week" to 12.0, "Late" to 2.0),
                bars("proposal_stage", "Proposal stages", "Draft to signed", "Draft" to 6.0, "Sent" to 5.0, "Review" to 3.0, "Signed" to 2.0)
            )
            "knowledge-steward" -> listOf(
                heatmap("freshness", "Doc freshness", "Review age by area", "Runbooks" to 1.0, "Client" to 3.0, "Ops" to 2.0, "Sales" to 4.0),
                bars("coverage", "Coverage", "Missing docs by area", "Services" to 19.0, "Restore" to 11.0, "Access" to 15.0, "Handoff" to 8.0),
                timeline("decisions", "Decision log", "Recent captured choices", "Mon" to 3.0, "Tue" to 1.0, "Wed" to 4.0, "Thu" to 2.0)
            )
            "ai-data-analyst" -> listOf(
                bars("data_pipeline", "Data pipeline", "Ingest to report", "Sources" to 18.0, "Indexed" to 15.0, "Notebooks" to 7.0, "Reports" to 4.0),
                spark("notebook_runs", "Notebook runs", "Executed outputs", 3.0, 4.0, 6.0, 5.0, 9.0, 8.0),
                lanes("agent_queue", "Agent queue", "Automation-ready work", "Prompts" to "14", "MCP" to "ok", "Workspaces" to "6")
            )
            "developer-builder" -> listOf(
                spark("repo_activity", "Repo activity", "Commits and reviews", 8.0, 12.0, 7.0, 15.0, 10.0, 18.0),
                bars("flow", "Issue flow", "Work state", "Open" to 16.0, "PR" to 5.0, "Review" to 4.0, "Merged" to 9.0),
                lanes("runner_grid", "Runner grid", "CI capacity", "Runner A" to "online", "Runner B" to "idle", "Workspace" to "ready")
            )
            "platform-operator" -> listOf(
                heatmap("service_matrix", "Service matrix", "Health across surfaces", "Routes" to 1.0, "Auth" to 1.0, "Backups" to 2.0, "Logs" to 1.0),
                bars("coverage", "Coverage", "Contracted proof", "Routes" to modules.count { it.href.startsWith("https://") }.toDouble(), "Backups" to modules.sumOf { it.backupTargets.size }.toDouble(), "Checks" to modules.sumOf { it.evidence.size }.toDouble(), "Sources" to proofOk.toDouble()),
                lanes("alerts", "Alerts", "Severity strip", "Critical" to "0", "Warn" to "${proofTotal - proofOk}", "OK" to "$proofOk")
            )
            "security-identity-admin" -> listOf(
                heatmap("policy_matrix", "Policy matrix", "Auth surface by class", "OIDC" to 2.0, "Forward" to 3.0, "Bearer" to 4.0, "Native" to 2.0),
                spark("failed_logins", "Failed login trend", "Seeded security signal", 2.0, 1.0, 4.0, 2.0, 5.0, 1.0),
                lanes("vault_map", "Vault exposure", "Safe summary only", "Vault" to "sealed", "Groups" to "mapped", "Stale" to "2")
            )
            "site-ops" -> listOf(
                heatmap("sensor_grid", "Sensor grid", "Local status", "Office" to 1.0, "Rack" to 2.0, "Power" to 1.0, "Climate" to 3.0),
                lanes("devices", "Devices", "Offline and routines", "Offline" to "1", "Automations" to "18", "Routines" to "7"),
                timeline("maintenance", "Maintenance", "Upcoming work", "Today" to 2.0, "Week" to 5.0, "Month" to 8.0)
            )
            "publishing-media" -> listOf(
                timeline("publish_calendar", "Publishing calendar", "Draft to public", "Draft" to 7.0, "Edit" to 4.0, "Scheduled" to 3.0, "Live" to 9.0),
                bars("media_mix", "Media library", "Asset composition", "Docs" to 18.0, "Images" to 32.0, "Video" to 7.0, "Training" to 11.0),
                lanes("campaigns", "Campaign files", "Current packets", "Launch" to "ready", "Assets" to "23", "Reviews" to "4")
            )
            "external-client" -> listOf(
                timeline("deliverables", "Deliverables", "Scoped client work", "Draft" to 85.0, "Review" to 62.0, "Approval" to 35.0, "Done" to 18.0),
                bars("client_progress", "Client progress", "Task state", "Open" to 8.0, "Doing" to 4.0, "Waiting" to 2.0, "Done" to 15.0),
                lanes("client_pack", "Client pack", "Shared surfaces", "Files" to "12", "Docs" to "6", "Meetings" to "3")
            )
            "reviewer-buyer" -> listOf(
                heatmap("catalog_matrix", "Catalog matrix", "Modules by proof", "Apps" to 4.0, "AI" to 3.0, "Ops" to 2.0, "Security" to 2.0),
                bars("proof_coverage", "Proof coverage", "What can be inspected", "Auth" to 12.0, "Backup" to 11.0, "Restore" to 7.0, "Screens" to 20.0),
                ring("readiness", "Review readiness", "Demo evidence available", ((proofOk.toDouble() / proofTotal) * 100.0).coerceIn(0.0, 100.0), "%", if (proofOk == proofTotal) "good" else "attention")
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
            "platform-operator" -> listOf(
                ActionItem("Open service health", "Check live dashboards, logs, and recent alert state.", "high", moduleHref(modules, "observability")),
                ActionItem("Review backup proof", "Confirm snapshots and restore evidence are current.", "normal", moduleHref(modules, "kopia"))
            )
            "security-identity-admin" -> listOf(
                ActionItem("Review access boundary", "Check identity, routes, stale accounts, and vault entry points.", "high", moduleHref(modules, "core")),
                ActionItem("Confirm vault health", "Open the vault service without exposing secret contents.", "normal", moduleHref(modules, "vaultwarden"))
            )
            "ai-data-analyst" -> listOf(
                ActionItem("Launch workspace", "Start a disposable analysis workspace for current datasets.", "high", moduleHref(modules, "workspace-provisioner")),
                ActionItem("Check search coverage", "Open indexed knowledge and query status.", "normal", moduleHref(modules, "search"))
            )
            "finance-admin" -> listOf(
                ActionItem("Review finance queue", "Open ERPNext for invoices, payables, and approvals.", "high", moduleHref(modules, "erpnext")),
                ActionItem("Check approval docs", "Review finance handoff files and admin docs.", "normal", moduleHref(modules, "seafile"))
            )
            "business-owner" -> listOf(
                ActionItem("Read executive brief", "Review high-level delivery, finance, and risk signals.", "normal", moduleHref(modules, "bookstack")),
                ActionItem("Inspect delivery health", "Open projects and active delivery surface.", "normal", moduleHref(modules, "erpnext"))
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
            "datasets", "recent_notebooks", "workspace_launcher", "ingestion_jobs", "analysis_prompts" -> modules.filter { it.category in setOf("AI", "Knowledge", "Development") }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "good", it.href)
            }
            "invoices", "unpaid_invoices", "overdue_payments", "payables", "purchase_orders", "receivables", "revenue" -> modules.filter { it.component == "erpnext" || it.component == "seafile" || it.component == "bookstack" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "neutral", it.href)
            }
            "recent_files", "client_files", "approval_docs", "campaign_files" -> modules.filter { it.component == "seafile" || it.component == "onlyoffice" || it.component == "bookstack" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "neutral", it.href)
            }
            "open_tasks", "assigned_tasks", "active_work", "blocked_tasks", "overdue_work", "client_tasks" -> modules.filter { it.component == "planka" || it.component == "donetick" || it.component == "erpnext" }.take(6).map {
                WidgetItem(it.name, it.category, it.description, "good", it.href)
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
            "datasets", "recent_notebooks", "workspace_launcher", "ingestion_jobs", "analysis_prompts" ->
                WidgetSpec(title, "activity_feed", "Analysis workspace and knowledge pipeline context.", "good")
            "users", "groups", "failed_logins", "service_policies", "stale_accounts", "vault_entry_points" ->
                WidgetSpec(title, "risk_queue", "Access, identity, and security review surface.", "attention")
            "open_tasks", "assigned_tasks", "active_work", "blocked_tasks", "overdue_work", "client_tasks" ->
                WidgetSpec(title, "kanban_summary", "Work queue summarized from project/task services.", "good")
            "recent_files", "client_files", "approval_docs", "campaign_files", "deliverables" ->
                WidgetSpec(title, "table", "Files, docs, and deliverables from shared work surfaces.", "neutral")
            else -> WidgetSpec(title, "table", "Stack-backed signals with safe drill-through metadata.", "neutral")
        }
    }

    private fun preferredHref(widgetId: String, modules: List<PortalModule>): String? = when (widgetId) {
        "service_health", "alerts", "route_health" -> moduleHref(modules, "observability")
        "backup_status", "backup_proof", "restore_drills" -> moduleHref(modules, "kopia")
        "workspace_launcher", "workspace_launch" -> moduleHref(modules, "workspace-provisioner")
        "datasets", "search_gaps" -> moduleHref(modules, "search")
        "users", "groups", "auth_model" -> moduleHref(modules, "core")
        "vault_entry_points" -> moduleHref(modules, "vaultwarden")
        "invoices", "unpaid_invoices", "revenue", "receivables" -> moduleHref(modules, "erpnext")
        "recent_docs", "client_docs", "public_docs" -> moduleHref(modules, "bookstack")
        "recent_files", "client_files", "deliverables" -> moduleHref(modules, "seafile")
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
        "business-owner", "external-client", "reviewer-buyer" -> DashboardDensity.EXECUTIVE
        "platform-operator", "security-identity-admin", "developer-builder", "ai-data-analyst" -> DashboardDensity.COCKPIT
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
