package org.webservices.pipeline.workers

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.webservices.pipeline.sinks.BookStackDocument
import org.webservices.pipeline.sinks.BookStackSink
import org.webservices.pipeline.sinks.QdrantPublicationSync
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.EmbeddingStatus
import org.webservices.pipeline.storage.StagedDocument
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BookStackWriterTest {
    @Test
    fun `syncExistingPublishedDocuments handles empty missing success and failure flows`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        val sink = mockk<BookStackSink>()
        val syncer = mockk<QdrantPublicationSync>()

        val emptyWriter = writer(stagingStore = stagingStore, sink = sink, syncer = syncer)
        coEvery { stagingStore.getPublishedForQdrantSync(any(), any()) } returns emptyList()
        assertTrue(emptyWriter.syncExistingPublishedDocuments())
        assertFalse(readField<Boolean>(emptyWriter, "publicationBackfillPending"))

        val docs = listOf(doc(id = "doc-1", bookstackUrl = "https://bookstack.example/page/1"))
        coEvery { stagingStore.getPublishedForQdrantSync(any(), any()) } returns docs

        val noSyncWriter = writer(stagingStore = stagingStore, sink = sink, syncer = null)
        assertTrue(noSyncWriter.syncExistingPublishedDocuments())
        assertFalse(readField<Boolean>(noSyncWriter, "publicationBackfillPending"))

        coEvery { syncer.syncPublishedDocuments(docs) } just runs
        val successWriter = writer(stagingStore = stagingStore, sink = sink, syncer = syncer)
        assertTrue(successWriter.syncExistingPublishedDocuments())
        assertFalse(readField<Boolean>(successWriter, "publicationBackfillPending"))

        coEvery { syncer.syncPublishedDocuments(docs) } throws IllegalStateException("boom")
        val failingWriter = writer(stagingStore = stagingStore, sink = sink, syncer = syncer, publicationBackfillRetrySeconds = 15)
        assertFalse(failingWriter.syncExistingPublishedDocuments())
        assertTrue(readField<Boolean>(failingWriter, "publicationBackfillPending"))
        assertTrue(readField<Long>(failingWriter, "nextPublicationBackfillAttemptAt") > System.currentTimeMillis())
    }

    @Test
    fun `start writes allowed documents and syncs page URLs`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        val sink = mockk<BookStackSink>()
        val syncer = mockk<QdrantPublicationSync>()
        val writer = writer(
            stagingStore = stagingStore,
            sink = sink,
            syncer = syncer,
            pollIntervalSeconds = 60,
            allowedSources = setOf("rss")
        )
        val rssDoc = doc(
            source = "rss",
            metadata = mapOf(
                "title" to "Release Notes",
                "feed_title" to "Web Services",
                "feed_url" to "https://feeds.example/rss.xml",
                "link" to "https://news.example/release",
                "author" to "gerald",
                "published_date" to "2026-04-07"
            ),
            text = "# Release Notes\n\n**Feed:** Web Services\n\nBody text\n\n**Read more:** https://news.example/release"
        )
        val skippedDoc = doc(source = "cve", id = "skip-me")

        coEvery { stagingStore.getPublishedForQdrantSync(any(), any()) } returns emptyList()
        coEvery { stagingStore.getBookStackReconciliationCandidates(any(), any()) } returns emptyList()
        coEvery { stagingStore.getPublishedBookStackValidationCandidates(any(), any()) } returns emptyList()
        coEvery { stagingStore.getPendingForBookStack(any(), any()) } returns listOf(rssDoc, skippedDoc)
        coEvery { sink.write(any()) } just runs
        every { sink.getLastPageUrl("Release Notes") } returns "https://bookstack.example/page/release-notes"
        coEvery { syncer.markPublished(rssDoc, "https://bookstack.example/page/release-notes") } just runs
        coEvery { stagingStore.updateBookStackUrl(rssDoc.id, "https://bookstack.example/page/release-notes") } just runs
        coEvery { stagingStore.updateBookStackUrl(skippedDoc.id, "skipped://source-filter/cve") } just runs
        coEvery { stagingStore.markBookStackComplete(rssDoc.id) } just runs
        coEvery { stagingStore.markBookStackComplete(skippedDoc.id) } just runs

        val job = launch { writer.start() }
        runCurrent()
        job.cancelAndJoin()

        coVerify(exactly = 1) { sink.write(match<BookStackDocument> { it.bookName == "RSS Articles" && it.chapterName == "Web Services" }) }
        coVerify(exactly = 1) { syncer.markPublished(rssDoc, "https://bookstack.example/page/release-notes") }
        coVerify(exactly = 1) { stagingStore.updateBookStackUrl(skippedDoc.id, "skipped://source-filter/cve") }
        coVerify(exactly = 1) { stagingStore.markBookStackComplete(rssDoc.id) }
        coVerify(exactly = 1) { stagingStore.markBookStackComplete(skippedDoc.id) }
    }

    @Test
    fun `start marks failed writes and skips documents above retry limit`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        val sink = mockk<BookStackSink>()
        val writer = writer(stagingStore = stagingStore, sink = sink, syncer = null, pollIntervalSeconds = 60, maxRetries = 3)
        val failingDoc = doc(source = "wikipedia", id = "fail-me")
        val exhaustedDoc = doc(source = "wikipedia", id = "skip-me", retryCount = 3)

        coEvery { stagingStore.getBookStackReconciliationCandidates(any(), any()) } returns emptyList()
        coEvery { stagingStore.getPublishedBookStackValidationCandidates(any(), any()) } returns emptyList()
        coEvery { stagingStore.getPendingForBookStack(any(), any()) } returns listOf(failingDoc, exhaustedDoc)
        coEvery { sink.write(any()) } throws IllegalStateException("write failed")
        coEvery { stagingStore.markBookStackFailed(failingDoc.id, "write failed") } just runs

        val job = launch { writer.start() }
        runCurrent()
        job.cancelAndJoin()

        coVerify(exactly = 1) { stagingStore.markBookStackFailed(failingDoc.id, "write failed") }
        coVerify(exactly = 0) { stagingStore.markBookStackFailed(exhaustedDoc.id, any()) }
    }

    @Test
    fun `reconciliation repairs unresolved publication rows`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        val sink = mockk<BookStackSink>()
        val syncer = mockk<QdrantPublicationSync>()
        val writer = writer(stagingStore = stagingStore, sink = sink, syncer = syncer)
        val doc = doc(id = "reconcile-me", retryCount = 3)
        val pageUrl = "https://bookstack.example/page/reconcile-me"

        coEvery { stagingStore.getBookStackReconciliationCandidates(any(), any()) } returns listOf(doc)
        coEvery { stagingStore.getPublishedBookStackValidationCandidates(any(), any()) } returns emptyList()
        coEvery { sink.reconcilePageUrl(any()) } returns pageUrl
        coEvery { stagingStore.updateBookStackUrl(doc.id, pageUrl) } just runs
        coEvery { stagingStore.markBookStackComplete(doc.id) } just runs
        coEvery { syncer.markPublished(doc, pageUrl) } just runs

        assertTrue(writer.reconcilePublicationState())

        coVerify(exactly = 1) { sink.reconcilePageUrl(any()) }
        coVerify(exactly = 1) { stagingStore.updateBookStackUrl(doc.id, pageUrl) }
        coVerify(exactly = 1) { stagingStore.markBookStackComplete(doc.id) }
        coVerify(exactly = 1) { syncer.markPublished(doc, pageUrl) }
    }

    @Test
    fun `reconciliation resets stale published BookStack URLs`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        val sink = mockk<BookStackSink>()
        val writer = writer(stagingStore = stagingStore, sink = sink)
        val staleDoc = doc(id = "stale-doc", bookstackUrl = "https://bookstack.example/books/linux/page/missing")

        coEvery { stagingStore.getBookStackReconciliationCandidates(any(), any()) } returns emptyList()
        coEvery { stagingStore.getPublishedBookStackValidationCandidates(any(), any()) } returns listOf(staleDoc)
        coEvery { sink.reconcilePageUrl(any()) } returns null
        every { sink.clearLocationCache() } just runs
        coEvery {
            stagingStore.resetBookStackPublication(
                staleDoc.id,
                match { it.contains("stored URL no longer resolves") }
            )
        } just runs

        assertTrue(writer.reconcilePublicationState())

        coVerify(exactly = 1) { sink.reconcilePageUrl(any()) }
        coVerify(exactly = 1) {
            stagingStore.resetBookStackPublication(
                staleDoc.id,
                match { it.contains("stored URL no longer resolves") }
            )
        }
    }

    @Test
    fun `reconciliation refreshes changed published BookStack URLs`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        val sink = mockk<BookStackSink>()
        val syncer = mockk<QdrantPublicationSync>()
        val writer = writer(stagingStore = stagingStore, sink = sink, syncer = syncer)
        val staleDoc = doc(id = "moved-doc", bookstackUrl = "https://bookstack.example/books/old/page/doc")
        val currentUrl = "https://bookstack.example/books/new/page/doc"

        coEvery { stagingStore.getBookStackReconciliationCandidates(any(), any()) } returns emptyList()
        coEvery { stagingStore.getPublishedBookStackValidationCandidates(any(), any()) } returns listOf(staleDoc)
        coEvery { sink.reconcilePageUrl(any()) } returns currentUrl
        coEvery { stagingStore.updateBookStackUrl(staleDoc.id, currentUrl) } just runs
        coEvery { syncer.markPublished(staleDoc, currentUrl) } just runs

        assertTrue(writer.reconcilePublicationState())

        coVerify(exactly = 1) { stagingStore.updateBookStackUrl(staleDoc.id, currentUrl) }
        coVerify(exactly = 1) { syncer.markPublished(staleDoc, currentUrl) }
    }

    @Test
    fun `reconciliation rotates valid published BookStack URLs`() = runTest {
        val stagingStore = mockk<DocumentStagingStore>()
        val sink = mockk<BookStackSink>()
        val writer = writer(stagingStore = stagingStore, sink = sink)
        val validDoc = doc(id = "valid-doc", bookstackUrl = "https://bookstack.example/books/linux/page/exists")

        coEvery { stagingStore.getBookStackReconciliationCandidates(any(), any()) } returns emptyList()
        coEvery { stagingStore.getPublishedBookStackValidationCandidates(any(), any()) } returns listOf(validDoc)
        coEvery { sink.reconcilePageUrl(any()) } returns validDoc.bookstackUrl
        coEvery { stagingStore.markBookStackPublicationValidated(validDoc.id) } just runs

        assertTrue(writer.reconcilePublicationState())

        coVerify(exactly = 1) { stagingStore.markBookStackPublicationValidated(validDoc.id) }
    }

    @Test
    fun `toBookStackDocument builds source specific documents`() {
        val writer = writer()

        val rss = invoke<BookStackDocument>(
            writer,
            "toBookStackDocument",
            doc(
                source = "rss",
                metadata = mapOf(
                    "title" to "Release Notes",
                    "feed_title" to "Web Services",
                    "feed_url" to "https://feeds.example/rss.xml",
                    "link" to "https://news.example/release",
                    "author" to "gerald",
                    "published_date" to "2026-04-07",
                    "categories" to "release, infra",
                    "description" to "Summary"
                ),
                text = "# Release Notes\n\n**Feed:** Web Services\n\nMain content\n\n**Read more:** https://news.example/release"
            )
        )
        assertEquals("RSS Articles", rss.bookName)
        assertEquals("Web Services", rss.chapterName)
        assertTrue(rss.pageContent.contains("Summary"))
        assertTrue(rss.tags["feed"] == "Web Services")

        val wikipedia = invoke<BookStackDocument>(
            writer,
            "toBookStackDocument",
            doc(source = "wikipedia", metadata = mapOf("title" to "OpenSearch", "wikipedia_id" to "42"), text = "OpenSearch is a search engine.\nSecond paragraph.")
        )
        assertEquals("Wikipedia Articles", wikipedia.bookName)
        assertTrue(wikipedia.pageContent.contains("View on Wikipedia"))

        val legal = invoke<BookStackDocument>(
            writer,
            "toBookStackDocument",
            doc(
                source = "australian_laws",
                metadata = mapOf(
                    "jurisdiction" to "Commonwealth",
                    "type" to "Act",
                    "citation" to "Test Act 2026",
                    "date" to "2026-01-01",
                    "source" to "AustLII",
                    "url" to "https://laws.example/act"
                ),
                text = "Section 1"
            )
        )
        assertEquals("Australian Legal Corpus", legal.bookName)
        assertEquals("Commonwealth", legal.chapterName)
        assertTrue(legal.pageContent.contains("Test Act 2026"))

        val linux = invoke<BookStackDocument>(
            writer,
            "toBookStackDocument",
            doc(source = "linux_docs", metadata = mapOf("title" to "ls", "type" to "man", "section" to "1", "path" to "/usr/share/man/man1/ls.1"), text = "NAME\n ls")
        )
        assertEquals("Linux Documentation", linux.bookName)
        assertEquals("man", linux.chapterName)
        assertTrue(linux.pageContent.contains("/usr/share/man/man1/ls.1"))

        val debianWiki = invoke<BookStackDocument>(
            writer,
            "toBookStackDocument",
            doc(source = "debian_wiki", metadata = mapOf("title" to "APT", "url" to "https://wiki.debian.org/APT", "categories" to "Packaging, Tools"), text = "APT guide")
        )
        assertEquals("Debian Wiki", debianWiki.bookName)
        assertEquals("Packaging", debianWiki.chapterName)

        val archWiki = invoke<BookStackDocument>(
            writer,
            "toBookStackDocument",
            doc(source = "arch_wiki", metadata = mapOf("title" to "systemd"), text = "systemd docs")
        )
        assertEquals("Arch Wiki", archWiki.bookName)

        val cve = invoke<BookStackDocument>(
            writer,
            "toBookStackDocument",
            doc(
                source = "cve",
                metadata = mapOf(
                    "title" to "CVE-2026-0001",
                    "severity" to "high",
                    "cveId" to "CVE-2026-0001"
                ),
                text = "A vulnerability"
            )
        )
        assertEquals("CVE Database", cve.bookName)
        assertEquals("HIGH", cve.chapterName)
        assertTrue(cve.pageContent.contains("View advisory"))
        assertTrue(cve.tags["severity"] == "HIGH")
    }

    @Test
    fun `formatting helpers render markdown like content and metadata`() {
        val writer = writer(allowedSources = setOf("rss"))
        val chunkedDoc = doc(
            source = "rss",
            chunkIndex = 1,
            totalChunks = 3,
            metadata = mapOf(
                "url" to "https://example.com/post",
                "published_at" to "2026-04-07",
                "author" to "gerald",
                "feed_title" to "Feed",
                "categories" to "ops, release"
            )
        )

        assertTrue(invoke(writer, "isSourceAllowed", "rss") as Boolean)
        assertFalse(invoke(writer, "isSourceAllowed", "cve") as Boolean)
        assertEquals("Article (Part 2 of 3)", invoke<String>(writer, "preferredTitle", "Article", "fallback", chunkedDoc))
        assertEquals(
            mapOf(
                "source" to "rss",
                "collection" to "rss_feeds",
                "chunk" to "2/3",
                "url" to "https://example.com/post",
                "published" to "2026-04-07",
                "author" to "gerald",
                "feed" to "Feed",
                "categories" to "ops, release"
            ),
            invoke<Map<String, String>>(writer, "buildTags", chunkedDoc)
        )

        val formatted = invoke<String>(
            writer,
            "formatParagraphs",
            """
            # Title

            Intro paragraph
            still intro

            - bullet one
            1. bullet two

            ## Heading

            ```kotlin
            println("hi")
            ```
            """.trimIndent()
        )
        assertTrue(formatted.contains("<h2>Title</h2>"))
        assertTrue(formatted.contains("<p>Intro paragraph still intro</p>"))
        assertTrue(formatted.contains("<ul><li>bullet one</li><li>bullet two</li></ul>"))
        assertTrue(formatted.contains("<pre"))

        assertTrue(invoke<String>(writer, "formatPre", "").contains("No content available"))
        assertTrue(invoke<String>(writer, "formatInlineCode", "<tag>").contains("&lt;tag&gt;"))
        assertEquals("<a href=\"https://example.com\" target=\"_blank\">Example</a>", invoke<String>(writer, "formatLink", "https://example.com", "Example", null))
        assertEquals("Label", invoke<String>(writer, "formatLink", "", "", "Label"))
        assertEquals("Body text", invoke<String>(writer, "extractRssBody", "# Title\n\n**Meta:** x\n\nBody text\n\n**Read more:** y"))
        assertEquals("1.00 MB", invoke<String>(writer, "formatBytes", 1_048_576L))
        assertTrue(invoke<String>(writer, "formatChunkNotice", chunkedDoc).contains("Part 2 of 3"))
        assertEquals("", invoke<String>(writer, "formatSection", "Ignored", ""))
        assertTrue(invoke<String>(writer, "buildPage", "Doc", listOf("A" to "B"), "<p>Body</p>", "source").contains("<h1>Doc</h1>"))
    }

    @Test
    fun `oversized BookStack content is excerpted`() {
        val writer = writer()
        val longText = (1..4000).joinToString(" ") { "token$it" }

        val wikipedia = invoke<BookStackDocument>(
            writer,
            "toBookStackDocument",
            doc(
                source = "wikipedia",
                metadata = mapOf("title" to "Large Article", "wikipedia_id" to "123"),
                text = longText
            )
        )

        assertTrue(wikipedia.pageContent.contains("Excerpted for BookStack performance"))
        assertFalse(wikipedia.pageContent.contains("token4000"))
    }

    private fun writer(
        stagingStore: DocumentStagingStore = mockk(relaxed = true),
        sink: BookStackSink = mockk(relaxed = true),
        syncer: QdrantPublicationSync? = null,
        pollIntervalSeconds: Long = 60,
        batchSize: Int = 200,
        maxRetries: Int = 3,
        maxConcurrentWrites: Int = 1,
        allowedSources: Set<String> = emptySet(),
        publicationBackfillLimit: Int = 5000,
        publicationBackfillRetrySeconds: Long = 30
    ): BookStackWriter {
        return BookStackWriter(
            stagingStore = stagingStore,
            bookStackSink = sink,
            qdrantPublicationSync = syncer,
            pollIntervalSeconds = pollIntervalSeconds,
            batchSize = batchSize,
            maxRetries = maxRetries,
            maxConcurrentWrites = maxConcurrentWrites,
            allowedSources = allowedSources,
            publicationBackfillLimit = publicationBackfillLimit,
            publicationBackfillRetrySeconds = publicationBackfillRetrySeconds
        )
    }

    private fun doc(
        id: String = "doc-1",
        source: String = "rss",
        collection: String = when (source) {
            "rss" -> "rss_feeds"
            "cve" -> "cve"
            else -> source
        },
        text: String = "Body",
        metadata: Map<String, String> = emptyMap(),
        chunkIndex: Int? = null,
        totalChunks: Int? = null,
        retryCount: Int = 0,
        bookstackUrl: String? = null
    ): StagedDocument {
        return StagedDocument(
            id = id,
            source = source,
            collection = collection,
            text = text,
            metadata = metadata,
            embeddingStatus = EmbeddingStatus.COMPLETED,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            createdAt = Instant.parse("2026-04-07T00:00:00Z"),
            updatedAt = Instant.parse("2026-04-07T00:00:00Z"),
            retryCount = retryCount,
            bookstackUrl = bookstackUrl
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invoke(target: Any, methodName: String, vararg args: Any?): T {
        val method = target.javaClass.declaredMethods.first {
            !it.isSynthetic && it.name == methodName && it.parameterCount == args.size
        }
        method.isAccessible = true
        return method.invoke(target, *args) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, fieldName: String): T {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target) as T
    }
}
