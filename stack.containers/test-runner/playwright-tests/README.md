# webservices Playwright tests

Playwright is split into explicit tiers:
- `route-contract`: fast anonymous boundary checks, no secret access required
- `app-smoke`: fast per-service authenticated checks with an isolated managed Keycloak user per service
- `sso`: shared-user cross-service session checks, requires deployed runtime env
- `deep`: slower service-specific flows, requires deployed runtime env
- `visual`: screenshot validation, separate from CI, requires deployed runtime env

## Preferred execution

Build the bundle locally, copy it to the host, deploy it, then run the bundled test runner:

```sh
./build.sh --manifest /path/to/manifest.json
rsync -av --delete ./dist/ <user@host>:~/webservices/
ssh <user@host> 'cd ~/webservices && ./deploy.sh && ./run-tests.sh ts-e2e'
```

Other bundled entrypoints:

```sh
./run-tests.sh ts-boundary
./run-tests.sh ts-app-smoke
./run-tests.sh ts-sso
./run-tests.sh ts-e2e-route
./run-tests.sh ts-e2e-deep
./run-tests.sh ts-e2e-visual
```

## Direct npm usage

Direct npm usage is supported for local debugging, but authenticated suites must have a deployed runtime env available.
The npm scripts will automatically source one of:
- `STACK_RUNTIME_ENV_FILE`
- `runtime/stack.env`

If you want to source the env yourself:

```sh
set -a
. /path/to/runtime/stack.env
set +a
cd stack.containers/test-runner/playwright-tests
npm run test:e2e:deep
```

## Suites

```sh
npm run test:unit
npm run test:e2e
npm run test:e2e:boundary
npm run test:e2e:app-smoke
npm run test:e2e:sso
npm run test:e2e:deep
npm run test:e2e:deep:forward-auth
npm run test:e2e:deep:oidc
npm run test:e2e:visual
npm run test:e2e:one -- tests/deep/forward-auth/homepage.spec.ts
```

## Runtime model

- Global setup provisions one managed Keycloak user and stores auth artifacts in `.auth/`
- `app-smoke` bypasses global setup and creates one managed Keycloak user per protected service test
- `sso`, `deep`, and `visual` reuse the saved Keycloak session on the shared managed user
- Global teardown removes the managed user and cleans `.auth/`
- `route-contract` bypasses global setup because it validates anonymous boundaries only

## Notes

- `route-contract` is the default fast suite for anonymous route coverage.
- `app-smoke` is the default fast authenticated honesty suite.
- `sso` proves cross-service session reuse explicitly instead of bundling it into generic smoke.
- `deep` and `visual` remain explicit suites in the full browser matrix.
- The legacy `playwright-tests/docker-compose.yml` is only a convenience file; the authoritative test-runner wiring is the bundled `run-tests.sh` plus `build/stack.compose/test-runners.yml`.
