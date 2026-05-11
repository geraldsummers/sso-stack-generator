package org.webservices.pipeline.sinks

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webservices.pipeline.core.Sink
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

/**
 * Sink implementation for writing documents to BookStack knowledge base via REST API.
 *
 * **BookStack Integration:**
 * - Uses REST API with token-based authentication (tokenId:tokenSecret)
 * - Manages three-level hierarchy: Books → Chapters → Pages
 * - Implements smart deduplication to avoid creating duplicate content
 * - Stores HTML-formatted content with metadata tags
 *
 * **Usage by BookStackWriter:**
 * The BookStackWriter polls PostgreSQL for documents with embedding_status=COMPLETED and
 * bookstack_status=PENDING, then writes them to BookStack using this sink. This ensures only
 * fully indexed documents (with vectors in Qdrant) appear in the knowledge base.
 *
 * **Hierarchy Model:**
 * - **Books**: Top-level containers (e.g., "RSS Articles", "CVE Database")
 * - **Chapters**: Organized by source type (e.g., "Hacker News", "High Severity CVEs")
 * - **Pages**: Individual documents with HTML content and metadata tags
 *
 * **Smart Deduplication:**
 * Three-level caching prevents duplicate API calls and ensures pages are updated (not duplicated)
 * when re-processing the same document:
 * - bookCache: Maps book name → BookStack book ID
 * - chapterCache: Maps "bookId:chapterName" → BookStack chapter ID
 * - pageCache: Maps "bookId:chapterId:pageTitle" → BookStack page ID
 *
 * **Idempotent Writes:**
 * When a page already exists, it's updated via PUT instead of creating a duplicate. This enables
 * safe re-processing of documents after content updates or pipeline restarts.
 *
 * **Downstream Consumers:**
 * - Human users browse BookStack for readable documentation
 * - Search results include bookstack_url metadata for easy navigation
 * - Future enhancement: Agent tools for programmatic BookStack access
 *
 * @param bookstackUrl The BookStack API base URL (e.g., "https://bookstack.example.com")
 * @param tokenId API token ID for authentication
 * @param tokenSecret API token secret for authentication
 *
 * @see org.webservices.pipeline.workers.BookStackWriter
 * @see BookStackDocument
 */
class BookStackSink(
    private val bookstackUrl: String,
    private val publicBookstackUrl: String = bookstackUrl,
    private val tokenId: String,
    private val tokenSecret: String,
    private val maxRetries: Int = 5,
    private val baseDelayMs: Long = 500
) : Sink<BookStackDocument> {
    override val name = "BookStackSink"

    private data class ExistingBookReference(
        val id: Int,
        val slug: String?,
        val description: String = "",
        val coverMissing: Boolean = false
    )

    companion object {
        private const val BOOK_COVER_SIZE = 512
        private val pngMediaType = "image/png".toMediaType()
    }

    private data class ExistingPageReference(
        val id: Int,
        val slug: String?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /** Cache mapping book name → BookStack book ID (prevents duplicate book creation) */
    private val bookCache = ConcurrentHashMap<String, Int>()

    /** Cache mapping book ID → slug so page URLs can be derived without reloading full book contents. */
    private val bookSlugCache = ConcurrentHashMap<Int, String>()

    /** Cache mapping "bookId:chapterName" → BookStack chapter ID (prevents duplicate chapter creation) */
    private val chapterCache = ConcurrentHashMap<String, Int>()

    /** Cache mapping "bookId:chapterId:pageTitle" → BookStack page ID (enables page updates instead of duplicates) */
    private val pageCache = ConcurrentHashMap<String, Int>()

    /** Tracks one-time structural initialization work per book. */
    private val initializedBookStructures = ConcurrentHashMap<String, Boolean>()

    /** Thread-safe map storing last written page URL for each page title (used to update PostgreSQL bookstack_url) */
    private val lastPageUrlMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun cacheBookReference(name: String, id: Int, slug: String?) {
        bookCache[name] = id
        slug?.trim()?.ifBlank { null }?.let { bookSlugCache[id] = it }
    }

    /**
     * Writes a document to BookStack, creating or updating the book/chapter/page hierarchy as needed.
     *
     * **Workflow:**
     * 1. Ensure book exists (create if needed, use cached ID if available)
     * 2. Ensure chapter exists if specified (create if needed, use cached ID if available)
     * 3. Check if page already exists (by title within the book/chapter)
     * 4. If page exists: Update via PUT (preserves page ID and URL)
     * 5. If page doesn't exist: Create via POST (generates new page ID and URL)
     * 6. Store page URL in lastPageUrlMap for PostgreSQL update
     *
     * **Authentication:**
     * All requests include "Authorization: Token tokenId:tokenSecret" header for API access.
     *
     * **Called by BookStackWriter:**
     * After a document has been embedded and stored in Qdrant (status=COMPLETED), BookStackWriter
     * calls this method to publish it to the human-readable knowledge base. The returned page URL
     * is stored in PostgreSQL's bookstack_url column.
     *
     * **HTML Formatting:**
     * The pageContent is expected to be HTML-formatted. Raw text is supported but may not render
     * optimally. Sources that generate Markdown should convert to HTML before passing to this sink.
     *
     * @param item The BookStack document containing book, chapter, page data, and HTML content
     * @throws Exception if BookStack API call fails (authentication error, network issue, etc.)
     */
    override suspend fun write(item: BookStackDocument) {
        try {
            // Step 1: Ensure book exists (cached lookup or API create)
            val bookId = getOrCreateBook(item.bookName, item.bookDescription)
            ensureBookStructure(bookId, item)

            // Step 2: Ensure chapter exists if specified (cached lookup or API create)
            val chapterName = item.chapterName
            val chapterDescription = item.chapterDescription
            val chapterId = if (chapterName != null) {
                getOrCreateChapter(bookId, chapterName, chapterDescription)
            } else null

            // Step 3: Create or update page (API checks for existing page by title)
            val pageUrl = createOrUpdatePage(bookId, chapterId, item)

            // Step 4: Store URL for PostgreSQL update (BookStackWriter retrieves this)
            lastPageUrlMap[item.pageTitle] = pageUrl

            logger.debug { "Wrote page '${item.pageTitle}' to BookStack: $pageUrl" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to write to BookStack: ${e.message}" }
            throw e
        }
    }

    /**
     * Writes a batch of documents by calling write() sequentially for each item.
     *
     * BookStack API doesn't support true batch operations, so this method simply iterates.
     * Future optimization: Parallelize writes with coroutines (requires rate limiting).
     *
     * @param items List of BookStack documents to write
     */
    override suspend fun writeBatch(items: List<BookStackDocument>) {
        items.forEach { write(it) }
    }

    /**
     * Retrieves the last written page URL for a given page title.
     *
     * **Used by BookStackWriter:**
     * After calling write(), BookStackWriter retrieves the page URL to update PostgreSQL's
     * bookstack_url column, enabling search results to link to the knowledge base.
     *
     * @param pageTitle The page title to look up
     * @return The BookStack page URL, or null if not found in cache
     */
    fun getLastPageUrl(pageTitle: String): String? {
        return lastPageUrlMap[pageTitle]
    }

    fun clearLocationCache() {
        bookCache.clear()
        bookSlugCache.clear()
        chapterCache.clear()
        pageCache.clear()
        lastPageUrlMap.clear()
        initializedBookStructures.clear()
    }

    suspend fun reconcilePageUrl(doc: BookStackDocument): String? {
        val existingBook = findExistingBook(doc.bookName) ?: return null
        val bookId = existingBook.id
        val slug = existingBook.slug
        cacheBookReference(doc.bookName, bookId, slug)

        val chapterName = doc.chapterName
        val chapterId = if (chapterName != null) {
            findExistingChapter(bookId, chapterName) ?: return null
        } else {
            null
        }

        val existingPage = findExistingPage(bookId, chapterId, doc.pageTitle) ?: return null
        val cacheKey = "${bookId}:${chapterId ?: "null"}:${doc.pageTitle}"
        pageCache[cacheKey] = existingPage.id

        val pageUrl = resolvePageUrlFromLookup(bookId, existingPage)
        if (pageUrl != null) {
            lastPageUrlMap[doc.pageTitle] = pageUrl
        }
        return pageUrl
    }

    /**
     * Performs a health check by listing books via the BookStack API.
     *
     * This verifies that:
     * - BookStack REST API endpoint is reachable
     * - Token-based authentication is working
     * - API permissions allow book listing
     *
     * Used by monitoring systems and startup checks to detect BookStack outages.
     *
     * @return true if BookStack is healthy and API is accessible, false otherwise
     */
    override suspend fun healthCheck(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$bookstackUrl/api/books")
                .header("Authorization", "Token $tokenId:$tokenSecret")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            logger.error(e) { "BookStack health check failed: ${e.message}" }
            false
        }
    }

    /**
     * Executes an HTTP request with exponential backoff retry logic for transient failures.
     *
     * **Retryable Errors:**
     * - HTTP 429 (Too Many Requests): Rate limiting
     * - HTTP 500-504: Server errors or proxy issues
     * - ConnectException: Service unreachable
     * - SocketTimeoutException: Request timeout
     * - IOException: Network instability
     *
     * **Retry Strategy:**
     * - Exponential backoff: delay = baseDelayMs * 2^attempt (500ms, 1s, 2s, 4s, 8s)
     * - Jitter: 0-50% added to prevent thundering herd
     * - Max retries: 5 attempts
     *
     * @param request The HTTP request to execute
     * @return The successful HTTP response body as string
     * @throws Exception if all retries exhausted or non-retryable error
     */
    private suspend fun executeWithRetry(request: Request): String {
        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""

                    // Retryable HTTP errors
                    if (response.code in listOf(429, 500, 502, 503, 504)) {
                        throw RetryableBookStackException("BookStack returned retryable error ${response.code}")
                    }

                    // Non-retryable errors (4xx except 429)
                    if (!response.isSuccessful) {
                        throw Exception("BookStack returned ${response.code}: $bodyString")
                    }

                    return bodyString
                }
            } catch (e: RetryableBookStackException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = baseDelayMs * (1L shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs
                    logger.warn { "BookStack API attempt ${attempt + 1}/$maxRetries failed (${e.message}), retrying in ${totalDelay}ms" }
                    delay(totalDelay)
                }
            } catch (e: java.net.ConnectException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1L shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs
                    logger.warn { "BookStack unreachable (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms" }
                    delay(totalDelay)
                } else {
                    throw Exception("BookStack unreachable after $maxRetries retries", e)
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1L shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs
                    logger.warn { "BookStack timeout (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms" }
                    delay(totalDelay)
                } else {
                    throw Exception("BookStack timeout after $maxRetries retries", e)
                }
            } catch (e: java.io.IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1L shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs
                    logger.warn { "Network error (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms: ${e.message}" }
                    delay(totalDelay)
                } else {
                    throw Exception("Network error after $maxRetries retries: ${e.message}", e)
                }
            } catch (e: Exception) {
                // Non-retryable errors
                logger.error(e) { "BookStack API error (non-retryable): ${e.message}" }
                throw e
            }
        }

        throw lastException ?: Exception("BookStack API failed after $maxRetries retries")
    }

    /**
     * Gets or creates a BookStack book by name.
     *
     * **Caching Strategy:**
     * - First check: bookCache (in-memory, fast)
     * - Second check: BookStack API (network call to list all books)
     * - If not found: Create new book via POST /api/books
     *
     * **Deduplication:**
     * The cache ensures we don't create duplicate books. Once a book is found or created, its ID
     * is cached for the lifetime of this sink instance. This prevents redundant API calls when
     * writing multiple pages to the same book.
     *
     * **Auto-generated Description:**
     * If no description is provided, defaults to "Auto-generated by webservices Pipeline".
     *
     * **Retry Logic:**
     * All HTTP calls use exponential backoff retry logic for transient failures (rate limits,
     * network issues, server errors).
     *
     * @param name The book name (e.g., "RSS Articles", "CVE Database")
     * @param description Optional book description
     * @return BookStack book ID
     * @throws Exception if API call fails after retries
     */
    private suspend fun getOrCreateBook(name: String, description: String?): Int {
        // Check cache first (fast path)
        bookCache[name]?.let { return it }

        // Search for existing book via API (slow path)
        findExistingBook(name)?.let { existingBook ->
            cacheBookReference(name, existingBook.id, existingBook.slug)
            val desiredDescription = (description ?: "Auto-generated by webservices Pipeline").trim()
            if (existingBook.coverMissing || existingBook.description != desiredDescription) {
                updateBookMetadata(existingBook.id, name, description)
            }
            return existingBook.id
        }

        // Book doesn't exist - create it
        val request = Request.Builder()
            .url("$bookstackUrl/api/books")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .post(buildBookPayload(name, description))
            .build()

        val createResponse = executeWithRetry(request)
        val createJson = JsonParser.parseString(createResponse).asJsonObject
        val id = createJson.get("id").asInt
        val slug = createJson.get("slug")?.takeUnless { it.isJsonNull }?.asString
        cacheBookReference(name, id, slug)
        logger.info { "Created BookStack book: $name (ID: $id)" }
        return id
    }

    private suspend fun findExistingBook(name: String): ExistingBookReference? {
        bookCache[name]?.let { cachedId ->
            return ExistingBookReference(id = cachedId, slug = bookSlugCache[cachedId])
        }

        val searchRequest = Request.Builder()
            .url("$bookstackUrl/api/books")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .get()
            .build()

        val bodyString = executeWithRetry(searchRequest)
        val json = JsonParser.parseString(bodyString).asJsonObject
        val books = json.getAsJsonArray("data")

        for (book in books) {
            val bookObj = book.asJsonObject
            if (bookObj.get("name").asString == name) {
                val id = bookObj.get("id").asInt
                val slug = bookObj.get("slug")?.takeUnless { it.isJsonNull }?.asString
                val description = bookObj.get("description")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                val coverMissing = bookObj.has("cover") && bookObj.get("cover").isJsonNull
                return ExistingBookReference(
                    id = id,
                    slug = slug,
                    description = description,
                    coverMissing = coverMissing
                )
            }
        }

        return null
    }

    private suspend fun updateBookMetadata(bookId: Int, name: String, description: String?) {
        val request = Request.Builder()
            .url("$bookstackUrl/api/books/$bookId")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .put(buildBookPayload(name, description))
            .build()

        executeWithRetry(request)
        logger.info { "Updated BookStack metadata: $name (ID: $bookId)" }
    }

    private fun buildBookPayload(name: String, description: String?) =
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("name", name)
            .addFormDataPart("description", description ?: "Auto-generated by webservices Pipeline")
            .addFormDataPart(
                "image",
                "${slugify(name)}-cover.png",
                generateBookCoverPng(name, description).toRequestBody(pngMediaType)
            )
            .build()

    private fun generateBookCoverPng(name: String, description: String?): ByteArray {
        val safeName = name.trim().ifBlank { "Book" }
        val summary = description?.trim().orEmpty()
        val image = BufferedImage(BOOK_COVER_SIZE, BOOK_COVER_SIZE, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()

        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val primary = paletteColor("$safeName-primary", 0.58f, 0.82f)
            val secondary = paletteColor("$safeName-secondary", 0.78f, 0.48f)
            val accent = paletteColor("$safeName-accent", 0.16f, 0.92f)

            graphics.paint = GradientPaint(
                0f,
                0f,
                primary,
                BOOK_COVER_SIZE.toFloat(),
                BOOK_COVER_SIZE.toFloat(),
                secondary
            )
            graphics.fillRect(0, 0, BOOK_COVER_SIZE, BOOK_COVER_SIZE)

            graphics.color = accent
            graphics.fillRoundRect(36, 36, BOOK_COVER_SIZE - 72, BOOK_COVER_SIZE - 72, 56, 56)

            graphics.color = Color(255, 255, 255, 228)
            graphics.fillRoundRect(36, 312, BOOK_COVER_SIZE - 72, 164, 40, 40)

            graphics.color = Color.WHITE
            graphics.font = Font("SansSerif", Font.BOLD, 132)
            val initials = safeName
                .split(Regex("[^A-Za-z0-9]+"))
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
                .ifBlank { safeName.take(1).uppercase() }
            val initialsMetrics = graphics.fontMetrics
            graphics.drawString(
                initials,
                (BOOK_COVER_SIZE - initialsMetrics.stringWidth(initials)) / 2,
                212
            )

            graphics.color = Color(24, 34, 50)
            graphics.font = Font("SansSerif", Font.BOLD, 34)
            val nameLines = wrapCoverText(safeName, 18, 2)
            var y = 360
            for (line in nameLines) {
                val metrics = graphics.fontMetrics
                graphics.drawString(line, (BOOK_COVER_SIZE - metrics.stringWidth(line)) / 2, y)
                y += metrics.height + 2
            }

            if (summary.isNotBlank()) {
                graphics.font = Font("SansSerif", Font.PLAIN, 18)
                graphics.color = Color(52, 70, 92)
                val summaryLines = wrapCoverText(summary, 34, 3)
                y += 8
                for (line in summaryLines) {
                    val metrics = graphics.fontMetrics
                    graphics.drawString(line, (BOOK_COVER_SIZE - metrics.stringWidth(line)) / 2, y)
                    y += metrics.height
                }
            }
        } finally {
            graphics.dispose()
        }

        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    private fun paletteColor(seed: String, saturation: Float, brightness: Float): Color {
        val hue = ((seed.hashCode().toLong() and 0x7fffffff) % 360).toFloat() / 360f
        return Color.getHSBColor(hue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    }

    private fun wrapCoverText(text: String, maxChars: Int, maxLines: Int): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        fun commitCurrent() {
            if (current.isNotEmpty() && lines.size < maxLines) {
                lines += current.toString().trim()
                current = StringBuilder()
            }
        }

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (candidate.length > maxChars && current.isNotEmpty()) {
                commitCurrent()
            }
            if (lines.size == maxLines) {
                break
            }
            if (word.length > maxChars) {
                val truncated = word.take(maxChars.coerceAtLeast(1))
                current.append(truncated)
            } else {
                if (current.isNotEmpty()) {
                    current.append(' ')
                }
                current.append(word)
            }
        }

        commitCurrent()

        if (lines.size == maxLines && words.joinToString(" ").length > lines.joinToString(" ").length) {
            val last = lines.last()
            lines[lines.lastIndex] = if (last.length >= maxChars - 1) {
                "${last.take((maxChars - 1).coerceAtLeast(1)).trimEnd()}…"
            } else {
                "$last…"
            }
        }

        return lines
    }

    private fun slugify(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "book" }

    private suspend fun ensureBookStructure(bookId: Int, item: BookStackDocument) {
        if (item.bookName != "CVE Database") {
            return
        }

        if (initializedBookStructures.putIfAbsent(item.bookName, true) != null) {
            return
        }

        listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN").forEach { severity ->
            getOrCreateChapter(bookId, severity, "$severity severity vulnerabilities")
        }
    }

    private suspend fun resolvePageUrlFromResponse(
        bookId: Int,
        responseJson: com.google.gson.JsonObject,
        pageSlug: String
    ): String {
        val bookSlug = responseJson.get("book_slug")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.ifBlank { null }
            ?: bookSlugCache[bookId]
            ?: throw Exception("No cached book slug for book ID $bookId")

        return "${publicBookstackUrl.trimEnd('/')}/books/$bookSlug/page/$pageSlug"
    }

    /**
     * Gets or creates a BookStack chapter within a book.
     *
     * **Caching Strategy:**
     * - First check: chapterCache using composite key "bookId:chapterName"
     * - Second check: BookStack API (GET /api/books/{bookId} to list chapters)
     * - If not found: Create new chapter via POST /api/chapters
     *
     * **Deduplication:**
     * Chapters are scoped to their parent book, so the cache key includes both book ID and chapter
     * name. This prevents collisions when different books have chapters with the same name.
     *
     * **Hierarchy Management:**
     * Chapters provide organizational structure within books (e.g., grouping RSS articles by source,
     * or CVEs by severity). Pages can exist directly in books (chapterId=null) or within chapters.
     *
     * **Retry Logic:**
     * All HTTP calls use exponential backoff retry logic for transient failures.
     *
     * @param bookId The parent book ID
     * @param name The chapter name (e.g., "Hacker News", "High Severity CVEs")
     * @param description Optional chapter description
     * @return BookStack chapter ID
     * @throws Exception if API call fails after retries
     */
    private suspend fun getOrCreateChapter(bookId: Int, name: String, description: String?): Int {
        val cacheKey = "$bookId:$name"
        chapterCache[cacheKey]?.let { return it }

        // Search for existing chapter within book (via book contents API)
        findExistingChapter(bookId, name)?.let { return it }

        // Chapter doesn't exist - create it
        val payload = mapOf(
            "book_id" to bookId,
            "name" to name,
            "description" to (description ?: "")
        )

        val request = Request.Builder()
            .url("$bookstackUrl/api/chapters")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        val createResponse = executeWithRetry(request)
        val createJson = JsonParser.parseString(createResponse).asJsonObject
        val id = createJson.get("id").asInt
        chapterCache[cacheKey] = id
        logger.info { "Created BookStack chapter: $name (ID: $id)" }
        return id
    }

    private suspend fun findExistingChapter(bookId: Int, name: String): Int? {
        val cacheKey = "$bookId:$name"
        chapterCache[cacheKey]?.let { return it }

        val searchRequest = Request.Builder()
            .url("$bookstackUrl/api/books/$bookId")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .get()
            .build()

        val bodyString = executeWithRetry(searchRequest)
        val json = JsonParser.parseString(bodyString).asJsonObject
        val contents = json.getAsJsonArray("contents")

        for (content in contents) {
            val contentObj = content.asJsonObject
            if (contentObj.get("type").asString == "chapter" &&
                contentObj.get("name").asString == name) {
                val id = contentObj.get("id").asInt
                chapterCache[cacheKey] = id
                return id
            }
        }

        return null
    }

    /**
     * Creates a new page or updates an existing page in BookStack.
     *
     * **Smart Deduplication:**
     * - Searches for existing pages by title within the same book/chapter
     * - If found: Updates page content and tags via PUT (preserves page ID and URL)
     * - If not found: Creates new page via POST (generates new ID and URL)
     *
     * **Page Cache:**
     * Uses composite key "bookId:chapterId:pageTitle" to track page IDs, enabling fast lookups
     * for subsequent updates. This is critical for idempotent writes - re-running the pipeline
     * updates existing pages instead of creating duplicates.
     *
     * **Update Behavior:**
     * When updating an existing page, the entire content and tags are replaced (PUT semantics).
     * This ensures pages reflect the latest document content from the source.
     *
     * **HTML Content:**
     * The doc.pageContent is expected to be HTML-formatted for optimal rendering. Raw text will
     * work but may not display as nicely. Sources should convert Markdown → HTML if needed.
     *
     * **Tags:**
     * Tags are stored as name-value pairs (e.g., "source: rss", "url: https://..."). These appear
     * in BookStack's UI and are searchable within the knowledge base.
     *
     * **Retry Logic:**
     * All HTTP calls use exponential backoff retry logic for transient failures.
     *
     * @param bookId The parent book ID
     * @param chapterId The parent chapter ID, or null for pages directly in the book
     * @param doc The BookStack document containing page title, content, and tags
     * @return The full BookStack page URL (e.g., "https://bookstack.example.com/books/rss-articles/page/my-article")
     * @throws Exception if API call fails after retries
     */
    private fun buildPagePayload(bookId: Int, chapterId: Int?, doc: BookStackDocument): MutableMap<String, Any> {
        val payload = mutableMapOf(
            "book_id" to bookId,
            "name" to doc.pageTitle,
            "html" to doc.pageContent,
            "tags" to doc.tags.map { tag ->
                mapOf("name" to tag.key, "value" to tag.value)
            }
        )

        if (chapterId != null) {
            payload["chapter_id"] = chapterId
        }
        return payload
    }

    private suspend fun findExistingPage(bookId: Int, chapterId: Int?, pageTitle: String): ExistingPageReference? {
        val encodedTitle = URLEncoder.encode(pageTitle, "UTF-8")
        val searchRequest = Request.Builder()
            .url("$bookstackUrl/api/pages?filter[name]=$encodedTitle")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .get()
            .build()

        val bodyString = executeWithRetry(searchRequest)
        val json = JsonParser.parseString(bodyString).asJsonObject
        val pages = json.getAsJsonArray("data")

        for (page in pages) {
            val pageObj = page.asJsonObject
            val pageBookId = pageObj.get("book_id").asInt
            val pageChapterId = pageObj.get("chapter_id")?.let {
                if (it.isJsonNull) null else it.asInt
            }
            if (pageBookId == bookId && pageChapterId == chapterId) {
                return ExistingPageReference(
                    id = pageObj.get("id").asInt,
                    slug = pageObj.get("slug")?.takeUnless { it.isJsonNull }?.asString
                )
            }
        }

        return null
    }

    private fun resolvePageUrlFromLookup(bookId: Int, reference: ExistingPageReference): String? {
        val slug = reference.slug?.trim()?.ifBlank { null } ?: return null
        return "${publicBookstackUrl.trimEnd('/')}/books/${bookSlugCache[bookId] ?: return null}/page/$slug"
    }

    private suspend fun updateExistingPage(
        bookId: Int,
        existingPageId: Int,
        payload: Map<String, Any>,
        pageTitle: String
    ): String {
        val request = Request.Builder()
            .url("$bookstackUrl/api/pages/$existingPageId")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .put(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        val bodyString = executeWithRetry(request)
        val json = JsonParser.parseString(bodyString).asJsonObject
        val slug = json.get("slug")?.asString ?: throw Exception("No slug in response")
        val pageUrl = resolvePageUrlFromResponse(bookId, json, slug)

        logger.debug { "Updated BookStack page: $pageTitle (ID: $existingPageId)" }
        return pageUrl
    }

    private suspend fun recoverTimedOutCreate(
        bookId: Int,
        chapterId: Int?,
        cacheKey: String,
        payload: Map<String, Any>,
        doc: BookStackDocument
    ): String? {
        repeat(3) { attempt ->
            val existing = runCatching { findExistingPage(bookId, chapterId, doc.pageTitle) }.getOrNull()
            if (existing != null) {
                pageCache[cacheKey] = existing.id
                logger.warn {
                    "Recovered BookStack page '${doc.pageTitle}' after create failure using existing page ID ${existing.id}"
                }
                return resolvePageUrlFromLookup(bookId, existing)
                    ?: updateExistingPage(bookId, existing.id, payload, doc.pageTitle)
            }
            delay(1000L * (attempt + 1))
        }
        return null
    }

    private suspend fun createOrUpdatePage(bookId: Int, chapterId: Int?, doc: BookStackDocument): String {
        val cacheKey = "${bookId}:${chapterId ?: "null"}:${doc.pageTitle}"
        val payload = buildPagePayload(bookId, chapterId, doc)

        pageCache[cacheKey]?.let { existingPageId ->
            return updateExistingPage(bookId, existingPageId, payload, doc.pageTitle)
        }

        val createRequest = Request.Builder()
            .url("$bookstackUrl/api/pages")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        val bodyString = try {
            executeWithRetry(createRequest)
        } catch (e: Exception) {
            recoverTimedOutCreate(bookId, chapterId, cacheKey, payload, doc)?.let { return it }
            throw e
        }

        val json = JsonParser.parseString(bodyString).asJsonObject
        val pageId = json.get("id").asInt
        val slug = json.get("slug")?.asString ?: throw Exception("No slug in response")
        val pageUrl = resolvePageUrlFromResponse(bookId, json, slug)

        pageCache[cacheKey] = pageId
        logger.debug { "Created BookStack page: ${doc.pageTitle} (ID: $pageId)" }
        return pageUrl
    }
}

/**
 * Custom exception for retryable BookStack API errors.
 */
class RetryableBookStackException(message: String, cause: Throwable? = null) : Exception(message, cause)
