# Safe Operations

## Token Handling

- Connector-issued tokens are one-time reveal only.
- Store only hashes in persistent databases.
- Revoke on account close.

## Deployment Practice

- Build locally with explicit manifest.
- Deploy from immutable `dist/` artifacts.
- Decrypt secrets only on target host runtime paths.
