# Expected Page Indicators for Authentication Tests

The browser tests verify that protected routes land on the intended service UI, not merely away from the identity provider. Keycloak is the only supported identity boundary.

## Identity Boundary

- Keycloak admin/account route: `keycloak.<domain>`
- Forward-auth gateway route: `keycloak-auth.<domain>`
- Synthetic protected smoke route: `keycloak-whoami.<domain>`
- Tests must fail if an authenticated service remains on a Keycloak login, consent, or required-action screen.

## Forward-Auth Services

- JupyterHub: `/JupyterHub|Start My Server|Control Panel|JupyterLab|Notebook/i`
- Prometheus: `/Prometheus|Graph|Alerts|Status|Time Series/i`
- Grafana: `/Grafana|Dashboards|Explore|Connections|Data sources|Loki/i`
- Vaultwarden forward-auth boundary: must not remain on `#/login`, `#/sso`, or setup screens.
- Stack Portal: `/Stack Portal|contract-backed modules|SOGo/i`
- Seafile: `/Seafile|Libraries|My Libraries|Shared with me|Favorites|Shared Links|Devices|Wiki/i`
- ntfy: `/ntfy/i`
- Search: `/webservices Search|Search Knowledge Base|Hybrid|Semantic|Keyword/i`
- Home Assistant: `/Overview|Developer Tools|History|Logbook|Automations|Devices|Areas|Integrations|Energy/i`
- Kopia: `/KopiaUI/i`
- Pipeline Monitor: `/Airflow|DAGs|webservices Pipeline Monitor|Pipeline Readiness|Sources|Status|Data Pipeline/i`

## OIDC Services

- Mastodon: `/What's on your mind|Compose new post|Publish|Home|Notifications|Profile setup|Save and continue|Display name/i`
- Forgejo: `/Account|Profile|Full name|Email Address|Dashboard|Your Repositories|New Repository|Issues|Pull Requests|Organizations/i`
- BookStack: `/Books|Shelves|Recently Updated Pages|Recent Activity|My Account/i`
- Planka: `/Boards|Projects|Add board|Create board|New board/i`
- Element: `/Element|Rooms|People|Home|Explore rooms|Continue with SSO/i`

## Validation Strategy

1. Anonymous route tests confirm public, service-login, or Keycloak redirect boundaries.
2. Authenticated smoke tests load the service route with a saved Keycloak session.
3. Deep tests complete OIDC flows through Keycloak when the app owns its own login button.
4. UI assertions match service-specific content and reject Keycloak login/consent screens as false positives.
