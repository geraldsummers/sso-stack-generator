# Compliance Posture

This project does not claim formal compliance certification by itself.
Compliance depends on the deployed environment, policies, logging, retention,
access reviews, legal agreements, operational process, and client-specific
requirements.

## What This Architecture Supports

- centralized identity
- RBAC
- encrypted secrets
- source-controlled deployment intent
- verification suite
- backups
- restore drills
- documented service ownership
- operator-visible lifecycle
- offboarding process if documented for the deployment

## What It Does Not Claim

This repo does not claim HIPAA, SOC 2, ISO 27001, GDPR, PCI, or other formal
certification. It also does not replace legal review, policy work, vendor risk
review, incident response planning, or client-specific controls.

## Useful Controls

The stack can support useful controls when properly deployed:

- shared login and group-based access
- target-host secret rendering
- documented deployment flow
- systemd-supervised services
- verification after deploy
- backup and restore procedures
- explicit service maturity notes

## Evidence A Buyer Can Inspect

- [Security And Auth](security-and-auth.md)
- [Threat Model](threat-model.md)
- [Testing](testing.md)
- [Restore Drill](restore-drill.md)
- [Update And Rollback](update-and-rollback.md)
- [Service Maturity](service-maturity.md)
- [Operations](operations.md)

## Questions For Regulated Deployments

- What regulated data is in scope?
- What retention rules apply?
- Who owns access reviews?
- What audit logs must be retained?
- What contracts or policies are required?
- What incident response process is required?
- What recovery time and recovery point are required?
- Which services are acceptable for regulated data?
