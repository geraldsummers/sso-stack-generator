# Security And Auth

The platform is designed around shared identity. Keycloak is the source of users, groups, login flows, and service access policy.

Security is part of the platform contract, not an optional hardening pass. New
services should enter through the same identity, routing, secret, and test
model unless there is a documented reason they cannot.

![Trust boundary screenshot](assets/trust-boundary.svg)

## Keycloak

Keycloak provides:

- user accounts
- groups
- OIDC clients
- service roles
- onboarding and login flows
- SSO sessions across web apps

Services should be Keycloak-backed from day one when practical.

## RBAC

RBAC means role-based access control. In this stack, roles are usually expressed through Keycloak groups.

Common groups include:

- `users` for normal authenticated users
- `operators` for trusted operational access
- `admins` for platform administration
- service-specific groups when one app needs its own access boundary

Avoid hard-coding access decisions in one-off scripts when a Keycloak group can express the policy.

## Auth Patterns

There are two normal patterns.

Native OIDC:

- the app talks directly to Keycloak
- the app receives identity claims
- the app maps users and groups internally

Matrix native OIDC:

- Synapse legacy SSO remains the default for Element Web and existing Matrix clients
- Matrix Authentication Service is generated as the Element X/native OIDC path
- MAS uses Keycloak as its upstream IdP so Matrix does not become a separate identity source
- MAS compatibility routes and Synapse delegated auth are gated by `MATRIX_AUTHENTICATION_SERVICE_ACTIVE`
- leave `MATRIX_AUTHENTICATION_SERVICE_ACTIVE=false` until `syn2mas check` and dry-run migration have succeeded
- activating MAS without migration will invalidate existing Synapse-issued sessions, so this is a planned downtime migration

Edge-protected app:

- Caddy sends unauthenticated users through the shared auth gateway
- the app receives only already-authenticated traffic
- RBAC is enforced at the edge or by injected identity headers

Use native OIDC when the app supports it well. Use edge auth when the app has weak or missing OIDC support.

## Onboarding

The platform has a web onboarding flow. New users should be able to self-onboard through the onboarding UI rather than depending on an admin-only script.

The expected user-facing entrypoint is:

```text
https://onboarding.<domain>/start
```

When an app or route requires a completed account, non-onboarded users should be redirected to onboarding instead of hitting unexplained 403 pages.

## Secrets

Secrets are not stored as plaintext source.

Rules:

- keep secrets encrypted with SOPS until deploy time
- render secrets on the target host
- do not add repo-root `.env` files
- do not commit runtime files
- prefer file or stdin secret passing over command-line secret arguments
- keep secret-store selection in the site manifest

The host render target is:

```text
~/webservices/runtime
```

Treat that directory as ephemeral runtime material.

## Service Accounts

Some services need machine accounts, API tokens, or connector credentials. Those should be:

- generated or rendered during deploy/bootstrap
- scoped to the smallest useful permission set
- stored in runtime secret material or the appropriate app database
- covered by contract tests when they are platform-owned

## Native Apps And Mobile Clients

Some services have native clients or mobile apps. Browser SSO does not always transfer cleanly to native apps.

For those services, document the supported login path in the service docs or app UI. If the native app requires an app password, API token, or service-specific login method, make that explicit.
