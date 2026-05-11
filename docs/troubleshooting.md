# Troubleshooting

Start from the layer closest to the symptom, then work downward.

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

Fix source, then rebuild.

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

If files disappear on the host after sync, check that you used `./dist/` with the trailing slash and that `~/webservices` is the intended target.

## Deploy Fails

On the host:

```bash
cd ~/webservices
./deploy.sh
systemctl --user status webservices.target --no-pager -l
systemctl --user list-units 'webservices-*' --all --no-pager
```

Common causes:

- SOPS cannot decrypt site secrets
- Docker is unavailable
- a generated unit points at missing bundle files
- a container cannot start because a volume or host path is missing

## Verify Fails

On the host:

```bash
cd ~/webservices
./verify.sh
docker ps --format '{{.Names}} {{.Status}}'
```

If a single service is unhealthy:

```bash
docker logs --since 10m <container-name>
systemctl --user status <unit-name> --no-pager -l
```

## A Web Route Fails

Check from outside the container network:

```bash
curl -Ik https://<service>.<domain>
```

Then check:

- Caddy is running
- DNS points at the target host
- the service container is healthy
- the service route exists in generated Caddy config
- Keycloak is reachable for auth-protected routes

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

Open screenshots first. A screenshot usually shows whether the problem is auth, routing, layout, theme, or app readiness.

## Media Playback Fails

For Jellyfin, separate server-side transcoding from browser playback:

- check the item plays through the external route
- check Jellyfin playback info for direct play versus transcoding
- check container logs during playback
- test more than one browser or client before changing server policy
- avoid custom UI shims unless a browser test proves the app itself is hiding video

## Purge Before A Fresh Deploy

Purge is destructive host-admin work. It belongs outside normal deploy.

Preview first:

```bash
ops/host-admin/<purge-script>.sh --print-only
```

Only run a real purge when you are comfortable deleting the stack runtime resources it lists.
