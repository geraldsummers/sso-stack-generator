from __future__ import annotations

import bz2
import hmac
import hashlib
import html
import io
import json
import os
import re
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterable

import feedparser
import psycopg
import requests
from bs4 import BeautifulSoup
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field


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
DEFAULT_EMBEDDING_BATCH_SIZE = 16


class RunRequest(BaseModel):
    source: str
    limit: int | None = Field(default=None, ge=1, le=5000)
    publish: bool = False


app = FastAPI(title="webservices ingestion runner")


def require_api_key(x_api_key: str | None = Header(default=None)) -> None:
    configured = os.getenv("MONITORING_API_KEY", "").strip()
    if not configured:
        return
    if not x_api_key or not hmac.compare_digest(x_api_key, configured):
        raise HTTPException(status_code=401, detail="invalid or missing API key")


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


def bookstack_headers() -> dict[str, str]:
    token_id = os.getenv("BOOKSTACK_API_TOKEN_ID", "").strip()
    token_secret = os.getenv("BOOKSTACK_API_TOKEN_SECRET", "").strip()
    if not token_id or not token_secret:
        raise HTTPException(status_code=503, detail="BookStack API credentials are not configured")
    return {
        "Authorization": f"Token {token_id}:{token_secret}",
        "Accept": "application/json",
        "Content-Type": "application/json",
    }


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
            ON CONFLICT (source_id, checkpoint_key) DO UPDATE
            SET checkpoint_value = EXCLUDED.checkpoint_value,
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
            ORDER BY updated_at DESC
            LIMIT 1
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
    existing = requests.get(
        f"{base}/collections/{collection}",
        headers=qdrant_headers(),
        timeout=30,
    )
    if existing.status_code == 200:
        return
    response = requests.put(
        f"{base}/collections/{collection}",
        headers=qdrant_headers(),
        json={"vectors": {"size": vector_size, "distance": "Cosine"}},
        timeout=30,
    )
    if response.status_code in {200, 201}:
        return
    if response.status_code == 400 and "already exists" in response.text.lower():
        return
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
    if response.status_code in {200, 201}:
        return
    if response.status_code == 400 and "resource_already_exists_exception" in response.text:
        return
    raise RuntimeError(f"OpenSearch index bootstrap failed: {response.text}")


def embedding_batch_size() -> int:
    raw = os.getenv("EMBEDDING_BATCH_SIZE", str(DEFAULT_EMBEDDING_BATCH_SIZE))
    try:
        return max(1, int(raw))
    except ValueError:
        return DEFAULT_EMBEDDING_BATCH_SIZE


def embed_once(texts: list[str]) -> list[list[float]]:
    base = os.environ["EMBEDDING_SERVICE_URL"].rstrip("/")
    response = requests.post(f"{base}/embed", json={"inputs": texts}, timeout=120)
    response.raise_for_status()
    data = response.json()
    if isinstance(data, dict):
        data = data.get("embeddings", data.get("data", data))
    return data


def embed_batch(texts: list[str]) -> list[list[float]]:
    try:
        return embed_once(texts)
    except requests.HTTPError as exc:
        response = exc.response
        if response is None or response.status_code != 413 or len(texts) == 1:
            raise
        midpoint = len(texts) // 2
        return embed_batch(texts[:midpoint]) + embed_batch(texts[midpoint:])


def embed(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    vectors: list[list[float]] = []
    batch_size = embedding_batch_size()
    for index in range(0, len(texts), batch_size):
        vectors.extend(embed_batch(texts[index : index + batch_size]))
    return vectors


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


def update_publication_search_metadata(source: str, presentation_url: str) -> int:
    collection = COLLECTIONS[source]
    os_base = os.environ["OPENSEARCH_URL"].rstrip("/")
    qdrant_base = os.environ["QDRANT_HTTP_URL"].rstrip("/")
    script = {
        "script": {
            "source": (
                "ctx._source.presentation_url = params.url; "
                "ctx._source.bookstack_url = params.url; "
                "ctx._source.published = true; "
                "ctx._source.search_ready = true; "
                "ctx._source.updated_at = params.updated_at"
            ),
            "lang": "painless",
            "params": {"url": presentation_url, "updated_at": utcnow()},
        },
        "query": {"term": {"source": source}},
    }
    response = requests.post(
        f"{os_base}/{SEARCH_INDEX}/_update_by_query?conflicts=proceed&refresh=true",
        auth=opensearch_auth(),
        json=script,
        timeout=120,
    )
    response.raise_for_status()
    updated = int(response.json().get("updated", 0))

    scroll = requests.post(
        f"{qdrant_base}/collections/{collection}/points/scroll",
        headers=qdrant_headers(),
        json={
            "limit": 1000,
            "with_payload": False,
            "with_vector": False,
            "filter": {"must": [{"key": "source", "match": {"value": source}}]},
        },
        timeout=60,
    )
    if scroll.status_code == 200:
        point_ids = [point["id"] for point in scroll.json().get("result", {}).get("points", [])]
        if point_ids:
            requests.post(
                f"{qdrant_base}/collections/{collection}/points/payload?wait=true",
                headers=qdrant_headers(),
                json={
                    "payload": {
                        "presentation_url": presentation_url,
                        "bookstack_url": presentation_url,
                        "published": True,
                        "search_ready": True,
                        "updated_at": utcnow(),
                    },
                    "points": point_ids,
                },
                timeout=120,
            ).raise_for_status()
    return updated


def bookstack_request(method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    response = requests.request(
        method,
        f"{os.environ['BOOKSTACK_URL'].rstrip('/')}{path}",
        headers=bookstack_headers(),
        json=payload,
        timeout=60,
    )
    if response.status_code < 200 or response.status_code >= 300:
        raise HTTPException(
            status_code=502,
            detail=f"BookStack API {method} {path} failed: HTTP {response.status_code} {response.text[:500]}",
        )
    if not response.text.strip():
        return {}
    return response.json()


def bookstack_fetch_all(path: str) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    page = 1
    while True:
        separator = "&" if "?" in path else "?"
        data = bookstack_request("GET", f"{path}{separator}count=100&page={page}")
        page_items = [item for item in data.get("data", []) if isinstance(item, dict)]
        items.extend(page_items)
        if len(page_items) < 100:
            return items
        page += 1


def ensure_bookstack_publication_book() -> dict[str, Any]:
    book_name = os.getenv("BOOKSTACK_PUBLICATION_BOOK", "Knowledge")
    book_description = os.getenv(
        "BOOKSTACK_PUBLICATION_BOOK_DESCRIPTION",
        "Runtime-generated knowledge pages published by the ingestion pipeline.",
    )
    for book in bookstack_fetch_all("/api/books"):
        if book.get("name") == book_name:
            book_id = int(book.get("id", 0))
            if book.get("description") == book_description:
                return book
            return bookstack_request("PUT", f"/api/books/{book_id}", {"name": book_name, "description": book_description})
    return bookstack_request("POST", "/api/books", {"name": book_name, "description": book_description})


def publication_docs(source: str, limit: int) -> list[dict[str, Any]]:
    os_base = os.environ["OPENSEARCH_URL"].rstrip("/")
    response = requests.post(
        f"{os_base}/{SEARCH_INDEX}/_search",
        auth=opensearch_auth(),
        json={
            "size": limit,
            "query": {"term": {"source": source}},
            "sort": [{"updated_at": {"order": "desc"}}],
            "_source": ["title", "url", "text", "metadata"],
        },
        timeout=30,
    )
    response.raise_for_status()
    return [hit.get("_source", {}) for hit in response.json().get("hits", {}).get("hits", [])]


def render_publication_html(source: str, docs: list[dict[str, Any]]) -> str:
    title = html.escape(source.replace("_", " ").title())
    generated = html.escape(utcnow())
    sections = [
        f"<p><em>Generated by the webservices ingestion pipeline at {generated}.</em></p>",
        f"<p>This page summarizes currently indexed material for <strong>{title}</strong>. Full text remains searchable in OpenSearch and Qdrant.</p>",
    ]
    for doc in docs:
        doc_title = html.escape(str(doc.get("title") or "Untitled"))
        doc_url = html.escape(str(doc.get("url") or ""))
        text = html.escape(str(doc.get("text") or "")[:4000])
        link = f'<p><a href="{doc_url}" target="_blank" rel="noreferrer noopener">{doc_url}</a></p>' if doc_url else ""
        sections.append(f"<h2>{doc_title}</h2>{link}<p>{text}</p>")
    if not docs:
        sections.append("<p>No indexed documents were available when this publication task ran.</p>")
    return "\n".join(sections)


def existing_bookstack_page_id(source: str) -> int | None:
    with pg_conn() as conn:
        row = conn.execute(
            """
            SELECT metadata->>'bookstack_page_id'
            FROM publication_records
            WHERE source_id = %s
              AND presentation_target = 'bookstack'
            ORDER BY updated_at DESC
            LIMIT 1
            """,
            (source,),
        ).fetchone()
    if not row or not row[0]:
        return None
    try:
        page_id = int(row[0])
    except ValueError:
        return None
    return page_id if page_id > 0 else None


def publish_bookstack_page(source: str) -> dict[str, Any]:
    book = ensure_bookstack_publication_book()
    book_id = int(book.get("id", 0))
    if book_id <= 0:
        raise HTTPException(status_code=502, detail="BookStack publication book did not include an id")
    page_title = f"{source.replace('_', ' ').title()} Knowledge"
    docs = publication_docs(source, int(os.getenv("BOOKSTACK_PUBLICATION_MAX_DOCS", "5")))
    payload = {
        "book_id": book_id,
        "name": page_title,
        "html": render_publication_html(source, docs),
        "tags": [
            {"name": "generated_by", "value": "ingestion-runner"},
            {"name": "source", "value": source},
        ],
    }
    page_id = existing_bookstack_page_id(source)
    if page_id:
        try:
            page = bookstack_request("PUT", f"/api/pages/{page_id}", payload)
        except HTTPException as exc:
            if "HTTP 404" not in str(exc.detail):
                raise
            page = bookstack_request("POST", "/api/pages", payload)
    else:
        page = bookstack_request("POST", "/api/pages", payload)

    public_base = os.environ["BOOKSTACK_PUBLIC_URL"].rstrip("/")
    book_slug = str(book.get("slug") or "").strip()
    page_slug = str(page.get("slug") or "").strip()
    if book_slug and page_slug:
        url = f"{public_base}/books/{book_slug}/page/{page_slug}"
    else:
        url = f"{public_base}/link/{page.get('id')}" if page.get("id") else public_base
    return {"url": url, "book_id": book_id, "page_id": page.get("id"), "page_title": page_title}


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


def http_json(url: str, timeout: int = 30) -> Any:
    response = requests.get(url, headers={"User-Agent": "webservices-ingestion-runner/1.0"}, timeout=timeout)
    response.raise_for_status()
    return response.json()


def http_text(url: str, timeout: int = 30) -> str:
    response = requests.get(url, headers={"User-Agent": "webservices-ingestion-runner/1.0"}, timeout=timeout)
    response.raise_for_status()
    return response.text


def http_text_with_url(url: str, timeout: int = 30) -> tuple[str, str]:
    response = requests.get(url, headers={"User-Agent": "webservices-ingestion-runner/1.0"}, timeout=timeout)
    response.raise_for_status()
    return response.text, response.url


def mediawiki_documents(source: str, api_url: str, page_titles: list[str], limit: int | None) -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    for title in page_titles[: limit or len(page_titles)]:
        data = http_json(
            f"{api_url}?action=query&prop=extracts&explaintext=1&redirects=1&format=json&titles={requests.utils.quote(title)}"
        )
        pages = data.get("query", {}).get("pages", {})
        for page in pages.values():
            page_title = page.get("title", title)
            extract = page.get("extract", "")
            if not extract:
                continue
            docs.append(
                {
                    "stable_key": f"{api_url}:{page.get('pageid', page_title)}",
                    "title": page_title,
                    "url": f"{api_url.rsplit('/api.php', 1)[0]}/index.php?title={requests.utils.quote(page_title.replace(' ', '_'))}",
                    "text": extract,
                    "metadata": {"page_id": page.get("pageid"), "source_api": api_url},
                    "audience": "agent",
                }
            )
            if limit and len(docs) >= limit:
                return docs
    return docs


def moinmoin_documents(source: str, base_url: str, page_titles: list[str], limit: int | None) -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    for title in page_titles[: limit or len(page_titles)]:
        page_path = requests.utils.quote(title)
        url = f"{base_url.rstrip('/')}/{page_path}"
        raw_url = f"{url}?action=raw"
        text = http_text(raw_url)
        if not text.strip():
            html_text = http_text(url)
            text = BeautifulSoup(html_text, "html.parser").get_text(" ")
        docs.append(
            {
                "stable_key": raw_url,
                "title": title,
                "url": url,
                "text": text,
                "metadata": {"source_type": "moinmoin", "raw_url": raw_url},
                "audience": "agent",
            }
        )
        if limit and len(docs) >= limit:
            return docs
    return docs


def cve_documents(limit: int | None) -> list[dict[str, Any]]:
    data = http_json(f"https://services.nvd.nist.gov/rest/json/cves/2.0?resultsPerPage={min(limit or 20, 50)}")
    docs: list[dict[str, Any]] = []
    for item in data.get("vulnerabilities", []):
        cve = item.get("cve", {})
        cve_id = cve.get("id", "")
        descriptions = cve.get("descriptions", [])
        text = next((d.get("value", "") for d in descriptions if d.get("lang") == "en"), "")
        if not cve_id or not text:
            continue
        docs.append(
            {
                "stable_key": cve_id,
                "title": cve_id,
                "url": f"https://nvd.nist.gov/vuln/detail/{cve_id}",
                "text": text,
                "metadata": {"published": cve.get("published"), "last_modified": cve.get("lastModified")},
                "audience": "operator",
            }
        )
    return docs


def australian_law_documents(limit: int | None) -> list[dict[str, Any]]:
    url = os.getenv("AUSTRALIAN_LAWS_SAMPLE_URL", "https://www.legislation.gov.au/C2024A00001/latest/text")
    text = BeautifulSoup(http_text(url), "html.parser").get_text(" ")
    return [
        {
            "stable_key": url,
            "title": "Australian legislation sample",
            "url": url,
            "text": text,
            "metadata": {"sample": True},
            "audience": "human",
        }
    ][: limit or 1]


def linux_docs_documents(limit: int | None) -> list[dict[str, Any]]:
    pages = [
        ("Linux kernel documentation", "https://docs.kernel.org/"),
        ("Kernel admin guide", "https://docs.kernel.org/admin-guide/README.html"),
    ]
    docs: list[dict[str, Any]] = []
    for title, url in pages[: limit or len(pages)]:
        text = BeautifulSoup(http_text(url), "html.parser").get_text(" ")
        docs.append({"stable_key": url, "title": title, "url": url, "text": text, "metadata": {"sample": True}, "audience": "agent"})
    return docs


def torrent_documents(limit: int | None) -> list[dict[str, Any]]:
    feed_url = os.getenv("TORRENT_RSS_URL", "https://distrowatch.com/news/torrents.xml")
    parsed = feedparser.parse(feed_url)
    docs: list[dict[str, Any]] = []
    for entry in parsed.entries[: limit or 20]:
        title = entry.get("title", feed_url)
        link = entry.get("link", feed_url)
        text = BeautifulSoup(entry.get("summary", "") or entry.get("description", ""), "html.parser").get_text(" ")
        docs.append({"stable_key": link, "title": title, "url": link, "text": f"{title}\n\n{text}", "metadata": {"feed_url": feed_url}, "audience": "operator"})
    return docs


def opendota_documents(limit: int | None) -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    for hero in http_json("https://api.opendota.com/api/heroes")[: limit or 20]:
        name = hero.get("localized_name", hero.get("name", "OpenDota hero"))
        text = json.dumps(hero, sort_keys=True)
        docs.append({"stable_key": str(hero.get("id", name)), "title": name, "url": "https://www.opendota.com/heroes", "text": text, "metadata": hero, "audience": "agent"})
    return docs


def poe_ninja_documents(limit: int | None) -> list[dict[str, Any]]:
    league = os.getenv("POE_NINJA_LEAGUE", "Standard")
    try:
        data = http_json(f"https://poe.ninja/api/data/currencyoverview?league={requests.utils.quote(league)}&type=Currency")
    except requests.HTTPError as exc:
        response = exc.response
        if response is None or response.status_code != 404:
            raise
        page_url = os.getenv("POE_NINJA_CURRENCY_URL", "https://poe.ninja/poe1/economy")
        html_text, final_url = http_text_with_url(page_url)
        text = BeautifulSoup(html_text, "html.parser").get_text(" ")
        return [
            {
                "stable_key": final_url,
                "title": "poe.ninja Path of Exile economy",
                "url": final_url,
                "text": text,
                "metadata": {"source_type": "poe_ninja_page", "requested_league": league},
                "audience": "agent",
            }
        ][: limit or 1]
    docs: list[dict[str, Any]] = []
    for line in data.get("lines", [])[: limit or 20]:
        name = line.get("currencyTypeName", "Currency")
        text = json.dumps(line, sort_keys=True)
        docs.append({"stable_key": f"{league}:{name}", "title": f"{name} ({league})", "url": "https://poe.ninja/economy", "text": text, "metadata": line, "audience": "agent"})
    return docs


def iter_wikipedia_pages(path_or_url: str, max_docs: int) -> Iterable[dict[str, Any]]:
    def page_from_elem(elem: ET.Element) -> dict[str, Any]:
        title = elem.findtext("./{*}title") or "Untitled"
        page_id = elem.findtext("./{*}id") or title
        revision = elem.find("./{*}revision")
        text = revision.findtext("./{*}text") if revision is not None else ""
        return {
            "stable_key": page_id,
            "title": title,
            "url": f"https://en.wikipedia.org/wiki/{title.replace(' ', '_')}",
            "text": text or "",
            "metadata": {"page_id": page_id, "dump": path_or_url},
            "audience": "mixed",
        }

    seen = 0
    if path_or_url.startswith("http"):
        response = requests.get(path_or_url, headers={"User-Agent": "webservices-ingestion-runner/1.0"}, stream=True, timeout=60)
        response.raise_for_status()
        response.raw.decode_content = True
        byte_stream = bz2.BZ2File(response.raw) if path_or_url.endswith(".bz2") else response.raw
        text_stream = io.TextIOWrapper(byte_stream, encoding="utf-8", errors="replace")
        for _, elem in ET.iterparse(text_stream, events=("end",)):
            if not elem.tag.endswith("page"):
                continue
            seen += 1
            yield page_from_elem(elem)
            write_checkpoint("wikipedia", "stream_position", {"dump": path_or_url, "pages_seen": seen, "at": utcnow()})
            elem.clear()
            if seen >= max_docs:
                return
        return

    opener = bz2.open if path_or_url.endswith(".bz2") else open
    with opener(path_or_url, "rt", encoding="utf-8", errors="replace") as handle:
        for _, elem in ET.iterparse(handle, events=("end",)):
            if not elem.tag.endswith("page"):
                continue
            seen += 1
            yield page_from_elem(elem)
            write_checkpoint("wikipedia", "stream_position", {"dump": path_or_url, "pages_seen": seen, "at": utcnow()})
            elem.clear()
            if seen >= max_docs:
                return


def wikipedia_documents(limit: int | None) -> list[dict[str, Any]]:
    path_or_url = os.environ["WIKIPEDIA_DUMP_PATH"]
    max_docs = limit or min(int(os.getenv("WIKIPEDIA_MAX_ARTICLES", "100")), int(os.getenv("WIKIPEDIA_RUNNER_HARD_LIMIT", "500")))
    return list(iter_wikipedia_pages(path_or_url, max_docs))


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
    if source == "cve":
        return cve_documents(limit)
    if source == "torrents":
        return torrent_documents(limit)
    if source == "australian_laws":
        return australian_law_documents(limit)
    if source == "linux_docs":
        return linux_docs_documents(limit)
    if source == "debian_wiki":
        return moinmoin_documents(source, "https://wiki.debian.org", ["FrontPage", "DebianRepository", "DebianPackageManagement"], limit)
    if source == "arch_wiki":
        return mediawiki_documents(source, "https://wiki.archlinux.org/api.php", ["Main page", "Pacman", "System maintenance"], limit)
    if source == "opendota":
        return opendota_documents(limit)
    if source == "poe_ninja":
        return poe_ninja_documents(limit)
    return placeholder_documents(source, limit)


@app.on_event("startup")
def bootstrap_on_startup() -> None:
    last_error: Exception | None = None
    for attempt in range(1, 13):
        try:
            bootstrap()
            return
        except Exception as exc:
            last_error = exc
            print(f"bootstrap attempt {attempt}/12 failed: {exc}", flush=True)
            time.sleep(min(attempt * 2, 20))
    raise RuntimeError(f"backend bootstrap failed during startup: {last_error}")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/sources")
def sources(_: None = Depends(require_api_key)) -> dict[str, Any]:
    for source in COLLECTIONS:
        ensure_source(source)
    return {"sources": [source_stats(source) for source in COLLECTIONS]}


@app.get("/status")
def status(_: None = Depends(require_api_key)) -> dict[str, Any]:
    return {"status": "ok", "uptime": 0, "sources": [source_stats(source) for source in COLLECTIONS]}


@app.get("/readiness/{source}")
def readiness(source: str, _: None = Depends(require_api_key)) -> dict[str, Any]:
    if source not in COLLECTIONS:
        raise HTTPException(status_code=404, detail=f"unknown source: {source}")
    ensure_source(source)
    return source_stats(source)


@app.post("/bootstrap")
def bootstrap(_: None = Depends(require_api_key)) -> dict[str, Any]:
    ensure_opensearch_index()
    for source, collection in COLLECTIONS.items():
        ensure_source(source)
        ensure_qdrant_collection(collection)
    return {"status": "ok", "collections": list(COLLECTIONS.values()), "index": SEARCH_INDEX}


@app.post("/run")
def run_ingestion(request: RunRequest, _: None = Depends(require_api_key)) -> dict[str, Any]:
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
def publish(request: RunRequest, _: None = Depends(require_api_key)) -> dict[str, Any]:
    source = request.source
    if source not in COLLECTIONS:
        raise HTTPException(status_code=400, detail=f"unknown source: {source}")
    allowed = {item.strip() for item in os.getenv("BOOKSTACK_ALLOWED_SOURCES", "").split(",") if item.strip()}
    if allowed and source not in allowed:
        raise HTTPException(status_code=403, detail=f"publication disabled for source: {source}")
    ensure_source(source)
    publication = publish_bookstack_page(source)
    presentation_url = publication["url"]
    updated = update_publication_search_metadata(source, presentation_url)
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
                metadata = EXCLUDED.metadata,
                updated_at = now()
            """,
            (
                f"{source}:publication-bootstrap",
                source,
                presentation_url,
                presentation_url,
                json.dumps(
                    {
                        "bootstrap": True,
                        "opensearch_updated": updated,
                        "bookstack_book_id": publication.get("book_id"),
                        "bookstack_page_id": publication.get("page_id"),
                        "bookstack_page_title": publication.get("page_title"),
                    }
                ),
            ),
        )
    return {
        "status": "ok",
        "source": source,
        "presentation_target": "bookstack",
        "presentation_url": presentation_url,
        "bookstack_page_id": publication.get("page_id"),
        "updated": updated,
    }
