# Knowledgebase

This knowledgebase is organized by audience. The core idea is simple: private,
client-owned, open-source infrastructure for small teams that need capability
without vendor dependency.

## Buyer Path

Start here when evaluating the offer rather than the implementation details:

- [Buyer Overview](buyer-overview.md)
- [Packages](packages.md)
- [Host Sizing](host-sizing.md)
- [Service Maturity](service-maturity.md)
- [Support Boundaries](support-boundaries.md)
- [Client Intake](client-intake.md)

## Engineering Evaluation Path

Use this path to inspect whether the project is serious platform engineering:

- [Project Overview](project-overview.md)
- [Engineering Evaluation Guide](evaluation-guide.md)
- [Architecture](architecture.md)
- [Build System](build-system.md)
- [Systemd Graph](systemd-graph.md)
- [Testing](testing.md)

## Operator Path

Use this path to deploy and operate the stack:

- [Quickstart](quickstart.md)
- [Minimal Build + Deploy System](minimal-build-deploy-system.md)
- [Operations](operations.md)
- [Troubleshooting](troubleshooting.md)
- [Host Sizing](host-sizing.md)

Normal operation is systemd-first: use `systemctl --user`, `./deploy.sh`, and
`./verify.sh` before dropping to raw Docker commands.

## Security Path

Use this path to inspect identity, secrets, caveats, and compliance posture:

- [Security And Auth](security-and-auth.md)
- [Threat Model](threat-model.md)
- [Compliance Posture](compliance-posture.md)
- [Service Maturity](service-maturity.md)
- [Security Policy](../SECURITY.md)

## Recovery Path

Use this path when planning restore, update, rollback, or incident response:

- [Recovery](recovery.md)
- [Restore Drill](restore-drill.md)
- [Update And Rollback](update-and-rollback.md)
- [Troubleshooting](troubleshooting.md)
- [Testing](testing.md)

## Service Development Path

Use this path when adding or changing platform services:

- [Service Standard](service-standard.md)
- [Services](services.md)
- [Architecture](architecture.md)
- [Testing](testing.md)
- [Contributing](../CONTRIBUTING.md)

## Mission Path

Use this path to understand the larger pattern without turning the docs into a
manifesto:

- [Mission](mission.md)
- [Buyer Overview](buyer-overview.md)
- [Packages](packages.md)

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

- [Buyer Overview](buyer-overview.md): plain-language offer and next step.
- [Mission](mission.md): grounded sovereignty-infrastructure thesis.
- [Packages](packages.md): buyer package tiers and scope boundaries.
- [Client Intake](client-intake.md): repeatable pre-engagement questions.
- [Host Sizing](host-sizing.md): starting host profiles and caveats.
- [Support Boundaries](support-boundaries.md): response expectations and client responsibilities.
- [Service Maturity](service-maturity.md): conservative maturity matrix.
- [Threat Model](threat-model.md): risks, mitigations, residual risks, and checks.
- [Compliance Posture](compliance-posture.md): compliance support without overclaims.
- [Restore Drill](restore-drill.md): restore procedure and acceptance checklist.
- [Update And Rollback](update-and-rollback.md): pins, updates, verification, and rollback caveats.
- [Demo Script](demo-script.md): buyer-safe walkthrough outline.
- [Proof Checklist](proof-checklist.md): proof completeness checklist.
- [Project Overview](project-overview.md): public summary and evaluation frame.
- [Engineering Evaluation Guide](evaluation-guide.md): adoption-oriented proof points.
- [Architecture](architecture.md): layers and source/generated boundaries.
- [Quickstart](quickstart.md): first successful build, deploy, and verify.
- [Build System](build-system.md): build/deploy artifact contract.
- [Minimal Build + Deploy System](minimal-build-deploy-system.md): compact operator contract.
- [Operations](operations.md): routine checks, deploy patterns, diagnostics, and purge boundaries.
- [Recovery](recovery.md): incident runbooks, restore drills, rollback, and post-recovery checks.
- [Systemd Graph](systemd-graph.md): generated unit graph and partial deploys.
- [Services](services.md): service catalog.
- [Security And Auth](security-and-auth.md): Keycloak, RBAC, edge auth, and secrets.
- [Service Standard](service-standard.md): checklist for platform-quality services.
- [Testing](testing.md): local, deployed, browser, and full test layers.
- [Troubleshooting](troubleshooting.md): failure-oriented operator guide.
- [Glossary](glossary.md): terms used across the project.
