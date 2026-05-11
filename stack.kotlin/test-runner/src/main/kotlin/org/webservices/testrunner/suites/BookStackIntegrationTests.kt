package org.webservices.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.PipelineSourceReadiness
import org.webservices.testrunner.framework.TestRunner
import org.webservices.testrunner.framework.requireSourcePublicationReady


suspend fun TestRunner.bookStackIntegrationTests() {
    suite("BookStack Integration Tests") {
        val runner = this@bookStackIntegrationTests
        val publicationReadinessCache = mutableMapOf<String, PipelineSourceReadiness?>()

        suspend fun getBookStackResponse(path: String, attempts: Int = 24, delayMs: Long = 5000): HttpResponse {
            val suffix = if (path.startsWith("/")) path else "/$path"
            val url = "${endpoints.bookstack}$suffix"

            repeat(attempts) { attempt ->
                try {
                    val response = client.getRawResponse(url)
                    if (response.status.value < 500) {
                        return response
                    }
                    println("      ℹ️  BookStack returned ${response.status} at $suffix (attempt ${attempt + 1}/$attempts)")
                } catch (e: Exception) {
                    println("      ℹ️  BookStack request failed at $suffix (attempt ${attempt + 1}/$attempts): ${e.message}")
                }

                if (attempt < attempts - 1) {
                    delay(delayMs)
                }
            }

            error("BookStack did not return a stable response for $suffix after $attempts attempts")
        }

        suspend fun bookStackResponse(path: String): HttpResponse = getBookStackResponse(path)

        suspend fun waitForBookByName(name: String, attempts: Int, delayMs: Long): JsonArray {
            repeat(attempts) { attempt ->
                val response = bookStackResponse("/api/books?filter[name]=${name.replace(" ", "%20")}")
                if (response.status == HttpStatusCode.Unauthorized) {
                    error("BookStack authentication required while checking '$name' book")
                }
                require(response.status == HttpStatusCode.OK) {
                    "BookStack returned ${response.status} while checking '$name' book"
                }
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val books = json["data"]?.jsonArray
                if (!books.isNullOrEmpty()) {
                    return books
                }
                if (attempt < attempts - 1) {
                    println("      ℹ️  Waiting for '$name' book publication (attempt ${attempt + 1}/$attempts)")
                    delay(delayMs)
                }
            }
            error("'$name' book not found in BookStack after waiting for publication")
        }

        suspend fun waitForBookContents(bookId: Int, name: String, attempts: Int, delayMs: Long): JsonArray {
            repeat(attempts) { attempt ->
                val bookDetailResponse = bookStackResponse("/api/books/$bookId")
                require(bookDetailResponse.status == HttpStatusCode.OK) {
                    "BookStack returned ${bookDetailResponse.status} while checking '$name' contents"
                }
                val bookDetail = Json.parseToJsonElement(bookDetailResponse.bodyAsText()).jsonObject
                val contents = bookDetail["contents"]?.jsonArray
                if (!contents.isNullOrEmpty()) {
                    return contents
                }
                if (attempt < attempts - 1) {
                    println("      ℹ️  Waiting for '$name' contents publication (attempt ${attempt + 1}/$attempts)")
                    delay(delayMs)
                }
            }
            error("'$name' book exists but has no contents after waiting for publication")
        }

        test("BookStack: API is accessible and authenticated") {
            val response = bookStackResponse("/api/books")

            if (response.status == HttpStatusCode.Unauthorized) {
                fail("BookStack requires authentication; configure BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET")
            }

            response.status shouldBe HttpStatusCode.OK
            println("      ✓ BookStack API is accessible and authenticated")
        }

        
        
        

        test("BookStack: RSS Feeds book exists") {
            requireSourcePublicationReady(runner, publicationReadinessCache, "rss", "RSS BookStack publication")
            val response = getBookStackResponse("/api/books?filter[name]=RSS%20Articles", attempts = 24, delayMs = 5000)

            if (response.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while checking RSS Feeds book")
            }

            response.status shouldBe HttpStatusCode.OK

            val books = waitForBookByName("RSS Articles", attempts = 18, delayMs = 5000)

            val book = books.first().jsonObject
            val bookName = book["name"]?.jsonPrimitive?.content

            bookName shouldBe "RSS Articles"
            println("      ✓ Found 'RSS Articles' book in BookStack")

            val description = book["description"]?.jsonPrimitive?.content
            require(description != null) { "RSS Feeds book description missing" }
            description shouldContain "Aggregated RSS articles"
            println("      ✓ Book has correct description")
        }

        test("BookStack: CVE Database book exists") {
            requireSourcePublicationReady(runner, publicationReadinessCache, "cve", "CVE BookStack publication")
            val books = waitForBookByName("CVE Database", attempts = 24, delayMs = 5000)
            val book = books.first().jsonObject
            val bookName = book["name"]?.jsonPrimitive?.content

            bookName shouldBe "CVE Database"
            println("      ✓ Found 'CVE Database' book in BookStack")

            val description = book["description"]?.jsonPrimitive?.content
            require(description != null) { "CVE Database book description missing" }
            require(
                description.contains("Security vulnerabilities", ignoreCase = true) ||
                    description.contains("Auto-generated by webservices Pipeline", ignoreCase = true)
            ) { "Unexpected CVE Database book description: $description" }
            println("      ✓ Book has acceptable description")
        }

        test("BookStack: Wikipedia book exists") {
            requireSourcePublicationReady(runner, publicationReadinessCache, "wikipedia", "Wikipedia BookStack publication")
            val response = bookStackResponse("/api/books?filter[name]=Wikipedia%20Articles")

            if (response.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while checking Wikipedia book")
            }

            response.status shouldBe HttpStatusCode.OK

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val books = json["data"]?.jsonArray

            require(!books.isNullOrEmpty()) { "'Wikipedia Articles' book not found in BookStack" }
            println("      ✓ Found 'Wikipedia Articles' book in BookStack")
        }

        test("BookStack: Linux Documentation book exists") {
            requireSourcePublicationReady(runner, publicationReadinessCache, "linux_docs", "Linux docs BookStack publication")
            val books = waitForBookByName("Linux Documentation", attempts = 18, delayMs = 5000)
            println("      ✓ Found 'Linux Documentation' book in BookStack")
        }

        
        
        

        test("BookStack: RSS book has chapters organized by feed") {
            requireSourcePublicationReady(runner, publicationReadinessCache, "rss", "RSS BookStack publication")
            val booksResponse = bookStackResponse("/api/books?filter[name]=RSS%20Articles")

            if (booksResponse.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while checking RSS chapters")
            }

            val booksJson = Json.parseToJsonElement(booksResponse.bodyAsText()).jsonObject
            val books = booksJson["data"]?.jsonArray

            require(!books.isNullOrEmpty()) { "RSS Articles book not found" }
            val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
            require(bookId != null) { "RSS Articles book id missing" }
            val bookDetailResponse = bookStackResponse("/api/books/$bookId")
            val bookDetail = Json.parseToJsonElement(bookDetailResponse.bodyAsText()).jsonObject
            val contents = bookDetail["contents"]?.jsonArray
            require(!contents.isNullOrEmpty()) { "RSS Articles book has no contents" }

            val chapters = contents.filter {
                it.jsonObject["type"]?.jsonPrimitive?.content == "chapter"
            }
            require(chapters.isNotEmpty()) { "RSS Articles book has no chapters" }
            println("      ✓ Found ${chapters.size} chapters in RSS Articles book")
            val chapterNames = chapters.map {
                it.jsonObject["name"]?.jsonPrimitive?.content
            }
            println("      ℹ️  Chapters: ${chapterNames.joinToString(", ")}")
        }

        test("BookStack: CVE book has chapters organized by severity") {
            requireSourcePublicationReady(runner, publicationReadinessCache, "cve", "CVE BookStack publication")
            val books = waitForBookByName("CVE Database", attempts = 24, delayMs = 5000)
            val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
            require(bookId != null) { "CVE Database book id missing" }
            val contents = waitForBookContents(bookId, "CVE Database", attempts = 18, delayMs = 5000)

            val chapters = contents.filter {
                it.jsonObject["type"]?.jsonPrimitive?.content == "chapter"
            }
            require(chapters.isNotEmpty()) { "CVE Database book has no chapters" }
            println("      ✓ Found ${chapters.size} chapters in CVE Database book")
            val chapterNames = chapters.map {
                it.jsonObject["name"]?.jsonPrimitive?.content
            }

            println("      ℹ️  Chapters: ${chapterNames.joinToString(", ")}")

            val hasSeverityChapters = chapterNames.any {
                it in listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")
            }
            require(hasSeverityChapters) { "CVE chapters are not organized by severity" }
            println("      ✓ CVE chapters are organized by severity")
        }

        
        
        

        test("BookStack: RSS pages contain proper HTML formatting") {
            val pagesResponse = bookStackResponse("/api/pages?count=1")

            if (pagesResponse.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while checking HTML formatting")
            }

            val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = pagesJson["data"]?.jsonArray

            require(!pages.isNullOrEmpty()) { "No pages found in BookStack" }
            val firstPageId = pages.first().jsonObject["id"]?.jsonPrimitive?.int
            require(firstPageId != null) { "BookStack page id missing" }
            val pageDetailResponse = bookStackResponse("/api/pages/$firstPageId")
            val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
            val html = pageDetail["html"]?.jsonPrimitive?.content
            require(html != null) { "BookStack page HTML missing" }

            require(html.contains("<") && html.contains(">")) { "BookStack page does not contain HTML markup" }
            require(
                html.contains("<h1>") ||
                    html.contains("<h2>") ||
                    html.contains("<p>") ||
                    html.contains("<div")
            ) { "BookStack page HTML does not contain structured content tags" }

            println("      ✓ Page has proper HTML formatting")
        }

        test("BookStack: Pages render structured content without raw markdown artifacts") {
            val pagesResponse = bookStackResponse("/api/pages?count=100")

            if (pagesResponse.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while checking structured content")
            }

            val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = pagesJson["data"]?.jsonArray ?: emptyList<JsonElement>()
            require(pages.isNotEmpty()) { "No pages found in BookStack" }

            var checkedPages = 0
            var structuredPages = 0

            for (page in pages) {
                val pageId = page.jsonObject["id"]?.jsonPrimitive?.int ?: continue
                val pageDetailResponse = bookStackResponse("/api/pages/$pageId")
                if (pageDetailResponse.status != HttpStatusCode.OK) continue

                val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
                val html = pageDetail["html"]?.jsonPrimitive?.content ?: continue
                val tags = pageDetail["tags"]?.jsonArray ?: emptyList<JsonElement>()
                val isPipelinePage = html.contains("webservices Pipeline", ignoreCase = true) ||
                    tags.any { tag ->
                        tag.jsonObject["name"]?.jsonPrimitive?.content.equals("source", ignoreCase = true)
                    }
                if (!isPipelinePage) continue

                checkedPages++

                require(!html.contains("```")) { "Page contains raw markdown code fences" }
                require(!Regex("(?m)^#\\s+").containsMatchIn(html)) { "Page contains raw markdown headings" }

                val hasStructure = html.contains("<h2>") ||
                    html.contains("<h3>") ||
                    html.contains("<ul>") ||
                    html.contains("<pre") ||
                    html.contains("<p>") ||
                    html.contains("<div")
                if (hasStructure) {
                    structuredPages++
                }

                if (checkedPages >= 5) break
            }

            require(checkedPages > 0) { "No pipeline-managed BookStack pages available to inspect" }

            require(structuredPages > 0) { "No inspected pages contained structured HTML sections" }
            println("      ✓ $structuredPages/$checkedPages inspected pipeline pages contain structured HTML content")
        }

        test("BookStack: Pages have proper tags") {
            val pagesResponse = bookStackResponse("/api/pages?count=5")

            if (pagesResponse.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while checking page tags")
            }

            val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = pagesJson["data"]?.jsonArray

            require(!pages.isNullOrEmpty()) { "No pages found in BookStack" }
            run {
                var pagesWithTags = 0
                var pagesWithSourceTag = 0

                pages.take(5).forEach { pageElement ->
                    val pageId = pageElement.jsonObject["id"]?.jsonPrimitive?.int

                    if (pageId != null) {
                        val pageDetailResponse = bookStackResponse("/api/pages/$pageId")
                        val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
                        val tags = pageDetail["tags"]?.jsonArray

                        if (!tags.isNullOrEmpty()) {
                            pagesWithTags++

                            
                            val hasSourceTag = tags.any {
                                it.jsonObject["name"]?.jsonPrimitive?.content == "source"
                            }

                            if (hasSourceTag) {
                                pagesWithSourceTag++
                            }
                        }
                    }
                }

                if (pagesWithTags > 0) {
                    println("      ✓ Found $pagesWithTags pages with tags")
                }

                require(pagesWithTags > 0) { "No inspected BookStack pages had tags" }
                require(pagesWithSourceTag > 0) { "No inspected BookStack pages had a 'source' tag" }
                println("      ✓ Found $pagesWithSourceTag pages with 'source' tag")
            }
        }

        
        
        

        test("BookStack: RSS pages contain article metadata") {
            requireSourcePublicationReady(runner, publicationReadinessCache, "rss", "RSS BookStack publication")
            val booksResponse = bookStackResponse("/api/books?filter[name]=RSS%20Articles")

            if (booksResponse.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while checking RSS page metadata")
            }

            val booksJson = Json.parseToJsonElement(booksResponse.bodyAsText()).jsonObject
            val books = booksJson["data"]?.jsonArray
            require(!books.isNullOrEmpty()) { "RSS Articles book not found" }
            val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
            require(bookId != null) { "RSS Articles book id missing" }

            val pagesResponse = bookStackResponse("/api/pages?filter[book_id]=$bookId")

            if (pagesResponse.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while checking RSS page metadata")
            }

            val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = pagesJson["data"]?.jsonArray

            require(!pages.isNullOrEmpty()) { "No RSS pages found in BookStack" }
            val firstPageId = pages.first().jsonObject["id"]?.jsonPrimitive?.int
            require(firstPageId != null) { "RSS page id missing" }
            val pageDetailResponse = bookStackResponse("/api/pages/$firstPageId")
            val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
            val html = pageDetail["html"]?.jsonPrimitive?.content
            require(html != null) { "RSS page HTML missing" }

            val hasSourceMetadata = html.contains("Feed:") || html.contains("Article:") || html.contains("Source:")
            require(hasSourceMetadata) { "RSS page missing source metadata" }

            val hasMetadata = html.contains("Published:") ||
                html.contains("Author:") ||
                html.contains("Categories:")
            require(hasMetadata) { "RSS page missing article metadata" }
            println("      ✓ RSS page contains article metadata")
        }

        test("BookStack: CVE pages contain vulnerability details") {
            requireSourcePublicationReady(runner, publicationReadinessCache, "cve", "CVE BookStack publication")
            val books = waitForBookByName("CVE Database", attempts = 24, delayMs = 5000)
            val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
            require(bookId != null) { "CVE Database book id missing" }
            val pagesResponse = bookStackResponse("/api/pages?filter[book_id]=$bookId&count=1")
            val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = pagesJson["data"]?.jsonArray
            require(!pages.isNullOrEmpty()) { "No CVE pages found in BookStack" }
            val pageId = pages.first().jsonObject["id"]?.jsonPrimitive?.int
            require(pageId != null) { "CVE page id missing" }
            val pageDetailResponse = bookStackResponse("/api/pages/$pageId")
            val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
            val html = pageDetail["html"]?.jsonPrimitive?.content
            require(html != null) { "CVE page HTML missing" }

            html shouldContain "Severity:"
            val hasVulnerabilityIdentifier = html.contains("CVE-", ignoreCase = true) || html.contains("View advisory")
            require(hasVulnerabilityIdentifier) {
                "CVE page missing vulnerability identifier or advisory link"
            }

            println("      ✓ CVE page contains vulnerability details")
        }

        
        
        

        test("BookStack: Count total books created by pipeline") {
            val response = bookStackResponse("/api/books")

            if (response.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while counting pipeline books")
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val books = json["data"]?.jsonArray

            require(books != null) { "BookStack books response missing data array" }

            val pipelineBooks = listOf(
                "RSS Articles",
                "CVE Database",
                "Wikipedia Articles",
                "Australian Legal Corpus",
                "Linux Documentation",
                "Debian Wiki",
                "Arch Wiki"
            )

            val foundBooks = books.filter {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                name in pipelineBooks
            }

            require(foundBooks.isNotEmpty()) { "No pipeline-generated books found in BookStack" }
            println("      ✓ Found ${foundBooks.size}/${pipelineBooks.size} pipeline-generated books in BookStack")

            foundBooks.forEach {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                println("        • $name")
            }
        }

        test("BookStack: Count total pages created by pipeline") {
            val response = bookStackResponse("/api/pages?count=1000")

            if (response.status == HttpStatusCode.Unauthorized) {
                fail("BookStack authentication required while counting pipeline pages")
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val pages = json["data"]?.jsonArray

            require(pages != null) { "BookStack pages response missing data array" }
            println("      ✓ Found ${pages.size} total pages in BookStack")

            var pipelinePages = 0
            pages.take(20).forEach { pageElement ->
                val pageId = pageElement.jsonObject["id"]?.jsonPrimitive?.int ?: return@forEach
                val pageDetailResponse = bookStackResponse("/api/pages/$pageId")
                if (pageDetailResponse.status != HttpStatusCode.OK) {
                    return@forEach
                }

                val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
                val tags = pageDetail["tags"]?.jsonArray ?: return@forEach
                val hasSourceTag = tags.any {
                    val tagName = it.jsonObject["name"]?.jsonPrimitive?.content
                    tagName == "source"
                }
                if (hasSourceTag) {
                    pipelinePages++
                }
            }

            require(pipelinePages > 0) { "No BookStack pages had pipeline source tags" }
            println("      ✓ At least $pipelinePages pages have pipeline source tags")
        }
    }
}
