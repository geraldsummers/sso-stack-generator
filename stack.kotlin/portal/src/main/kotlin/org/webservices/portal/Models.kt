package org.webservices.portal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ServiceContracts(
    val contractVersion: Int = 1,
    val components: Map<String, ComponentContract> = emptyMap()
)

@Serializable
data class ComponentContract(
    val name: String = "",
    val description: String = "",
    val moduleType: String = "service",
    val primaryHost: String = "",
    val auth: AuthContract = AuthContract(),
    val state: StateContract = StateContract(),
    val backup: PolicyContract = PolicyContract(),
    val restore: RestoreContract = RestoreContract(),
    val observability: ObservabilityContract = ObservabilityContract(),
    val evidence: EvidenceContract = EvidenceContract(),
    val screenshots: List<String> = emptyList(),
    val portal: PortalContract = PortalContract(),
    val slo: SloContract = SloContract(),
    val access: AccessContract = AccessContract(),
    val artifacts: ArtifactContract = ArtifactContract()
)

@Serializable
data class AuthContract(val mode: String = "unknown")

@Serializable
data class StateContract(
    val mode: String = "",
    val stores: List<String> = emptyList()
)

@Serializable
data class PolicyContract(
    val policy: String = "",
    val targets: List<String> = emptyList()
)

@Serializable
data class RestoreContract(val drills: List<String> = emptyList())

@Serializable
data class ObservabilityContract(
    val health: Boolean = false,
    val logs: Boolean = false,
    val metrics: Boolean = false
)

@Serializable
data class EvidenceContract(val expectations: List<String> = emptyList())

@Serializable
data class PortalContract(
    val visible: Boolean = false,
    val hrefHost: String = "",
    val path: String = "",
    val category: String = "Platform",
    val audience: String = "employee",
    val profiles: List<String> = emptyList(),
    val description: String = ""
)

@Serializable
data class SloContract(
    val availability: String = "",
    val latencyMs: Int? = null
)

@Serializable
data class AccessContract(
    val offboarding: String = "",
    val owner: String = ""
)

@Serializable
data class ArtifactContract(val json: List<String> = emptyList())

@Serializable
data class ComponentLock(val components: List<String> = emptyList())

@Serializable
data class PortalProfiles(
    val schemaVersion: Int = 1,
    val defaultProfile: String = "employee",
    val profiles: List<ProfileDefinition> = emptyList()
)

@Serializable
data class ProfileDefinition(
    val id: String,
    val name: String,
    val defaultView: String = "",
    val purpose: String = "",
    val widgets: List<String> = emptyList(),
    val services: List<String> = emptyList()
)

@Serializable
data class PortalModule(
    val component: String,
    val name: String,
    val description: String,
    val category: String,
    val audience: String,
    val href: String,
    val auth: String,
    val profiles: List<String>,
    val evidence: List<String>,
    val screenshots: List<String>,
    val slo: SloContract,
    val stateStores: List<String>,
    val backupTargets: List<String>,
    val restoreDrills: List<String>,
    val owner: String
)

@Serializable
data class ProfileSummary(
    val profile: String,
    val name: String,
    val defaultView: String,
    val purpose: String,
    val widgets: List<String>,
    val services: List<String>,
    val moduleCount: Int,
    val modules: List<String>
)

@Serializable
data class DashboardResponse(
    val profile: DashboardProfile,
    val status: DashboardStatus,
    val visualLanguage: VisualLanguage,
    val kpis: List<KpiTile>,
    val heroVisuals: List<HeroVisual>,
    val widgets: List<DashboardWidget>,
    val visualizations: List<DashboardVisualization>,
    val actions: List<ActionItem>,
    val modules: List<PortalModule>,
    val evidence: List<EvidenceItem>,
    val sources: List<SourceStatus>
)

@Serializable
data class DashboardProfile(
    val id: String,
    val name: String,
    val purpose: String,
    val defaultView: String,
    val density: DashboardDensity
)

@Serializable
enum class DashboardDensity {
    @SerialName("executive")
    EXECUTIVE,

    @SerialName("operational")
    OPERATIONAL,

    @SerialName("cockpit")
    COCKPIT
}

@Serializable
data class DashboardStatus(
    val overall: String,
    val updatedAt: String,
    val partialSources: List<String> = emptyList()
)

@Serializable
data class KpiTile(
    val id: String,
    val label: String,
    val value: String,
    val detail: String,
    val tone: String = "neutral",
    val href: String? = null
)

@Serializable
data class VisualLanguage(
    val accent: String,
    val motif: String,
    val layout: String,
    val density: String
)

@Serializable
data class HeroVisual(
    val id: String,
    val title: String,
    val type: String,
    val summary: String,
    val tone: String = "neutral",
    val points: List<VisualizationPoint> = emptyList(),
    val lanes: List<VisualLane> = emptyList(),
    val value: String = "",
    val unit: String = ""
)

@Serializable
data class VisualLane(
    val label: String,
    val value: String,
    val tone: String = "neutral"
)

@Serializable
data class DashboardWidget(
    val id: String,
    val title: String,
    val type: String,
    val summary: String,
    val tone: String = "neutral",
    val items: List<WidgetItem> = emptyList(),
    val href: String? = null
)

@Serializable
data class WidgetItem(
    val label: String,
    val value: String = "",
    val detail: String = "",
    val tone: String = "neutral",
    val href: String? = null
)

@Serializable
data class DashboardVisualization(
    val id: String,
    val title: String,
    val type: String,
    val summary: String,
    val points: List<VisualizationPoint> = emptyList(),
    val href: String? = null
)

@Serializable
data class VisualizationPoint(
    val label: String,
    val value: Double,
    val tone: String = "neutral"
)

@Serializable
data class ActionItem(
    val label: String,
    val detail: String,
    val priority: String = "normal",
    val href: String? = null
)

@Serializable
data class EvidenceItem(
    val label: String,
    val status: String,
    val source: String,
    val detail: String = "",
    val href: String? = null
)

@Serializable
data class SourceStatus(
    val id: String,
    val label: String,
    val status: String,
    val updatedAt: String,
    val summary: String,
    val errors: List<String> = emptyList()
)

@Serializable
data class ReportDocument(
    val name: String,
    val path: String,
    val payload: JsonObject? = null,
    val error: String? = null
)

@Serializable
data class IntegrationStatusResponse(
    val generatedAt: String,
    val sources: List<SourceStatus>
)

@Serializable
data class RawReportsResponse(
    val generatedAt: String,
    val reports: List<ReportDocument>
)

@Serializable
data class HealthResponse(val status: String)
