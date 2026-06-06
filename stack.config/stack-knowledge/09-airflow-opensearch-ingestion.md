# Airflow and OpenSearch Ingestion Runtime

The active ingestion and search runtime is no longer the custom `knowledge-ingestion`,
`embedding-worker`, `content-publisher`, `search-service`, or `vector-bootstrap`
path.

Active services:

- `airflow-init`
- `airflow-webserver`
- `airflow-scheduler`
- `nats`
- `opensearch`
- `ingestion-runner`
- existing `embedding-gpu`
- existing `qdrant`
- existing `postgres-ssd`
- existing `bookstack`

Airflow owns scheduling, retries, run history, bootstrap orchestration, and
publication tasks. NATS JetStream is the bounded durable batch stream. The
`ingestion-runner` performs source fetch/parse/chunk/embed/upsert work and writes
lightweight checkpoint, run, error, and publication metadata to PostgreSQL.

Search uses official backend APIs:

- OpenSearch for text and BM25 search at `https://opensearch:9200`
- Qdrant for vector search at `http://qdrant:6333`

The protected external routes are:

- `https://pipeline.<domain>` for Airflow
- `https://search.<domain>` for OpenSearch

Runtime metadata tables:

- `ingestion_sources`
- `ingestion_runs`
- `ingestion_checkpoints`
- `ingestion_errors`
- `publication_records`

Legacy tables such as `document_staging`, `fetch_history`, and `dedupe_records`
are not part of the active ingestion path.
