# Knowledgebase

This knowledgebase explains the platform as an engineer would evaluate it:
what it is, why it exists, how it is built, how it is secured, and how to
operate or extend it without guessing.

## Start Here

| Goal | Read |
| --- | --- |
| Understand the project quickly | [Project Overview](project-overview.md) |
| Deploy it once end to end | [Quickstart](quickstart.md) |
| Understand the architecture | [Architecture](architecture.md) |
| Add or change a service | [Service Standard](service-standard.md) |
| Debug a failing deploy | [Troubleshooting](troubleshooting.md) |
| Choose the right tests | [Testing](testing.md) |

## Visual Tour

![Sanitized platform homepage screenshot](assets/platform-home.svg)

The generated platform is meant to feel like one internal web surface, not a
collection of unrelated containers. Caddy owns the edge, Keycloak owns
identity, and the homepage is the user-facing catalog.

![Build deploy verify flow](assets/build-deploy-verify.svg)

The core operational contract is deliberately small: build a secret-free
bundle, sync it to the host, deploy in place, and verify the deployed runtime.

![Verification suite screenshot](assets/verification-suite.svg)

The stack is treated as untrusted until `./verify.sh` proves readiness,
authentication boundaries, service health, and core application behavior.

## Reader Paths

### Evaluating The Platform

Start with:

- [Project Overview](project-overview.md)
- [Services](services.md)
- [Security And Auth](security-and-auth.md)

You should come away knowing whether this operating model fits your team:
source templates, explicit site manifests, SOPS-encrypted site inputs, generated
systemd user units, Docker Compose shards, and a deployed verification suite.

### Operating A Deployment

Start with:

- [Quickstart](quickstart.md)
- [Build System](build-system.md)
- [Systemd Graph](systemd-graph.md)
- [Troubleshooting](troubleshooting.md)

The most important invariant is the split between local build and host deploy.
Local build produces a secret-free `dist/`; host deploy renders runtime config
and starts services from `~/webservices`.

### Extending The Stack

Start with:

- [Service Standard](service-standard.md)
- [Architecture](architecture.md)
- [Testing](testing.md)
- [Contributing](../CONTRIBUTING.md)

New services should arrive as platform citizens: route, auth, RBAC, homepage
entry, storage decision, health check, tests, screenshots, and docs.

### Reviewing Security

Start with:

- [Security And Auth](security-and-auth.md)
- [Keycloak Identity Cutover](keycloak-identity-cutover.md)
- [Minimal Build + Deploy System](minimal-build-deploy-system.md)
- [Security Policy](../SECURITY.md)

The trust model is intentionally visible: encrypted site inputs stay outside
this repo, runtime secrets are rendered on the host, and access policy comes
from Keycloak groups instead of per-app drift.

## Core Concepts

`site manifest`

: Selects the site and points at encrypted site inputs.

`dist/`

: The local deploy bundle. It is generated output and should remain
secret-free.

`runtime/`

: Host-rendered env and config material. It is runtime state, not source.

`component`

: A selectable feature area from `stack.config/components.json`.

`lifecycle domain`

: A generated systemd unit that starts one Docker Compose shard and its health
gate.

`stack contract`

: The blocking verification suite that proves the deployed platform is usable.

## Documentation Map

- [Project Overview](project-overview.md): public summary and evaluation frame.
- [Architecture](architecture.md): layers and source/generated boundaries.
- [Quickstart](quickstart.md): first successful build, deploy, and verify.
- [Build System](build-system.md): build/deploy artifact contract.
- [Minimal Build + Deploy System](minimal-build-deploy-system.md): compact operator contract.
- [Systemd Graph](systemd-graph.md): generated unit graph and partial deploys.
- [Services](services.md): service catalog.
- [Security And Auth](security-and-auth.md): Keycloak, RBAC, edge auth, and secrets.
- [Service Standard](service-standard.md): checklist for platform-quality services.
- [Testing](testing.md): local, deployed, browser, and full test layers.
- [Troubleshooting](troubleshooting.md): failure-oriented operator guide.
- [Glossary](glossary.md): terms used across the project.
