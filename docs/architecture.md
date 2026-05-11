# Architecture

This repository is a stack generator. It does not run one app. It builds a whole platform from source templates plus a site manifest.

## Mental Model

The flow is:

```text
repo source + site manifest
        |
        v
local build
        |
        v
secret-free dist bundle
        |
        v
rsync to target host
        |
        v
host deploy renders runtime secrets
        |
        v
systemd user units start Docker Compose shards
```

The important split is local build versus host deploy.

- Local build reads source and site metadata.
- Host deploy decrypts secrets and starts services.
- Runtime files live under `~/webservices/runtime`.
- Generated deploy files live under `~/webservices/build`.

## Main Layers

### Site Bundle

The site bundle lives outside this repo. It tells the generator which site is being built, what domains exist, and where encrypted deployment inputs live.

An example manifest path looks like:

```text
/path/to/site/manifest.json
```

### Edge

Caddy is the public edge. It owns HTTPS, public routes, auth handoff, and service reverse proxies.

Most user-facing services appear as:

```text
https://<service>.<domain>
```

### Identity

Keycloak is the shared identity provider. Services either use native OIDC or sit behind the shared auth gateway at the edge.

Groups in Keycloak drive RBAC. Platform services should not invent unrelated local role systems unless the app requires local mapping.

### Services

Service definitions are spread across a few source areas:

- `stack.compose/` for Compose shards
- `stack.config/` for app config and entrypoints
- `stack.containers/` for custom images
- `stack.systemd/` for unit graph source
- `stack.kotlin/` and `stack.containers/test-runner/` for tests

The generated bundle turns those into Docker Compose files, systemd units, runtime env, and app config.

### Systemd And Compose

The platform uses `systemd --user` as the host supervisor. Each service or service group has generated user units. Docker Compose is the per-service container backend.

The Compose project name is intentionally stable:

```text
webservices
```

Do not rename it casually. Tests, labels, purge tooling, and operational commands rely on it.

The graph contract is documented in [systemd-graph.md](systemd-graph.md).

### Runtime Secrets

Secrets stay encrypted until host deploy. `./deploy.sh` renders decrypted runtime material under `~/webservices/runtime`.

That directory is runtime state, not source. Do not commit it and do not copy it back into this repo.

### Test Runner

The test runner is platform-owned. It can run:

- Kotlin contract checks
- TypeScript unit tests
- Playwright browser tests
- visual screenshots
- auth and workflow checks

Deep browser checks need the deployed stack because they depend on real domains, Caddy, Keycloak, cookies, and app containers.

## Generated Versus Source

Edit these as source:

- `stack.compose/`
- `stack.config/`
- `stack.containers/`
- `stack.systemd/`
- `stack.kotlin/`
- `scripts/`
- `docs/`

Do not edit these as source:

- `dist/`
- `out/`
- Gradle build outputs
- Bazel outputs
- rendered `runtime/stack.env`

If generated output is wrong, fix the source that generated it.
