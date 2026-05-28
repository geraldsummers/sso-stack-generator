package org.webservices.progression

import kotlinx.serialization.json.Json
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class RegistryLoader(private val json: Json = progressionJson) {
    fun load(config: ProgressionConfig): Registry {
        val root = config.progressionConfigDir
        val taskFiles = root.resolve("tasks").jsonFiles()
        val dashboardFiles = root.resolve("dashboards").jsonFiles()
        val tasks = taskFiles.flatMap { json.decodeFromString<TaskCatalog>(it.readText()).tasks }
        val dashboards = dashboardFiles.flatMap { json.decodeFromString<DashboardCatalog>(it.readText()).dashboards }
        require(tasks.map { it.id }.distinct().size == tasks.size) { "duplicate progression task id" }
        require(dashboards.map { it.id }.distinct().size == dashboards.size) { "duplicate progression dashboard id" }
        return Registry(tasks = tasks, dashboards = dashboards)
    }
}

private fun java.nio.file.Path.jsonFiles() =
    if (!exists()) {
        emptyList()
    } else {
        listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == "json" }
            .sortedBy { it.fileName.toString() }
    }
