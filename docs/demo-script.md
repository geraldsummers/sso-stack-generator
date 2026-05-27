# Demo Script

Use this for a 5-8 minute buyer-safe proof video or live walkthrough.

## 1. Problem

Show the scattered-SaaS problem: many tools, many logins, unclear backups, weak
ownership, and no shared operating surface.

## 2. Package Selection

Show the three packages and choose one demo path, usually Secure Core or Ops
Platform for a first buyer walkthrough.

## 3. Site Repo / Manifest

Show a sanitized site manifest and explain that site-specific inputs live
outside the generator.

## 4. Build Secret-Free Bundle

Run:

```bash
./build.sh --manifest /path/to/site/manifest.json
```

Show that `dist/` is generated output and should not contain plaintext secrets.

## 5. Deploy To Host

Show:

```bash
rsync -av --no-group --delete ./dist/ <user@host>:~/webservices/
ssh <user@host> 'cd ~/webservices && ./deploy.sh'
```

Explain that secrets render only on the target host.

## 6. Login Through SSO

Open the service homepage and log in through Keycloak.

## 7. Show Service Catalog

Show the homepage/catalog and point out docs, files, passwords, monitoring, and
package-specific services.

## 8. Show Auth Boundary

Open a protected route in a private/incognito browser and show that unauthenticated
access is blocked or redirected.

## 9. Show Monitoring

Show Grafana, service health, unit status, or logs without exposing private data.

## 10. Show Backup/Restore Proof

Show the restore drill page and, if available, a sanitized backup snapshot or
restore note.

## 11. Run Verification

Run:

```bash
ssh <user@host> 'cd ~/webservices && ./verify.sh'
```

Explain that the stack is not trusted until verification passes.

## 12. Buyer Next Step

Close with the tool-list audit:

```text
Send me your current SaaS/tool list. I will tell you what can be replaced, what
should stay SaaS, and what a private stack would look like.
```

## Buyer-Safe Rules

- do not show real secrets
- use sanitized domain names
- use a demo site
- do not show private user data
- do not claim formal compliance certification
