# Quickstart

This guide gets a new operator from a source checkout to a verified deployment.

The examples use placeholder values:

- site: `example`
- host: `user@example-host`
- manifest: `/path/to/site/manifest.json`
- remote deploy directory: `~/webservices`

For local private development, replace those with the site and host you actually operate.

## What You Need First

On the machine where this repository is checked out:

- a clean git worktree
- access to the site manifest
- the normal local build toolchain used by this repo

On the target host:

- Docker
- `systemd --user`
- SOPS access for the encrypted site secrets
- DNS or local name resolution for the configured service domains
- write access to `~/webservices`

The local build does not decrypt secrets. Secret rendering happens on the target host during `./deploy.sh`.

## 1. Check The Worktree

The build expects source to be in a known state.

```bash
cd web-services
git status --short
```

If this prints changed files, decide whether those changes are intended before building.

## 2. Build The Bundle

```bash
SITE_MANIFEST="/path/to/site/manifest.json"
./build.sh --manifest "$SITE_MANIFEST"
```

This creates `dist/`, a deployable bundle.

The bundle is intended to be immutable and secret-free. Do not edit files inside `dist/` to fix behavior. Fix the source files and rebuild.

## 3. Copy The Bundle To The Host

```bash
TARGET_HOST="user@example-host"

ssh "$TARGET_HOST" 'mkdir -p ~/webservices && test -w ~/webservices'
rsync -av --no-group --delete ./dist/ "$TARGET_HOST":~/webservices/
```

The trailing slash on `./dist/` matters. It copies the contents of `dist` into `~/webservices`.

## 4. Deploy On The Host

```bash
ssh "$TARGET_HOST" 'cd ~/webservices && ./deploy.sh'
```

Deploy renders runtime files on the host, installs generated `systemd --user` units, and starts the stack.

## 5. Verify The Deployment

```bash
ssh "$TARGET_HOST" 'cd ~/webservices && ./verify.sh'
```

`verify.sh` runs readiness checks and the blocking platform contract. Treat a failing verify as a deployment failure until investigated.

## 6. Run Optional Tests

After a deploy is up, use the bundled test runner from the host:

```bash
ssh "$TARGET_HOST" 'cd ~/webservices && ./run-tests.sh list'
ssh "$TARGET_HOST" 'cd ~/webservices && ./run-tests.sh plan all'
ssh "$TARGET_HOST" 'cd ~/webservices && ./run-tests.sh all'
```

Use `all` for full confidence. Use narrower targets while iterating because browser and deep workflow suites can take a long time.

## Common First-Run Mistakes

Build fails because the manifest path is missing:

- pass `--manifest` explicitly
- do not rely on repo-local discovery

Deploy fails because SOPS cannot decrypt:

- check that the target host has the expected SOPS key access
- remember that secrets are intentionally not rendered locally

Browser tests fail before login:

- check DNS for the service domains
- check Caddy and Keycloak health first
- run `./verify.sh` before deep Playwright suites

Something looks wrong in `dist/`:

- do not patch `dist/`
- find the source template or generator that produced the file
- rebuild and redeploy

## Useful Remote Inspection Commands

Run these on the target host:

```bash
cd ~/webservices
systemctl --user status webservices.target --no-pager -l
systemctl --user list-units 'webservices-*' --all --no-pager
docker ps --format '{{.Names}} {{.Status}}'
./verify.sh
```
