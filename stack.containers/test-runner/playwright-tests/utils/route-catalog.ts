import * as fs from 'fs';
import * as path from 'path';
import { rootUrl, serviceUrl, stackDomain } from './stack-urls';

export type RouteKind = 'public' | 'forward_auth' | 'oidc_login' | 'non_ui' | 'orphaned';

export type AnonymousContract =
  | { kind: 'public_page'; matcher: RegExp; path?: string }
  | { kind: 'forward_auth'; path?: string }
  | { kind: 'service_login'; matcher: RegExp; loginLabel?: string; path?: string; allowAuthRedirect?: boolean }
  | { kind: 'canonical_redirect'; targetHost: 'apex' | string; followup: 'forward_auth' | 'public_page'; matcher?: RegExp; path?: string }
  | { kind: 'non_ui'; reason: string }
  | { kind: 'orphaned'; reason: string };

export type SmokeContract = {
  matcher: RegExp;
  path?: string;
  pathForUser?: (user: { username: string; email: string }) => string;
  selector?: string;
  loginLabel?: string;
  disallowMatcher?: RegExp;
  disallowUrlMatcher?: RegExp;
  headers?: Record<string, string>;
  oidcStartPath?: string;
};

export type VisualContract = SmokeContract & {
  fileStem: string;
  fullPage?: boolean;
  quality?: number;
};

export type BrowserRoute = {
  host: string;
  label: string;
  kind: RouteKind;
  path?: string;
  anonymous: AnonymousContract;
  smoke?: SmokeContract;
  visual?: VisualContract;
  ownership: {
    route: boolean;
    smoke: boolean;
    visual: boolean;
    deep: boolean;
  };
};

function resolveCaddyHostsPath(): string {
  const candidates = [
    process.env.CADDY_HOSTS_FILE,
    '/app/repo-fixtures/caddy-hosts.txt',
    path.resolve(process.cwd(), '../fixtures/caddy-hosts.txt'),
    path.resolve(__dirname, '../../fixtures/caddy-hosts.txt'),
  ].filter((candidate): candidate is string => Boolean(candidate && candidate.trim().length > 0));

  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }

  throw new Error('Unable to locate caddy-hosts.txt for Playwright route catalog validation.');
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function readCaddyHostsInventory(): string[] {
  return fs
    .readFileSync(resolveCaddyHostsPath(), 'utf-8')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith('#'));
}

export const browserRouteCatalog: BrowserRoute[] = [
  {
    host: 'apex',
    label: 'Apex Portal',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /Stack Portal|Profile dashboards|contract-backed modules|SOGo/i,
      selector: 'text=/Stack Portal|contract-backed modules|SOGo/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
    },
    visual: {
      fileStem: 'apex-portal-authenticated',
      matcher: /Stack Portal|Profile dashboards|contract-backed modules|SOGo/i,
      selector: 'text=/Stack Portal|contract-backed modules|SOGo/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
      quality: 85,
      fullPage: true,
    },
    ownership: { route: true, smoke: true, visual: true, deep: false },
  },
  {
    host: 'onboarding',
    label: 'Account Onboarding',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /Finish account setup in Keycloak|Open Keycloak Account Console|Enroll OTP\/MFA/i,
      selector: 'text=/Finish account setup in Keycloak|Open Keycloak Account Console|Enroll OTP\\/MFA/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
    },
    visual: {
      fileStem: 'onboarding-authenticated',
      matcher: /Finish account setup in Keycloak|Open Keycloak Account Console|Enroll OTP\/MFA/i,
      selector: 'text=/Finish account setup in Keycloak|Open Keycloak Account Console|Enroll OTP\\/MFA/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'alerts',
    label: 'Alertmanager',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /\bAlertmanager\b|\bAlerts\b|\bSilences\b|\bReceivers\b|\bStatus\b/i,
      selector: 'text=/Alertmanager|Alerts|Silences|Receivers|Status/i',
    },
    visual: {
      fileStem: 'alertmanager-authenticated',
      matcher: /\bAlertmanager\b|\bAlerts\b|\bSilences\b|\bReceivers\b|\bStatus\b/i,
      selector: 'text=/Alertmanager|Alerts|Silences|Receivers|Status/i',
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'bookstack',
    label: 'BookStack',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /(?=.*(?:\bBookStack\b|\bdatamancy\b))(?=.*(?:Log in with Keycloak|\bLog In\b|\bLogin\b))/is, loginLabel: 'Keycloak', allowAuthRedirect: true, path: '/login' },
    smoke: {
      path: '/books',
      matcher: /\bBooks\b|\bShelves\b|Recently (Created|Updated)|My Recently Viewed|Recent Activity|Recently Updated Pages/i,
      selector: 'a[href="/books"], a[href="/shelves"], a[href$="/create-book"], .entity-list-item',
      loginLabel: 'Keycloak',
      disallowMatcher: /\bLog In\b|Log in with Keycloak/i,
      disallowUrlMatcher: /\/login\b/i,
    },
    visual: {
      fileStem: 'bookstack-authenticated',
      path: '/books',
      matcher: /\bBooks\b|\bShelves\b|Recently (Created|Updated)|My Recently Viewed|Recent Activity|Recently Updated Pages/i,
      selector: 'a[href="/books"], a[href="/shelves"], a[href$="/create-book"], .entity-list-item',
      loginLabel: 'Keycloak',
      disallowMatcher: /\bLog In\b|Log in with Keycloak/i,
      disallowUrlMatcher: /\/login\b/i,
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'chatgpt-connector',
    label: 'ChatGPT Connector',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /\bChatGPT MCP Connector\b|\bPOST \/mcp\b|\bAgent account APIs\b/i,
      selector: 'text=/ChatGPT MCP Connector|POST \\/mcp|Agent account APIs/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
    },
    visual: {
      fileStem: 'chatgpt-connector-authenticated',
      matcher: /\bChatGPT MCP Connector\b|\bPOST \/mcp\b|\bAgent account APIs\b/i,
      selector: 'text=/ChatGPT MCP Connector|POST \\/mcp|Agent account APIs/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'sogo',
    label: 'SOGo',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /\bSOGo\b|\bKeycloak\b|\bLogin\b|\bSign in\b/i, loginLabel: 'Keycloak', allowAuthRedirect: true, path: '/SOGo/' },
    visual: {
      path: '/SOGo/',
      fileStem: 'sogo-authenticated',
      matcher: /\bCalendar\b|\bMail\b|\bInbox\b|\bSent\b|\bDrafts\b|\bTrash\b/i,
      loginLabel: 'Keycloak',
      oidcStartPath: '/SOGo/',
      disallowUrlMatcher: /keycloak|keycloak-auth/i,
      headers: {
        'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36',
      },
    },
    ownership: { route: true, smoke: false, visual: true, deep: true },
  },
  {
    host: 'jellyfin',
    label: 'Jellyfin',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /\bJellyfin\b|\bKeycloak\b|\bSign In\b|\bLogin\b/i, loginLabel: 'Keycloak', allowAuthRedirect: true },
    visual: {
      fileStem: 'jellyfin-authenticated',
      matcher: /\bJellyfin\b|\bDashboard\b|\bHome\b|\bFavorites\b|\bLibraries\b|\bNothing here\b|create a library/i,
      selector: 'text=/Jellyfin|Dashboard|Home|Favorites|Libraries|Nothing here|create a library/i',
      loginLabel: 'Keycloak',
      oidcStartPath: '/sso/OID/start/keycloak',
      disallowMatcher: /\bWelcome to Jellyfin\b|\bset up your server\b|\bPlease select your preferred language\b|\b503 Service Unavailable\b/i,
    },
    ownership: { route: true, smoke: false, visual: true, deep: true },
  },
  {
    host: 'donetick',
    label: 'Donetick',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /\bDonetick\b|\bContinue with Keycloak\b/i, loginLabel: 'Keycloak', allowAuthRedirect: true },
    visual: {
      fileStem: 'donetick-authenticated',
      matcher: /\bAll Tasks\b|\bArchived\b|\bThings\b|\bLabels\b|\bProjects\b|\bFilters\b|\bActivities\b|\bPoints\b/i,
      selector: 'text=/All Tasks|Archived|Things|Labels|Projects|Filters|Activities|Points/i',
      loginLabel: 'Keycloak',
      disallowMatcher: /\bContinue with Keycloak\b|\bLoading\.\.\.|\btaking longer than usual\b|\b503 Service Unavailable\b/i,
    },
    ownership: { route: true, smoke: false, visual: true, deep: true },
  },
  {
    host: 'huly',
    label: 'Huly',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    ownership: { route: false, smoke: false, visual: false, deep: false },
  },
  {
    host: 'erpnext',
    label: 'ERPNext',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /\bERPNext\b|\bFrappe\b|\bKeycloak\b|\bLogin\b/i, loginLabel: 'Keycloak', allowAuthRedirect: true },
    visual: {
      fileStem: 'erpnext-authenticated',
      matcher: /\bERPNext\b|\bFrappe\b|\bDesk\b|\bHome\b|\bModules\b|\bSettings\b|Alice Morgan/i,
      selector: 'text=/ERPNext|Frappe|Desk|Home|Modules|Settings|Alice Morgan/i',
      loginLabel: 'Keycloak',
      disallowMatcher: /\bLogin to Frappe\b|\bEmail Address\b|\b503 Service Unavailable\b/i,
    },
    ownership: { route: true, smoke: false, visual: true, deep: true },
  },
  {
    host: 'clickhouse',
    label: 'ClickHouse HTTP API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'HTTP API endpoint, not a human browser surface.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'element',
    label: 'Element',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /\bElement\b/i, loginLabel: 'Keycloak', allowAuthRedirect: true },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'api.element',
    label: 'Element Bootstrap API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Element client bootstrap endpoint, not a human browser surface.' },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'forgejo',
    label: 'Forgejo',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /(?=.*\bForgejo\b)(?=.*(?:Sign in with Keycloak|Sign In|Sign in))/is, loginLabel: 'Keycloak', allowAuthRedirect: true },
    smoke: {
      path: '/user/settings',
      matcher: /Account|Profile|Full name|Email Address|Dashboard|Your Repositories|New Repository|Issues|Pull Requests|Organizations/i,
      selector: 'input[name="full_name"], input#full_name, a[href="/repo/create"], .dashboard, a[href="/issues"], a[href="/pulls"]',
      loginLabel: 'Keycloak',
      disallowMatcher: /\bSign in\b|A painless, self-hosted Git service|Easy to install|Cross-platform|Lightweight|Open Source/i,
      disallowUrlMatcher: /\/user\/login\b/i,
    },
    visual: {
      fileStem: 'forgejo-authenticated',
      path: '/user/settings',
      matcher: /Account|Profile|Full name|Email Address|Dashboard|Your Repositories|New Repository|Issues|Pull Requests|Organizations/i,
      selector: 'input[name="full_name"], input#full_name, a[href="/repo/create"], .dashboard, a[href="/issues"], a[href="/pulls"]',
      loginLabel: 'Keycloak',
      disallowMatcher: /\bSign in\b|A painless, self-hosted Git service|Easy to install|Cross-platform|Lightweight|Open Source/i,
      disallowUrlMatcher: /\/user\/login\b/i,
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'grafana',
    label: 'Grafana',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      path: '/d/logs-home/logs',
      matcher: /\bAll Logs\b|\bLogs\b|Loki|Last 24 hours|Refresh|Dashboards|Explore/i,
      selector: 'text=/All Logs|Logs|Loki|Dashboards|Explore|Grafana/i',
      disallowMatcher: /Loading \.\.\.|Loading plugin panel|Failed to load dashboard|Failed to load home dashboard|Not found|\bLog in\b|Email or username|Forgot your password/i,
    },
    visual: {
      fileStem: 'grafana-authenticated',
      path: '/d/logs-home/logs',
      matcher: /\bAll Logs\b|\bLogs\b|Loki|Last 24 hours|Refresh|Dashboards|Explore/i,
      selector: 'text=/All Logs|Logs|Loki|Dashboards|Explore|Grafana/i',
      disallowMatcher: /Loading \.\.\.|Loading plugin panel|Failed to load dashboard|Failed to load home dashboard|Not found|\bLog in\b|Email or username|Forgot your password/i,
      quality: 85,
      fullPage: false,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'homeassistant',
    label: 'Home Assistant',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /Overview|Developer Tools|History|Logbook|Automations|Devices|Areas|Integrations|Energy|Settings|Map|Media/i,
      selector: 'text=/Overview|Developer tools|Settings/i',
      disallowMatcher: /Home Assistant\s+Login|Trusted Networks|select a user|please select a user|forgot password\?|keep me logged in|^log in$/im,
      disallowUrlMatcher: /keycloak|keycloak-auth|\/auth\/(authorize|login_flow|login)/i,
    },
    ownership: { route: true, smoke: true, visual: false, deep: true },
  },
  {
    host: 'direct.homeassistant',
    label: 'Home Assistant Direct',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Direct device endpoint protected by Home Assistant native auth.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'portal',
    label: 'Stack Portal',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /Stack Portal|Profile dashboards|contract-backed modules|SOGo/i,
      selector: 'text=/Stack Portal|contract-backed modules|SOGo/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
    },
    visual: {
      fileStem: 'portal-authenticated',
      matcher: /Stack Portal|Profile dashboards|contract-backed modules|SOGo/i,
      selector: 'text=/Stack Portal|contract-backed modules|SOGo/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'homepage',
    label: 'Homepage Compatibility Redirect',
    kind: 'public',
    anonymous: { kind: 'canonical_redirect', targetHost: 'portal', followup: 'forward_auth' },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'progress',
    label: 'Progression',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /Sovereign Compute Progression|Ownership Path|Next Action/i,
      selector: 'text=/Sovereign Compute Progression|Ownership Path|Next Action/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
    },
    visual: {
      fileStem: 'progression-authenticated',
      matcher: /Sovereign Compute Progression|Ownership Path|Next Action/i,
      selector: 'text=/Sovereign Compute Progression|Ownership Path|Next Action/i',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: false },
  },
  {
    host: 'keycloak',
    label: 'Keycloak Portal',
    kind: 'non_ui',
    anonymous: {
      kind: 'non_ui',
      reason: 'Identity provider host; covered by Keycloak OIDC smoke tests.',
    },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'keycloak-auth',
    label: 'Keycloak Auth Gateway',
    kind: 'non_ui',
    anonymous: {
      kind: 'non_ui',
      reason: 'OAuth2 Proxy gateway endpoints are covered by Keycloak OIDC smoke tests.',
    },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'keycloak-whoami',
    label: 'Keycloak Protected Whoami',
    kind: 'non_ui',
    anonymous: {
      kind: 'non_ui',
      reason: 'Synthetic Keycloak-protected route is covered by Keycloak OIDC smoke tests.',
    },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'workspaces',
    label: 'Workspaces',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /Disposable Workspaces|Create Workspace|Your Workspaces/i,
      selector: '#displayName, #createButton, #workspaceRows',
    },
    visual: {
      fileStem: 'workspaces-authenticated',
      matcher: /Disposable Workspaces|Create Workspace|Your Workspaces/i,
      selector: '#displayName, #createButton, #workspaceRows',
      disallowMatcher: /\bSign in to your account\b|\b503 Service Unavailable\b/i,
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'jupyterhub',
    label: 'JupyterHub',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth', path: '/user-redirect/lab' },
    path: '/user-redirect/lab',
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'kopia',
    label: 'Kopia',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'mail',
    label: 'Mail Host',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Certificate/SMTP host, not a web UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'mastodon',
    label: 'Mastodon',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /(?=.*\bMastodon\b)(?=.*(?:Login or Register|openid_connect|Sign in with Keycloak|Log in|Sign in|SSO))/is, loginLabel: 'Keycloak', allowAuthRedirect: true },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'matrix',
    label: 'Matrix API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Matrix client-server/federation API surface.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'matrix-rtc',
    label: 'MatrixRTC Backend',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'MatrixRTC LiveKit signaling and JWT API surface.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'models',
    label: 'Embedding API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Embedding API endpoint, not a browser UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'ntfy',
    label: 'ntfy',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'onlyoffice',
    label: 'OnlyOffice Stub',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Seafile integration endpoint, not a standalone user UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'pipeline',
    label: 'Pipeline Monitor',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /\bAirflow\b|\bDAGs\b|\bPipeline Readiness\b|\bSources\b|\bStatus\b|\bData Pipeline\b/i,
      selector: 'text=/Airflow|DAGs|Pipeline Readiness|Sources|Status|Data Pipeline/i',
    },
    visual: {
      fileStem: 'pipeline-monitor-authenticated',
      matcher: /\bAirflow\b|\bDAGs\b|\bPipeline Readiness\b|\bSources\b|\bStatus\b|\bData Pipeline\b/i,
      selector: 'text=/Airflow|DAGs|Pipeline Readiness|Sources|Status|Data Pipeline/i',
      quality: 85,
    },
    ownership: { route: true, smoke: true, visual: true, deep: true },
  },
  {
    host: 'planka',
    label: 'Planka',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /(?=.*\bPlanka\b)(?=.*(?:Log in with SSO|OIDC|Keycloak|\bLog in\b))/is, loginLabel: 'Keycloak', allowAuthRedirect: true, path: '/login' },
    path: '/login',
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'prometheus',
    label: 'Prometheus',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'search',
    label: 'OpenSearch',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    smoke: {
      matcher: /cluster_name|opensearch|You Know, for Search/i,
      selector: 'body',
      disallowMatcher: /Sign in|Log in|Keycloak|Bad Gateway|Service Unavailable|Internal Server Error/i,
      disallowUrlMatcher: /keycloak|keycloak-auth/i,
    },
    ownership: { route: true, smoke: true, visual: false, deep: true },
  },
  {
    host: 'seafile',
    label: 'Seafile',
    kind: 'forward_auth',
    anonymous: { kind: 'forward_auth' },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'vaultwarden',
    label: 'Vaultwarden',
    kind: 'oidc_login',
    anonymous: { kind: 'service_login', matcher: /(?=.*(?:Vaultwarden|Bitwarden|Web Vault))(?=.*(?:Single sign-on|Use single sign-on|SSO|Log in))/is, loginLabel: 'Single sign-on', allowAuthRedirect: true },
    ownership: { route: true, smoke: false, visual: false, deep: true },
  },
  {
    host: 'vllm-router',
    label: 'vLLM Router API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Protected model API endpoint, not a browser UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'www',
    label: 'WWW Redirect',
    kind: 'public',
    anonymous: { kind: 'canonical_redirect', targetHost: 'apex', followup: 'forward_auth' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'api.bookstack',
    label: 'BookStack API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Token/API surface, not a browser UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'api.homeassistant',
    label: 'Home Assistant API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'API surface, not a browser UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'api.matrix',
    label: 'Matrix API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'API surface, not a browser UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'api.seafile',
    label: 'Seafile API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Token/API surface, not a browser UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'api.vaultwarden',
    label: 'Vaultwarden API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Bitwarden native API endpoint, not a browser UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
  {
    host: 'api.mastodon',
    label: 'Mastodon API',
    kind: 'non_ui',
    anonymous: { kind: 'non_ui', reason: 'Mastodon native app/API endpoint, not a browser UI.' },
    ownership: { route: true, smoke: false, visual: false, deep: false },
  },
];

export function routeUrl(route: BrowserRoute, overridePath?: string): string {
  const targetPath = overridePath ?? route.path ?? '/';
  if (route.host === 'apex') {
    return rootUrl(targetPath);
  }
  if (route.host === 'www') {
    return `https://www.${stackDomain}${targetPath}`;
  }
  return serviceUrl(route.host, targetPath);
}

export function routeUrlPattern(host: string): RegExp {
  if (host === 'apex') {
    return new RegExp(`^https://(?:www\\.)?${escapeRegex(stackDomain)}(?:/|$)`, 'i');
  }

  return new RegExp(`^https://${escapeRegex(host)}\\.${escapeRegex(stackDomain)}(?:/|$)`, 'i');
}

export function findRoute(host: string): BrowserRoute {
  const route = browserRouteCatalog.find((candidate) => candidate.host === host);
  if (!route) {
    throw new Error(`No route catalog entry exists for host '${host}'.`);
  }
  return route;
}

function isTestdevExcluded(route: BrowserRoute): boolean {
  return process.env.TESTDEV_SKIP_GPU_INGESTION === '1' && route.host === 'pipeline';
}

export const routeContractRoutes = browserRouteCatalog.filter((route) => route.ownership.route);
export const smokeRoutes = browserRouteCatalog.filter((route) => route.ownership.smoke && !isTestdevExcluded(route));
export const visualRoutes = browserRouteCatalog.filter((route) => route.ownership.visual && !isTestdevExcluded(route));

export function uncataloguedHosts(): string[] {
  const cataloguedHosts = new Set(browserRouteCatalog.map((route) => route.host));
  return readCaddyHostsInventory().filter((host) => !cataloguedHosts.has(host));
}
