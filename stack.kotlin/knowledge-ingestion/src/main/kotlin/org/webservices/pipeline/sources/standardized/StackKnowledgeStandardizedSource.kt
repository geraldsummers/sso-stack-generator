package org.webservices.pipeline.sources.standardized

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.webservices.pipeline.core.Chunkable
import org.webservices.pipeline.core.StandardizedSource
import org.webservices.pipeline.scheduling.BackfillStrategy
import org.webservices.pipeline.scheduling.ResyncStrategy
import org.webservices.pipeline.scheduling.RunMetadata
import java.io.File
import java.security.MessageDigest

data class StackKnowledgeChunk(
    val documentId: String,
    val title: String,
    val section: String,
    val filepath: String,
    val content: String,
    val chunkIndex: Int,
    val totalChunks: Int
) : Chunkable {
    override fun toText(): String = buildString {
        appendLine(title)
        if (section.isNotBlank() && section != title) {
            appendLine(section)
        }
        appendLine()
        append(content)
    }

    override fun getId(): String = documentId

    override fun getMetadata(): Map<String, String> = mapOf(
        "title" to title,
        "section" to section,
        "filepath" to filepath,
        "path" to filepath,
        "source" to "stack_knowledge",
        "content_type" to "stack_documentation",
        "audience" to "agent",
        "chunk_index" to chunkIndex.toString(),
        "total_chunks" to totalChunks.toString()
    )
}

class StackKnowledgeStandardizedSource(
    private val knowledgePath: String = "/configs/stack-knowledge",
    private val scheduleMinutes: Int = 60
) : StandardizedSource<StackKnowledgeChunk> {
    override val name = "stack_knowledge"

    override fun resyncStrategy() = ResyncStrategy.Hourly(minute = scheduleMinutes % 60)

    override fun backfillStrategy() = BackfillStrategy.FilesystemScan(paths = listOf(knowledgePath))

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<StackKnowledgeChunk> {
        val root = File(knowledgePath)
        if (!root.isDirectory) {
            return emptyList<StackKnowledgeChunk>().asFlow()
        }

        return root.listFiles { file ->
            file.isFile && file.extension == "md" && file.name.matches(Regex("""\d+-.*\.md"""))
        }
            ?.sortedBy { it.name }
            ?.flatMap(::parseMarkdown)
            .orEmpty()
            .asFlow()
    }

    private fun parseMarkdown(file: File): List<StackKnowledgeChunk> {
        val lines = file.readLines()
        val fileTitle = file.nameWithoutExtension
            .replace(Regex("""^\d+-"""), "")
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

        val sections = mutableListOf<Pair<String, String>>()
        var currentSection = fileTitle
        val currentContent = StringBuilder()

        fun flush() {
            val content = currentContent.toString().trim()
            if (content.isNotBlank()) {
                sections += currentSection to content
            }
            currentContent.clear()
        }

        for (line in lines) {
            when {
                line.startsWith("# ") -> {
                    flush()
                    currentSection = line.removePrefix("# ").trim().ifBlank { fileTitle }
                }
                line.startsWith("## ") -> {
                    flush()
                    currentSection = line.removePrefix("## ").trim().ifBlank { fileTitle }
                    currentContent.appendLine(line)
                }
                else -> currentContent.appendLine(line)
            }
        }
        flush()

        val total = sections.size.coerceAtLeast(1)
        return sections.mapIndexed { index, (section, content) ->
            val digest = sha256("${file.name}:$section:$content").take(16)
            StackKnowledgeChunk(
                documentId = "stack_knowledge:${file.name}:$index:$digest",
                title = fileTitle,
                section = section,
                filepath = file.name,
                content = content,
                chunkIndex = index,
                totalChunks = total
            )
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
