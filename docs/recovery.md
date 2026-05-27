# Recovery

Use this page when the stack is broken, degraded, or suspected unsafe. For
routine operation, use [Operations](operations.md).

## Recovery Rules

- Preserve evidence before deleting state.
- Prefer `systemd --user`, `./deploy.sh`, and `./verify.sh` over raw Compose
  actions.
- Fix source or site inputs, rebuild, sync, deploy, and verify. Do not patch
  `dist/` or rendered runtime files as the lasting fix.
- Treat a failing `./verify.sh` as a failed deployment until the cause is known.
- Restore state before purging state when user data may be involved.

## Fast Triage

Run these on the target host:

```bash
cd ~/webservices
systemctl --user status webservices.target --no-pager -l
systemctl --user --failed --no-pager
systemctl --user list-units 'webservices-*' --all --no-pager
docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Image}}' | sort
./verify.sh --ready-only
```

For a failed unit:

```bash
systemctl --user status <unit-name> --no-pager -l
journalctl --user -u <unit-name> -n 160 --no-pager
docker logs --since 10m <container-name>
```

## Failed Full Deploy

If `./deploy.sh` fails, it prints diagnostics for the current phase. Keep that
output and then inspect the host:

```bash
cd ~/webservices
systemctl --user status webservices.target --no-pager -l
systemctl --user --failed --no-pager
docker ps -a --format '{{.Names}}\t{{.Status}}' | sort
```

Common recovery paths:

- SOPS failure: fix host SOPS identity or manifest secret-store path, then rerun
  `./deploy.sh`.
- missing host path: create or mount the expected site storage path, then rerun
  `./deploy.sh`.
- generated unit mismatch: rebuild from source, resync `dist/`, then run a full
  deploy.
- failed health gate: inspect the corresponding unit journal and container logs,
  fix the service cause, then run `./deploy.sh` again.

After any fix:

```bash
cd ~/webservices
./deploy.sh
./verify.sh
```

## Scoped Deploy Refused

Scoped deploys are guarded by the last successful full deploy signature. If a
scoped deploy reports that global deployment inputs changed, do not bypass it.
Run a full deploy:

```bash
cd ~/webservices
./deploy.sh
./verify.sh
```

That guard is expected when component selection, the systemd graph, Docker
network metadata, or Docker volume metadata changed.

## Target Failed Or Degraded

Reset failed state and ask systemd to reconcile the target through the deploy
contract:

```bash
cd ~/webservices
systemctl --user reset-failed
./deploy.sh
./verify.sh --ready-only
```

If the target still fails, inspect the failed units:

```bash
systemctl --user --failed --no-pager
systemctl --user status <unit-name> --no-pager -l
journalctl --user -u <unit-name> -n 160 --no-pager
```

Use a scoped deploy only after the failing unit is understood:

```bash
./deploy.sh --plan-only --unit <unit-name>
./deploy.sh --unit <unit-name>
./verify.sh --ready-only
```

## Single Service Unhealthy

Find the owning unit, inspect it, then redeploy that unit or component:

```bash
cd ~/webservices
systemctl --user list-units 'webservices-*' --all --no-pager
systemctl --user status <unit-name> --no-pager -l
journalctl --user -u <unit-name> -n 160 --no-pager
docker logs --since 10m <container-name>
./deploy.sh --plan-only --unit <unit-name>
./deploy.sh --unit <unit-name>
./verify.sh --ready-only
```

If the service owns persistent data, check disk, mount, and permission state
before recreating containers or purging anything.

## Web Route Fails

Check the public edge first:

```bash
curl -Ik https://<service>.<domain>
```

Then check:

- DNS or local name resolution points at the host.
- `webservices-caddy.service` is active.
- the service unit is active and healthy.
- generated Caddy config contains the route.
- Keycloak and the auth gateway are reachable for protected routes.

Useful commands:

```bash
systemctl --user status webservices-caddy.service --no-pager -l
journalctl --user -u webservices-caddy.service -n 160 --no-pager
docker logs --since 10m caddy
docker logs --since 10m keycloak
docker logs --since 10m keycloak-auth-gateway
```

## Login Loop Or 403

Check identity and policy before changing app config:

- the user exists in Keycloak
- onboarding is complete if the route requires it
- the user has the required Keycloak groups
- the app OIDC redirect URI matches the public URL
- the app is not presenting a conflicting local login path

If many apps fail at once, treat Keycloak, the auth gateway, cookies, and Caddy
as shared infrastructure and run:

```bash
cd ~/webservices
./deploy.sh --plan-only --component core
./deploy.sh --component core
./verify.sh
```

## Runtime Render Or Secret Failure

If rendered files are missing or stale, rerun deploy. Runtime files are generated
host state:

```bash
cd ~/webservices
./deploy.sh
./verify.sh --ready-only
```

If SOPS fails:

- confirm the host has the expected age identity
- confirm the manifest points at the intended encrypted secret store
- confirm the target user can read the encrypted site files bundled under
  `~/webservices/build/site/`

Do not commit plaintext env files or copy `runtime/` into source.

## Docker Infra Drift

Network and volume metadata is global control-plane state. If networks or
volumes do not match the bundle, run a full deploy:

```bash
cd ~/webservices
./deploy.sh
./verify.sh
```

If Docker itself is unhealthy, fix Docker first, then run the full deploy again.
Avoid deleting named volumes unless the recovery plan explicitly restores their
data.

## Backup Restore Drill

A backup is operational only after a restore has been tested.

Minimum restore drill:

1. Identify the service, databases, Docker volumes, host paths, and app-level
   exports that must be restored together.
2. Record the current generator commit, site manifest, and running unit state.
3. Stop writers with `systemctl --user stop <unit-name>` or stop
   `webservices.target` for a full-stack restore.
4. Restore into a temporary path first when possible.
5. Validate ownership and expected files before replacing live data.
6. Start the affected unit or run `./deploy.sh`.
7. Run `./verify.sh`, then targeted `./run-tests.sh` suites.

Kopia-backed file restores normally start by identifying snapshots from the
Kopia UI or container CLI, restoring to a temporary directory, and then copying
validated data into the stopped service's volume or bind mount. Prefer
app-aware database dumps for Postgres and MariaDB. If restoring raw database
directories, stop all writers first and restore the database files and dependent
app files from the same point in time.

Seafile-style split state must be restored as a set: filesystem data, database
schemas, and generated config must agree. Partial non-empty state should be
inspected or purged explicitly instead of auto-repaired.

## Purge And Fresh Rebuild

Purge is destructive host-admin work. Use it when the goal is to reset a host,
not to fix an ordinary failed deploy.

Preview:

```bash
EXPECTED_HOSTNAME=<host> ./ops/host-admin/purge-webservices-stack.sh --print-only
```

Delete stack runtime resources:

```bash
EXPECTED_HOSTNAME=<host> ./ops/host-admin/purge-webservices-stack.sh --yes-delete-webservices-stack
```

Delete configured site storage only when the recovery plan says user data should
be removed or restored separately:

```bash
EXPECTED_HOSTNAME=<host> ./ops/host-admin/purge-webservices-stack.sh --purge-storage --yes-delete-webservices-stack
```

After purge, rebuild from source, sync `dist/`, deploy, and verify.

## Rollback

Rollback is normally a site-repo and generator-pin operation:

1. Choose the previous trusted generator commit or site-config commit.
2. Rebuild with the explicit site manifest.
3. Sync the resulting `dist/` to `~/webservices/`.
4. Run a full deploy.
5. Run `./verify.sh`.

Do not roll back stateful service data by only rolling back source. Database
schema, app files, and generated config may need a matching restore point.

## Post-Recovery Checks

After any recovery:

```bash
cd ~/webservices
systemctl --user status webservices.target --no-pager -l
systemctl --user --failed --no-pager
./verify.sh
./run-tests.sh changed
```

For auth, routing, shared storage, or multi-service incidents, run the broader
suite that matches the affected surface.
