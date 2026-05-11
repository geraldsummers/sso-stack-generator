# Codex runtime notes

Use `agent-codex-doctor` to validate the local Codex runtime wiring.
Use `agent-codex-run` to execute non-interactive tasks inside the owned workspace.

Expected env vars for local OpenAI-compatible backends:
- `CODEX_MODEL`
- `CODEX_API_KEY`
- `CODEX_BASE_URL`
- `CODEX_REQUEST_ID` (optional local run label)

`CODEX_BASE_URL` should point at the OpenAI-compatible `/v1` root exposed by the stack inference gateway, for example `https://models.<domain>/llm/v1`.
`agent-codex-run` records `CODEX_REQUEST_ID` in its local log output. Codex itself emits an automatic `X-Client-Request-Id` on OpenAI-compatible requests, which is the reliable transport-level correlation ID today.

Controller-managed Disposable Workspaces may receive a write-only Codex token through the workspace provisioner API. The provisioner writes `/workspace-home/.config/webservices/codex.env` with mode `0600`; runtime wrappers source that file before invoking Codex. When only `CODEX_API_KEY` is set, the wrappers also export it as `OPENAI_API_KEY` for OpenAI-compatible clients.

Do not commit Codex tokens to repositories or notes. Use the workspace UI or `POST /api/workspaces/{id}/codex-token` to set the runtime secret and `DELETE /api/workspaces/{id}/codex-token` to clear it.
