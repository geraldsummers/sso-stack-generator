# Host Sizing

These profiles are starting points for single-host deployments. Final sizing
depends on enabled components, user behavior, data volume, retention, and
support expectations.

| Profile | Users | Components | CPU | RAM | Disk | Notes |
| --- | ---: | --- | ---: | ---: | ---: | --- |
| Tiny | 1-5 | Secure Core | 2-4 vCPU | 4-8 GB | 100-250 GB SSD | Keep retention modest |
| Small Team | 5-25 | Ops Platform | 4-8 vCPU | 16-32 GB | 250 GB-1 TB SSD | Files/mail/logs drive storage |
| AI/Data Lab | variable | AI Sovereign Lab | 8+ vCPU | 32+ GB | 1 TB+ NVMe | Workloads vary; GPU optional |

## Caveats

- media libraries dominate disk
- monitoring retention affects disk
- search/vector workloads affect RAM
- mail adds deliverability complexity
- backups require separate storage
- single-host deployment is not high availability
- heavy AI inference is a separate scope

## Backup Storage

Backup storage should be separate from the primary host when the data matters.
A local backup repository can help with quick restores, but it does not protect
against full host loss.

## Growth Planning

Start smaller when requirements are clear and data is limited. Size up when the
team needs mail, large file storage, media, long log retention, search/vector
workloads, or workspace-heavy usage.
