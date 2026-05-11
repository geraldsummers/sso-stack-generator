package org.webservices.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.webservices.pipeline.config.PipelineConfig
import org.webservices.pipeline.core.StandardizedRunner
import org.webservices.pipeline.monitoring.MonitoredSourceDefinition
import org.webservices.pipeline.monitoring.MonitoringServer
import org.webservices.pipeline.monitoring.PipelineStatsCache
import org.webservices.pipeline.monitoring.ProgressReporter
import org.webservices.pipeline.monitoring.SourceRuntimeTracker
import org.webservices.pipeline.sources.standardized.*
import org.webservices.pipeline.storage.DeduplicationStore
import org.webservices.pipeline.storage.DocumentStagingStore
import org.webservices.pipeline.storage.StagedDocument
import org.webservices.pipeline.storage.SourceMetadataStore
import org.webservices.pipeline.sentiment.RssSentimentAnalyzer
import org.webservices.pipeline.sentiment.RssSentimentSignalStore
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}
private const val DB_INIT_MAX_ATTEMPTS = 60
private const val DB_INIT_DELAY_MS = 2000L
private const val PIPELINE_METADATA_PATH_ENV = "PIPELINE_METADATA_PATH"
private const val PIPELINE_DEDUP_PATH_ENV = "PIPELINE_DEDUP_PATH"
private const val PIPELINE_METADATA_PATH_PROPERTY = "pipeline.metadata.path"
private const val PIPELINE_DEDUP_PATH_PROPERTY = "pipeline.dedup.path"
private const val DEFAULT_PIPELINE_METADATA_PATH = "/data/metadata"
private const val DEFAULT_PIPELINE_DEDUP_PATH = "/data/dedup/dedup.tsv"

fun main() {
    logger.info { "🔥 Knowledge ingestion starting" }

    val config = PipelineConfig.fromEnv()

    val dedupStore = DeduplicationStore(storePath = resolvePipelineDedupPath())
    val metadataStore = SourceMetadataStore(storePath = resolvePipelineMetadataPath())
    val monitoredSources = buildMonitoredSources(config)
    val runtimeTracker = SourceRuntimeTracker().also { tracker ->
        monitoredSources.filter { it.enabled }.forEach { tracker.registerSource(it.id) }
    }

    val stagingStore = withRetry(
        label = "DocumentStagingStore",
        maxAttempts = DB_INIT_MAX_ATTEMPTS,
        delayMs = DB_INIT_DELAY_MS
    ) {
        DocumentStagingStore(
            jdbcUrl = config.postgres.jdbcUrl,
            user = config.postgres.user,
            dbPassword = config.postgres.password
        )
    }
    val rssSentimentSignalStore = withRetry(
        label = "RssSentimentSignalStore",
        maxAttempts = DB_INIT_MAX_ATTEMPTS,
        delayMs = DB_INIT_DELAY_MS
    ) {
        RssSentimentSignalStore(
            jdbcUrl = config.postgres.jdbcUrl,
            user = config.postgres.user,
            password = config.postgres.password
        )
    }
    val rssSentimentAnalyzer = RssSentimentAnalyzer()

    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            dedupStore.flush()
            stagingStore.close()
            rssSentimentSignalStore.close()
        } catch (e: Exception) {
            logger.error(e) { "Error during shutdown: ${e.message}" }
        }
    })

    runBlocking {
        val statsCache = PipelineStatsCache(
            stagingStore = stagingStore,
            sourceIdsProvider = { monitoredSources.map { it.id } }
        )

        launch {
            statsCache.refreshLoop()
        }

        val monitoringServer = MonitoringServer(
            port = 8090,
            metadataStore = metadataStore,
            sources = monitoredSources,
            stagingStore = stagingStore,
            statsCache = statsCache,
            runtimeTracker = runtimeTracker
        )
        monitoringServer.start()

        recoverPersistedPipelineState(
            stagingStore = stagingStore,
            metadataStore = metadataStore,
            sourceIds = monitoredSources.filter { it.enabled }.map { it.id }
        )

        delay(1000)

        val progressReporter = ProgressReporter(
            stagingStore = stagingStore,
            statsCache = statsCache,
            reportIntervalSeconds = 30
        )

        launch {
            progressReporter.start()
        }

        if (config.rss.enabled) {
            launch {
                runStandardizedSource(
                    collectionName = config.qdrant.rssCollection,
                    stagingStore = stagingStore,
                    dedupStore = dedupStore,
                    metadataStore = metadataStore,
                    runtimeTracker = runtimeTracker,
                    onDocumentsStaged = { stagedDocs ->
                        val signals = stagedDocs.flatMap(rssSentimentAnalyzer::analyze)
                        rssSentimentSignalStore.persistBatch(signals)
                    }
                ) {
                    RssStandardizedSource(
                        feedUrls = config.rss.feedUrls,
                        backfillDays = 7
                    )
                }
            }
        }

        if (config.cve.enabled) {
            launch {
                runStandardizedSource(config.qdrant.cveCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                    CveStandardizedSource(
                        apiKey = config.cve.apiKey,
                        maxResults = config.cve.maxResults
                    )
                }
            }
        }

        if (config.torrents.enabled) {
            launch {
                runStandardizedSource(config.qdrant.torrentsCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                    TorrentsStandardizedSource(
                        dataPath = config.torrents.dataPath,
                        maxTorrents = config.torrents.maxResults
                    )
                }
            }
        }

        if (config.wikipedia.enabled) {
            launch {
                runStandardizedSource(config.qdrant.wikipediaCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                    WikipediaStandardizedSource(
                        dumpUrl = config.wikipedia.dumpPath,
                        maxArticles = config.wikipedia.maxArticles
                    )
                }
            }
        }

        if (config.australianLaws.enabled) {
            launch {
                runStandardizedSource(config.qdrant.australianLawsCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                    OpenAustralianLegalCorpusStandardizedSource(
                        cacheDir = "/data/australian-legal-corpus",
                        jurisdictions = if (config.australianLaws.jurisdictions.isNotEmpty())
                            config.australianLaws.jurisdictions else null,
                        maxDocuments = config.australianLaws.maxLawsPerJurisdiction * 100
                    )
                }
            }
        }

        if (config.linuxDocs.enabled) {
            launch {
                runStandardizedSource(config.qdrant.linuxDocsCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                    LinuxDocsStandardizedSource(
                        sources = config.linuxDocs.sources.mapNotNull {
                            try {
                                org.webservices.pipeline.sources.LinuxDocsSource.DocSource.valueOf(it.uppercase())
                            } catch (e: Exception) { null }
                        },
                        maxDocs = config.linuxDocs.maxDocs
                    )
                }
            }
        }

        if (config.stackKnowledge.enabled) {
            launch {
                runStandardizedSource(config.qdrant.stackKnowledgeCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                    StackKnowledgeStandardizedSource(
                        knowledgePath = config.stackKnowledge.path,
                        scheduleMinutes = config.stackKnowledge.scheduleMinutes
                    )
                }
            }
        }

        if (config.agentDocs.enabled) {
            launch {
                runStandardizedSource(config.qdrant.agentDocsCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                    AgentDocsStandardizedSource(
                        docsPath = config.agentDocs.path,
                        scheduleMinutes = config.agentDocs.scheduleMinutes
                    )
                }
            }
        }

        if (config.wiki.enabled) {
            if (config.wiki.wikiTypes.any { it.equals("debian", ignoreCase = true) }) {
                launch {
                    runStandardizedSource(config.qdrant.debianWikiCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                        DebianWikiStandardizedSource(
                            maxPages = config.wiki.maxPagesPerWiki,
                            categories = config.wiki.categories
                        )
                    }
                }
            }

            if (config.wiki.wikiTypes.any { it.equals("arch", ignoreCase = true) }) {
                launch {
                    runStandardizedSource(config.qdrant.archWikiCollection, stagingStore, dedupStore, metadataStore, runtimeTracker = runtimeTracker) {
                        ArchWikiStandardizedSource(
                            maxPages = config.wiki.maxPagesPerWiki,
                            categories = config.wiki.categories
                        )
                    }
                }
            }
        }

        awaitCancellation()
    }
}

internal fun resolvePipelineMetadataPath(
    env: Map<String, String> = System.getenv(),
    propertyLookup: (String) -> String? = System::getProperty
): String = resolvePath(
    envKey = PIPELINE_METADATA_PATH_ENV,
    propertyKey = PIPELINE_METADATA_PATH_PROPERTY,
    defaultValue = DEFAULT_PIPELINE_METADATA_PATH,
    env = env,
    propertyLookup = propertyLookup
)

internal fun resolvePipelineDedupPath(
    env: Map<String, String> = System.getenv(),
    propertyLookup: (String) -> String? = System::getProperty
): String = resolvePath(
    envKey = PIPELINE_DEDUP_PATH_ENV,
    propertyKey = PIPELINE_DEDUP_PATH_PROPERTY,
    defaultValue = DEFAULT_PIPELINE_DEDUP_PATH,
    env = env,
    propertyLookup = propertyLookup
)

private fun resolvePath(
    envKey: String,
    propertyKey: String,
    defaultValue: String,
    env: Map<String, String>,
    propertyLookup: (String) -> String?
): String {
    val envValue = env[envKey]?.trim()?.takeIf { it.isNotEmpty() }
    if (envValue != null) {
        return envValue
    }

    val propertyValue = propertyLookup(propertyKey)?.trim()?.takeIf { it.isNotEmpty() }
    return propertyValue ?: defaultValue
}

internal suspend fun recoverPersistedPipelineState(
    stagingStore: DocumentStagingStore,
    metadataStore: SourceMetadataStore,
    sourceIds: List<String>,
    queryTimeoutMs: Long = 5_000L,
    clock: Clock = Clock.systemUTC()
): Int {
    if (sourceIds.isEmpty()) {
        return 0
    }

    val now = Instant.now(clock).toString()
    var repaired = 0

    sourceIds.forEach { sourceId ->
        val readiness = stagingStore
            .getReadinessEvidenceBySourcesWithQueryTimeout(listOf(sourceId), queryTimeoutMs)
            ?.get(sourceId)
            ?: return@forEach
        val hasPersistedSearchState =
            readiness.searchableDocuments > 0L ||
                readiness.publishedDocuments > 0L ||
                readiness.pendingPublication > 0L ||
                readiness.skippedPublication > 0L
        if (!hasPersistedSearchState) {
            return@forEach
        }

        val metadata = metadataStore.load(sourceId)
        if (metadata.lastSuccessfulRun != null) {
            return@forEach
        }

        metadataStore.save(
            metadata.copy(
                lastSuccessfulRun = now,
                lastAttemptedRun = metadata.lastAttemptedRun ?: now
            )
        )
        repaired++
    }

    if (repaired > 0) {
        logger.info { "Recovered persisted pipeline completion state for $repaired sources from staging evidence" }
    }

    return repaired
}

private fun <T> withRetry(
    label: String,
    maxAttempts: Int,
    delayMs: Long,
    block: () -> T
): T {
    var lastError: Exception? = null
    for (attempt in 1..maxAttempts) {
        try {
            if (attempt > 1) {
                logger.info { "$label init retry $attempt/$maxAttempts" }
            }
            return block()
        } catch (e: Exception) {
            lastError = e
            logger.warn { "$label init failed ($attempt/$maxAttempts): ${e.message}" }
            if (attempt < maxAttempts) {
                Thread.sleep(delayMs)
            }
        }
    }
    throw IllegalStateException("$label failed to initialize after $maxAttempts attempts", lastError)
}

suspend fun <T : org.webservices.pipeline.core.Chunkable> runStandardizedSource(
    collectionName: String,
    stagingStore: DocumentStagingStore,
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore,
    runtimeTracker: SourceRuntimeTracker? = null,
    onDocumentsStaged: (suspend (List<StagedDocument>) -> Unit)? = null,
    sourceFactory: () -> org.webservices.pipeline.core.StandardizedSource<T>
) {
    val source = sourceFactory()

    val runner = StandardizedRunner(
        source = source,
        collectionName = collectionName,
        stagingStore = stagingStore,
        dedupStore = dedupStore,
        metadataStore = metadataStore,
        onDocumentsStaged = onDocumentsStaged,
        runtimeTracker = runtimeTracker
    )

    runner.run()
}

private fun buildMonitoredSources(config: PipelineConfig): List<MonitoredSourceDefinition> {
    return listOf(
        MonitoredSourceDefinition("rss", "RSS Feeds", "Aggregates news and blog feeds", config.rss.enabled),
        MonitoredSourceDefinition("cve", "CVE Database", "Security vulnerabilities from NVD", config.cve.enabled),
        MonitoredSourceDefinition("torrents", "Torrents CSV", "Torrent metadata from DHT", config.torrents.enabled),
        MonitoredSourceDefinition("wikipedia", "Wikipedia", "Wikipedia article dumps", config.wikipedia.enabled),
        MonitoredSourceDefinition("australian_laws", "Australian Laws", "Legal documents from legislation.gov.au", config.australianLaws.enabled),
        MonitoredSourceDefinition("linux_docs", "Linux Documentation", "Kernel and system documentation", config.linuxDocs.enabled),
        MonitoredSourceDefinition("stack_knowledge", "Stack Knowledge", "Platform documentation for AI agents and operators", config.stackKnowledge.enabled),
        MonitoredSourceDefinition(
            "debian_wiki",
            "Debian Wiki",
            "Debian community documentation",
            config.wiki.enabled && config.wiki.wikiTypes.any { it.equals("debian", ignoreCase = true) }
        ),
        MonitoredSourceDefinition(
            "arch_wiki",
            "Arch Wiki",
            "Arch Linux documentation",
            config.wiki.enabled && config.wiki.wikiTypes.any { it.equals("arch", ignoreCase = true) }
        )
    )
}
