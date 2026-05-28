package org.webservices.progression

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

val progressionJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class StateStore(private val runtimeDir: Path, private val json: Json = progressionJson) {
    private val evidenceDir = runtimeDir.resolve("evidence")

    fun readActual(): StateSnapshot = readSnapshot("actual-state.json")

    fun readDesired(): StateSnapshot = readSnapshot("desired-state.json")

    fun readVerified(): StateSnapshot = readSnapshot("verified-state.json")

    fun readProgress(): ProgressState {
        val path = runtimeDir.resolve("progress-state.json")
        return if (path.exists()) {
            json.decodeFromString(path.readText())
        } else {
            ProgressState(generatedAt = now())
        }
    }

    fun mergeActual(claims: Map<String, Boolean>, facts: Map<String, JsonElement> = emptyMap()): StateSnapshot {
        val existing = readActual()
        val snapshot = StateSnapshot(
            generatedAt = now(),
            claims = existing.claims + claims,
            facts = existing.facts + facts
        )
        write("actual-state.json", json.encodeToString(snapshot))
        return snapshot
    }

    fun mergeVerified(claims: Map<String, Boolean>, facts: Map<String, JsonElement> = emptyMap()): StateSnapshot {
        val existing = readVerified()
        val snapshot = StateSnapshot(
            generatedAt = now(),
            claims = existing.claims + claims,
            facts = existing.facts + facts
        )
        write("verified-state.json", json.encodeToString(snapshot))
        return snapshot
    }

    fun writeEvidence(record: EvidenceRecord): EvidenceRecord {
        evidenceDir.createDirectories()
        evidenceDir.resolve("${record.id}.json").writeText(json.encodeToString(record))
        mergeVerified(record.claims, mapOf("evidence.${record.id}" to JsonObject(record.details)))
        return record
    }

    fun readEvidence(id: String): EvidenceRecord? {
        val path = evidenceDir.resolve("$id.json")
        return if (path.exists()) json.decodeFromString(path.readText()) else null
    }

    fun listEvidence(): List<EvidenceRecord> =
        if (!evidenceDir.exists()) {
            emptyList()
        } else {
            evidenceDir.listDirectoryEntries("*.json")
                .sortedBy { it.fileName.toString() }
                .mapNotNull { runCatching { json.decodeFromString<EvidenceRecord>(it.readText()) }.getOrNull() }
        }

    private fun readSnapshot(name: String): StateSnapshot {
        val path = runtimeDir.resolve(name)
        return if (path.exists()) {
            json.decodeFromString(path.readText())
        } else {
            StateSnapshot(generatedAt = now())
        }
    }

    private fun write(name: String, content: String) {
        runtimeDir.createDirectories()
        runtimeDir.resolve(name).writeText(content)
    }
}

fun now(): String = Instant.now().toString()
