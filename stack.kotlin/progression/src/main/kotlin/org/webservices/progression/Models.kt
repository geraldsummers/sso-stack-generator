package org.webservices.progression

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class TaskCatalog(val tasks: List<TaskDefinition> = emptyList())

@Serializable
data class TaskDefinition(
    val id: String,
    val track: String,
    val stage: String,
    val title: String,
    val summary: String,
    val requires: List<String> = emptyList(),
    val surfaces: TaskSurfaces,
    val commands: TaskCommands = TaskCommands(),
    val evidence: EvidenceRequirement,
    val reward: RewardDefinition,
    val riskReduction: Map<String, String> = emptyMap()
)

@Serializable
data class TaskSurfaces(
    val now: String,
    val why: String,
    val deeper: List<String> = emptyList()
)

@Serializable
data class TaskCommands(
    val guided: List<String> = emptyList(),
    val verify: List<String> = emptyList()
)

@Serializable
data class EvidenceRequirement(
    val required: List<String> = emptyList()
)

@Serializable
data class RewardDefinition(
    val name: String,
    val capability: String
)

@Serializable
data class DashboardCatalog(val dashboards: List<DashboardDefinition> = emptyList())

@Serializable
data class DashboardDefinition(
    val id: String,
    val title: String,
    val stage: String,
    val density: String,
    val foregroundWhen: List<String> = emptyList(),
    val recommendedAfter: List<String> = emptyList(),
    val emotionalTone: List<String> = emptyList(),
    val panels: DashboardPanels = DashboardPanels(),
    val commands: List<String> = emptyList(),
    val forbiddenByDefault: List<String> = emptyList()
)

@Serializable
data class DashboardPanels(
    val beginner: List<String> = emptyList(),
    val operator: List<String> = emptyList(),
    val expert: List<String> = emptyList()
)

@Serializable
data class Registry(
    val tasks: List<TaskDefinition>,
    val dashboards: List<DashboardDefinition>
)

@Serializable
data class StateSnapshot(
    val generatedAt: String = "",
    val claims: Map<String, Boolean> = emptyMap(),
    val facts: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ProgressState(
    val generatedAt: String = "",
    val stackRewards: List<String> = emptyList(),
    val userEvents: List<String> = emptyList()
)

@Serializable
data class EvidenceRecord(
    val id: String,
    val generatedAt: String,
    val summary: String,
    val claims: Map<String, Boolean>,
    val details: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class TaskView(
    val id: String,
    val title: String,
    val summary: String,
    val track: String,
    val stage: String,
    val status: String,
    val missingEvidence: List<String>,
    val blockedBy: List<String>,
    val reward: RewardDefinition,
    val rewardUnlocked: Boolean,
    val surfaces: TaskSurfaces,
    val commands: TaskCommands,
    val riskReduction: Map<String, String>
)

@Serializable
data class DashboardView(
    val id: String,
    val title: String,
    val stage: String,
    val density: String,
    val foregrounded: Boolean,
    val panels: DashboardPanels,
    val commands: List<String>,
    val forbiddenByDefault: List<String>
)

@Serializable
data class ServiceView(
    val id: String,
    val defined: Boolean,
    val routeExists: Boolean,
    val route: String?,
    val stateMapped: Boolean,
    val accessVerified: Boolean,
    val restoreProven: Boolean,
    val notes: List<String>
)

@Serializable
data class ProgressionView(
    val generatedAt: String,
    val primaryNextTask: TaskView?,
    val tasks: List<TaskView>,
    val dashboards: List<DashboardView>,
    val rewardsUnlocked: List<String>,
    val actual: StateSnapshot,
    val verified: StateSnapshot,
    val progress: ProgressState
)

@Serializable
data class CliMessage(
    val status: String,
    val message: String,
    val data: JsonElement? = null
)
