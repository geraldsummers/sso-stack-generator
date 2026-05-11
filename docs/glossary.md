# Glossary

## Stack Generator

A repository that generates a deployable platform bundle from source templates and a site manifest. This repo is a stack generator.

## Site Manifest

A JSON file outside this repo that selects the site being built and points at site-specific inputs.

Example:

```text
/path/to/site/manifest.json
```

## Site Bundle

The manifest plus the external files it references. Site bundles are not discovered through repo-local symlinks.

## `dist/`

The local generated deploy bundle. It is output, not source.

## Runtime

Host-rendered runtime material under:

```text
~/webservices/runtime
```

This includes decrypted env/config material created during deploy.

## Caddy

The edge reverse proxy. It handles HTTPS, public routing, and auth integration.

## Keycloak

The shared identity provider. It owns users, groups, OIDC clients, sessions, and RBAC source data.

## OIDC

OpenID Connect. The login protocol used by many services to authenticate with Keycloak.

## RBAC

Role-based access control. In this stack, RBAC is usually expressed with Keycloak groups.

## Forward Auth

An edge-auth pattern where Caddy checks a request with an auth service before proxying it to an app.

## Compose Shard

A Docker Compose file that owns one part of the stack. The stack uses Compose as a container backend, while `systemd --user` supervises generated units.

## Systemd User Unit

A systemd unit installed under the deploy user's user manager. These units start and supervise stack services.

## Labware

The isolated container environment used by Disposable Workspaces and agent development workflows.

## Disposable Workspaces

Short-lived development environments that users can create, access, and delete. They are intended for isolated AI or development work.

## BookStack Procedural Docs

Runtime-generated documentation pages created from platform data and pipeline output. These docs are generated because they depend on live ingested data.

## Stack Contract

The blocking Kotlin verification suite that proves core platform behavior after deploy.

## Visual Suite

Playwright tests that capture screenshots of web UIs to prove the service is visible and usable.
