# Service Standard

Use this checklist when adding or changing a platform service.

The goal is that every service behaves like part of one shared platform, not like a pile of unrelated containers.

## Source Files

Add or update the source that owns the behavior:

- `stack.compose/` for Compose services, volumes, networks, and dependencies
- `stack.config/` for application config, init scripts, and templates
- `stack.containers/` for custom images
- `stack.systemd/` for generated unit graph source
- `stack.kotlin/` for Kotlin contract coverage
- `stack.containers/test-runner/` for TypeScript, Playwright, visual, and workflow coverage
- `docs/` for human documentation

Do not patch `dist/`.

## Required Integration Points

For a normal user-facing web service, include:

- Caddy route
- Keycloak-backed authentication
- group-based RBAC
- homepage entry
- health check
- backup/storage decision
- recovery decision
- logs and metrics decision
- tests
- screenshots if it has a web UI
- documentation

## Auth And RBAC

New services must be multi-role and Keycloak-backed from day one unless there is a documented reason they cannot be.

Decide:

- who can open the app
- who can administer it
- whether users need per-user data isolation
- whether native clients need app passwords or API tokens
- whether the app supports native OIDC or needs edge auth

Prefer Keycloak groups for policy. Keep service-specific local admin users as bootstrap or break-glass accounts, not the primary access model.

## Routing

Most services should route as:

```text
https://<service>.<domain>
```

Route config should be generated from source templates and site inputs. Avoid hard-coded domains unless the surrounding file already uses rendered variables.

## Storage

Decide whether data is:

- disposable
- backed up
- mounted from a large media/data volume
- a database volume
- external site-specific storage

Document unusual paths. If the service owns user data, include it in backup or purge decisions.

Also document restore coupling. For example, a service that uses both a database
and a filesystem path must say whether those states need to be restored from the
same point in time.

## Health Checks

Add a health signal that proves the service is useful, not merely that a process exists.

Good checks include:

- HTTP status from the external Caddy route
- OIDC redirect behavior
- authenticated API check
- database-backed readiness endpoint
- service-specific CLI health command

## Tests

At minimum, add coverage for:

- generated config or compose contract
- external route availability
- auth boundary
- RBAC boundary for normal user versus admin/operator
- app smoke behavior

For web UIs, add screenshot coverage. Screenshots should prove the app is visible, themed acceptably, and not stuck on a login or error page.

## Portal

Add the user-facing entry in the service contract:

```text
stack.config/service-contracts.json
```

Use a plain description that tells a user what the app does. Keep names consistent with docs and tests.

## Documentation

Update:

- [services.md](services.md) for user-facing services
- [security-and-auth.md](security-and-auth.md) for unusual auth behavior
- [testing.md](testing.md) for new test groups or important commands
- [operations.md](operations.md) or [recovery.md](recovery.md) for new runbooks,
  restore requirements, or non-obvious repair steps
- service-specific docs if the service has operational quirks

## Purge And Rebuild

If the service adds persistent volumes, host paths, generated users, or systemd units, check purge behavior.

Destructive host-admin tooling belongs under:

```text
ops/host-admin/
```

Do not add destructive cleanup to the normal deploy path.
