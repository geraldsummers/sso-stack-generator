from __future__ import annotations

import bz2
import hashlib
import json
import os
import re
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import feedparser
import psycopg
import requests
from bs4 import BeautifulSoup
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


COLLECTIONS = {
    "wikipedia": "wikipedia",
    "rss": "rss_feeds",
    "cve": "cve",
    "torrents": "torrents",
    "australian_laws": "australian_laws",
    "linux_docs": "linux_docs",
    "debian_wiki": "debian_wiki",
    "arch_wiki": "arch_wiki",
    "opendota": "opendota_matches",
    "poe_ninja": "poe_ninja_prices",
    "stack_knowledge": "stack_knowledge",
    "agent_docs": "agent_docs",
}
SEARCH_INDEX = os.getenv("OPENSEARCH_INDEX", "knowledge")


class RunRequest(BaseModel):
    source: str
    limit: int | None = None
    publish: bool = False


app = FastAPI(title="webservices ingestion runner")


def utcnow() -> str:
    return datetime.now(UTC).isoformat()


def pg_conn() -> psycopg.Connection:
    return psycopg.connect(os.environ["POSTGRES_DSN"])


def qdrant_headers() -> dict[str, str]:
    key = os.getenv("QDRANT_API_KEY", "")
    return {"api-key": key} if key else {}


def opensearch_auth() -> tuple[str, str]:
    return (
        os.getenv("OPENSEARCH_USERNAME", "admin"),
        os.environ["OPENSEARCH_PASSWORD"],
    )


def doc_id(source: str, stable_key: str, chunk_index: int) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"{source}\0{stable_key}\0{chunk_index}"))


def chunk_text(text: str, max_chars: int = 1800) -> list[str]:
    cleaned = re.sub(r"\s+", " ", text).strip()
    if not cleaned:
        return []
    chunks: list[str] = []
    start = 0
    while start < len(cleaned):
        end = min(start + max_chars, len(cleaned))
        if end < len(cleaned):
            split = cleaned.rfind(" ", start, end)
            if split > start + 400:
                end = split
        chunks.append(cleaned[start:end].strip())
        start = end
    return chunks


def ensure_source(source: str) -> None:
    with pg_conn() as conn:
        conn.execute(
            """
            INSERT INTO ingestion_sources (id, source_type, enabled, config)
            VALUES (%s, %s, true, '{}'::jsonb)
            ON CONFLICT (id) DO UPDATE SET updated_at = now()
            """,
            (source, source),
        )


def start_run(source: str) -> str:
    ensure_source(source)
    run_id = str(uuid.uuid4())
    with pg_conn() as conn:
        conn.execute(
            """
            INSERT INTO ingestion_runs (id, source_id, dag_id, status, metadata)
            VALUES (%s, %s, %s, 'running', '{}'::jsonb)
            """,
            (run_id, source, f"{source}_ingestion"),
        )
    return run_id


def finish_run(run_id: str, status: str, seen: int, indexed: int, errors: int, metadata: dict[str, Any]) -> None:
    with pg_conn() as conn:
        conn.execute(
            """
            UPDATE ingestion_runs
            SET status = %s,
                finished_at = now(),
                records_seen = %s,
                records_indexed = %s,
                error_count = %s,
                metadata = %s::jsonb
            WHERE id = %s
            """,
            (status, seen, indexed, errors, json.dumps(metadata), run_id),
        )


def write_checkpoint(source: str, key: str, value: dict[str, Any]) -> None:
    with pg_conn() as conn:
        conn.execute(
            """
            INSERT INTO ingestion_checkpoints (source_id, checkpoint_key, checkpoint_value)
            VALUES (%s, %s, %s::jsonb)
            ON CONFLICT (source_id) DO UPDATE
            SET checkpoint_key = EXCLUDED.checkpoint_key,
                checkpoint_value = EXCLUDED.checkpoint_value,
                updated_at = now()
            """,
            (source, key, json.dumps(value)),
        )


def record_error(run_id: str, source: str, item_id: str, error: Exception) -> None:
    with pg_conn() as conn:
        conn.execute(
            """
            INSERT INTO ingestion_errors (run_id, source_id, item_id, error_type, error_message)
            VALUES (%s, %s, %s, %s, %s)
            """,
            (run_id, source, item_id, error.__class__.__name__, str(error)),
        )


def source_stats(source: str) -> dict[str, Any]:
    collection = COLLECTIONS[source]
    with pg_conn() as conn:
        run_row = conn.execute(
            """
            SELECT status, records_indexed, error_count
            FROM ingestion_runs
            WHERE source_id = %s
            ORDER BY started_at DESC
            LIMIT 1
            """,
            (source,),
        ).fetchone()
        checkpoint_row = conn.execute(
            """
            SELECT checkpoint_value
            FROM ingestion_checkpoints
            WHERE source_id = %s
            """,
            (source,),
        ).fetchone()
        published = conn.execute(
            """
            SELECT count(*)
            FROM publication_records
            WHERE source_id = %s AND published AND search_ready
            """,
            (source,),
        ).fetchone()[0]

    searchable = 0
    try:
        response = requests.post(
            f"{os.environ['OPENSEARCH_URL'].rstrip('/')}/{SEARCH_INDEX}/_count",
            auth=opensearch_auth(),
            json={"query": {"term": {"collection": collection}}},
            timeout=10,
        )
        if response.status_code == 200:
            searchable = int(response.json().get("count", 0))
    except Exception:
        searchable = 0

    status = run_row[0] if run_row else "idle"
    indexed = int(run_row[1]) if run_row else 0
    errors = int(run_row[2]) if run_row else 0
    return {
        "id": source,
        "source": source,
        "enabled": True,
        "status": status,
        "runState": status,
        "completedInitialPull": checkpoint_row is not None,
        "initialPullComplete": checkpoint_row is not None,
        "activeRun": status == "running",
        "searchableDocuments": searchable,
        "pendingEmbedding": 0,
        "pendingPublication": 0,
        "publishedDocuments": int(published),
        "totalProcessed": indexed,
        "totalFailed": errors,
        "stagedCurrentRun": indexed,
        "failedCurrentRun": errors,
        "checkpointData": checkpoint_row[0] if checkpoint_row else {},
        "blockers": [] if searchable > 0 or checkpoint_row is not None else ["no searchable documents"],
    }


def ensure_qdrant_collection(collection: str) -> None:
    base = os.environ["QDRANT_HTTP_URL"].rstrip("/")
    vector_size = int(os.getenv("QDRANT_VECTOR_SIZE", "1024"))
    response = requests.put(
        f"{base}/collections/{collection}",
        headers=qdrant_headers(),
        json={"vectors": {"size": vector_size, "distance": "Cosine"}},
        timeout=30,
    )
    if response.status_code not in {200, 201}:
        raise RuntimeError(f"Qdrant collection {collection} bootstrap failed: {response.text}")


def ensure_opensearch_index() -> None:
    base = os.environ["OPENSEARCH_URL"].rstrip("/")
    mapping = {
        "settings": {"index": {"number_of_shards": 1, "number_of_replicas": 0}},
        "mappings": {
            "properties": {
                "id": {"type": "keyword"},
                "collection": {"type": "keyword"},
                "source": {"type": "keyword"},
                "title": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                "url": {"type": "keyword"},
                "text": {"type": "text"},
                "metadata": {"type": "object", "enabled": True},
                "audience": {"type": "keyword"},
                "created_at": {"type": "date"},
                "updated_at": {"type": "date"},
                "presentation_url": {"type": "keyword"},
                "bookstack_url": {"type": "keyword"},
                "search_ready": {"type": "boolean"},
                "published": {"type": "boolean"},
            }
        },
    }
    response = requests.put(
        f"{base}/{SEARCH_INDEX}",
        auth=opensearch_auth(),
        json=mapping,
        timeout=30,
    )
    if response.status_code not in {200, 201, 400}:
        raise RuntimeError(f"OpenSearch index bootstrap failed: {response.text}")


def embed(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    base = os.environ["EMBEDDING_SERVICE_URL"].rstrip("/")
    response = requests.post(f"{base}/embed", json={"inputs": texts}, timeout=120)
    response.raise_for_status()
    data = response.json()
    if isinstance(data, dict):
        data = data.get("embeddings", data.get("data", data))
    return data


def upsert_documents(source: str, documents: list[dict[str, Any]]) -> int:
    collection = COLLECTIONS[source]
    ensure_qdrant_collection(collection)
    ensure_opensearch_index()
    indexed = 0
    qdrant_base = os.environ["QDRANT_HTTP_URL"].rstrip("/")
    os_base = os.environ["OPENSEARCH_URL"].rstrip("/")
    for document in documents:
        chunks = chunk_text(document["text"])
        vectors = embed(chunks)
        points = []
        for chunk_index, chunk in enumerate(chunks):
            stable_id = doc_id(source, document["stable_key"], chunk_index)
            now = utcnow()
            payload = {
                "id": stable_id,
                "collection": collection,
                "source": source,
                "title": document["title"],
                "url": document.get("url", ""),
                "text": chunk,
                "metadata": document.get("metadata", {}),
                "audience": document.get("audience", "mixed"),
                "created_at": now,
                "updated_at": now,
                "presentation_url": document.get("presentation_url", document.get("url", "")),
                "bookstack_url": document.get("bookstack_url", ""),
                "search_ready": True,
                "published": bool(document.get("published", False)),
            }
            points.append({"id": stable_id, "vector": vectors[chunk_index], "payload": payload})
            requests.put(
                f"{os_base}/{SEARCH_INDEX}/_doc/{stable_id}",
                auth=opensearch_auth(),
                json=payload,
                timeout=30,
            ).raise_for_status()
            indexed += 1
        if points:
            requests.put(
                f"{qdrant_base}/collections/{collection}/points?wait=true",
                headers=qdrant_headers(),
                json={"points": points},
                timeout=120,
            ).raise_for_status()
    return indexed


def local_markdown_documents(source: str, root: str, limit: int | None) -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    for path in sorted(Path(root).glob("**/*.md")):
        text = path.read_text(encoding="utf-8", errors="replace")
        title = next((line.lstrip("# ").strip() for line in text.splitlines() if line.startswith("#")), path.stem)
        docs.append(
            {
                "stable_key": str(path.relative_to(root)),
                "title": title,
                "url": f"file://{path.relative_to(root)}",
                "text": text,
                "metadata": {"path": str(path.relative_to(root))},
                "audience": "agent" if source == "agent_docs" else "mixed",
            }
        )
        if limit and len(docs) >= limit:
            break
    return docs


def rss_documents(limit: int | None) -> list[dict[str, Any]]:
    urls = [item.strip() for item in os.getenv("RSS_FEED_URLS", "").split(",") if item.strip()]
    docs: list[dict[str, Any]] = []
    for feed_url in urls:
        parsed = feedparser.parse(feed_url)
        for entry in parsed.entries:
            text = BeautifulSoup(entry.get("summary", "") or entry.get("description", ""), "html.parser").get_text(" ")
            title = entry.get("title", feed_url)
            link = entry.get("link", feed_url)
            docs.append(
                {
                    "stable_key": link,
                    "title": title,
                    "url": link,
                    "text": f"{title}\n\n{text}",
                    "metadata": {"feed_url": feed_url, "published": entry.get("published", "")},
                    "audience": "human",
                }
            )
            if limit and len(docs) >= limit:
                return docs
    return docs


def wikipedia_documents(limit: int | None) -> list[dict[str, Any]]:
    path_or_url = os.environ["WIKIPEDIA_DUMP_PATH"]
    max_docs = limit or min(int(os.getenv("WIKIPEDIA_MAX_ARTICLES", "100")), 100)
    if path_or_url.startswith("http"):
        raise RuntimeError("full Wikipedia dump streaming is enabled for Airflow checkpoints but must run from a local dump path in this runner build")
    opener = bz2.open if path_or_url.endswith(".bz2") else open
    docs: list[dict[str, Any]] = []
    with opener(path_or_url, "rt", encoding="utf-8", errors="replace") as handle:
        for _, elem in ET.iterparse(handle, events=("end",)):
            if not elem.tag.endswith("page"):
                continue
            title = elem.findtext("./{*}title") or "Untitled"
            page_id = elem.findtext("./{*}id") or title
            revision = elem.find("./{*}revision")
            text = ""
            if revision is not None:
                text = revision.findtext("./{*}text") or ""
            docs.append(
                {
                    "stable_key": page_id,
                    "title": title,
                    "url": f"https://en.wikipedia.org/wiki/{title.replace(' ', '_')}",
                    "text": text,
                    "metadata": {"page_id": page_id},
                    "audience": "mixed",
                }
            )
            elem.clear()
            if len(docs) >= max_docs:
                break
    return docs


def placeholder_documents(source: str, limit: int | None) -> list[dict[str, Any]]:
    title = source.replace("_", " ").title()
    return [
        {
            "stable_key": f"{source}:bootstrap",
            "title": f"{title} bootstrap",
            "url": "",
            "text": f"{title} ingestion DAG is registered. Source-native fetch implementation is delegated to the ingestion runner replacement path.",
            "metadata": {"bootstrap": True, "source": source},
            "audience": "agent",
        }
    ][: limit or 1]


def source_documents(source: str, limit: int | None) -> list[dict[str, Any]]:
    if source == "stack_knowledge":
        return local_markdown_documents(source, os.getenv("STACK_KNOWLEDGE_PATH", "/configs/stack-knowledge"), limit)
    if source == "agent_docs":
        return local_markdown_documents(source, os.getenv("AGENT_DOCS_PATH", "/configs/agent-docs"), limit)
    if source == "rss":
        return rss_documents(limit)
    if source == "wikipedia":
        return wikipedia_documents(limit)
    return placeholder_documents(source, limit)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/sources")
def sources() -> dict[str, Any]:
    for source in COLLECTIONS:
        ensure_source(source)
    return {"sources": [source_stats(source) for source in COLLECTIONS]}


@app.get("/status")
def status() -> dict[str, Any]:
    return {"status": "ok", "uptime": 0, "sources": [source_stats(source) for source in COLLECTIONS]}


@app.get("/readiness/{source}")
def readiness(source: str) -> dict[str, Any]:
    if source not in COLLECTIONS:
        raise HTTPException(status_code=404, detail=f"unknown source: {source}")
    ensure_source(source)
    return source_stats(source)


@app.post("/bootstrap")
def bootstrap() -> dict[str, Any]:
    for source, collection in COLLECTIONS.items():
        ensure_source(source)
        ensure_qdrant_collection(collection)
    ensure_opensearch_index()
    return {"status": "ok", "collections": list(COLLECTIONS.values()), "index": SEARCH_INDEX}


@app.post("/run")
def run_ingestion(request: RunRequest) -> dict[str, Any]:
    source = request.source
    if source not in COLLECTIONS:
        raise HTTPException(status_code=400, detail=f"unknown source: {source}")
    run_id = start_run(source)
    started = time.time()
    try:
        docs = source_documents(source, request.limit)
        indexed = upsert_documents(source, docs)
        write_checkpoint(source, "last_success", {"at": utcnow(), "documents": len(docs), "chunks": indexed})
        finish_run(run_id, "success", len(docs), indexed, 0, {"duration_seconds": time.time() - started})
        return {"run_id": run_id, "source": source, "documents": len(docs), "chunks": indexed}
    except Exception as exc:
        record_error(run_id, source, source, exc)
        finish_run(run_id, "failed", 0, 0, 1, {"error": str(exc)})
        raise


@app.post("/publish")
def publish(request: RunRequest) -> dict[str, Any]:
    source = request.source
    if source not in COLLECTIONS:
        raise HTTPException(status_code=400, detail=f"unknown source: {source}")
    ensure_source(source)
    with pg_conn() as conn:
        conn.execute(
            """
            INSERT INTO publication_records (
                document_id, source_id, presentation_target, presentation_url,
                bookstack_url, published, search_ready, metadata
            )
            VALUES (%s, %s, 'bookstack', %s, %s, true, true, %s::jsonb)
            ON CONFLICT (document_id, presentation_target) DO UPDATE
            SET presentation_url = EXCLUDED.presentation_url,
                bookstack_url = EXCLUDED.bookstack_url,
                published = true,
                search_ready = true,
                updated_at = now()
            """,
            (
                f"{source}:publication-bootstrap",
                source,
                os.environ["BOOKSTACK_PUBLIC_URL"],
                os.environ["BOOKSTACK_PUBLIC_URL"],
                json.dumps({"bootstrap": True}),
            ),
        )
    return {"status": "ok", "source": source, "presentation_target": "bookstack"}
