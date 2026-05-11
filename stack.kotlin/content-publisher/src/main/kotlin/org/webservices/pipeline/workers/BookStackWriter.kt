package org.webservices.pipeline.workers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.webservices.pipeline.core.BookStackHtmlHelper
import org.webservices.pipeline.sinks.BookStackDocument
import org.webservices.pipeline.sinks.BookStackSink
import org.webservices.pipeline.sinks.QdrantPublicationSync
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.StagedDocument
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private const val RSS_BOOKSTACK_BODY_MAX_CHARS = 6_000
private const val WIKIPEDIA_BOOKSTACK_BODY_MAX_CHARS = 7_000
private const val AUSTRALIAN_LAWS_BOOKSTACK_BODY_MAX_CHARS = 8_000
private const val LINUX_DOCS_BOOKSTACK_BODY_MAX_CHARS = 8_000
private const val WIKI_BOOKSTACK_BODY_MAX_CHARS = 7_000
private const val CVE_BOOKSTACK_BODY_MAX_CHARS = 6_000
private const val GENERIC_BOOKSTACK_BODY_MAX_CHARS = 6_000


class BookStackWriter(
    private val stagingStore: DocumentStagingStore,
    private val bookStackSink: BookStackSink,
    private val qdrantPublicationSync: QdrantPublicationSync? = null,
    private val pollIntervalSeconds: Long = 5,
    private val batchSize: Int = 200,
    private val maxRetries: Int = 3,
    private val maxConcurrentWrites: Int = 1,
    private val allowedSources: Set<String> = emptySet(),
    private val publicationBackfillLimit: Int = 5000,
    private val publicationBackfillRetrySeconds: Long = 30,
    private val reconciliationBatchSize: Int = 200,
    private val reconciliationIntervalSeconds: Long = 300
) {
    private var publicationBackfillPending = qdrantPublicationSync != null
    private var nextPublicationBackfillAttemptAt = 0L
    private var nextReconciliationAttemptAt = 0L

    suspend fun syncExistingPublishedDocuments(limit: Int = publicationBackfillLimit): Boolean {
        val publishedDocs = stagingStore.getPublishedForQdrantSync(limit = limit, allowedSources = allowedSources)
        if (publishedDocs.isEmpty()) {
            logger.info { "No published BookStack docs require Qdrant sync backfill" }
            publicationBackfillPending = false
            return true
        }

        val syncer = qdrantPublicationSync
        if (syncer == null) {
            logger.info { "Qdrant publication sync disabled; skipping backfill for ${publishedDocs.size} published docs" }
            publicationBackfillPending = false
            return true
        }

        logger.info { "Backfilling Qdrant publication payloads for ${publishedDocs.size} published docs" }
        return try {
            syncer.syncPublishedDocuments(publishedDocs)
            publicationBackfillPending = false
            logger.info { "Completed Qdrant publication payload backfill for ${publishedDocs.size} docs" }
            true
        } catch (e: Exception) {
            publicationBackfillPending = true
            nextPublicationBackfillAttemptAt = System.currentTimeMillis() + (publicationBackfillRetrySeconds * 1000)
            logger.warn(e) {
                "Failed Qdrant publication payload backfill for ${publishedDocs.size} docs; retrying in ${publicationBackfillRetrySeconds}s"
            }
            false
        }
    }

    suspend fun start() {
        logger.info { "BookStack writer starting (poll interval: ${pollIntervalSeconds}s, batch size: $batchSize)" }

        while (true) {
            try {
                maybeSyncExistingPublishedDocuments()
                maybeReconcilePublicationState()

                val pendingDocs = stagingStore.getPendingForBookStack(
                    limit = batchSize,
                    allowedSources = allowedSources
                )

                if (pendingDocs.isEmpty()) {
                    logger.debug { "No pending BookStack writes" }
                    delay(pollIntervalSeconds.seconds)
                    continue
                }

                val allowedDocs = pendingDocs.filter { isSourceAllowed(it.source) }
                val skippedDocs = pendingDocs - allowedDocs
                if (skippedDocs.isNotEmpty()) {
                    skippedDocs.forEach { doc ->
                        stagingStore.updateBookStackUrl(doc.id, "skipped://source-filter/${doc.source}")
                        stagingStore.markBookStackComplete(doc.id)
                    }
                    logger.info { "Skipped ${skippedDocs.size} docs due to BOOKSTACK_ALLOWED_SOURCES source filter" }
                }

                if (allowedDocs.isEmpty()) {
                    delay(pollIntervalSeconds.seconds)
                    continue
                }

                logger.info {
                    "Publishing ${allowedDocs.size} docs to BookStack" +
                        if (allowedSources.isEmpty()) "" else " (allowed sources: ${allowedSources.joinToString(",")})"
                }


                val writeSemaphore = Semaphore(maxConcurrentWrites.coerceAtLeast(1))
                coroutineScope {
                    allowedDocs.map { doc ->
                        async {
                            writeSemaphore.withPermit {
                                try {
                                    // Check if document has exceeded retry limit
                                    if (doc.retryCount >= maxRetries) {
                                        logger.warn { "Document ${doc.id} exceeded max BookStack retries (${doc.retryCount}/$maxRetries), skipping" }
                                        return@async
                                    }

                                    // Apply exponential backoff if this is a retry
                                    if (doc.retryCount > 0) {
                                        val backoffSeconds = 2.0.pow(doc.retryCount.toDouble()).toLong()
                                        logger.debug { "Applying backoff for ${doc.id}: ${backoffSeconds}s (retry ${doc.retryCount}/$maxRetries)" }
                                        delay(backoffSeconds * 1000)
                                    }

                                    val bookStackDoc = toBookStackDocument(doc)

                                    bookStackSink.write(bookStackDoc)

                                    val pageUrl = bookStackSink.getLastPageUrl(bookStackDoc.pageTitle)

                                    if (pageUrl != null) {
                                        stagingStore.updateBookStackUrl(doc.id, pageUrl)
                                        try {
                                            qdrantPublicationSync?.markPublished(doc, pageUrl)
                                        } catch (e: Exception) {
                                            requestPublicationBackfillRetry()
                                            logger.warn(e) { "Failed to sync Qdrant publication payload for ${doc.collection}/${doc.id}" }
                                        }
                                    }

                                    stagingStore.markBookStackComplete(doc.id)

                                    logger.debug { "Wrote document ${doc.id} to BookStack: $pageUrl" }

                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to write document ${doc.id} to BookStack (attempt ${doc.retryCount + 1}/$maxRetries): ${e.message}" }

                                    stagingStore.markBookStackFailed(doc.id, e.message ?: "Unknown error")
                                }
                            }
                        }
                    }.awaitAll()
                }

            } catch (e: Exception) {
                logger.error(e) { "Error in BookStack writer loop: ${e.message}" }
            }

            
            delay(pollIntervalSeconds.seconds)
        }
    }

    private suspend fun maybeSyncExistingPublishedDocuments() {
        if (!publicationBackfillPending || qdrantPublicationSync == null) {
            return
        }

        if (System.currentTimeMillis() < nextPublicationBackfillAttemptAt) {
            return
        }

        syncExistingPublishedDocuments()
    }

    suspend fun reconcilePublicationState(limit: Int = reconciliationBatchSize): Boolean {
        val candidates = stagingStore.getBookStackReconciliationCandidates(limit = limit, allowedSources = allowedSources)
        val publishedCandidates = stagingStore.getPublishedBookStackValidationCandidates(
            limit = limit,
            allowedSources = allowedSources
        )
        if (candidates.isEmpty() && publishedCandidates.isEmpty()) {
            logger.debug { "No BookStack reconciliation candidates" }
            return true
        }

        logger.info {
            "Reconciling ${candidates.size} unresolved and ${publishedCandidates.size} published BookStack publication rows"
        }

        var reconciledCount = 0
        candidates.forEach { doc ->
            try {
                val pageUrl = bookStackSink.reconcilePageUrl(toBookStackDocument(doc)) ?: return@forEach
                stagingStore.updateBookStackUrl(doc.id, pageUrl)
                try {
                    qdrantPublicationSync?.markPublished(doc, pageUrl)
                } catch (e: Exception) {
                    requestPublicationBackfillRetry()
                    logger.warn(e) { "Failed to sync Qdrant publication payload during reconciliation for ${doc.collection}/${doc.id}" }
                }
                stagingStore.markBookStackComplete(doc.id)
                reconciledCount += 1
            } catch (e: Exception) {
                logger.warn(e) { "Failed to reconcile BookStack state for ${doc.id}: ${e.message}" }
            }
        }

        var refreshedCount = 0
        var resetCount = 0
        publishedCandidates.forEach { doc ->
            try {
                val currentPageUrl = bookStackSink.reconcilePageUrl(toBookStackDocument(doc))
                if (currentPageUrl == null) {
                    bookStackSink.clearLocationCache()
                    stagingStore.resetBookStackPublication(
                        doc.id,
                        "stored URL no longer resolves in BookStack: ${doc.bookstackUrl.orEmpty()}"
                    )
                    resetCount += 1
                    return@forEach
                }

                if (currentPageUrl != doc.bookstackUrl) {
                    stagingStore.updateBookStackUrl(doc.id, currentPageUrl)
                    try {
                        qdrantPublicationSync?.markPublished(doc, currentPageUrl)
                    } catch (e: Exception) {
                        requestPublicationBackfillRetry()
                        logger.warn(e) {
                            "Failed to sync Qdrant publication payload during published URL refresh for ${doc.collection}/${doc.id}"
                        }
                    }
                    refreshedCount += 1
                } else {
                    stagingStore.markBookStackPublicationValidated(doc.id)
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to validate published BookStack state for ${doc.id}: ${e.message}" }
            }
        }

        logger.info {
            "BookStack reconciliation repaired $reconciledCount/${candidates.size} unresolved rows, " +
                "refreshed $refreshedCount/${publishedCandidates.size} published rows, " +
                "reset $resetCount stale published rows"
        }
        return true
    }

    private suspend fun maybeReconcilePublicationState() {
        if (System.currentTimeMillis() < nextReconciliationAttemptAt) {
            return
        }
        reconcilePublicationState()
        nextReconciliationAttemptAt = System.currentTimeMillis() + (reconciliationIntervalSeconds * 1000)
    }

    private fun requestPublicationBackfillRetry() {
        publicationBackfillPending = true
        val retryAt = System.currentTimeMillis() + (publicationBackfillRetrySeconds * 1000)
        if (nextPublicationBackfillAttemptAt == 0L || retryAt < nextPublicationBackfillAttemptAt) {
            nextPublicationBackfillAttemptAt = retryAt
        }
    }

    private fun isSourceAllowed(source: String): Boolean {
        if (allowedSources.isEmpty()) return true
        return source in allowedSources
    }

    
    private fun toBookStackDocument(doc: StagedDocument): BookStackDocument {
        return when (doc.source) {
            "rss" -> buildRssDocument(doc)
            "torrents" -> buildTorrentDocument(doc)
            "wikipedia" -> buildWikipediaDocument(doc)
            "australian_laws" -> buildAustralianLegalDocument(doc)
            "linux_docs" -> buildLinuxDocDocument(doc)
            "debian_wiki" -> buildWikiDocument(doc, wikiName = "Debian Wiki", accentColor = "#D70A53")
            "arch_wiki" -> buildWikiDocument(doc, wikiName = "Arch Wiki", accentColor = "#1793D1")
            "cve" -> buildCveDocument(doc)
            "agent_docs" -> buildGenericDocument(doc, bookName = "Agent Docs")
            else -> buildGenericDocument(doc, bookName = "Knowledge Base")
        }
    }

    
    private fun buildTags(doc: StagedDocument): Map<String, String> {
        val tags = mutableMapOf<String, String>()

        
        tags["source"] = doc.source

        
        tags["collection"] = doc.collection

        
        val chunkIndex = doc.chunkIndex
        val totalChunks = doc.totalChunks
        if (chunkIndex != null && totalChunks != null) {
            tags["chunk"] = "${chunkIndex + 1}/${totalChunks}"
        }

        
        (doc.metadata["url"] ?: doc.metadata["link"])?.let { tags["url"] = it }

        
        (doc.metadata["published_at"] ?: doc.metadata["published_date"])?.let { tags["published"] = it }

        
        doc.metadata["author"]?.let { tags["author"] = it }

        
        doc.metadata["feed_title"]?.let { tags["feed"] = it }

        
        doc.metadata["infohash"]?.let { tags["infohash"] = it }

        
        doc.metadata["jurisdiction"]?.let { tags["jurisdiction"] = it }
        doc.metadata["type"]?.let { tags["type"] = it }
        doc.metadata["section"]?.let { tags["section"] = it }
        doc.metadata["citation"]?.let { tags["citation"] = it }
        doc.metadata["severity"]?.let { tags["severity"] = it }
        doc.metadata["cvss_severity"]?.takeIf { "severity" !in tags }?.let { tags["severity"] = it }
        doc.metadata["cveId"]?.let { tags["cve_id"] = it }
        doc.metadata["cve_id"]?.takeIf { "cve_id" !in tags }?.let { tags["cve_id"] = it }

        
        doc.metadata["wikiType"]?.let { tags["wiki_type"] = it }
        doc.metadata["categories"]?.let { tags["categories"] = it }

        return tags
    }

    private fun buildRssDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val title = preferredTitle(metadata["title"], doc.id, doc)
        val feedTitle = metadata["feed_title"]?.ifBlank { null } ?: "RSS Feed"
        val feedUrl = metadata["feed_url"]?.ifBlank { null }
        val link = metadata["link"]?.ifBlank { null }
        val published = metadata["published_date"]?.ifBlank { null } ?: metadata["published_at"]?.ifBlank { null }
        val author = metadata["author"]?.ifBlank { null }
        val categories = metadata["categories"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val description = metadata["description"]?.ifBlank { null }

        val metadataItems = buildList {
            add("Feed" to formatLink(feedUrl ?: "", feedTitle, fallback = feedTitle))
            if (link != null) add("Article" to formatLink(link, "View original"))
            if (published != null) add("Published" to BookStackHtmlHelper.sanitizeForHtml(published))
            if (author != null) add("Author" to BookStackHtmlHelper.sanitizeForHtml(author))
            if (categories.isNotEmpty()) add("Categories" to BookStackHtmlHelper.sanitizeForHtml(categories.joinToString(", ")))
        }

        val contentBody = extractRssBody(doc.text)
        val contentSection = if (contentBody.isNotBlank()) {
            formatParagraphSection("Content", contentBody, RSS_BOOKSTACK_BODY_MAX_CHARS)
        } else ""

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                description?.let { formatSection("Summary", "<p>${BookStackHtmlHelper.sanitizeForHtml(it)}</p>") },
                contentSection.ifBlank { null }
            ).joinToString("\n"),
            footerSource = "RSS feed ${feedTitle}"
        )

        return BookStackDocument(
            bookName = "RSS Articles",
            bookDescription = "Aggregated RSS articles and feeds",
            chapterName = feedTitle,
            chapterDescription = feedUrl?.let { "Articles from $it" },
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc) + mapOf(
                "feed" to feedTitle
            )
        )
    }

    private fun buildTorrentDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val infohash = metadata["infohash"]?.ifBlank { null }
        val name = metadata["name"]?.ifBlank { null } ?: infohash ?: doc.id
        val seeders = metadata["seeders"]?.ifBlank { null }
        val leechers = metadata["leechers"]?.ifBlank { null }
        val sizeBytes = metadata["sizeBytes"]?.toLongOrNull()

        val metadataItems = buildList {
            if (infohash != null) add("Infohash" to formatInlineCode(infohash))
            if (sizeBytes != null) add("Size" to BookStackHtmlHelper.sanitizeForHtml(formatBytes(sizeBytes)))
            if (seeders != null) add("Seeders" to BookStackHtmlHelper.sanitizeForHtml(seeders))
            if (leechers != null) add("Leechers" to BookStackHtmlHelper.sanitizeForHtml(leechers))
            if (infohash != null) {
                val magnet = "magnet:?xt=urn:btih:$infohash"
                add("Magnet" to formatLink(magnet, "Open magnet"))
            }
        }

        val pageContent = buildPage(
            title = name,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(formatChunkNotice(doc)).joinToString("\n"),
            footerSource = "torrent index dataset"
        )

        return BookStackDocument(
            bookName = "Torrent Index",
            bookDescription = "Torrent metadata index for inspection and analysis",
            chapterName = null,
            pageTitle = name,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildWikipediaDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val title = preferredTitle(metadata["title"], doc.id, doc)
        val wikipediaId = metadata["wikipedia_id"]?.ifBlank { null }
        val pageUrl = "https://en.wikipedia.org/wiki/${title.replace(" ", "_")}"
        val cleanText = doc.text.trim()

        val summary = cleanText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?.let { excerptForBookStack(it, 320).text }
        val contentSection = formatParagraphSection(
            title = "Article",
            text = cleanText,
            maxChars = WIKIPEDIA_BOOKSTACK_BODY_MAX_CHARS,
            emptyHtml = "<p><em>No content available.</em></p>"
        )

        val metadataItems = buildList {
            if (wikipediaId != null) add("Wikipedia ID" to formatInlineCode(wikipediaId))
            add("Source" to formatLink(pageUrl, "View on Wikipedia"))
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                summary?.let { formatSection("Summary", "<p>${BookStackHtmlHelper.sanitizeForHtml(it)}</p>") },
                contentSection
            ).joinToString("\n"),
            footerSource = "Wikipedia"
        )

        return BookStackDocument(
            bookName = "Wikipedia Articles",
            bookDescription = "Articles from Wikipedia, the free encyclopedia",
            chapterName = null,
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildAustralianLegalDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val jurisdiction = metadata["jurisdiction"]?.ifBlank { null } ?: "Unknown jurisdiction"
        val type = metadata["type"]?.ifBlank { null }
        val citation = metadata["citation"]?.ifBlank { null }
        val date = metadata["date"]?.ifBlank { null }
        val source = metadata["source"]?.ifBlank { null }
        val url = metadata["url"]?.ifBlank { null }

        val title = preferredTitle(citation ?: type, doc.id, doc)

        val metadataItems = buildList {
            if (type != null) add("Type" to BookStackHtmlHelper.sanitizeForHtml(type))
            add("Jurisdiction" to BookStackHtmlHelper.sanitizeForHtml(jurisdiction))
            if (date != null) add("Date" to BookStackHtmlHelper.sanitizeForHtml(date))
            if (citation != null) add("Citation" to formatInlineCode(citation))
            if (source != null && url != null) {
                add("Source" to formatLink(url, source))
            } else if (url != null) {
                add("Source" to formatLink(url, url))
            }
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatPreSection("Document", doc.text, AUSTRALIAN_LAWS_BOOKSTACK_BODY_MAX_CHARS)
            ).joinToString("\n"),
            footerSource = "Open Australian Legal Corpus"
        )

        return BookStackDocument(
            bookName = "Australian Legal Corpus",
            bookDescription = "Legal documents from Australian Commonwealth and State sources",
            chapterName = jurisdiction,
            chapterDescription = "Legal documents from $jurisdiction",
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildLinuxDocDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val title = preferredTitle(metadata["title"], doc.id, doc)
        val type = metadata["type"]?.ifBlank { null }
        val section = metadata["section"]?.ifBlank { null }
        val path = metadata["path"]?.ifBlank { null }

        val metadataItems = buildList {
            if (type != null) add("Type" to BookStackHtmlHelper.sanitizeForHtml(type))
            if (section != null) add("Section" to BookStackHtmlHelper.sanitizeForHtml(section))
            if (path != null) add("Path" to formatInlineCode(path))
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatPreSection("Content", doc.text, LINUX_DOCS_BOOKSTACK_BODY_MAX_CHARS)
            ).joinToString("\n"),
            footerSource = "Linux documentation"
        )

        return BookStackDocument(
            bookName = "Linux Documentation",
            bookDescription = "Manual pages and system documentation for Linux",
            chapterName = type,
            chapterDescription = type?.let { "Section $section" },
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildWikiDocument(doc: StagedDocument, wikiName: String, accentColor: String): BookStackDocument {
        val metadata = doc.metadata
        val title = preferredTitle(metadata["title"], doc.id, doc)
        val url = metadata["url"]?.ifBlank { null }
        val categories = metadata["categories"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val chapterName = categories.firstOrNull()

        val metadataItems = buildList {
            add("Wiki" to "<span style=\"color: $accentColor; font-weight: bold;\">${BookStackHtmlHelper.sanitizeForHtml(wikiName)}</span>")
            if (url != null) add("URL" to formatLink(url, url))
            if (categories.isNotEmpty()) add("Categories" to BookStackHtmlHelper.sanitizeForHtml(categories.joinToString(", ")))
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatParagraphSection("Content", doc.text, WIKI_BOOKSTACK_BODY_MAX_CHARS)
            ).joinToString("\n"),
            footerSource = wikiName
        )

        return BookStackDocument(
            bookName = wikiName,
            bookDescription = "Community-maintained documentation for $wikiName",
            chapterName = chapterName,
            chapterDescription = chapterName?.let { "Pages in $it category" },
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildCveDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val cveId = metadata["cveId"]?.ifBlank { null }
            ?: metadata["cve_id"]?.ifBlank { null }
            ?: metadata["title"]?.takeIf { it.startsWith("CVE-", ignoreCase = true) }
            ?: doc.id.substringBefore("-chunk-")
        val severity = metadata["severity"]?.ifBlank { null }
            ?: metadata["cvss_severity"]?.ifBlank { null }
            ?: "UNKNOWN"
        val normalizedSeverity = severity.uppercase().takeIf {
            it in setOf("CRITICAL", "HIGH", "MEDIUM", "LOW")
        } ?: "UNKNOWN"
        val title = preferredTitle(metadata["title"] ?: cveId, doc.id, doc)
        val sourceUrl = metadata["url"]?.ifBlank { null }
            ?: "https://nvd.nist.gov/vuln/detail/$cveId"

        val metadataItems = buildList {
            add("CVE" to formatInlineCode(cveId))
            add("Severity" to BookStackHtmlHelper.sanitizeForHtml(normalizedSeverity))
            add("Source" to formatLink(sourceUrl, "View advisory"))
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatParagraphSection("Details", doc.text, CVE_BOOKSTACK_BODY_MAX_CHARS)
            ).joinToString("\n"),
            footerSource = "National Vulnerability Database"
        )

        return BookStackDocument(
            bookName = "CVE Database",
            bookDescription = "Security vulnerabilities and advisories indexed by the webservices pipeline",
            chapterName = normalizedSeverity,
            chapterDescription = "$normalizedSeverity severity vulnerabilities",
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc) + mapOf(
                "severity" to normalizedSeverity,
                "cve_id" to cveId
            )
        )
    }

    private fun buildGenericDocument(doc: StagedDocument, bookName: String): BookStackDocument {
        val title = preferredTitle(doc.metadata["title"], doc.id, doc)
        val metadataItems = buildList {
            doc.metadata["url"]?.let { add("Source" to formatLink(it, it)) }
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatParagraphSection("Content", doc.text, GENERIC_BOOKSTACK_BODY_MAX_CHARS)
            ).joinToString("\n"),
            footerSource = doc.source
        )

        return BookStackDocument(
            bookName = bookName,
            chapterName = doc.source.replaceFirstChar { it.titlecase() },
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun preferredTitle(primary: String?, fallback: String, doc: StagedDocument): String {
        val base = primary?.ifBlank { null } ?: fallback
        val chunkIndex = doc.chunkIndex
        val totalChunks = doc.totalChunks
        val needsChunkSuffix = chunkIndex != null && totalChunks != null && totalChunks > 1 &&
            !Regex("\\(part\\s+\\d+", RegexOption.IGNORE_CASE).containsMatchIn(base)
        return if (needsChunkSuffix) "$base (Part ${chunkIndex + 1} of ${totalChunks})" else base
    }

    private fun buildPage(
        title: String,
        metadataItems: List<Pair<String, String>>,
        bodyHtml: String,
        footerSource: String
    ): String {
        val safeTitle = BookStackHtmlHelper.sanitizeForHtml(title)
        val metadataBox = if (metadataItems.isNotEmpty()) BookStackHtmlHelper.formatMetadataBox(metadataItems) else ""
        val footer = BookStackHtmlHelper.formatFooter(footerSource)

        return """
            <h1>$safeTitle</h1>
            $metadataBox
            $bodyHtml
            $footer
        """.trimIndent()
    }

    private fun formatChunkNotice(doc: StagedDocument): String {
        val chunkIndex = doc.chunkIndex
        val totalChunks = doc.totalChunks
        if (chunkIndex == null || totalChunks == null || totalChunks <= 1) return ""

        val label = "Part ${chunkIndex + 1} of $totalChunks"
        return """
            <div style="margin-bottom: 16px; padding: 10px; background-color: #eef6ff; border-left: 4px solid #1e88e5;">
                <strong>Chunked document:</strong> ${BookStackHtmlHelper.sanitizeForHtml(label)}
            </div>
        """.trimIndent()
    }

    private fun formatSection(title: String, bodyHtml: String): String {
        if (bodyHtml.isBlank()) return ""
        return """
            <div style="margin-top: 20px;">
                <h2>${BookStackHtmlHelper.sanitizeForHtml(title)}</h2>
                $bodyHtml
            </div>
        """.trimIndent()
    }

    private data class BookStackExcerpt(
        val text: String,
        val truncated: Boolean
    )

    private fun excerptForBookStack(text: String, maxChars: Int): BookStackExcerpt {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return BookStackExcerpt("", truncated = false)
        }
        if (normalized.length <= maxChars) {
            return BookStackExcerpt(normalized, truncated = false)
        }

        val safeLimit = maxChars.coerceAtLeast(256).coerceAtMost(normalized.length)
        val splitIndex = normalized.lastIndexOfAny(charArrayOf('\n', ' '), safeLimit - 1)
            .takeIf { it >= safeLimit / 2 }
            ?: safeLimit

        return BookStackExcerpt(
            text = normalized.substring(0, splitIndex).trimEnd(),
            truncated = true
        )
    }

    private fun truncationNotice(): String =
        "<p><em>Excerpted for BookStack performance. Full content remains indexed in search.</em></p>"

    private fun formatParagraphSection(
        title: String,
        text: String,
        maxChars: Int,
        emptyHtml: String = ""
    ): String {
        val excerpt = excerptForBookStack(text, maxChars)
        if (excerpt.text.isBlank()) {
            return if (emptyHtml.isBlank()) "" else formatSection(title, emptyHtml)
        }

        val body = buildString {
            append(formatParagraphs(excerpt.text))
            if (excerpt.truncated) {
                append('\n')
                append(truncationNotice())
            }
        }
        return formatSection(title, body)
    }

    private fun formatPreSection(title: String, text: String, maxChars: Int): String {
        val excerpt = excerptForBookStack(text, maxChars)
        val body = buildString {
            append(formatPre(excerpt.text))
            if (excerpt.truncated) {
                append('\n')
                append(truncationNotice())
            }
        }
        return formatSection(title, body)
    }

    private fun formatParagraphs(text: String): String {
        if (text.isBlank()) return ""
        val lines = text.lines()
        val output = mutableListOf<String>()
        val paragraph = mutableListOf<String>()
        val listItems = mutableListOf<String>()
        val codeBlock = mutableListOf<String>()
        var inCodeBlock = false

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val content = paragraph.joinToString(" ").trim()
            if (content.isNotBlank()) {
                output.add("<p>${BookStackHtmlHelper.sanitizeForHtml(content)}</p>")
            }
            paragraph.clear()
        }

        fun flushList() {
            if (listItems.isEmpty()) return
            output.add(
                buildString {
                    append("<ul>")
                    listItems.forEach { append("<li>$it</li>") }
                    append("</ul>")
                }
            )
            listItems.clear()
        }

        fun flushCodeBlock() {
            if (codeBlock.isEmpty()) return
            output.add(formatPre(codeBlock.joinToString("\n")))
            codeBlock.clear()
        }

        val bulletRegex = Regex("""^[-*]\s+(.+)$""")
        val numberedRegex = Regex("""^\d+\.\s+(.+)$""")

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    flushCodeBlock()
                    inCodeBlock = false
                } else {
                    flushParagraph()
                    flushList()
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBlock.add(line)
                continue
            }

            if (trimmed.isBlank()) {
                flushParagraph()
                flushList()
                continue
            }

            when {
                trimmed.startsWith("### ") -> {
                    flushParagraph()
                    flushList()
                    output.add("<h4>${BookStackHtmlHelper.sanitizeForHtml(trimmed.removePrefix("### ").trim())}</h4>")
                }
                trimmed.startsWith("## ") -> {
                    flushParagraph()
                    flushList()
                    output.add("<h3>${BookStackHtmlHelper.sanitizeForHtml(trimmed.removePrefix("## ").trim())}</h3>")
                }
                trimmed.startsWith("# ") -> {
                    flushParagraph()
                    flushList()
                    output.add("<h2>${BookStackHtmlHelper.sanitizeForHtml(trimmed.removePrefix("# ").trim())}</h2>")
                }
                bulletRegex.matches(trimmed) -> {
                    flushParagraph()
                    val item = bulletRegex.find(trimmed)?.groupValues?.get(1).orEmpty()
                    listItems.add(BookStackHtmlHelper.sanitizeForHtml(item))
                }
                numberedRegex.matches(trimmed) -> {
                    flushParagraph()
                    val item = numberedRegex.find(trimmed)?.groupValues?.get(1).orEmpty()
                    listItems.add(BookStackHtmlHelper.sanitizeForHtml(item))
                }
                else -> {
                    flushList()
                    paragraph.add(trimmed)
                }
            }
        }

        flushParagraph()
        flushList()
        if (inCodeBlock) {
            flushCodeBlock()
        }

        return output.joinToString("\n")
    }

    private fun formatPre(text: String): String {
        if (text.isBlank()) return "<p><em>No content available.</em></p>"
        val safe = BookStackHtmlHelper.sanitizeForHtml(text)
        return "<pre style=\"white-space: pre-wrap; word-wrap: break-word; font-family: 'Courier New', monospace; background-color: #f8f9fa; padding: 15px; border-radius: 4px; line-height: 1.5;\">$safe</pre>"
    }

    private fun formatInlineCode(value: String): String {
        return "<code>${BookStackHtmlHelper.sanitizeForHtml(value)}</code>"
    }

    private fun formatLink(url: String, label: String, fallback: String? = null): String {
        val safeUrl = BookStackHtmlHelper.sanitizeForHtml(url)
        val safeLabel = BookStackHtmlHelper.sanitizeForHtml(label.ifBlank { fallback ?: url })
        return if (safeUrl.isNotBlank()) {
            "<a href=\"$safeUrl\" target=\"_blank\">$safeLabel</a>"
        } else {
            safeLabel
        }
    }

    private fun extractRssBody(text: String): String {
        if (text.isBlank()) return ""
        val lines = text.lines()
        var index = 0
        if (lines.isNotEmpty() && lines[0].trim().startsWith("#")) {
            index++
        }
        while (index < lines.size && lines[index].isBlank()) index++
        while (index < lines.size && lines[index].trim().startsWith("**")) index++
        while (index < lines.size && lines[index].isBlank()) index++
        val remainder = lines.drop(index).toMutableList()
        val readMoreIndex = remainder.indexOfFirst { it.trim().startsWith("**Read more:**") }
        if (readMoreIndex >= 0) {
            remainder.subList(readMoreIndex, remainder.size).clear()
        }
        return remainder.joinToString("\n").trim()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
