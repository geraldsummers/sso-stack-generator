# Keycloak Identity Cutover

Keycloak is the stack identity source of truth for users, credentials, groups,
roles, browser SSO, MFA, and OIDC clients.

The previous directory-backed identity path was a one-time cutover concern and
must not remain as reusable migration tooling, runtime service wiring, or app
configuration. User recreation is manual and intentionally lightweight for this
pre-alpha stack.

## Current State

| Area | Status |
| --- | --- |
| Keycloak | Source of truth for users, groups, roles, credentials, MFA, and OIDC |
| Edge auth | oauth2-proxy backed by Keycloak with server-side Redis sessions |
| Mail | File-provisioned accounts generated from stack config |
| Home Assistant | Keycloak edge-auth trusted headers |
| SOGo | Restored as Keycloak-backed groupware with OpenID Connect |
| Jellyfin | App SSO with Keycloak group authorization; native client/API routes are served by Jellyfin token auth, and password login is blocked at the edge |
| Donetick | Keycloak edge-auth and app OAuth2 client wiring |
| Vaultwarden | Keycloak SSO entry path derives email from the verified email claim |
| ERPNext | Keycloak edge-auth and app OAuth2 client wiring |
| Disposable Workspaces | Keycloak edge-auth to the dispatcher for UI, ttyd, notebooks, and SSH cert issuance |
| LAM | Retired; Keycloak admin UI is the user/group admin surface |

## Completion Criteria

- Keycloak runs as a standard stack service.
- Realm import/export is reproducible and excludes secrets.
- Browser SSO and direct OIDC app clients use Keycloak.
- Edge-protected routes use the Keycloak auth gateway.
- Group-based edge RBAC uses the Keycloak `groups` claim propagated by the auth gateway.
- Mail provisioning does not depend on a directory service.
- No reusable one-time migration helper is bundled.
- Local build, remote deploy, `./verify.sh`, and `./run-tests.sh all` pass.
