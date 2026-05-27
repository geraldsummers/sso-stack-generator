# Update And Rollback

## What Is Pinned

The site repo pins the generator version and stores site-specific deployment
intent. The generator source owns templates, service definitions, docs, and
tests. Service images may also have explicit tags in Compose shards.

## What Can Change

Updates can involve:

- generator source
- site repo config
- enabled components
- service image tags
- runtime templates
- service-specific migrations
- docs and tests

## Generator Updates

Update the generator through the site repo's update flow, normally using
`stack-update.sh`. Review the generator diff before deploying when auth,
routing, storage, systemd graph, or secret rendering changed.

## Service Image Updates

Service image updates should be treated as application changes. Check release
notes when available, watch for migrations, and run verification after deploy.

## Site Repo Updates

Site repo changes should be reviewed as deployment intent. They may alter
components, domains, secrets, backup paths, or client-specific policy.

## Pre-Update Checklist

- [ ] Current deployment is healthy
- [ ] Current generator pin recorded
- [ ] Site repo is clean
- [ ] Backup snapshot available for stateful services
- [ ] Migration notes reviewed for changed apps
- [ ] Maintenance window agreed when needed

## Update Procedure

```bash
cd /path/to/site-repo
./stack-update.sh

cd /path/to/sso-stack-generator
./build.sh --manifest /path/to/site/manifest.json
rsync -av --no-group --delete ./dist/ <user@host>:~/webservices/
ssh <user@host> 'cd ~/webservices && ./deploy.sh && ./verify.sh'
```

Do not edit `dist/` as source. If generated output is wrong, fix the generator
source or site inputs and rebuild.

## Post-Update Verification

After update:

```bash
cd ~/webservices
./verify.sh
./run-tests.sh changed
```

Run broader tests when shared auth, routing, generated units, storage, or
multiple apps changed.

## Rollback Procedure

1. Choose the previous trusted generator commit or site-config commit.
2. Rebuild with the explicit site manifest.
3. Sync `dist/` to the host.
4. Run a full deploy.
5. Run `./verify.sh`.

Rollback is easiest before stateful migrations. Stateful services require
service-specific restore or migration procedures.

## Breaking Change Policy

Breaking changes should be documented with the affected components, required
site changes, migration steps, verification command, and rollback caveats.

## Emergency Recovery

If an update breaks the stack, preserve diagnostics, restore the previous
trusted source/site state, and follow [Recovery](recovery.md). If data migrated,
do not assume source rollback is enough; use the restore drill for affected
stateful services.
