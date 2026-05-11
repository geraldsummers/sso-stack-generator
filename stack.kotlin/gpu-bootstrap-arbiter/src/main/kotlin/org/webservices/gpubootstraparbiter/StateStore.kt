package org.webservices.gpubootstraparbiter

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ArbiterStateStore(
    private val path: Path,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) {
    fun load(): ArbiterState {
        if (!path.exists()) {
            return ArbiterState()
        }
        return runCatching { json.decodeFromString<ArbiterState>(path.readText()) }
            .getOrElse { ArbiterState(note = "failed to parse persisted state: ${it.message}") }
    }

    fun save(state: ArbiterState) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        path.writeText(json.encodeToString(ArbiterState.serializer(), state))
    }
}
