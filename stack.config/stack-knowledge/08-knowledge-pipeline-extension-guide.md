# Knowledge Pipeline Extension Guide

This is the agent-facing contract for adding new knowledge ingestion sources to the webservices stack.

The goal is that an agent can add a source, stage it for embedding, publish it to BookStack when useful, and query it through the search layer without inventing a separate ingestion path.

## Architecture

The production path is:

```text
StandardizedSource<T : Chunkable>
  -> StandardizedRunner
  -> DocumentStagingStore in Postgres
  -> embedding-worker
  -> Qdrant collection
  -> OpenSearch index + Qdrant collection
  -> optional content-publisher to BookStack
```

Do not write vectors directly from a source unless the task explicitly requires a special-case fast path. The normal staging path gives the stack deduplication, retry state, monitoring, exact document retrieval, BookStack publication state, and Grafana-ready source metrics.

## Source Types

Prefer `StandardizedSource<T : Chunkable>` for all new sources.

Use a plain `Source<T>` only for legacy or one-off local transformations. New production sources should implement:

- `name`: stable source id, lower snake case, for example `openalex` or `python_docs`.
- `backfillStrategy()`: how the first run discovers historical content.
- `resyncStrategy()`: how repeated runs discover updates.
- `needsChunking()`: true for long documents.
- `fetchForRun(metadata)`: emits source items as a Kotlin `Flow`.

Each emitted item implements `Chunkable`:

- `toText()`: clean plain text for embedding.
- `getId()`: stable globally unique id.
- `getMetadata()`: search and publication metadata.

Minimum metadata keys:

```kotlin
mapOf(
    "source" to "my_source",
    "title" to title,
    "url" to canonicalUrl,
    "content_type" to "documentation",
    "audience" to "agent"
)
```

Use `audience=agent` for operational, API, code, CLI, schema, or troubleshooting material. Use `audience=human` for readable articles. Use `audience=both` when the same item is useful to people and agents.

## Files To Touch

For a new source named `my_source`, expect to touch these files:

- `stack.kotlin/knowledge-ingestion/src/main/kotlin/org/webservices/pipeline/sources/standardized/MySourceStandardizedSource.kt`
- `stack.kotlin/knowledge-ingestion/src/main/kotlin/org/webservices/pipeline/KnowledgeIngestionMain.kt`
- `stack.kotlin/pipeline-common/src/main/kotlin/org/webservices/pipeline/config/Config.kt`
- Source tests under `stack.kotlin/knowledge-ingestion/src/test/kotlin/...`
- Config tests under `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/config/ConfigTest.kt`
- BookStack mapping tests if publishing is required: `stack.kotlin/content-publisher/src/test/kotlin/org/webservices/pipeline/workers/BookStackWriterTest.kt`
- Search ranking or presentation tests if metadata changes: `stack.kotlin/search-service/src/test/kotlin/org/webservices/searchservice/`

If the source needs a container dependency, cache directory, or environment variable, update the relevant compose source under `stack.compose/` and source config under `stack.config/`.

## Implementation Template

```kotlin
package org.webservices.pipeline.sources.standardized

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.webservices.pipeline.core.Chunkable
import org.webservices.pipeline.core.StandardizedSource
import org.webservices.pipeline.scheduling.BackfillStrategy
import org.webservices.pipeline.scheduling.ResyncStrategy
import org.webservices.pipeline.scheduling.RunMetadata

data class MySourceDocument(
    val id: String,
    val title: String,
    val text: String,
    val url: String,
    val updatedAt: String?
) : Chunkable {
    override fun toText(): String = buildString {
        appendLine(title)
        appendLine()
        append(text)
    }

    override fun getId(): String = id

    override fun getMetadata(): Map<String, String> = buildMap {
        put("source", "my_source")
        put("title", title)
        put("url", url)
        put("content_type", "documentation")
        put("audience", "agent")
        updatedAt?.let { put("updated_at", it) }
    }
}

class MySourceStandardizedSource(
    private val endpoint: String,
    private val maxDocuments: Int
) : StandardizedSource<MySourceDocument> {
    override val name = "my_source"

    override fun resyncStrategy(): ResyncStrategy = ResyncStrategy.DailyAt(hour = 2, minute = 0)

    override fun backfillStrategy(): BackfillStrategy = BackfillStrategy.NoBackfill

    override fun needsChunking(): Boolean = true

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<MySourceDocument> {
        return fetchDocuments(endpoint, maxDocuments).asFlow()
    }

    private fun fetchDocuments(endpoint: String, maxDocuments: Int): List<MySourceDocument> {
        TODO("Fetch, validate, normalize, and bound results")
    }
}
```

Keep `getId()` stable across runs. If the upstream item has no stable id, derive one from a canonical URL or source-specific namespace plus an immutable title. Do not include fetched timestamps in the id unless every fetch is supposed to create a new document.

## Wiring The Source

Add config in `PipelineConfig`:

```kotlin
val mySource: MySourceConfig = MySourceConfig()
```

Add an env-backed config block in `fromEnv()`:

```kotlin
mySource = MySourceConfig(
    enabled = getEnvOrPropertyBoolean("MY_SOURCE_ENABLED", false),
    endpoint = getEnvOrProperty("MY_SOURCE_ENDPOINT") ?: "https://example.invalid/export.json",
    maxDocuments = getEnvOrPropertyInt("MY_SOURCE_MAX_DOCUMENTS", 1000, min = 1)
)
```

Add a collection in `QdrantConfig`:

```kotlin
mySourceCollection = getEnvOrProperty("QDRANT_MY_SOURCE_COLLECTION") ?: "my_source"
```

Launch it in `KnowledgeIngestionMain.kt`:

```kotlin
if (config.mySource.enabled) {
    launch {
        runStandardizedSource(
            config.qdrant.mySourceCollection,
            stagingStore,
            dedupStore,
            metadataStore,
            runtimeTracker = runtimeTracker
        ) {
            MySourceStandardizedSource(
                endpoint = config.mySource.endpoint,
                maxDocuments = config.mySource.maxDocuments
            )
        }
    }
}
```

Add monitoring:

```kotlin
MonitoredSourceDefinition("my_source", "My Source", "Short useful description", config.mySource.enabled)
```

## Storage And Search Metadata

The runner writes `StagedDocument` rows with:

- `source`: the source id from `StandardizedSource.name`.
- `collection`: the Qdrant collection configured for the source.
- `text`: `toText()` output.
- `metadata`: `getMetadata()`.
- `embedding_status=PENDING`.

The embedding worker later writes vectors to Qdrant and marks rows `COMPLETED`.

Search quality depends heavily on metadata. Use:

- `title`: concise human-readable title.
- `url`: canonical source URL if available.
- `published_at` or `updated_at`: ISO timestamp if available.
- `content_type`: `documentation`, `article`, `reference`, `code`, `dataset`, `security_advisory`, or another stable type.
- `audience`: `agent`, `human`, or `both`.
- `document_id`: only if different from `getId()` and useful for exact retrieval.

For agent-optimized sources, include metadata that lets an agent decide whether the result is actionable without opening it: service name, command, language, package, schema, API path, version, platform, or error code.

## BookStack Publication

Not every source should publish to BookStack.

Publish to BookStack when:

- Humans should browse or search it in the wiki.
- The source is curated or useful as readable reference material.
- The generated page can have stable book/chapter/page placement.

Skip BookStack when:

- The source is high-volume, noisy, or mainly machine-readable.
- The source is time-series or dashboard-oriented.
- The source is legally or operationally better kept as indexed-only metadata.

The content publisher polls completed staged documents. To control publication, update `BookStackWriter.toBookStackDocument` and tests. Use source-specific books and chapters, and include a source link plus generated-page notice. If a source should not publish, make the writer mark it with the skipped publication URL prefix rather than repeatedly retrying it.

When publishing, set or preserve these metadata keys if applicable:

- `bookstack_url`: after publish, set by the writer.
- `presentation_target=bookstack`
- `presentation_url=<public BookStack URL>`
- `search_ready=true`

The search layer prefers a usable `presentation_url` or `bookstack_url` for human-friendly results.

## Querying The New Source

From an agent workspace, use:

```bash
stack-search --collection my_source --mode hybrid --audience agent "query terms"
stack-search --collection my_source --mode bm25 "exact error or identifier"
stack-doc-get '<document-id>' my_source
```

Direct API:

```bash
curl -fsS -H "Authorization: Bearer ${STACK_AGENT_TOKEN}" \
  -H 'Content-Type: application/json' \
  -X POST \
  --data '{"query":"query terms","mode":"hybrid","collections":["my_source"],"limit":5,"audience":"agent"}' \
  "${STACK_KNOWLEDGE_SEARCH_URL}"
```

Check source readiness:

```bash
curl -fsS http://knowledge-ingestion:8090/sources
curl -fsS http://knowledge-ingestion:8090/status
curl -fsS http://knowledge-ingestion:8090/readiness
curl -fsS http://knowledge-ingestion:8090/queue/my_source
```

Use the workspace proxy and helper commands when available. Do not log stack tokens.

## Testing Checklist

Minimum local tests before committing:

```bash
./stack.containers/test-runner/run-tests.sh ts-unit
./gradlew :pipeline-common:test :knowledge-ingestion:test :content-publisher:test :search-service:test
```

If the source changes stack contracts or live service behavior, run:

```bash
./build.sh --manifest /path/to/site/manifest.json
```

After deploy, verify:

```bash
cd ~/webservices && ./verify.sh
cd ~/webservices && ./run-tests.sh kt-live-ingestion
```

For a new source, tests should cover:

- Config defaults and env parsing.
- Source fetch normalization with bounded result counts.
- Stable `getId()` behavior.
- Metadata contains `source`, `title`, `content_type`, and `audience`.
- Chunking behavior for long documents.
- BookStack mapping or explicit skip behavior.
- Search result presentation if the source has special URLs or capabilities.

## Operational Rules

- Bound all backfills with a max document count, date range, page limit, or cursor.
- Keep source credentials in SOPS-rendered runtime env, never in repo files.
- Cache bulk downloads under `/data/...` or another declared volume path.
- Prefer HTTP clients and parsers over shelling out to source-specific CLIs.
- Normalize malformed source data before staging.
- Treat upstream failures as source failures, not as reasons to crash unrelated sources.
- Add dashboards or readiness evidence for long-running or high-volume sources.
- Update this guide when adding a new source pattern.
