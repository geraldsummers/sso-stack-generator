# Minimal Build + Deploy System

## Public Surface

Local:
- `./build.sh --manifest /path/to/site/manifest.json`
- `ssh <user@host> 'mkdir -p ~/webservices && test -w ~/webservices'`
- `rsync -av --no-group --delete ./dist/ <user@host>:~/webservices/`

Host:
- `cd ~/webservices && ./deploy.sh`
- `cd ~/webservices && ./verify.sh`
- `cd ~/webservices && ./run-tests.sh all`

## Invariants

- `~/webservices` is the deployed bundle root.
- `~/webservices` must be writable by the target user.
- `~/webservices/build` is the secret-free bundle payload.
- `~/webservices/runtime` points at tmpfs-backed user runtime storage.
- deploy runs in place and does not swap release directories.
- secrets are decrypted only on the host.
- the bundle is secret-free at build time.
- the site bundle is selected explicitly by manifest path.
- the repo does not discover site config through a symlink.

## Site Bundle

Minimal site bundle contents:
- `manifest.json`
- `stack.config.yaml`
- `webservices.sops.json`

Minimal manifest schema:

```json
{
  "site": "example",
  "stackConfig": "./global.settings/stack.config.yaml",
  "secretStore": "./global.settings/web-services.sops.json"
}
```

## Flow

1. `build.sh`
   - runs TypeScript checks, Jest unit tests, Gradle tests, shadow jars, and Bazel packaging
   - writes `dist/`
   - bundles the encrypted site files under `dist/build/site/`
   - requires `--manifest`
   - never decrypts secrets

2. manual sync
   - copies `dist/` to `~/webservices/`
   - transport is operator-owned, for example `rsync -av --no-group --delete`

3. `deploy.sh`
   - runs from `~/webservices/`
   - ensures `~/webservices/runtime` points at `/run/user/$UID/webservices-runtime`
   - decrypts with `sops`
   - renders runtime env and config files into the runtime dir only
   - runs `docker compose up -d --build --force-recreate --remove-orphans` from `~/webservices/build/`

4. `verify.sh`
   - runs readiness
   - runs `kt stack-contract` by default

5. `run-tests.sh`
   - runs optional grouped and granular validation after deploy
   - exposes `list`, `plan`, `changed`, granular Kotlin IDs, Playwright selectors, and exhaustive `all`

## Host Reset

Destructive host cleanup is intentionally outside the bundled deploy UX. Operator-owned purge entrypoints live under:

```bash
ops/host-admin/
```

`purge-webservices-stack.sh` removes user systemd units, webservices compose resources, runtime material, configured storage targets, and labware workspace containers/volumes by stack labels. Use `--print-only` first when validating a purge plan. Use `--skip-labware-runtime` only when workspace/labware cleanup must be deferred.

## Why This Is Simpler

The build step only packages.
The deploy step only decrypts, renders, and starts.
The host has one deploy directory and one ephemeral runtime directory.
