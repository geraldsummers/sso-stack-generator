# ChatGPT MCP Connector

The `chatgpt-connector` service exposes account lifecycle and token APIs, and an MCP endpoint at `/mcp`.

## API Surface

- `GET /api/me`
- `GET /api/agent-accounts`
- `POST /api/agent-accounts`
- `POST /api/agent-accounts/{id}/tokens`
- `POST /api/tokens/{id}/revoke`

## MCP Tools

- `search`
- `fetch`
- `pipeline_status`
- `workspace_readiness`
