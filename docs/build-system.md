# Build System

The build is split into two stages.

1. `./build.sh --manifest <path-to-manifest.json>`
   - Resolves any pinned external module manifest from the site's `.webservices-generator.json`.
   - Runs TypeScript compile + unit tests, Gradle tests + shadow jars, and Bazel packaging.
   - Writes a deployable secret-free bundle to `dist/`.
   - Bundles the manifest-selected encrypted site files under `dist/build/site/`.
   - Resolves `manifest.json` `components` against the base component catalog plus module-provided catalogs.
   - Generates Compose and systemd output only for the selected components.
   - Does not call `sops`.
   - Does not render runtime env or secret-bearing config files.

2. `./deploy.sh` on the host from `~/webservices/`
   - Reads the bundled site manifest and encrypted secret store.
   - Decrypts with `sops` on the host only.
   - Renders runtime material under `~/webservices/runtime/`.
   - Validates `build/docker-compose.yml` with `--env-file ~/webservices/runtime/stack.env`.
   - Starts Compose in place from `~/webservices/build/`.

## Templating Contract

- `{{VAR}}`
  - Deploy-time substitution.
  - Used for site-bound or secret-bound config templates under `stack.config/`.

- `${VAR}`
  - Runtime or Compose interpolation.
  - Used in `stack.compose/`, `global.settings/`, and runtime-owned config files that are filled by container entrypoints.

## Bundle Layout

`dist/` contains:
- `deploy.sh`
- `verify.sh`
- `run-tests.sh`
- `build/`

`dist/build/` contains:
- `docker-compose.yml`
- `build-info.json`
- `artifact.tar`
- `artifact.sha256`
- `site/`
- `scripts/`
- `global.settings/`
- `stack.compose/`
- `stack.config/`
- `stack.containers/`
- `stack.kotlin/`

`dist/build/site/components.lock.json` records the resolved component set used
for that build. Deploy uses that lock to render component-aware runtime config,
including Caddy routes and Keycloak clients.

## External Modules

Sites can pin a private module-manifest repo from `.webservices-generator.json`:

```json
{
  "moduleManifestRemote": "git@github.com:owner/module-manifest.git",
  "moduleManifestRef": "main",
  "moduleManifestCommit": "0123456789abcdef",
  "moduleManifestPath": "modules.json"
}
```

The module manifest is not stored in this public repo. It contains pinned module
repos:

```json
{
  "modules": [
    {
      "name": "portable-service",
      "git": "git@github.com:owner/portable-service.git",
      "ref": "main",
      "commit": "0123456789abcdef",
      "path": "."
    }
  ]
}
```

Module repos may contribute `stack.compose/`, `stack.config/`,
`stack.containers/`, `stack.kotlin/`, `stack.js/`, `scripts/modules/`, and
`docs/modules/`. A module `stack.config/components.json` is merged into the base
catalog. Any module file that replaces a base generator file must be listed in
that module's `overrides` array.

## Runtime Layout

`~/webservices/runtime` should resolve to a tmpfs-backed user runtime path such as:
- `/run/user/<uid>/webservices-runtime`

It contains:
- `stack.env`
- `configs/`
- `build-info.json`

These files are rendered just in time by `deploy.sh` and should not be committed or treated as source.

## Entry Points

- `./build.sh --manifest <path-to-manifest.json>`
  - Builds the secret-free bundle locally.
  - Sync the resulting `dist/` with `rsync -av --no-group --delete` into a user-writable `~/webservices/` on the host.

- `./deploy.sh`
  - Runs on the host from the deployed `~/webservices/` directory.
  - Renders runtime state, installs the pre-rendered `systemd --user` units,
    refreshes Docker network/volume metadata, and asks systemd to reconcile
    `webservices.target` or the selected scoped units.

- `./verify.sh`
  - Runs readiness, then blocking verification.

- `./run-tests.sh`
  - Dispatches bundled test-runner container commands.
  - Supports grouped targets such as `kt-core`, `kt-auth`, `kt-apps`, `kt-contract`, `kt-full`, `ts-unit`, `ts-boundary`, `ts-app-smoke`, `ts-sso`, and `ts-e2e-all`.
  - Supports granular iteration with `list`, `plan [target]`, `changed`, `source-unit`, `kt-tests [suite]`, `kt-plan [suite]`, `kt-one <id> [suite]`, `ts-unit-one`, `ts-unit-name`, `ts-e2e-one`, and `ts-e2e-name`.
  - `all` is the exhaustive target. It expands to every registered check and includes source-local unit work when the command is run from a source checkout.

- `build/scripts/deploy/render-runtime.sh`
  - Internal render helper used by `deploy.sh`.

- `build/scripts/lib/wait-ready.sh`
  - Internal readiness helper used by `verify.sh`.
