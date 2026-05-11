# Testing

The repo has several test layers. Use the smallest layer that proves the change while iterating, then run broader checks before trusting a deployment.

## Local Build Tests

The normal build command runs source-local checks:

```bash
./build.sh --manifest /path/to/site/manifest.json
```

Use this before syncing to the host.

## Source-Local Test Runner

From the source checkout:

```bash
./stack.containers/test-runner/run-tests.sh ts-unit
./stack.containers/test-runner/run-tests.sh kt stack-contract
./stack.containers/test-runner/run-tests.sh source-unit
```

These are useful for fast feedback on code and config changes.

## Deployed Verification

After deploying on the target host:

```bash
cd ~/webservices
./verify.sh
```

`verify.sh` is the default blocking deployment check. It verifies readiness and runs the platform contract.

## Full Test Runner

From the deployed bundle on the host:

```bash
cd ~/webservices
./run-tests.sh list
./run-tests.sh plan all
./run-tests.sh all
```

`all` means every registered check exposed by the bundled runner. It is intentionally broad and can be slow.

## Useful Targets

Kotlin:

```bash
./run-tests.sh kt-list
./run-tests.sh kt-tests stack-contract
./run-tests.sh kt-plan stack-contract
./run-tests.sh kt-one <test-id> stack-contract
./run-tests.sh kt-core
./run-tests.sh kt-auth
./run-tests.sh kt-apps
./run-tests.sh kt-contract
./run-tests.sh kt-live-ingestion
./run-tests.sh kt-full
```

Runner help names these granular forms as `kt-tests [suite]`, `kt-plan [suite]`, and `kt-one <id> [suite]`.

TypeScript and Playwright:

```bash
./run-tests.sh ts-unit
./run-tests.sh ts-unit-one <path>
./run-tests.sh ts-unit-name <name>
./run-tests.sh ts-boundary
./run-tests.sh ts-app-smoke
./run-tests.sh ts-sso
./run-tests.sh ts-e2e-deep
./run-tests.sh ts-e2e-visual
./run-tests.sh ts-e2e-one <path>
./run-tests.sh ts-e2e-name <name>
./run-tests.sh ts-e2e-all
```

Meta commands:

```bash
./run-tests.sh list
./run-tests.sh plan <target>
./run-tests.sh changed
./run-tests.sh slowest 20
./run-tests.sh failed
```

Runner help names the planning form as `plan [target]`.

## Browser Test Requirements

Playwright and deep auth suites need a deployed runtime. They depend on:

- rendered `runtime/stack.env`
- real service domains
- Caddy routing
- Keycloak login
- cookies and redirects
- running app containers

If a browser test fails before it reaches the app, check Caddy and Keycloak first.

## Test Artifacts

On the target host, test output is written under:

```text
~/webservices-test-results
```

Common locations:

```text
~/webservices-test-results/all-summary.txt
~/webservices-test-results/playwright/report/index.html
~/webservices-test-results/playwright/test-results
~/webservices-test-results/playwright/screenshots
```

Use screenshots and traces when debugging UI failures. They usually explain routing, login, theme, or layout problems faster than logs alone.

## Choosing The Right Test

For a config template change:

- run `./build.sh`
- run `./verify.sh` after deploy

For an auth or routing change:

- run `./verify.sh`
- run targeted Playwright SSO or route tests

For a visual or UI change:

- run the relevant service browser test
- run `./run-tests.sh ts-e2e-visual`
- inspect screenshots

For release confidence:

- run `./run-tests.sh plan all`
- run `./run-tests.sh all`
