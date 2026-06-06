from __future__ import annotations

import os
from datetime import datetime, timedelta

import requests
from airflow.decorators import dag, task


RUNNER_URL = os.getenv("AIRFLOW_CONN_INGESTION_RUNNER", "http://ingestion-runner:8090").rstrip("/")

SOURCES = {
    "wikipedia": timedelta(days=30),
    "rss": timedelta(hours=1),
    "cve": timedelta(days=1),
    "torrents": timedelta(hours=6),
    "australian_laws": timedelta(days=1),
    "linux_docs": timedelta(days=7),
    "debian_wiki": timedelta(days=7),
    "arch_wiki": timedelta(days=7),
    "opendota": timedelta(hours=1),
    "poe_ninja": timedelta(hours=6),
    "stack_knowledge": timedelta(hours=1),
    "agent_docs": timedelta(hours=1),
}

PUBLISHABLE_SOURCES = [
    "rss",
    "cve",
    "wikipedia",
    "australian_laws",
    "linux_docs",
    "debian_wiki",
    "arch_wiki",
    "agent_docs",
    "stack_knowledge",
]


def call_runner(path: str, payload: dict) -> dict:
    response = requests.post(f"{RUNNER_URL}{path}", json=payload, timeout=60 * 30)
    response.raise_for_status()
    return response.json()


@dag(
    dag_id="knowledge_bootstrap",
    start_date=datetime(2025, 1, 1),
    schedule=None,
    catchup=False,
    default_args={"retries": 3, "retry_delay": timedelta(minutes=2)},
    tags=["knowledge", "bootstrap"],
)
def knowledge_bootstrap():
    @task
    def bootstrap_backends() -> dict:
        return call_runner("/bootstrap", {})

    bootstrap_backends()


knowledge_bootstrap()


def build_source_dag(source: str, schedule_delta: timedelta):
    @dag(
        dag_id=f"{source}_ingestion",
        start_date=datetime(2025, 1, 1),
        schedule=schedule_delta,
        catchup=False,
        max_active_runs=1,
        default_args={"retries": 3, "retry_delay": timedelta(minutes=5)},
        tags=["knowledge", source],
    )
    def source_dag():
        @task
        def ingest() -> dict:
            return call_runner("/run", {"source": source})

        ingest()

    return source_dag()


for _source, _schedule_delta in SOURCES.items():
    globals()[f"{_source}_ingestion"] = build_source_dag(_source, _schedule_delta)


@dag(
    dag_id="bookstack_publication",
    start_date=datetime(2025, 1, 1),
    schedule=None,
    catchup=False,
    max_active_tasks=1,
    default_args={"retries": 3, "retry_delay": timedelta(minutes=5)},
    tags=["knowledge", "bookstack"],
)
def bookstack_publication():
    @task
    def publish_allowed_source(source: str) -> dict:
        return call_runner("/publish", {"source": source, "publish": True})

    previous = None
    for source in PUBLISHABLE_SOURCES:
        current = publish_allowed_source.override(task_id=f"publish_{source}")(source)
        if previous is not None:
            previous >> current
        previous = current


bookstack_publication()
