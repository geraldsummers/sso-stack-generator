# Systemd Graph

`stack.systemd/graph.json` describes how generated `systemd --user` units are grouped from the merged Compose stack.

The renderer is:

```text
scripts/deploy/render-systemd-user.py
```

The rendered output is bundled under:

```text
build/systemd-user/
```

## Mental Model

The stack is supervised by systemd user units. Docker Compose remains the container backend, but systemd owns start order, target grouping, readiness, and operator-facing service state.

The graph has two concepts:

- targets: groups that an operator can start, stop, or enable
- lifecycle domains: Compose shards started and waited on as one unit

Services not assigned to an explicit lifecycle domain are rendered as single-service domains unless they are excluded or on-demand.

## Top-Level Fields

`unitPrefix`

The prefix used for generated units. This repo expects `webservices`.

`defaultTarget`

The installable target for the full platform stack. It normally wants core, observability, inference, pipeline, and app targets.

`auxiliaryTargets`

Named target groups used by the default target or by operators who need a narrower start surface.

`lifecycleDomains`

Explicit Compose shards. Use these when several services must start, stop, and become healthy together.

`excludedServices`

Compose services that should not get generated runtime units.

`onDemandServices`

Services kept out of the default target. They can still be rendered for explicit use.

`onDemandDomains`

Lifecycle domains kept out of the default target.

## Target Fields

`name`

Generated systemd target name. Must end in `.target`.

`description`

Human-readable systemd description.

`install`

Whether the target is enabled as part of the deploy install step.

`includeUnitsFromNonOnDemandDomains`

Whether the target should want all non-on-demand generated service units in addition to explicitly selected services/domains.

`wantsTargets`, `afterTargets`, `conflicts`, `partOfTargets`

Raw systemd target relationships copied into generated units after validation.

`services`

Compose service names included in the target.

`domains`

Lifecycle domain names included in the target.

## Lifecycle Domain Fields

`name`

Stable generated domain name. It becomes part of generated unit and Compose shard filenames.

`services`

Compose service names in that lifecycle domain.

## When To Add A Lifecycle Domain

Add one when:

- several services are one app runtime
- a health/wait boundary should apply to a group
- an app has worker roles that should share start/stop behavior
- systemd status is clearer as one domain than many independent units

Avoid adding one when a single Compose service already has a clear lifecycle.

## Safety Rules

- Every service can belong to only one lifecycle domain.
- Excluded services must not be referenced by lifecycle domains.
- Names are validated before generating files.
- Generated Compose shards strip build-only fields.
- Fix the graph source, not generated files under `dist/`.

## Scoped Deploys

`deploy.sh` supports scoped deploys for small application changes:

```text
./deploy.sh --component <name>
./deploy.sh --service <compose-service>
./deploy.sh --unit <systemd-domain-or-unit>
./deploy.sh --plan-only --component <name>
./deploy.sh --component <name> --include-component-dependencies
```

A scoped deploy still renders runtime files, installs the pre-rendered unit set,
reloads the user systemd manager, and validates the selected units. It then
reloads, restarts, or starts only the selected lifecycle units and their healthy
gates instead of reconciling `webservices.target`.

Unit scope also resolves the selected lifecycle unit or target back to its
Compose services before the build step. For example, `--unit autobattler`
builds the `autobattler` image before reloading
`webservices-autobattler.service`, a multi-service lifecycle domain builds every
service assigned to that domain, and `--unit webservices-apps.target` expands
through the target graph before building and reconciling the selected app
lifecycle units.
Scoped unit names are restricted to systemd service/target references and are
rejected before any filesystem lookup or `systemctl` invocation.

Component scope is direct by default: `--component homepage` targets the Compose
files owned by `homepage`, not `core` and every runtime dependency. Use
`--include-component-dependencies` when the dependency components themselves are
part of the intended operational change.

Scoped deploys are intentionally refused when global deployment inputs changed
since the last completed deploy:

- selected component lock
- `stack.systemd/graph.json`
- rendered Docker network metadata
- rendered Docker volume metadata

Those inputs affect the control plane rather than one app process. Run a full
deploy when they change so component selection, target membership, auth/routing
shape, Docker networks, and Docker volumes reconcile together.
