# Agent Workspace Guide

The authenticated webservices stack provides agent workspaces through `workspace-provisioner`.

## Core Endpoints

- `GET /api/workspaces`
- `POST /api/workspaces`
- `POST /api/workspaces/{id}/start`
- `POST /api/workspaces/{id}/stop`

## Safety Rules

- Keep long-running operations inside the workspace runtime.
- Use delegated access tokens with minimal scope.
- Rotate and revoke tokens after task completion.
