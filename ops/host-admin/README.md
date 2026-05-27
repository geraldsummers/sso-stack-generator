# Host Admin Tools

This directory contains destructive operator tools that are intentionally kept out of the bundled deploy UX.

Normal operators should use:

```bash
cd ~/webservices
./deploy.sh
./verify.sh
```

Use these host-admin scripts only when intentionally resetting or repairing a host.

## When To Use These

Use normal deploy recovery first:

```bash
cd ~/webservices
./deploy.sh
./verify.sh
```

Use host-admin purge only when one of these is true:

- the host should be reset to a fresh stack state
- generated user units or Docker resources are badly stale
- labware/workspace runtime resources need destructive cleanup
- storage is being intentionally deleted or restored from backup

Do not use purge as the first response to a failed health check. Inspect the
failed unit and preserve data first.

## Scripts

`purge-webservices-stack.sh`

Stops systemd user units, removes webservices Docker resources, removes runtime material, optionally purges labware workspace/test resources, and optionally removes configured storage paths.

Preview first:

```bash
EXPECTED_HOSTNAME=<host> ./ops/host-admin/purge-webservices-stack.sh --print-only
```

Destructive execution requires both a matching `EXPECTED_HOSTNAME` and the explicit confirmation flag:

```bash
EXPECTED_HOSTNAME=<host> ./ops/host-admin/purge-webservices-stack.sh --yes-delete-webservices-stack
```

After a stack purge, rebuild, sync, deploy, and verify from the generator
checkout:

```bash
./build.sh --manifest /path/to/site/manifest.json
rsync -av --no-group --delete ./dist/ <user@host>:~/webservices/
ssh <user@host> 'cd ~/webservices && ./deploy.sh && ./verify.sh'
```

`purge-site-storage-dirs.sh`

Deletes the site-specific storage directories listed in the script. It does not parse the manifest at runtime because the point is to make destructive targets obvious before execution.

Preview first:

```bash
EXPECTED_HOSTNAME=<host> ./ops/host-admin/purge-site-storage-dirs.sh --print-only
```

## Adapting Storage Purge

Before using `purge-site-storage-dirs.sh` on a new site:

1. Edit `TARGET_DIRS`.
2. Update `validate_target_dir` to exactly match the same paths.
3. Run `--print-only`.
4. Review the output before using `--yes-delete-site-storage`.

Do not add broad paths such as `/mnt`, `/var`, or a user home directory.
