# Project Overview

This project is a self-hosted platform stack generator. It takes source templates plus a site manifest and produces a deployable bundle for a full authenticated webservices platform.

It is designed to show practical platform engineering rather than a toy demo. The stack includes identity, routing, secrets handling, app integration, observability, runtime provisioning, and automated validation.

## Problem It Solves

Small teams and individual operators often want the capabilities of a larger internal platform:

- single sign-on
- service routing
- role-based access control
- collaboration apps
- media and file services
- observability
- agent and notebook environments
- reliable deployment and verification

Running those as unrelated containers becomes fragile quickly. This repo turns them into one generated, tested, documented platform.

## What It Demonstrates

Platform architecture:

- Caddy edge routing
- Keycloak identity and RBAC
- Docker Compose service shards
- `systemd --user` supervision
- generated deploy bundles
- host-rendered runtime secrets

Application integration:

- OIDC and edge-auth patterns
- shared homepage catalog
- per-service health checks
- service-specific bootstrap scripts
- media, file, docs, chat, productivity, and development tools

Testing and validation:

- source-local unit checks
- Kotlin platform contract tests
- TypeScript unit tests
- Playwright browser suites
- SSO and auth boundary tests
- visual screenshot validation
- deployed runtime verification

Operational discipline:

- source versus generated output separation
- explicit site manifests
- SOPS-backed secrets
- reproducible build/deploy flow
- purge and rebuild support
- documentation for operators and contributors

## Why It Is Built This Way

The repo separates build-time and deploy-time concerns.

Local build:

- reads source templates
- reads a site manifest
- runs local checks
- produces a secret-free bundle

Host deploy:

- decrypts secrets with SOPS
- renders runtime config
- installs generated systemd user units
- starts Docker Compose backed services
- runs verification

This keeps secrets off the development machine and makes the deploy artifact easy to inspect.

## Technologies Used

- Docker and Docker Compose
- Caddy
- Keycloak
- SOPS
- Bash
- Kotlin
- TypeScript
- Playwright
- Gradle
- systemd user services
- Grafana, Prometheus, Loki
- Postgres, MariaDB, Valkey, Memcached

## Current Service Areas

- Identity and SSO
- Edge routing
- Observability
- Documentation and knowledge search
- File storage and WebDAV
- Mail, calendar, and contacts
- Messaging
- Password management
- Media streaming
- Notebooks and disposable workspaces
- Git hosting
- Backups
- Home automation
- ERP and task workflows

See [services.md](services.md) for the concrete service catalog.

## Engineering Priorities

The project favors:

- explicit deployment contracts over hidden magic
- generated artifacts over hand-maintained host state
- Keycloak-backed access control over per-app drift
- tests that exercise deployed reality
- docs that explain how to operate and extend the stack

## What This Is Not

This is not a one-click SaaS clone or a generic Helm chart collection. It is an opinionated platform generator for a specific self-hosted operating model.

It is also not a place for plaintext secrets, site-specific private data, or generated output treated as source.
