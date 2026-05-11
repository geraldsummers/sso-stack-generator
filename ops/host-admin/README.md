# Host Admin Tools

This directory contains destructive operator tools that are intentionally kept out of the bundled deploy UX.

Normal operators should use:

```bash
cd ~/webservices
./deploy.sh
./verify.sh
```

Use these host-admin scripts only when intentionally resetting or repairing a host.

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

