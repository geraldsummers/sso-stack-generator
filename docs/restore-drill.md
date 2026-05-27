# Restore Drill

## Principle

Backups are not considered working until a restore has been tested.

## Restore Types

- single service restore
- database restore
- file/object restore
- full host rebuild
- migration to replacement host

## Prerequisites

- access to the target host or replacement host
- SOPS identity available on the target host
- backup repository credentials
- current site manifest and generator pin
- enough storage for temporary restore validation
- planned maintenance window for stateful services

## Drill Schedule

Run a restore drill after initial deployment, after major storage changes, and
on a recurring schedule agreed with the client. For small teams, quarterly or
semiannual drills are often a practical starting point.

## Single Service Restore Procedure

1. Identify the service unit, containers, databases, volumes, and host paths.
2. Stop writers with `systemctl --user stop <unit-name>`.
3. Restore to a temporary path first when possible.
4. Validate expected files, ownership, and timestamps.
5. Replace live data only after validation.
6. Start the unit or run `./deploy.sh --unit <unit-name>`.
7. Run `./verify.sh --ready-only` and targeted tests.

## Full Stack Restore Procedure

1. Prepare the host, Docker, `systemd --user`, DNS, and SOPS identity.
2. Check out the site repo and generator at the intended pinned versions.
3. Build with the explicit site manifest.
4. Restore persistent data from the selected backup point.
5. Sync `dist/` to `~/webservices/`.
6. Run `./deploy.sh`.
7. Run `./verify.sh`.

## Verification After Restore

After restore:

```bash
cd ~/webservices
systemctl --user status webservices.target --no-pager -l
systemctl --user --failed --no-pager
./verify.sh
./run-tests.sh changed
```

Also test the user-facing route, login flow, and a small data sanity check for
the restored service.

## RTO/RPO Notes

Restore time and acceptable data loss are client decisions. They depend on
backup frequency, data size, network speed, service migration behavior, and
whether replacement hardware is ready.

## Known Gaps

Single-host deployment is not high availability. Raw database-directory
restores require all writers to be stopped and should match dependent app files
from the same point in time. Mail deliverability and external DNS recovery may
require provider-specific steps.

## Restore Acceptance Checklist

- [ ] Backup snapshot selected
- [ ] Restore target prepared
- [ ] Secrets available on target host
- [ ] Service restored
- [ ] `./verify.sh` passes
- [ ] User-facing route tested
- [ ] Auth boundary tested
- [ ] Data sanity checked
- [ ] Restore notes committed to site repo
