# Troubleshooting

Start from the layer closest to the symptom, then work downward. When the fix
requires changing runtime state, follow [Recovery](recovery.md). For routine
checks and deploy patterns, use [Operations](operations.md).

The normal control surface is `systemd --user` plus the bundled scripts. Use
Docker commands for inspection, not as the first restart mechanism.

## First Snapshot

On the host:

```bash
cd ~/webservices
systemctl --user status webservices.target --no-pager -l
systemctl --user --failed --no-pager
systemctl --user list-units 'webservices-*' --all --no-pager
docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Image}}' | sort
./verify.sh --ready-only
```

If a unit is failed:

```bash
systemctl --user status <unit-name> --no-pager -l
journalctl --user -u <unit-name> -n 160 --no-pager
docker logs --since 10m <container-name>
```

## Build Fails

Check:

```bash
git status --short
./build.sh --manifest /path/to/site/manifest.json
```

Common causes:

- missing `--manifest`
- dirty worktree
- invalid generated config from a source template
- local unit test failure

Fix source, then rebuild. Do not patch `dist/`.

## Sync Fails

Check remote write access:

```bash
TARGET_HOST="user@example-host"
ssh "$TARGET_HOST" 'mkdir -p ~/webservices && test -w ~/webservices && pwd'
```

Sync with:

```bash
rsync -av --no-group --delete ./dist/ "$TARGET_HOST":~/webservices/
```

If files disappear on the host after sync, check that you used `./dist/` with
the trailing slash and that `~/webservices` is the intended target.

## Deploy Fails

On the host:

```bash
cd ~/webservices
./deploy.sh
systemctl --user status webservices.target --no-pager -l
systemctl --user --failed --no-pager
```

Common causes and recovery:

- SOPS cannot decrypt site secrets: fix host SOPS identity or manifest
  secret-store path, then rerun `./deploy.sh`.
- Docker is unavailable: fix Docker, then rerun `./deploy.sh`.
- a generated unit points at missing bundle files: rebuild, resync `dist/`, then
  run a full deploy.
- a volume or host path is missing: restore, mount, or create the expected path,
  then rerun `./deploy.sh`.
- a health gate fails: inspect the unit journal and service logs, fix the
  service cause, then rerun `./deploy.sh`.

After the deploy completes:

```bash
./verify.sh
```

## Scoped Deploy Fails Or Is Refused

Preview the scope:

```bash
cd ~/webservices
./deploy.sh --plan-only --component <name>
./deploy.sh --plan-only --service <compose-service>
./deploy.sh --plan-only --unit <systemd-unit-or-domain>
```

If the deploy-state guard refuses a scoped deploy, run a full deploy:

```bash
./deploy.sh
./verify.sh
```

That refusal means component selection, the systemd graph, Docker networks, or
Docker volumes changed and must reconcile globally.

## Verify Fails

On the host:

```bash
cd ~/webservices
./verify.sh
systemctl --user --failed --no-pager
docker ps --format '{{.Names}} {{.Status}}'
```

If readiness fails, inspect the failed unit first:

```bash
systemctl --user status <unit-name> --no-pager -l
journalctl --user -u <unit-name> -n 160 --no-pager
docker logs --since 10m <container-name>
```

If readiness passes but tests fail, use the runner to narrow the failing group:

```bash
./run-tests.sh failed
./run-tests.sh plan <target>
./run-tests.sh <target>
```

## A Web Route Fails

Check from outside the container network:

```bash
curl -Ik https://<service>.<domain>
```

Then check:

- DNS points at the target host
- `webservices-caddy.service` is active
- the service unit is active and healthy
- the route exists in generated Caddy config
- Keycloak and the auth gateway are reachable for auth-protected routes

Useful commands:

```bash
systemctl --user status webservices-caddy.service --no-pager -l
journalctl --user -u webservices-caddy.service -n 160 --no-pager
docker logs --since 10m caddy
docker logs --since 10m keycloak
docker logs --since 10m keycloak-auth-gateway
```

## Login Loops Or 403

Check:

- the user exists in Keycloak
- the user completed onboarding if onboarding is required
- the user has the required Keycloak groups
- the app's OIDC client redirect URI matches the public URL
- the service is not also presenting a conflicting local login flow

For non-onboarded users, the preferred behavior is redirecting to:

```text
https://onboarding.<domain>/start
```

If many apps fail at once, redeploy `core` or run a full deploy. Do not tune a
single app before checking shared identity and edge auth.

## Browser Or Visual Tests Fail

Find artifacts under:

```text
~/webservices-test-results/playwright
```

Useful files:

```text
report/index.html
test-results
screenshots
```

Open screenshots first. A screenshot usually shows whether the problem is auth,
routing, layout, theme, or app readiness.

## Media Playback Fails

For Jellyfin, separate server-side transcoding from browser playback:

- check the item plays through the external route
- check Jellyfin playback info for direct play versus transcoding
- check container logs during playback
- test more than one browser or client before changing server policy
- avoid custom UI shims unless a browser test proves the app itself is hiding
  video

## Storage Or Data Looks Wrong

Before restarting or purging, identify whether the service uses a Docker volume,
host bind mount, database, or app-level storage.

Check:

```bash
docker inspect <container-name> --format '{{json .Mounts}}'
df -h
docker system df -v
```

For restore procedures and split-state services, use [Recovery](recovery.md).

## Purge Before A Fresh Deploy

Purge is destructive host-admin work. It belongs outside normal deploy.

Preview first:

```bash
EXPECTED_HOSTNAME=<host> ops/host-admin/purge-webservices-stack.sh --print-only
```

Only run a real purge when you are comfortable deleting the stack runtime
resources it lists. Use [Recovery](recovery.md) for the purge and rebuild flow.
