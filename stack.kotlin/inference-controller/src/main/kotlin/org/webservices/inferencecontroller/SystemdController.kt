package org.webservices.inferencecontroller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

interface SystemdController {
    suspend fun startUnit(unitName: String)
    suspend fun stopUnit(unitName: String)
    suspend fun inspectUnit(unitName: String): UnitStatus
}

data class UnitStatus(
    val activeState: String,
    val subState: String
) {
    val running: Boolean = activeState == "active" && subState == "running"
    val transitioning: Boolean = activeState == "activating" || subState in setOf("start", "stop-sigterm", "auto-restart")
}

class BusctlSystemdController(
    private val dbusAddress: String
) : SystemdController {
    override suspend fun startUnit(unitName: String) {
        busctl(
            "call",
            "org.freedesktop.systemd1",
            "/org/freedesktop/systemd1",
            "org.freedesktop.systemd1.Manager",
            "StartUnit",
            "ss",
            unitName,
            "replace"
        ).requireSuccess("start $unitName")
    }

    override suspend fun stopUnit(unitName: String) {
        busctl(
            "call",
            "org.freedesktop.systemd1",
            "/org/freedesktop/systemd1",
            "org.freedesktop.systemd1.Manager",
            "StopUnit",
            "ss",
            unitName,
            "replace"
        ).requireSuccess("stop $unitName")
    }

    override suspend fun inspectUnit(unitName: String): UnitStatus {
        val unitPathResult = busctl(
            "call",
            "org.freedesktop.systemd1",
            "/org/freedesktop/systemd1",
            "org.freedesktop.systemd1.Manager",
            "GetUnit",
            "s",
            unitName,
            allowFailure = true
        )
        if (unitPathResult.exitCode != 0) {
            return UnitStatus(activeState = "inactive", subState = "dead")
        }
        val unitPath = Regex("""o \"([^\"]+)\"""").find(unitPathResult.stdout)?.groupValues?.getOrNull(1)
            ?: return UnitStatus(activeState = "unknown", subState = "unknown")
        val activeState = busctl(
            "get-property",
            "org.freedesktop.systemd1",
            unitPath,
            "org.freedesktop.systemd1.Unit",
            "ActiveState"
        ).requireSuccess("get ActiveState for $unitName").stdout.parsePropertyValue()
        val subState = busctl(
            "get-property",
            "org.freedesktop.systemd1",
            unitPath,
            "org.freedesktop.systemd1.Unit",
            "SubState"
        ).requireSuccess("get SubState for $unitName").stdout.parsePropertyValue()
        return UnitStatus(activeState = activeState, subState = subState)
    }

    private suspend fun busctl(vararg args: String, allowFailure: Boolean = false): CommandResult = withContext(Dispatchers.IO) {
        val command = listOf("busctl", "--address=$dbusAddress", "--user") + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        process.inputStream.use { it.copyTo(stdout) }
        process.errorStream.use { it.copyTo(stderr) }
        val exitCode = process.waitFor()
        val result = CommandResult(exitCode, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        if (!allowFailure && exitCode != 0) {
            error("busctl command failed (${command.joinToString(" ")}): ${result.stderr.ifBlank { result.stdout }}")
        }
        result
    }
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    fun requireSuccess(action: String): CommandResult {
        require(exitCode == 0) { "$action failed: ${stderr.ifBlank { stdout }}" }
        return this
    }
}

private fun String.parsePropertyValue(): String {
    val trimmed = trim()
    return when {
        trimmed.startsWith("s ") -> trimmed.removePrefix("s ").trim().trim('"')
        else -> trimmed
    }
}
