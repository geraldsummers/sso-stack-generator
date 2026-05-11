package org.webservices.inferencecontroller

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class InferenceControllerStateStore(
    private val path: Path,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) {
    fun load(): InferenceControllerState {
        if (!path.exists()) {
            return InferenceControllerState()
        }
        return runCatching { json.decodeFromString<InferenceControllerState>(path.readText()) }
            .getOrElse { InferenceControllerState(note = "failed to parse persisted state: ${it.message}") }
    }

    fun save(state: InferenceControllerState) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        path.writeText(json.encodeToString(InferenceControllerState.serializer(), state))
    }
}
