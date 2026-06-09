# Search APIs Guide

## Overview

The active search runtime is no longer the custom `search-service` process.
The current stack uses:

- `https://search.<domain>` for protected OpenSearch text and BM25 queries
- `http://qdrant:6333` for internal vector search
- workspace helpers such as `stack-search` and `stack-doc-get` for agent-safe retrieval

Prefer the helper commands for routine operator and agent work. Use backend APIs
directly only when you need explicit OpenSearch or Qdrant behavior.

## OpenSearch

### Base URL

```text
https://search.<domain>
```

### Cluster Health

```bash
curl -u "admin:${OPENSEARCH_ADMIN_PASSWORD}" \
  "https://search.<domain>/_cluster/health?pretty"
```

### Text Search

```bash
curl -u "admin:${OPENSEARCH_ADMIN_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -X POST "https://search.<domain>/stack_knowledge/_search" \
  --data '{
    "query": {
      "multi_match": {
        "query": "docker networking",
        "fields": ["title^3", "text", "metadata.*"]
      }
    },
    "size": 5
  }'
```

### Example Response Shape

```json
{
  "hits": {
    "total": {
      "value": 5
    },
    "hits": [
      {
        "_index": "stack_knowledge",
        "_id": "wikipedia:docker-networking:0",
        "_score": 12.34,
        "_source": {
          "title": "Docker Networking",
          "text": "Docker networking allows containers to communicate...",
          "metadata": {
            "source": "wikipedia",
            "url": "https://example.com/article"
          }
        }
      }
    ]
  }
}
```

## Qdrant

### Base URL

```text
http://qdrant:6333
```

### List Collections

```bash
curl http://qdrant:6333/collections
```

### Vector Search

```bash
curl -H 'Content-Type: application/json' \
  -X POST "http://qdrant:6333/collections/stack_knowledge/points/search" \
  --data '{
    "vector": [0.1, 0.2, 0.3],
    "limit": 5
  }'
```

## Preferred Operator Surface

For stack operators and agent workspaces, prefer:

- `stack-search` for guided search queries
- `stack-doc-get` for exact document retrieval
- `https://search.<domain>` when you explicitly need raw OpenSearch APIs
- direct Qdrant access only for vector-database administration or debugging

## Notes

- OpenSearch is the active public search route.
- Qdrant remains the vector backend for semantic search and embedding workflows.
- The legacy `search-service` code still exists in-tree for compatibility and
  test coverage, but it is not the active runtime contract.
