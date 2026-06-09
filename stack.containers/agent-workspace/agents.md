# AGENTS.md

This home directory is a persistent working environment for agents.

Use it aggressively, but keep it legible. The goal is not to preserve every action. The goal is to preserve useful context, reusable capability, and durable notes.

## Priorities

1. Leave the environment more useful than you found it.
2. Prefer organized, durable notes over relying on chat memory.
3. Prefer reusable scripts over repeating manual command sequences.
4. Keep the home directory understandable to another future agent.
5. Do not leak secrets into notes, logs, histories, or repositories.

## Directory conventions

- `~/repositories/`
  - Put cloned repos and project working trees here.
  - Do not scatter git clones elsewhere.

- `~/notes/daily/`
  - Daily working notes.
  - Good for quick plans, breadcrumbs, and what changed today.

- `~/notes/projects/`
  - Durable project knowledge.
  - Put current status, important facts, useful commands, gotchas, and next steps here.

- `~/notes/incidents/`
  - Troubleshooting and operational writeups.
  - Use for failures, root causes, and recovery steps.

- `~/notes/decisions/`
  - Architectural or procedural decisions and why they were made.

- `~/state/session-journal/`
  - Append-only session traces and rough logs.
  - This can be messy, but should still be readable.

- `~/state/summaries/`
  - Concise summaries of completed or paused work.
  - Future agents should be able to read these first.

- `~/state/discoveries/`
  - Small factual findings worth preserving.
  - Example: version quirks, paths, service names, command behavior.

- `~/state/checkpoints/`
  - Unfinished work that should be resumed later.
  - Include exactly what remains and how to continue.

- `~/bin/`
  - Reusable helper scripts.
  - If you do something more than once and it can be scripted safely, put it here.

- `~/outputs/`
  - Keep generated artifacts worth preserving.

- `~/tmp/`
  - Disposable scratch space.
  - Clean as needed. Do not treat this as durable memory.

## Startup routine

When starting work:

1. Read `~/README_FIRST.md`.
2. Read `~/AGENTS.md`.
3. Run `stack-auth status` if this is a controller-managed workspace.
4. Inspect recent files in `~/notes/daily/`.
5. Inspect `~/state/checkpoints/` for unfinished work.
6. Inspect `~/state/summaries/` for recent task history.
7. If working on an existing project, read its note in `~/notes/projects/`.

## Stack interaction

This workspace may be managed by the webservices stack. If so, stack access details are written to:

- `~/.config/webservices/agent.env`

Do not print or store tokens from that file. Use the helper commands instead.

### Check access

Run:

```bash
stack-auth status
```

This reports whether stack knowledge access is configured and where the search/document APIs are.

### Search stack knowledge

Use `stack-search` before guessing about stack behavior, service names, deployment flows, or known incidents.

Examples:

```bash
stack-search "how does deployment work"
stack-search --mode hybrid --audience agent --limit 5 "qdrant embedding pipeline"
stack-search --collection linux_docs "systemd user service dependencies"
stack-search --collection cve --mode bm25 "CVE-2024"
```

### Extend the knowledge pipeline

If the task is to add a new ingestion source, first read the stack knowledge document `08-knowledge-pipeline-extension-guide.md`:

```bash
stack-search --collection stack_knowledge --mode bm25 --audience agent "Knowledge Pipeline Extension Guide"
```

The supported path is `StandardizedSource<T : Chunkable> -> DocumentStagingStore -> embedding-worker -> Qdrant + OpenSearch`, with optional BookStack publication through `content-publisher`. Prefer this path over writing directly to Qdrant so the new source gets deduplication, exact document retrieval, monitoring, publication state, and search API support.

Search modes:

- `hybrid`: default, combines semantic and keyword search.
- `vector`: semantic search for conceptual questions.
- `bm25`: keyword search for exact names, commands, errors, and CVEs.

Audience:

- `agent`: prefer structured/tool/code-oriented results.
- `human`: prefer readable article or documentation results.
- `both`: no audience filter.

### Fetch exact documents

Search results include an `id`. Use it to retrieve the exact source text:

```bash
stack-doc-get '<document-id>'
stack-doc-get '<document-id>' '<collection>'
```

Prefer exact document retrieval before citing or acting on a search result. Search snippets are context, not authority.

### Direct API access

The helper commands are preferred. If direct API access is necessary, source the environment in a short-lived shell and avoid logging the token:

```bash
set -a
. ~/.config/webservices/agent.env
set +a
curl -fsS -H "Authorization: Bearer ${STACK_AGENT_TOKEN}" \
  -H 'Content-Type: application/json' \
  -X POST \
  --data '{"query":"deployment flow","mode":"hybrid","collections":["*"],"limit":5,"audience":"agent"}' \
  "${STACK_KNOWLEDGE_SEARCH_URL}"
```

Available direct endpoints are discoverable through the workspace search helpers and the protected search route.

### Useful stack services

Use names exactly; many services are only reachable through stack networks or Caddy routes.

- `search`: protected OpenSearch route for stack knowledge text queries.
- `knowledge-ingestion`: pipeline monitor and source readiness.
- `embedding-gpu`: BGE-M3 embedding backend.
- `embedding-worker`: stages embeddings into Qdrant.
- `qdrant`: vector database.
- `workspace-provisioner`: workspace controller and knowledge proxy.

If a service is not reachable from this workspace, do not assume it is down. It may be intentionally network-isolated behind the workspace proxy.

### Operational boundaries

- Do not bypass the workspace knowledge proxy unless explicitly instructed.
- Do not write stack tokens into notes, command logs, shell history, commits, or issue comments.
- Do not mutate stack services from a workspace unless the task explicitly asks for operations.
- Prefer read-only diagnostics first: `stack-auth status`, `stack-search`, `stack-doc-get`, service health endpoints, and logs if available.
- When documenting findings, cite document IDs, service names, and commands used, but not credentials.

## Working rules

- Prefer editing or extending existing notes over creating duplicates.
- Prefer short, factual notes over long rambling transcripts.
- When you discover something non-obvious, write it down.
- When you run a sequence of commands more than once, consider turning it into a script in `~/bin/`.
- Keep project-specific notes close to the project topic, not scattered across daily notes unless they are just temporary breadcrumbs.
- If a repo has its own documentation, update that too when appropriate, but keep local agent-operational context in `~/notes/`.

## Shutdown routine

Before finishing work:

1. Update the relevant file in `~/notes/projects/`, `~/notes/incidents/`, or `~/notes/decisions/`.
2. Leave a concise summary in `~/state/summaries/` if useful.
3. If work is unfinished, create or update a file in `~/state/checkpoints/` with:
   - what was done
   - what remains
   - exact commands or files needed to resume
   - any risks or uncertainties
4. Move durable artifacts to `~/outputs/`.
5. Clean obvious junk from `~/tmp/`.

## Notes format guidance

Good notes are:

- short
- factual
- specific
- easy to skim
- useful to a future agent with zero context

Prefer including:

- paths
- service names
- commands
- versions
- constraints
- failure modes
- next steps

## Security rules

- Do not write secrets into notes, summaries, checkpoints, shell history, or git repos.
- Do not paste API keys, passwords, tokens, or private keys into markdown files.
- Treat `~/.ssh/`, secret mounts, and credentials as sensitive.
- Notes may reference the existence of a credential by name, but not its value.

## Persistence philosophy

This home directory is not just a scratchpad. It is a maintained operational memory.

Use it to build continuity:
- notes
- checklists
- scripts
- project memory
- incident knowledge
- resumable checkpoints

A future agent should be able to enter this home directory and become effective quickly.

## Default file naming suggestions

- Daily notes: `~/notes/daily/YYYY-MM-DD.md`
- Project notes: `~/notes/projects/<project-name>.md`
- Incident notes: `~/notes/incidents/YYYY-MM-DD-<short-name>.md`
- Decision logs: `~/notes/decisions/YYYY-MM-DD-<short-name>.md`
- Session summaries: `~/state/summaries/YYYY-MM-DDTHHMMSSZ-<short-name>.md`
- Checkpoints: `~/state/checkpoints/<project-name>.md`

## Biases

Prefer:
- plain text
- markdown
- grep-friendly formats
- small reusable shell scripts
- explicit next steps

Avoid:
- hidden state
- unexplained artifacts
- giant walls of text
- duplicate notes
- storing important context only in chat
