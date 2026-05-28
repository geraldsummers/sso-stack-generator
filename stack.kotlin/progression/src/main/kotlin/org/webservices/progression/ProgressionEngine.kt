package org.webservices.progression

class ProgressionEngine(
    private val registry: Registry,
    private val store: StateStore
) {
    fun view(): ProgressionView {
        val actual = store.readActual()
        val verified = store.readVerified()
        val progress = store.readProgress()
        val claimState = actual.claims + verified.claims
        val taskViews = evaluateTasks(claimState)
        val rewards = taskViews.filter { it.rewardUnlocked }.map { it.reward.name }.distinct()
        return ProgressionView(
            generatedAt = now(),
            primaryNextTask = taskViews.firstOrNull { it.status == "available" },
            tasks = taskViews,
            dashboards = evaluateDashboards(taskViews, claimState),
            rewardsUnlocked = rewards,
            actual = actual,
            verified = verified,
            progress = progress.copy(stackRewards = rewards)
        )
    }

    fun task(id: String): TaskView? = view().tasks.firstOrNull { it.id == id }

    fun dashboard(id: String): DashboardView? = view().dashboards.firstOrNull { it.id == id }

    fun bookstackService(): ServiceView {
        val claims = store.readActual().claims + store.readVerified().claims
        return ServiceView(
            id = "bookstack",
            defined = claims["actual.service.bookstack.defined"] == true,
            routeExists = claims["actual.route.bookstack.exists"] == true,
            route = if (claims["actual.route.bookstack.exists"] == true) "https://bookstack.<domain>" else null,
            stateMapped = claims["actual.persistence.bookstack.volume_mapped"] == true &&
                claims["actual.persistence.bookstack.database_mapped"] == true,
            accessVerified = claims["verified.access.bookstack.route_defined"] == true &&
                claims["verified.access.bookstack.oidc_client_defined"] == true &&
                claims["verified.access.bookstack.oauth_configured"] == true &&
                claims["verified.access.bookstack.anonymous_denied"] == true,
            restoreProven = claims["verified.restore.bookstack.backup_artifact_found"] == true &&
                claims["verified.restore.bookstack.database_imported"] == true &&
                claims["verified.restore.bookstack.healthcheck_passed"] == true &&
                claims["verified.restore.bookstack.cleanup_completed"] == true,
            notes = listOf(
                "BookStack is the first ownership slice because it spans route, identity, database, files, logs, and restore evidence.",
                "Rewards are gated by verified evidence, not by opening this view."
            )
        )
    }

    private fun evaluateTasks(claimState: Map<String, Boolean>): List<TaskView> {
        val completed = mutableSetOf<String>()
        val output = mutableListOf<TaskView>()
        for (task in registry.tasks) {
            val missingEvidence = task.evidence.required.filter { claimState[it] != true }
            val blockedBy = task.requires.filter { it !in completed }
            val evidenceComplete = missingEvidence.isEmpty()
            val status = when {
                evidenceComplete -> "complete"
                blockedBy.isEmpty() -> "available"
                else -> "blocked"
            }
            if (evidenceComplete) {
                completed += task.id
            }
            output += TaskView(
                id = task.id,
                title = task.title,
                summary = task.summary,
                track = task.track,
                stage = task.stage,
                status = status,
                missingEvidence = missingEvidence,
                blockedBy = blockedBy,
                reward = task.reward,
                rewardUnlocked = evidenceComplete,
                surfaces = task.surfaces,
                commands = task.commands,
                riskReduction = task.riskReduction
            )
        }
        return output
    }

    private fun evaluateDashboards(tasks: List<TaskView>, claimState: Map<String, Boolean>): List<DashboardView> {
        val completedTasks = tasks.filter { it.status == "complete" }.map { it.id }.toSet()
        val firstIncomplete = tasks.firstOrNull { it.status != "complete" }
        return registry.dashboards.mapIndexed { index, dashboard ->
            val foregrounded = dashboard.foregroundWhen.isEmpty() && index == 0 ||
                dashboard.foregroundWhen.any { it in completedTasks || claimState[it] == true } ||
                firstIncomplete?.id in dashboard.recommendedAfter
            DashboardView(
                id = dashboard.id,
                title = dashboard.title,
                stage = dashboard.stage,
                density = dashboard.density,
                foregrounded = foregrounded,
                panels = dashboard.panels,
                commands = dashboard.commands,
                forbiddenByDefault = dashboard.forbiddenByDefault
            )
        }
    }
}
