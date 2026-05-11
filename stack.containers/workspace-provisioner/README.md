# Workspace Provisioner

Workspace provisioner runtime state lives under `/data`:
- `/data/db/workspaces.sqlite` for metadata
- `/data/step` for the Smallstep SSH CA material

The public surface is expected to sit behind the Keycloak-authenticated Caddy route for Disposable Workspaces. The provisioner trusts the proxy identity headers and enforces workspace ownership before issuing access metadata.

Dispatcher auth endpoints:
- `/api/workspaces/{id}/shell/auth` returns `X-Workspace-Health`, `X-Workspace-Ttyd-Port`, and `X-Workspace-Ttyd-Base-Path` for ttyd proxying.
- `/api/workspaces/{id}/notebook/auth` returns `X-Workspace-Health`, `X-Workspace-Notebook-Port`, and `X-Workspace-Notebook-Base-Path` for notebook proxying.
- `/api/workspaces/{id}/ssh-cert` issues short-lived SSH certificate material for the owning user.

Workspace summaries include SSH, ttyd shell, and notebook links while the workspace exists. The runtime labels workspace and test-tenant containers/volumes so the host purge script can remove stale labware resources after architectural changes.

Codex access tokens are write-only runtime secrets:
- `POST /api/workspaces/{id}/codex-token` writes the token into the owned workspace.
- `DELETE /api/workspaces/{id}/codex-token` clears it.
- The token file is created with mode `0600` inside the workspace home and is not returned by the API.
