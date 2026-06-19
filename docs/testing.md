# Testing

The generator uses layered tests so failures point to the smallest responsible
surface.

## Repository Hygiene

The CI hygiene job checks required publication files, private path leaks, shell
syntax, ShellCheck, deploy-state guards, environment-file security, and
documentation links.

```bash
git ls-files 'scripts/**/*.sh' 'ops/**/*.sh' 'stack.config/**/*.sh' | xargs -r -n1 bash -n
git ls-files 'scripts/**/*.sh' 'ops/**/*.sh' 'stack.config/**/*.sh' | xargs -r shellcheck -S error -s bash
./scripts/test-deploy-state.sh
./scripts/test-env-file-security.sh
./scripts/test-docs.sh
```

## Module-Owned TypeScript Tests

The Playwright test-runner package is provided by the `test-runners` module and
is available after module materialization or inside a built bundle. From a public
generator-only checkout, run module tests in the module repository instead.

```bash
cd modules-workspace/test-runners-stack-module/stack.containers/test-runner/playwright-tests
npm ci
npm run build
npm run test:unit
```

## Kotlin Tests

Kotlin service modules are built and tested in their source or stack-module
repositories, then materialized into the bundle.

```bash
./gradlew test shadowJar --no-daemon
```

## Artifact Tests

The release artifact path validates generated contracts, package assembly, and
selected component output after the configured modules have been materialized.

```bash
./scripts/test-component-selection.sh
./scripts/build-artifact.sh
```

## Deployed Stack Tests

After deployment, the generated `verify.sh` script runs readiness checks and the
blocking stack-contract suite. Full end-to-end and visual suites should be run
against representative deployed environments before release decisions that
touch authentication, routing, screenshots, or service startup behavior.
