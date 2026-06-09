# webservices Stack Architecture

## Overview

webservices is a self-hosted platform stack with shared edge routing, identity, collaboration apps, observability, data/search services, and test-runner contract suites.

## Edge And Identity

```
Internet
  -> Caddy
       -> public routes
       -> direct OIDC apps
       -> Keycloak-backed oauth2-proxy forward-auth routes
  -> Keycloak
       -> users, groups, roles, sessions, MFA, OIDC clients
  -> services
```

- Caddy owns TLS, routing, and protected-route enforcement.
- Keycloak is the identity source of truth and OIDC provider.
- `keycloak-auth.<domain>` is the shared oauth2-proxy gateway for apps that do not own direct OIDC.
- The retired directory stack is removed; Keycloak is the only identity source of truth.

## Service Categories

- Infrastructure: Caddy, Docker proxy, Keycloak, oauth2-proxy gateway.
- Data: PostgreSQL, MariaDB, Valkey, Qdrant.
- AI/ML: inference gateway, embedding service, OpenSearch, Qdrant, workspace provisioner, and model-context services.
- Pipeline: document ingestion, vectorization, and staging.
- Collaboration: BookStack, Seafile, OnlyOffice, Vaultwarden, SOGo, Donetick, ERPNext.
- Media and home: Jellyfin, Home Assistant.
- Communication: mailserver, Synapse, Element, Mastodon, ntfy.
- Development: Forgejo, Forgejo Runner, JupyterHub, Disposable Workspaces, registry.
- Monitoring: Prometheus, Grafana, Alertmanager, cAdvisor, node exporter.

## Authentication Flow

1. User opens `https://app.<domain>`.
2. Caddy either forwards to the app directly, lets the app start its own OIDC flow, or calls the Keycloak auth gateway.
3. If authentication is required, the browser is redirected through `keycloak-auth.<domain>` and Keycloak.
4. Keycloak handles login, required actions, MFA, and group/role claims.
5. Caddy forwards trusted identity headers only after the auth gateway validates the session.
6. Apps that support OIDC consume Keycloak discovery, token, userinfo, and JWKS endpoints directly.

## Configuration Patterns

- Build source lives under `stack.compose/`, `stack.config/`, `stack.containers/`, `stack.systemd/`, `stack.kotlin/`, and `scripts/`.
- Generated `dist/` output is not source of truth.
- Secrets remain encrypted in the site bundle and are rendered on the target host at deploy time.
- Keycloak realm/client setup is reproducible through the stack Keycloak configuration scripts, not hand-clicked in the admin UI.
- Procedural documentation is generated at runtime by the ingestion/pipeline path and published to BookStack with URL and API indexes.
- Disposable Workspaces use a dispatcher model: Caddy authenticates with Keycloak, the provisioner authorizes ownership, then returns notebook, ttyd, and SSH access metadata for active labware containers.

## Troubleshooting

- Check Caddy routing: `docker logs caddy`.
- Check Keycloak: `docker logs keycloak`.
- Check auth gateway: `docker logs keycloak-auth-gateway`.
- Check direct OIDC apps for issuer/redirect URI mismatch.
- Directory-stack troubleshooting is obsolete; check Keycloak, the auth gateway, and app OIDC settings instead.
