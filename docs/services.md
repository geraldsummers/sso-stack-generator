# Services

This page explains what the user-facing services are for. The homepage catalog is generated from `stack.config/homepage/services.yaml`, so service names here should match the UI.

![Sanitized platform homepage screenshot](assets/platform-home.svg)

URLs are shown as `https://<subdomain>.<domain>`. Replace `<domain>` with the site domain from the manifest.

## AI And Development

| Service | URL | Purpose |
| --- | --- | --- |
| ChatGPT Connector | `https://chatgpt-connector.<domain>` | MCP tools and agent account token management. |
| JupyterHub | `https://jupyterhub.<domain>` | Shared notebook environment for data science and agent workflows. |
| Forgejo | `https://forgejo.<domain>` | Git hosting and repository workflows. |
| Workspaces | `https://workspaces.<domain>` | Disposable SSH/labware workspaces, including shell access to active environments. |

## Data And Analytics

| Service | URL | Purpose |
| --- | --- | --- |
| Search Service | `https://search.<domain>` | Knowledge search API and search UI for platform knowledge. |

## Collaboration

| Service | URL | Purpose |
| --- | --- | --- |
| BookStack | `https://bookstack.<domain>` | Human-readable documentation wiki and generated procedural docs. |
| SOGo | `https://sogo.<domain>` | Mail, calendar, and contacts. |
| Planka | `https://planka.<domain>` | Boards and project planning. |
| Element | `https://element.<domain>` | Matrix messaging client. |

## Productivity

| Service | URL | Purpose |
| --- | --- | --- |
| Seafile | `https://seafile.<domain>` | File storage, WebDAV, sync, and collaborative office integration. |
| Donetick | `https://donetick.<domain>` | Shared chores, routines, and household/task tracking. |
| Vaultwarden | `https://vaultwarden.<domain>/sso-login` | Password manager with SSO entrypoint. |

## Social And Media

| Service | URL | Purpose |
| --- | --- | --- |
| Jellyfin | `https://jellyfin.<domain>` | Media library and streaming server. |
| Mastodon | `https://mastodon.<domain>` | Social networking instance. |

## System

| Service | URL | Purpose |
| --- | --- | --- |
| Progression | `https://progress.<domain>` | Evidence-backed apprenticeship dashboard and `stackctl` mirror. |
| Grafana | `https://grafana.<domain>` | Monitoring dashboards. |
| Kopia | `https://kopia.<domain>` | Backups. |
| Home Assistant | `https://homeassistant.<domain>` | Home automation. |
| ERPNext | `https://erpnext.<domain>` | ERP and operations suite. |

## Platform Services

These services are part of the platform but are not always listed as normal user apps:

| Service | Purpose |
| --- | --- |
| Caddy | TLS, routing, reverse proxy, and edge auth integration. |
| Keycloak | Shared identity provider, groups, OIDC clients, and RBAC source. |
| Postgres, MariaDB, Valkey, Memcached | Shared data stores used by platform apps. |
| Prometheus, Loki, cAdvisor, exporters | Metrics, logs, and container monitoring. |
| Qdrant | Vector database used by knowledge and agent search workflows. |
| Mailserver | Mail transport and mailbox support for SOGo and related services. |
| Test runner | Contract, browser, auth, workflow, and visual validation. |

## Service Standards

New user-facing services should normally have:

- a Caddy route
- Keycloak-backed login
- group-based RBAC
- a homepage entry
- a health check
- test coverage
- screenshots if the service has a web UI
- documentation in this page when the service is user-facing

See [service-standard.md](service-standard.md) for the full checklist.
