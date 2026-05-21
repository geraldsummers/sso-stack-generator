# Web Services

This repository builds and tests a self-hosted platform stack for authenticated shared web services. It demonstrates production-style platform engineering: identity, routing, secrets, observability, service integration, generated deployment artifacts, and an end-to-end validation suite.

It is a stack generator, not a single application. You give it a site manifest, it builds a secret-free deploy bundle, and the target host deploys that bundle into Docker/systemd services.

For a portfolio-level summary, read [docs/project-overview.md](docs/project-overview.md).

## What This Repo Gives You

- One Caddy edge for service routing.
- One Keycloak realm for shared login, groups, and RBAC.
- Shared observability with Grafana, Prometheus, Loki, cAdvisor, and exporters.
- Shared collaboration and productivity apps such as BookStack, Seafile, SOGo, Element, Planka, Vaultwarden, Donetick, ERPNext, and Home Assistant.
- Media and utility apps such as Jellyfin, qBittorrent, Kopia, and Mastodon.
- Agent and AI-facing services such as JupyterHub, Disposable Workspaces, Qdrant, search, knowledge ingestion, and the ChatGPT Connector.
- A platform-owned test runner with contract, browser, auth, visual, and service checks.

## What This Repo Does Not Own

- Site-specific secrets.
- Downstream app-specific business logic.
- Downstream-only deploy scripts.
- Downstream-only tests.
- Generated output as source.

Site-specific inputs live outside this repo and are selected explicitly with a manifest path.

## Quick Start

Most operators only need this flow:

```bash
cd sso-stack-generator

SITE_MANIFEST="/path/to/site/manifest.json"
TARGET_HOST="user@example-host"

./build.sh --manifest "$SITE_MANIFEST"

ssh "$TARGET_HOST" 'mkdir -p ~/webservices && test -w ~/webservices'
rsync -av --no-group --delete ./dist/ "$TARGET_HOST":~/webservices/

ssh "$TARGET_HOST" 'cd ~/webservices && ./deploy.sh'
ssh "$TARGET_HOST" 'cd ~/webservices && ./verify.sh'
```

Read [docs/quickstart.md](docs/quickstart.md) for the same flow with explanations and troubleshooting.

## How The Build Works

1. `./build.sh --manifest <manifest.json>` runs locally.
2. The build creates `dist/`.
3. `dist/` is copied to the target host.
4. `./deploy.sh` runs on the target host from `~/webservices`.
5. `./verify.sh` checks readiness and the blocking platform contract.

Important rule: the build bundle is secret-free. Secrets remain encrypted until `deploy.sh` renders runtime files on the target host.

See [docs/build-system.md](docs/build-system.md) for the detailed contract.

## Repository Map

| Path | Purpose |
| --- | --- |
| `stack.compose/` | Source Docker Compose shards for platform services. |
| `stack.config/` | Source config templates, service entrypoints, Caddy config, Keycloak config, and app configs. |
| `stack.containers/` | Custom container build contexts. |
| `stack.kotlin/` | Kotlin services and Kotlin test runner code. |
| `stack.systemd/` | Source graph for generated systemd user units. |
| `scripts/` | Build/deploy/render helper scripts. |
| `global.settings/` | Shared non-secret defaults and generated-bundle settings. |
| `ops/host-admin/` | Destructive host-admin tooling such as purge scripts. |
| `docs/` | Human documentation. |
| `dist/` | Generated deploy bundle. Do not edit as source. |
| `out/`, `build/`, Bazel/Gradle outputs | Generated outputs. Do not edit as source. |

## Public Commands

Local source checkout:

```bash
./build.sh --manifest /path/to/site/manifest.json
./stack.containers/test-runner/run-tests.sh ts-unit
./stack.containers/test-runner/run-tests.sh kt stack-contract
```

Target host, from `~/webservices` after syncing `dist/`:

```bash
./deploy.sh
./verify.sh
./run-tests.sh list
./run-tests.sh plan all
./run-tests.sh all
```

Use `./run-tests.sh all` when you really want every registered check. Use targeted tests while iterating.

Granular test commands include `plan [target]`, `kt-tests [suite]`, `kt-plan [suite]`, `kt-one <id> [suite]`, `source-unit`, and `changed`.

See [docs/testing.md](docs/testing.md).

## Services

The user-facing service catalog is documented in [docs/services.md](docs/services.md).

Most services follow this standard:

- Caddy route under `https://<service>.<domain>`.
- Keycloak-backed authentication or Caddy forward-auth.
- Keycloak groups for RBAC from day one.
- Homepage entry.
- Health check.
- Contract or browser coverage.
- Visual screenshot coverage when there is a web UI.

## Auth Model

Keycloak is the identity source. Services either:

- use native OIDC against Keycloak, or
- sit behind the Keycloak auth gateway at the Caddy edge.

RBAC is group-based. Common groups include `users`, `operators`, `admins`, and service-specific groups when needed.

See [docs/security-and-auth.md](docs/security-and-auth.md).

## Testing Model

The platform has three testing layers:

- Source-local unit/config tests during `build.sh`.
- Blocking deployed verification through `./verify.sh`.
- Optional granular or exhaustive suites through `./run-tests.sh`.

Browser and deep auth tests require a deployed runtime because they need real domains, Caddy, Keycloak, cookies, and service containers.

## Adding Or Changing Services

Start with [docs/service-standard.md](docs/service-standard.md). It explains the expected source files, routing, auth, homepage, health checks, generated docs, and test coverage.

Do not patch generated output in `dist/`. Fix the source that generates it.

## Docs Index

- [docs/project-overview.md](docs/project-overview.md): public-facing summary of what this project demonstrates.
- [docs/quickstart.md](docs/quickstart.md): first successful build, deploy, and verify.
- [docs/architecture.md](docs/architecture.md): how the platform is put together.
- [docs/services.md](docs/services.md): service catalog and ownership notes.
- [docs/security-and-auth.md](docs/security-and-auth.md): Keycloak, RBAC, secrets, and runtime material.
- [docs/testing.md](docs/testing.md): test commands and when to use each one.
- [docs/service-standard.md](docs/service-standard.md): checklist for adding or changing services.
- [docs/systemd-graph.md](docs/systemd-graph.md): generated systemd user unit graph contract.
- [docs/troubleshooting.md](docs/troubleshooting.md): common failures and where to look.
- [docs/glossary.md](docs/glossary.md): terms used in this repo.
- [docs/build-system.md](docs/build-system.md): detailed build/deploy contract.
- [docs/minimal-build-deploy-system.md](docs/minimal-build-deploy-system.md): compact operator contract.
- [CONTRIBUTING.md](CONTRIBUTING.md): change workflow for contributors.
- [SECURITY.md](SECURITY.md): security reporting and secret-handling policy.
- [LICENSE](LICENSE): MIT license.

## Safety Rules

- Edit source, not generated output.
- Keep secrets encrypted until host deploy.
- Do not add repo-root `.env` files.
- Do not commit `dist/`, `out/`, Gradle outputs, Bazel outputs, or rendered `runtime/` material.
- Keep destructive host operations under `ops/host-admin/`.
- Prefer targeted tests during development and `verify.sh` before trusting a deployment.
