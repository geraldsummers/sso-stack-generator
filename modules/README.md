# Module Inventory

This directory is the generator-owned inventory for active source and stack-module
repositories.

- `catalog.json` is the master repository list.
- `groups/*.json` define generic pull groups for local development.
- `scripts/pull-modules.sh` clones or fast-forwards repositories from these
  groups into a local workspace.

The catalog is not a site lock. Site-specific module selection and exact commit
pins belong in the site configuration repository.

The `destruction` group is local-only inventory for retired extraction remnants.
The pull helper reports those entries as skipped and does not clone them.
