#!/usr/bin/env python3
"""Small client-side hybrid search helper for OpenSearch plus Qdrant."""

from __future__ import annotations

import argparse
import json
import os
from collections import defaultdict
from typing import Any

import requests


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("query")
    parser.add_argument("--collection", default=None)
    parser.add_argument("--limit", type=int, default=10)
    return parser.parse_args()


def reciprocal_rank_fusion(result_lists: list[list[dict[str, Any]]], limit: int) -> list[dict[str, Any]]:
    scores: dict[str, float] = defaultdict(float)
    docs: dict[str, dict[str, Any]] = {}
    for results in result_lists:
        for rank, result in enumerate(results, start=1):
            result_id = result["id"]
            scores[result_id] += 1.0 / (60 + rank)
            docs.setdefault(result_id, result)
    return sorted(docs.values(), key=lambda item: scores[item["id"]], reverse=True)[:limit]


def opensearch_results(query: str, collection: str | None, limit: int) -> list[dict[str, Any]]:
    base = os.environ["OPENSEARCH_URL"].rstrip("/")
    index = os.getenv("OPENSEARCH_INDEX", "knowledge")
    filters = []
    if collection:
        filters.append({"term": {"collection": collection}})
    body = {
        "size": limit,
        "query": {
            "bool": {
                "must": [{"multi_match": {"query": query, "fields": ["title^2", "text"]}}],
                "filter": filters,
            }
        },
    }
    response = requests.post(
        f"{base}/{index}/_search",
        auth=(os.getenv("OPENSEARCH_USERNAME", "admin"), os.environ["OPENSEARCH_PASSWORD"]),
        json=body,
        timeout=20,
    )
    response.raise_for_status()
    return [hit["_source"] | {"score": hit["_score"], "search_backend": "opensearch"} for hit in response.json()["hits"]["hits"]]


def main() -> int:
    args = parse_args()
    results = reciprocal_rank_fusion([opensearch_results(args.query, args.collection, args.limit)], args.limit)
    print(json.dumps(results, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
