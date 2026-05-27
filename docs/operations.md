# Operations

This page covers normal day-2 operation after a stack has been deployed once.
For break/fix procedures, use [Recovery](recovery.md).

## Control Surface

Run normal operator commands from the deployed bundle on the host:

```bash
cd ~/webservices
```

The stack is supervised by `systemd --user`. Docker Compose is the container
backend, but systemd owns start order, readiness, restarts, and scoped deploys.

Use systemd and the bundled scripts first:

```bash
systemctl --user status webservices.target --no-pager -l
systemctl --user list-units 'webservices-*' --all --no-pager
./verify.sh
./run-tests.sh list
```

Use raw `docker` and `docker compose` commands for inspection. Do not use them
as the normal restart or deploy mechanism unless a runbook explicitly says to
drop below systemd.

## Routine Checks

Daily checks:

```bash
cd ~/webservices
systemctl --user --failed --no-pager
systemctl --user status webservices.target --no-pager -l
docker ps --format '{{.Names}}\t{{.Status}}' | sort
./verify.sh --ready-only
```

Weekly or after larger changes:

```bash
cd ~/webservices
./verify.sh
./run-tests.sh plan all
```

Run `./run-tests.sh all` when release confidence matters or when shared auth,
routing, generated units, storage, or multiple apps changed.

## Deploy Patterns

Use a full deploy when component selection, systemd graph, Docker network
metadata, Docker volume metadata, shared auth, routing, or storage shape
changed:

```bash
cd ~/webservices
./deploy.sh
./verify.sh
```

Use a scoped deploy for small app or config changes:

```bash
cd ~/webservices
./deploy.sh --plan-only --component <name>
./deploy.sh --component <name>
./verify.sh --ready-only
./run-tests.sh changed
```

Other scoped forms:

```bash
./deploy.sh --service <compose-service>
./deploy.sh --unit <systemd-domain-or-unit>
./deploy.sh --component <name> --include-component-dependencies
```

If a scoped deploy is refused by the deploy-state guard, run a full deploy.
That refusal means global inputs changed and must reconcile together.

## Logs And Diagnostics

Start with the user unit:

```bash
systemctl --user status <unit-name> --no-pager -l
journalctl --user -u <unit-name> -n 160 --no-pager
```

Then inspect the container:

```bash
docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Image}}' | sort
docker logs --since 10m <container-name>
```

For route issues, check from outside the container network first:

```bash
curl -Ik https://<service>.<domain>
```

Then inspect Caddy, Keycloak, the auth gateway, and the service container.

## Runtime State

The deploy directory is:

```text
~/webservices
```

The host-rendered runtime directory is:

```text
~/webservices/runtime
```

That path points at tmpfs-backed user runtime storage. It contains rendered
env/config material and deploy-state files. Treat it as runtime state, not
source. Do not copy it back into this repo.

If runtime material is stale or missing, re-run deploy:

```bash
cd ~/webservices
./deploy.sh
./verify.sh
```

## Secrets

Secrets stay encrypted until host deploy. If deploy cannot decrypt, check the
target host's SOPS identity and the manifest's secret store path. Do not render
or commit plaintext secrets locally to work around a deploy failure.

Prefer file or stdin secret passing for manual repair commands. Avoid putting
secret values in shell history, process arguments, diagnostics bundles, or docs.

## Data Protection

Every stateful service needs an explicit backup decision:

- disposable state
- Docker named volume
- host bind mount
- database data
- external site storage
- app-level export

Kopia provides backup infrastructure, but a backup is not proven until a restore
has been rehearsed. Raw database-directory snapshots can be a last-resort file
safety net; prefer app-aware dumps or restoring while the relevant writers are
stopped.

For restore procedures, use [Recovery](recovery.md).

## Purge Tools

Destructive host-admin tools live outside the bundled deploy UX:

```text
ops/host-admin/
```

Preview before deleting:

```bash
EXPECTED_HOSTNAME=<host> ./ops/host-admin/purge-webservices-stack.sh --print-only
```

Use purge scripts only when intentionally resetting or repairing a host. Normal
deploys should not delete persistent storage.

## Change Records

For operational changes, keep enough notes to answer:

- what changed
- which manifest and generator commit were deployed
- whether it was full or scoped
- which verification command passed
- what was restored, purged, or manually repaired

The site repo owns generator pins and rollback history. Update that pin after a
trusted generator change has been built, deployed, and verified.
