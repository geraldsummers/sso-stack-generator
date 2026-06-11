# Engineering Evaluation Guide

Use this guide when deciding whether the stack is worth taking seriously. It is
written for engineers who want signal quickly: architecture, security,
operability, extension cost, and failure behavior.

## Executive Read

This project is valuable when you want a self-hosted platform with many apps
but you do not want each app to invent its own login, route, storage, deploy,
and test story.

It is a poor fit if you want a generic Helm chart library, a one-click desktop
installer, or a stack that hides operational details from the operator.

## What To Inspect First

| Question | Evidence |
| --- | --- |
| Does it separate source from generated output? | [Build System](build-system.md) and `dist/` contract |
| Does it keep secrets out of local builds? | [Security And Auth](security-and-auth.md) |
| Does it have a coherent service lifecycle? | [Systemd Graph](systemd-graph.md) |
| Does it prove deployed behavior? | [Testing](testing.md) and `./verify.sh` |
| Can services be added consistently? | [Service Standard](service-standard.md) |
| Can failures be diagnosed by layer? | [Troubleshooting](troubleshooting.md) |

## Proof Points

### Build Artifacts Are Inspectable

The local build produces a secret-free `dist/` bundle. That bundle includes the
selected Compose files, generated systemd units, scripts, source config, and a
component lock. It does not render plaintext secrets locally.

### Identity Is Centralized

Keycloak is the identity source. Services either use native OIDC or sit behind
Caddy forward auth. Group-based RBAC is the default policy language.

![Trust boundary screenshot](assets/trust-boundary.svg)

### Runtime Is Supervised

The generated systemd graph gives operators a real service surface. Compose is
still the container backend, but lifecycle, readiness, and scoped deploys are
owned by systemd user units.

![Systemd orchestration screenshot](assets/systemd-orchestration.svg)

### Verification Exercises Reality

The blocking verification suite runs against the deployed stack. It checks
readiness, authentication boundaries, service APIs, and important workflows
through the same network and routing layers users hit.

![Verification suite screenshot](assets/verification-suite.svg)

## Evaluation Checklist

Before trusting a deployment, an engineer should be able to answer:

- Which site manifest selected this bundle?
- Which components were locked into the build?
- Where are runtime secrets rendered?
- Which Keycloak groups grant access to user-facing services?
- Which systemd target owns the service being changed?
- Which tests prove the changed route, auth boundary, or service behavior?
- How would the service be purged or restored?

If any answer is unclear, the right fix is usually documentation, test coverage,
or a tighter service standard entry, not a private operator note.

## What Good Changes Look Like

A strong service change usually touches several layers:

- `stack.compose/` for service runtime
- `stack.config/` for app config and entrypoints
- `stack.systemd/` when lifecycle grouping changes
- `stack.kotlin/` or Playwright tests for behavior
- `stack.config/service-contracts.json` for discoverability and evidence contracts
- `docs/` for operator and contributor context

That is deliberate. A platform service is not complete just because a container
starts.

## Red Flags

Treat these as design problems:

- a service has a public route but no auth story
- secrets appear in source, generated docs, logs, or command arguments
- deploy steps mutate the host outside the bundle contract
- a service works only because of manual host state
- tests check a process but not the route or auth boundary users depend on
- generated `dist/` files are edited directly

## Next Reading

- [Knowledgebase](README.md)
- [Project Overview](project-overview.md)
- [Quickstart](quickstart.md)
- [Architecture](architecture.md)
