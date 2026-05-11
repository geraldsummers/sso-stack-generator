# Caddy Configuration

`Caddyfile` is the public edge contract for the stack.

## Sections

- global options rendered by the site build
- access log filtering for privacy-bounded observability
- Keycloak forward-auth snippets
- onboarding redirects for users with required actions
- service routes
- native-client exceptions for apps that need API/mobile access

## Auth Patterns

Routes use one of these patterns:

- native OIDC in the upstream app
- Caddy forward-auth through `keycloak-auth-gateway`
- public/static paths followed by protected app routes
- native-client exceptions where app tokens or mobile clients cannot use browser SSO

Header scrubbing in the auth snippets is intentional. Public clients must not be able to spoof identity headers.

## Editing Rules

- Keep service hostnames rendered from `{$DOMAIN}`.
- Prefer shared snippets for repeated auth behavior.
- Document public path exceptions near the route that needs them.
- Add route or browser tests when auth behavior changes.

