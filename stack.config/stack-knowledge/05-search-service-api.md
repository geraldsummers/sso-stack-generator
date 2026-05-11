# Search Service API Guide

## Overview

The Search Service provides a unified API for searching across multiple data sources using vector similarity (semantic), keyword matching (BM25), or hybrid approaches.

**Base URL**: `http://search-service:8098`

## Endpoints

### POST /search

Primary search endpoint supporting multiple search modes.

#### Request Body

```json
{
  "query": "docker container networking",
  "mode": "hybrid",
  "collections": ["*"],
  "limit": 10,
  "audience": "agent"
}
```

**Parameters**:
- `query` (string, required): Search query text
- `mode` (string, optional): Search mode - `"vector"`, `"bm25"`, or `"hybrid"` (default: `"hybrid"`)
- `collections` (array[string], optional): Collections to search - Use `["*"]` for all (default: `["*"]`)
- `limit` (integer, optional): Maximum results to return (default: 20, max: 1000)
- `audience` (string, optional): Result audience filter - `"agent"`, `"human"`, or `"both"` (default: `"both"`)

#### Response

```json
{
  "results": [
    {
      "id": "wikipedia:docker-networking:0",
      "score": 0.92,
      "source": "wikipedia",
      "title": "Docker Networking",
      "snippet": "Docker networking allows containers to communicate...",
      "url": "https://example.com/article",
      "metadata": {
        "document_id": "wikipedia:docker-networking:0",
        "author": "Jane Doe",
        "published": "2024-01-15",
        "type": "fulltext"
      },
      "contentType": "documentation",
      "capabilities": {
        "humanFriendly": true,
        "agentFriendly": true,
        "hasTimeSeries": false,
        "hasRichContent": true,
        "isInteractive": false,
        "isStructured": false
      }
    }
  ],
  "total": 1,
  "mode": "hybrid"
}
```

**Response Fields**:
- `results`: Array of search results
  - `id`: Stable document identifier for exact retrieval with `GET /documents/{id}`
  - `score`: Relevance score (0.0-1.0, higher is more relevant)
  - `source`: Data source (rss_feeds, wikipedia, bookstack, etc.)
  - `title`: Result title
  - `snippet`: Result content preview
  - `url`: Link to original content (if available)
  - `metadata`: Additional metadata
  - `contentType`: Inferred result type
  - `capabilities`: Agent/human usability hints
- `total`: Number of results returned
- `mode`: Search mode used

### GET /documents/{id}

Fetch exact source text for a result returned by `/search`.

Optional query parameters:
- `collection`: Restricts lookup to one collection.

Example:
```bash
curl "http://search-service:8098/documents/wikipedia:docker-networking:0?collection=wikipedia"
```

Response:
```json
{
  "id": "wikipedia:docker-networking:0",
  "source": "wikipedia",
  "collection": "wikipedia",
  "title": "Docker Networking",
  "text": "Full source text...",
  "url": "https://example.com/article",
  "metadata": {
    "document_id": "wikipedia:docker-networking:0"
  },
  "createdAt": "2026-04-29T00:00:00Z",
  "updatedAt": "2026-04-29T00:00:00Z"
}
```

### GET /health

Health check endpoint.

**Response**:
```json
{
  "status": "ok"
}
```

### GET /tools

Machine-readable tool contract for agent clients.

The endpoint describes:
- `semantic_search` -> `POST /search`
- `get_document` -> `GET /documents/{id}`
- `list_collections` -> `GET /collections`

### GET /collections

List available collections.

**Response**:
```json
{
  "collections": ["rss_feeds", "cve", "torrents", "wikipedia", "australian_laws", "linux_docs"]
}
```

## Search Modes

### Vector (Semantic) Search

Uses embedding vectors to find semantically similar content.

**Best for**:
- Conceptual similarity
- Paraphrased queries
- Cross-lingual search
- Abstract concepts

**Example**:
```json
{
  "query": "how to isolate containers from each other",
  "mode": "vector",
  "collections": ["bookstack", "wikipedia"],
  "limit": 5
}
```

**Matches**: Content about network isolation, security boundaries, container sandboxing (even if exact words don't match)

### BM25 (Keyword) Search

Traditional keyword-based search using BM25 ranking.

**Best for**:
- Exact term matching
- Technical identifiers (e.g., "CVE-2024-1234")
- Proper nouns
- Acronyms

**Example**:
```json
{
  "query": "kubernetes pod deployment",
  "mode": "bm25",
  "collections": ["*"],
  "limit": 10
}
```

**Matches**: Documents containing the exact words "kubernetes", "pod", "deployment"

### Hybrid Search

Combines vector and BM25 search, merging and re-ranking results.

**Best for**:
- General-purpose search
- Best of both worlds
- Unknown query types

**Example**:
```json
{
  "query": "docker compose health checks",
  "mode": "hybrid",
  "collections": ["*"],
  "limit": 10
}
```

**Result**: Combines semantic understanding with exact term matching

## Collection-Specific Searches

### RSS Feeds
```json
{
  "query": "latest news about kubernetes security",
  "mode": "hybrid",
  "collections": ["rss_feeds"],
  "limit": 10
}
```

**Content**: Technology news, blog posts, articles

### CVE (Security Vulnerabilities)
```json
{
  "query": "remote code execution vulnerabilities in docker",
  "mode": "hybrid",
  "collections": ["cve"],
  "limit": 20
}
```

**Content**: CVE entries, security advisories

### Torrents
```json
{
  "query": "ubuntu linux distribution iso",
  "mode": "hybrid",
  "collections": ["torrents"],
  "limit": 10
}
```

**Content**: Torrent metadata (legal content only)

### Wikipedia
```json
{
  "query": "container orchestration systems",
  "mode": "vector",
  "collections": ["wikipedia"],
  "limit": 5
}
```

**Content**: Encyclopedia articles

### BookStack (Documentation)
```json
{
  "query": "how to configure postgres in the stack",
  "mode": "hybrid",
  "collections": ["bookstack"],
  "limit": 5
}
```

**Content**: Internal documentation, guides, notes

### Australian Laws
```json
{
  "query": "privacy data protection",
  "mode": "hybrid",
  "collections": ["australian_laws"],
  "limit": 10
}
```

**Content**: Australian legal documents, acts, regulations

### Linux Documentation
```json
{
  "query": "bash shell scripting loops",
  "mode": "hybrid",
  "collections": ["linux_docs"],
  "limit": 10
}
```

**Content**: Linux man pages, documentation

## Query Patterns

### Simple Query
```python
import requests

response = requests.post("http://search-service:8098/search", json={
    "query": "docker networking",
    "mode": "hybrid",
    "collections": ["*"],
    "limit": 10
})

if response.status_code == 200:
    data = response.json()
    for result in data["results"]:
        print(f"[{result['score']:.2f}] {result['title']}")
        print(f"  ID: {result['id']}")
        print(f"  Source: {result['source']}")
        print(f"  {result['snippet'][:100]}...")
```

### Filtered Query
```python
# Search only in documentation
response = requests.post("http://search-service:8098/search", json={
    "query": "authentication setup",
    "mode": "hybrid",
    "collections": ["bookstack", "wikipedia"],
    "limit": 5
})
```

### Agent-Focused Query
```python
# Return content classified as useful for agents
response = requests.post("http://search-service:8098/search", json={
    "query": "kubernetes security best practices",
    "mode": "vector",
    "collections": ["*"],
    "limit": 20,
    "audience": "agent"
})
```

### Pagination
```python
# Get more results by increasing limit
response = requests.post("http://search-service:8098/search", json={
    "query": "python tutorials",
    "mode": "hybrid",
    "collections": ["wikipedia", "bookstack"],
    "limit": 50  # Up to 1000
})
```

## Score Interpretation

### Vector Search Scores (Cosine Similarity)
- **0.9 - 1.0**: Nearly identical content
- **0.8 - 0.89**: Very relevant
- **0.7 - 0.79**: Relevant
- **0.6 - 0.69**: Somewhat relevant
- **< 0.6**: Less relevant

### BM25 Scores
- **> 10**: Highly relevant (multiple keyword matches)
- **5 - 10**: Relevant (good keyword presence)
- **2 - 5**: Moderately relevant (some keyword matches)
- **< 2**: Low relevance

### Hybrid Scores
Normalized combination of vector and BM25 scores (0.0-1.0 scale).

## Best Practices

### Choose the Right Mode

```python
# Use vector for conceptual queries
{
    "query": "how to ensure data persistence",
    "mode": "vector"
}

# Use bm25 for exact terms
{
    "query": "CVE-2024-1234",
    "mode": "bm25"
}

# Use hybrid for general queries
{
    "query": "docker compose best practices",
    "mode": "hybrid"
}
```

### Target Specific Collections

```python
# Searching for code examples? Target documentation
{
    "query": "python pandas dataframe filter examples",
    "collections": ["bookstack", "wikipedia"]
}

# Searching for security info? Target CVE
{
    "query": "container escape vulnerabilities",
    "collections": ["cve", "bookstack"]
}
```

### Fetch Exact Documents After Search

```python
search = requests.post("http://search-service:8098/search", json={
    "query": "security configuration",
    "mode": "hybrid",
    "audience": "agent",
    "limit": 10
}).json()

for result in search["results"]:
    doc = requests.get(
        f"http://search-service:8098/documents/{result['id']}",
        params={"collection": result["source"]}
    ).json()
    print(doc["title"], doc["text"][:500])
```

### Handle Empty Results

```python
response = requests.post("http://search-service:8098/search", json={
    "query": "very specific query that might not match",
    "mode": "hybrid",
    "collections": ["*"],
    "limit": 10
})

data = response.json()
if data["total"] == 0:
    print("No results found. Try:")
    print("- Broaden your query")
    print("- Use different keywords")
    print("- Try vector mode for semantic matching")
else:
    print(f"Found {data['total']} results")
```

## Error Handling

```python
import requests

try:
    response = requests.post(
        "http://search-service:8098/search",
        json={"query": "test query", "mode": "hybrid"},
        timeout=30
    )
    response.raise_for_status()

    data = response.json()
    return data["results"]

except requests.exceptions.Timeout:
    print("Search request timed out")
except requests.exceptions.ConnectionError:
    print("Could not connect to search service")
except requests.exceptions.HTTPError as e:
    print(f"HTTP error: {e}")
except Exception as e:
    print(f"Unexpected error: {e}")
```

## Integration Examples

### Simple Search Function
```python
def search_webservices(query: str, limit: int = 10) -> list:
    """Search across all webservices knowledge sources."""
    response = requests.post(
        "http://search-service:8098/search",
        json={
            "query": query,
            "mode": "hybrid",
            "collections": ["*"],
            "limit": limit
        }
    )

    if response.status_code == 200:
        return response.json()["results"]
    return []
```

### RAG (Retrieval Augmented Generation)
```python
def search_for_context(query: str) -> str:
    """Retrieve context for LLM prompting."""
    results = search_webservices(query, limit=5)

    context_parts = []
    for i, result in enumerate(results, 1):
        context_parts.append(
            f"[{i}] {result['title']} ({result['source']})\n"
            f"id: {result['id']}\n"
            f"{result['snippet']}\n"
        )

    return "\n".join(context_parts)

# Use in LLM prompt
user_query = "How do I configure Docker volumes?"
context = search_for_context(user_query)
prompt = f"""Using this context:

{context}

Answer the question: {user_query}
"""
```

### Multi-Source Aggregation
```python
def search_multiple_sources(query: str) -> dict:
    """Search each source separately and aggregate."""
    sources = ["wikipedia", "bookstack", "linux_docs"]
    all_results = {}

    for source in sources:
        response = requests.post(
            "http://search-service:8098/search",
            json={
                "query": query,
                "mode": "hybrid",
                "collections": [source],
                "limit": 3
            }
        )

        if response.status_code == 200:
            all_results[source] = response.json()["results"]

    return all_results
```
