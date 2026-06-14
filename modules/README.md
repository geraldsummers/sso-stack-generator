# Module Inventory

This directory is the generator-owned inventory for active source and stack-module
repositories.

- `catalog.json` is the master repository list.
- `groups/*.json` define generic pull groups for local development.
- `stack.module.schema.json` documents the metadata interface for a module repo.
- `scripts/pull-modules.sh` clones or fast-forwards repositories from these
  groups into a local workspace.
- `scripts/test-module.sh` and `scripts/test-module-group.sh` validate module
  metadata and run module-owned tests.

The catalog is not a site lock. Site-specific module selection and exact commit
pins belong in the site configuration repository.

The `destruction` group is local-only inventory for retired extraction remnants.
The pull helper reports those entries as skipped and does not clone them.
