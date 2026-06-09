# Service Maturity

Ratings are conservative. Values are limited to Stable, Good, Partial,
Experimental, Unknown, and Not applicable.

| Service | Auth maturity | Backup maturity | Test maturity | Native/mobile caveats | Recommended package | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| ChatGPT Connector | Partial | Partial | Partial | Not applicable | AI Sovereign Lab | Connector/token workflows need careful client scoping. |
| JupyterHub | Good | Partial | Partial | Not applicable | AI Sovereign Lab | Useful for technical teams; storage expectations should be scoped. |
| Forgejo | Good | Partial | Partial | Not applicable | Ops Platform | Git hosting is operationally sensitive; backups matter. |
| Workspaces | Partial | Experimental | Partial | Not applicable | AI Sovereign Lab | Disposable workspace runtime has more moving parts. |
| Search | Partial | Partial | Partial | Not applicable | AI Sovereign Lab | OpenSearch and Qdrant quality depends on ingestion health and corpus quality. |
| BookStack | Good | Partial | Good | Not applicable | Secure Core | Strong docs fit; restore should include app data and database. |
| Progression | Good | Partial | Partial | Not applicable | Secure Core | Evidence-backed apprenticeship layer; MVP focuses on BookStack ownership. |
| SOGo | Partial | Partial | Partial | Good | Ops Platform | Mail/calendar clients add configuration and deliverability caveats. |
| Planka | Good | Partial | Partial | Not applicable | Ops Platform | Project data should be included in backup scope. |
| Element | Good | Partial | Partial | Partial | Ops Platform | Matrix/native client behavior needs planned identity handling. |
| Seafile | Partial | Partial | Partial | Good | Ops Platform | Split filesystem/database state requires careful restore. |
| Donetick | Good | Partial | Partial | Unknown | Ops Platform | Good candidate for small team routines. |
| Vaultwarden | Good | Partial | Partial | Good | Secure Core | Sensitive data; backup and admin ownership are critical. |
| Jellyfin | Partial | Partial | Partial | Good | Ops Platform | Media storage dominates disk; native clients may bypass browser SSO. |
| Mastodon | Partial | Partial | Partial | Good | Ops Platform | Public-facing social service has moderation and email concerns. |
| Grafana | Good | Partial | Partial | Not applicable | Secure Core | Monitoring is useful once dashboards and alert paths are scoped. |
| Kopia | Partial | Good | Partial | Not applicable | Secure Core | Backups are credible only after restore drills. |
| Home Assistant | Partial | Partial | Partial | Good | Ops Platform | Device integrations are site-specific. |
| ERPNext | Partial | Partial | Partial | Good | Ops Platform | Business-critical app; migration and backup scope must be explicit. |

## How To Read This

Good means the repo has a credible integration pattern. Partial means useful
but still requiring client-specific review, restore work, native-client
handling, or deeper tests before relying on it for critical operations.
