# Threat Model

This threat model is not a guarantee of security. It is a working map of risks,
mitigations, residual risks, and checks.

| Threat | Mitigation | Residual Risk | Test/Check |
| --- | --- | --- | --- |
| public route bypasses auth | Caddy routes and auth gateway patterns | misconfigured route can expose app | route and auth boundary tests |
| service accepts spoofed identity headers | identity established at edge; services should not trust client headers | app-specific header behavior may vary | service standard review |
| plaintext secret enters repo | SOPS-backed site secrets; runtime rendering on host | human error | secret scanning and review |
| secret appears in logs or command arguments | prefer file/stdin secret passing; docs warn against command args | third-party apps may log too much | log review after bootstrap |
| compromised app container | network boundaries, least useful service scope, separate identity provider | host/container escape risk remains | update cadence and exposed route review |
| compromised admin account | Keycloak central identity and group policy | admin can still cause broad damage | access review and MFA where required |
| compromised target host | secrets render on target host | host compromise is high impact | host hardening and backup separation |
| lost host | backup and rebuild procedure | restore time depends on backup quality | restore drill |
| failed backup | Kopia and documented restore expectations | backup may silently be incomplete | restore drill acceptance checklist |
| broken restore | restore drill and post-restore verification | stateful apps may need app-specific procedure | `./verify.sh` and data sanity checks |
| stale service images | image tags and update process | upstream vulnerabilities may remain | update review and vulnerability monitoring |
| native/mobile app login inconsistency | document native app caveats and token paths | some apps do not support SSO cleanly | service maturity matrix |
| manual host mutation outside deploy contract | source-generated bundle and operations docs | urgent manual changes may drift | deploy from source and inspect drift |
| generated output edited directly | docs warn to fix source and rebuild | local emergency edits can be lost | review `dist/` changes and rebuild |
| DNS misconfiguration | quickstart and troubleshooting checks | external provider errors | `curl -Ik` and DNS checks |
| exposed admin route | Caddy/auth review and service standard | app defaults may expose admin path | route review and browser tests |
| excessive RBAC permissions | Keycloak groups as policy | groups can drift | access review and auth tests |
| weak offboarding | centralized identity and group removal | app-local accounts may remain | service-specific offboarding check |

## Notes

Compliance depends on the deployed environment, policies, logging, retention,
access reviews, legal agreements, operational process, and client-specific
requirements.
