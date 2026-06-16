import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { authenticatedSessionState, testForwardAuthService, testUser } from '../deep/shared/forward-auth';
import { guessBaseDomain, testOIDCService } from '../deep/shared/oidc';
import { serviceUrl, stackDomain } from '../../utils/stack-urls';

const screenshotRoot = process.env.PLAYWRIGHT_SCREENSHOTS_DIR || '/app/test-results/screenshots';

type DirectCapture = {
  label: string;
  url: string;
  fileName: string;
  matcher: RegExp;
  evidenceMatcher?: RegExp;
  waitForSelector?: string;
  fullPage?: boolean;
  headers?: Record<string, string>;
  beforeCapture?: (page: Page) => Promise<void>;
};

const noBrowserScreenshotTargets = [
  {
    module: 'CrowdSec',
    reason: 'Security engine with CLI/API/container health proof; no human browser UI is exposed by this stack.',
  },
  {
    module: 'Huly',
    reason: 'Route is catalogued but not owned as a deployed visual target in the current stack.',
  },
  {
    module: 'Mailserver',
    reason: 'SMTP/IMAP infrastructure; SOGo is the browser mail/groupware UI.',
  },
  {
    module: 'Test Manager',
    reason: 'Internal test workflow concept; no deployed browser route is declared.',
  },
  {
    module: 'Test Runners',
    reason: 'Execution infrastructure; Playwright reports and screenshots are the artifacts.',
  },
] as const;

function screenshotPath(fileName: string): string {
  fs.mkdirSync(screenshotRoot, { recursive: true });
  return path.join(screenshotRoot, fileName);
}

const demoEvidence = {
  forgejoRepo: 'stack-demo-operations-playbook',
  ntfyTopic: 'test-stack-demo-ops-alerts',
  qdrantCollection: 'stack_demo_customer_context',
  qdrantPayload: 'Acme Field Service renewal briefing',
  opensearchIndex: 'stack-demo-accounts',
  opensearchCustomer: 'Northwind Field Operations',
  notebookTitle: 'Platform Notebook Demo',
  prometheusQuery: 'up',
};

async function captureDirectSurface(page: Page, capture: DirectCapture) {
  await page.setViewportSize({ width: 1366, height: 900 });
  if (capture.headers) {
    await page.setExtraHTTPHeaders(capture.headers);
  }
  if (capture.beforeCapture) {
    await capture.beforeCapture(page);
  }
  const response = await page.goto(capture.url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  expect(response?.status(), `${capture.label} should return a non-error HTTP status`).toBeLessThan(500);

  if (capture.waitForSelector) {
    await page.waitForSelector(capture.waitForSelector, { state: 'visible', timeout: 45000 });
  }

  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  const bodyText = (await page.textContent('body').catch(() => null)) ?? '';
  const title = await page.title().catch(() => '');
  const html = await page.locator('body').innerHTML().catch(() => '');
  const combined = [title, bodyText, html].join('\n');
  expect(combined, `${capture.label} should render the expected service surface`).toMatch(capture.matcher);
  if (capture.evidenceMatcher) {
    expect(combined, `${capture.label} should show seeded realistic synthetic data`).toMatch(capture.evidenceMatcher);
  }
  expect(html.length, `${capture.label} should not be blank`).toBeGreaterThan(10);

  await page.screenshot({
    path: screenshotPath(capture.fileName),
    type: 'jpeg',
    quality: 85,
    fullPage: capture.fullPage ?? true,
  });
}

function basicAuth(username: string, password: string): string {
  return `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`;
}

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`${name} must be set for seeded screenshot capture`);
  }
  return value;
}

function qdrantHeaders(): Record<string, string> {
  return { 'api-key': requiredEnv('QDRANT_API_KEY') };
}

function opensearchHeaders(): Record<string, string> {
  return { Authorization: basicAuth(process.env.OPENSEARCH_USERNAME || 'admin', requiredEnv('OPENSEARCH_ADMIN_PASSWORD')) };
}

function ntfyHeaders(): Record<string, string> {
  return { Authorization: basicAuth(requiredEnv('NTFY_USERNAME'), requiredEnv('NTFY_PASSWORD')) };
}

async function dismissCommonOverlays(page: Page): Promise<void> {
  await page.keyboard.press('Escape').catch(() => {});
  const closeCandidates = [
    page.getByRole('button', { name: /close|dismiss|skip|later|cancel/i }).first(),
    page.locator('button[aria-label*="close" i], button[title*="close" i]').first(),
  ];
  for (const candidate of closeCandidates) {
    if (await candidate.isVisible().catch(() => false)) {
      await candidate.click({ force: true }).catch(() => {});
      await page.waitForTimeout(500);
    }
  }
}

async function captureUsableAuthenticatedPage(
  page: Page,
  label: string,
  url: string,
  matcher: RegExp,
  fileName: string,
  onAfterLoad: (page: Page) => Promise<void>,
  options: {
    selector?: string;
    viewport?: { width: number; height: number };
    fullPage?: boolean;
    evidenceMatcher?: RegExp;
  } = {}
): Promise<void> {
  await testForwardAuthService(page, label, url, matcher, {
    maxPatternRetries: 10,
    retryDelayMs: 2500,
    waitForSelectorVisible: options.selector,
    waitForSelectorTimeoutMs: 60000,
    requireSelectorVisible: Boolean(options.selector),
    screenshotViewport: options.viewport ?? { width: 1440, height: 900 },
    screenshotSuffix: 'authenticated',
    screenshotFullPage: options.fullPage ?? true,
    screenshotQuality: 85,
    skipScreenshot: true,
    onAfterLoad: async (page) => {
      await onAfterLoad(page);
      await dismissCommonOverlays(page);
      const combined = [
        await page.title().catch(() => ''),
        await page.textContent('body').catch(() => ''),
        await page.locator('body').innerHTML().catch(() => ''),
      ].join('\n');
      if (options.evidenceMatcher) {
        expect(combined, `${label} should show seeded realistic synthetic data`).toMatch(options.evidenceMatcher);
      }
    },
  });

  await page.screenshot({
    path: screenshotPath(fileName),
    type: 'jpeg',
    quality: 85,
    fullPage: options.fullPage ?? true,
  });
}

async function createJupyterNotebookEvidence(page: Page): Promise<void> {
  if (/\/hub\/(home|login)/.test(page.url())) {
    await page.goto(serviceUrl('jupyterhub', '/user-redirect/lab'), { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
  }

  const startButton = page.locator('#start, button:has-text("Start My Server"), a:has-text("Start My Server")').first();
  if (await startButton.isVisible().catch(() => false)) {
    await startButton.click().catch(() => {});
    await page.waitForURL((url) => !url.toString().includes('/spawn-pending/'), { timeout: 120000 }).catch(() => {});
  }

  await page.waitForURL(/\/user\/[^/]+\/(lab|tree)/, { timeout: 120000 }).catch(() => {});
  const userBaseMatch = page.url().match(/^https:\/\/[^/]+\/user\/[^/]+/);
  if (!userBaseMatch) {
    throw new Error(`Could not determine Jupyter user base URL from ${page.url()}`);
  }
  const userBase = userBaseMatch[0];
  const notebookName = 'platform-notebook-demo.ipynb';
  const xsrfCookie = (await page.context().cookies()).find((cookie) => cookie.name === '_xsrf');
  const headers: Record<string, string> = { Referer: page.url() };
  if (xsrfCookie?.value) {
    headers['X-XSRFToken'] = decodeURIComponent(xsrfCookie.value);
  }

  const createResponse = await page.request.put(`${userBase}/api/contents/${notebookName}`, {
    headers,
    data: {
      type: 'notebook',
      format: 'json',
      content: {
        cells: [
          {
            cell_type: 'markdown',
            metadata: {},
            source: [`# ${demoEvidence.notebookTitle}\n`, '\n', 'Synthetic revenue, service, and operations checks for a stack demo.'],
          },
          {
            cell_type: 'code',
            execution_count: 1,
            metadata: {},
            outputs: [{ output_type: 'stream', name: 'stdout', text: ['Northwind Field Operations renewal risk: low\n'] }],
            source: ['print("Northwind Field Operations renewal risk: low")'],
          },
        ],
        metadata: {
          kernelspec: { display_name: 'Python 3 (ipykernel)', language: 'python', name: 'python3' },
          language_info: { name: 'python' },
        },
        nbformat: 4,
        nbformat_minor: 5,
      },
    },
  });
  expect(createResponse.ok(), `Jupyter notebook seed should succeed: ${await createResponse.text().catch(() => '')}`).toBeTruthy();
  await page.goto(`${userBase}/lab/tree/${encodeURIComponent(notebookName)}?reset`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await expect(page.locator('.jp-NotebookPanel')).toBeVisible({ timeout: 60000 });
  await expect(page.locator('body')).toContainText(/Platform Notebook Demo|Northwind Field Operations/i, { timeout: 60000 });
}

async function seedQdrant(page: Page): Promise<void> {
  const headers = qdrantHeaders();
  await page.request.put(`http://qdrant:6333/collections/${demoEvidence.qdrantCollection}`, {
    headers,
    data: { vectors: { size: 4, distance: 'Cosine' } },
  });
  const upsert = await page.request.put(`http://qdrant:6333/collections/${demoEvidence.qdrantCollection}/points?wait=true`, {
    headers,
    data: {
      points: [
        {
          id: 1,
          vector: [0.2, 0.4, 0.1, 0.8],
          payload: {
            account: demoEvidence.qdrantPayload,
            owner: 'Mira Patel',
            stage: 'board review',
            next_action: 'prepare renewal risk summary',
          },
        },
      ],
    },
  });
  expect(upsert.ok(), `Qdrant upsert should succeed: ${await upsert.text().catch(() => '')}`).toBeTruthy();
}

async function seedOpenSearch(page: Page): Promise<void> {
  const headers = opensearchHeaders();
  await page.request.put(`http://opensearch:9200/${demoEvidence.opensearchIndex}`, {
    headers,
    data: {
      mappings: {
        properties: {
          account: { type: 'keyword' },
          owner: { type: 'keyword' },
          summary: { type: 'text' },
        },
      },
    },
  }).catch(() => {});
  const index = await page.request.put(`http://opensearch:9200/${demoEvidence.opensearchIndex}/_doc/renewal-briefing?refresh=true`, {
    headers,
    data: {
      account: demoEvidence.opensearchCustomer,
      owner: 'Rae Chen',
      summary: 'Synthetic account packet with support history, renewal timing, and implementation risks.',
    },
  });
  expect(index.ok(), `OpenSearch document seed should succeed: ${await index.text().catch(() => '')}`).toBeTruthy();
}

async function seedNtfy(page: Page): Promise<void> {
  const publish = await page.request.post(`http://ntfy/${demoEvidence.ntfyTopic}`, {
    headers: {
      ...ntfyHeaders(),
      Title: 'Synthetic stack alert',
      Priority: 'default',
      Tags: 'bar_chart',
    },
    data: 'Northwind Field Operations backup completed and customer portal latency is normal.',
  });
  expect(publish.ok(), `ntfy publish should succeed: ${await publish.text().catch(() => '')}`).toBeTruthy();
}

test.describe('Remaining real screenshot coverage', () => {
  test.describe('Authenticated browser UI captures', () => {
    test.use({ storageState: authenticatedSessionState });

    test('qBittorrent WebUI screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await testForwardAuthService(page, 'qBittorrent', serviceUrl('qbittorrent'), /qBittorrent|Transfers|Name|Size|Status|Tracker/i, {
        maxPatternRetries: 8,
        retryDelayMs: 2500,
        screenshotSuffix: 'authenticated',
        screenshotFullPage: true,
        screenshotQuality: 85,
      });
    });

    test('Jupyter Notebook/JupyterLab screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await captureUsableAuthenticatedPage(page, 'Jupyter Notebook', serviceUrl('jupyterhub', '/user-redirect/lab'), /JupyterLab|Notebook|Launcher|Python|platform-notebooks/i, 'jupyter-notebook-authenticated.jpeg', async (page) => {
        await createJupyterNotebookEvidence(page);
      }, {
        selector: '.jp-LabShell, .jp-NotebookPanel, #jp-main-dock-panel',
        evidenceMatcher: /Platform Notebook Demo|Northwind Field Operations/i,
        fullPage: false,
      });
    });

    test('Prometheus populated query screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await captureUsableAuthenticatedPage(page, 'Prometheus', serviceUrl('prometheus', `/graph?g0.expr=${encodeURIComponent(demoEvidence.prometheusQuery)}&g0.tab=1&g0.range_input=1h`), /Prometheus|Query|Execute|Alerts|up/i, 'prometheus-authenticated.jpeg', async (page) => {
        await page.waitForTimeout(3000);
        const execute = page.getByRole('button', { name: /execute/i }).first();
        if (await execute.isVisible().catch(() => false)) {
          await execute.click().catch(() => {});
        }
        await expect(page.locator('body')).toContainText(/up|instance|job|prometheus/i, { timeout: 30000 });
      }, {
        evidenceMatcher: /up|instance|job|prometheus/i,
        fullPage: false,
      });
    });

    test('ntfy seeded topic screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await seedNtfy(page);
      await page.addInitScript(() => {
        try {
          Object.defineProperty(Notification, 'permission', { configurable: true, get: () => 'granted' });
          Notification.requestPermission = async () => 'granted';
        } catch {}
      });
      await captureUsableAuthenticatedPage(page, 'Ntfy', serviceUrl('ntfy', `/${demoEvidence.ntfyTopic}`), /ntfy|Synthetic stack alert|Northwind Field Operations|test-stack-demo-ops-alerts/i, 'ntfy-authenticated.jpeg', async (page) => {
        await page.waitForTimeout(3000);
        await expect(page.locator('body')).toContainText(/Synthetic stack alert|Northwind Field Operations|backup completed|test-stack-demo-ops-alerts/i, { timeout: 30000 });
      }, {
        evidenceMatcher: /Synthetic stack alert|Northwind Field Operations|backup completed|test-stack-demo-ops-alerts/i,
      });
    });

    test('Forgejo seeded repository screenshot', async ({ page }) => {
      test.setTimeout(180000);
      const forgejoBaseDomain = guessBaseDomain(new URL(serviceUrl('forgejo')).hostname);
      await testOIDCService(page, 'Forgejo', serviceUrl('forgejo'), /Dashboard|Your Repositories|New Repository|Issues|Pull Requests|Repositories/i, ['Keycloak', 'OpenID', 'OpenID Connect', 'OIDC'], {
        skipScreenshot: true,
        disallowUrlPatterns: [/\/user\/login\b/i],
        loginPath: serviceUrl('forgejo', '/user/login'),
        loginButtonPatterns: [/sign in|log in/i],
        oidcLinkPatterns: [/keycloak/i, /openid/i, /oidc/i],
        oidcIssuer: `https://keycloak.${forgejoBaseDomain}`,
        authenticatedRecoveryPath: serviceUrl('forgejo', '/user/settings'),
        authenticatedProbe: async (page) => {
          const fullNameInput = page.locator('input[name="full_name"], input#full_name').first();
          if (await fullNameInput.isVisible().catch(() => false)) {
            return true;
          }
          const bodyText = (await page.textContent('body').catch(() => '')) || '';
          return /Signed in as|New repository|Dashboard|Your Repositories|Issues|Pull requests/i.test(bodyText);
        },
        postLogin: async (page) => {
          await page.goto(serviceUrl('forgejo', '/repo/create'), { waitUntil: 'domcontentloaded', timeout: 30000 });
        const repoName = page.locator('input[name="repo_name"], input#repo_name').first();
        if (await repoName.isVisible().catch(() => false)) {
          await repoName.fill(demoEvidence.forgejoRepo);
          await page.locator('input[name="description"], textarea[name="description"]').first()
            .fill('Synthetic operations playbook repository for screenshot validation.')
            .catch(() => {});
          const submit = page.getByRole('button', { name: /create repository|create repo|create/i }).first();
          if (await submit.isVisible().catch(() => false)) {
            await submit.click().catch(() => {});
            await page.waitForLoadState('domcontentloaded', { timeout: 30000 }).catch(() => {});
          }
        }
        if (!new RegExp(demoEvidence.forgejoRepo, 'i').test(await page.textContent('body').catch(() => '') || '')) {
          await page.goto(serviceUrl('forgejo', `/${testUser.username}/${demoEvidence.forgejoRepo}`), { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
        }
        await expect(page.locator('body')).toContainText(/stack-demo-operations-playbook|operations playbook|repository/i, { timeout: 30000 });
        },
      });
      await page.screenshot({ path: screenshotPath('forgejo-authenticated.jpeg'), type: 'jpeg', quality: 85, fullPage: true });
    });

    test('Keycloak account profile screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await page.goto(`https://keycloak.${stackDomain}/realms/webservices/account/#/personal-info`, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
      await expect(page.locator('body')).toContainText(/Personal info|Account security|Email|Username/i, { timeout: 60000 });
      await page.screenshot({ path: screenshotPath('keycloak-realm-login.jpeg'), type: 'jpeg', quality: 85, fullPage: true });
    });

    test('Home Assistant authenticated dashboard screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await captureUsableAuthenticatedPage(page, 'Home Assistant', serviceUrl('homeassistant'), /Overview|Developer Tools|History|Logbook|Automations|Devices|Settings|Welcome Home/i, 'home-assistant-authenticated.jpeg', async (page) => {
        await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
        await expect(page.locator('body')).toContainText(/Overview|Developer tools|Settings|Welcome Home/i, { timeout: 30000 });
      }, {
        evidenceMatcher: /Overview|Developer tools|Settings|Welcome Home/i,
      });
    });

    test('Vaultwarden authenticated vault screenshot', async ({ page }) => {
      test.setTimeout(180000);
      const vaultwardenSsoIdentifier = process.env.VAULTWARDEN_ORG_ID?.trim() || process.env.VAULTWARDEN_ORG_IDENTIFIER?.trim() || stackDomain;
      const masterPassword = process.env.VAULTWARDEN_TEST_MASTER_PASSWORD
        || `${testUser.username.replace(/[^A-Za-z0-9]/g, '') || 'playwright'}Vault!2026`;

      await page.goto(serviceUrl('vaultwarden', `/#/sso?identifier=${encodeURIComponent(vaultwardenSsoIdentifier)}`), {
        waitUntil: 'domcontentloaded',
        timeout: 45000,
      });
      await page.waitForURL((url) => /keycloak|identity\/connect|sso-connector|set-initial-password|vault/i.test(url.toString()), { timeout: 45000 }).catch(() => {});
      if (/keycloak/i.test(page.url())) {
        const { KeycloakLoginPage } = await import('../../pages/KeycloakLoginPage');
        const login = new KeycloakLoginPage(page);
        await login.login(testUser.username, testUser.password);
      }
      await page.waitForURL((url) => /set-initial-password|vault|setup-extension|lock/i.test(url.toString()), { timeout: 60000 }).catch(() => {});

      const newPassword = page.locator('#input-password-form_new-password').first();
      const confirmPassword = page.locator('#input-password-form_new-password-confirm').first();
      if (await newPassword.isVisible().catch(() => false)) {
        await newPassword.fill(masterPassword, { force: true });
        await confirmPassword.fill(masterPassword, { force: true });
        await page.locator('#input-password-form_new-password-hint').first().fill('Synthetic platform demo vault').catch(() => {});
        const breachCheck = page.locator('#input-password-form_check-for-breaches').first();
        if (await breachCheck.isChecked().catch(() => false)) {
          await breachCheck.uncheck({ force: true }).catch(() => {});
        }
        await page.getByRole('button', { name: /create account|continue|save/i }).first().click({ force: true }).catch(async () => {
          await page.locator('button[type="submit"]').first().click({ force: true });
        });
      }

      const addLater = page.getByRole('button', { name: /add it later/i }).first();
      if (await addLater.isVisible().catch(() => false)) {
        await addLater.click({ force: true }).catch(() => {});
      }
      await page.waitForTimeout(3000);
      await expect(page.locator('body')).toContainText(/My Vault|Vaults|Items|Search vault|Generator|Account|Join organization|Synthetic platform demo vault/i, { timeout: 60000 });
      await page.screenshot({ path: screenshotPath('vaultwarden-authenticated.jpg'), type: 'jpeg', quality: 85, fullPage: true });
    });
  });

  test.describe('Seeded runtime/API captures', () => {
    const directCaptures: DirectCapture[] = [
      {
        label: 'Dozzle',
        url: 'http://dozzle:8080/dozzle/',
        fileName: 'dozzle-authenticated.jpeg',
        matcher: /Dozzle|log viewer for Docker|config__json|dockerVersion/i,
        evidenceMatcher: /running|healthy|container|webservices|forgejo|caddy|postgres/i,
        waitForSelector: '#app',
      },
      {
        label: 'Qdrant dashboard',
        url: `http://qdrant:6333/dashboard#/collections/${demoEvidence.qdrantCollection}`,
        fileName: 'qdrant-dashboard.jpeg',
        matcher: /Qdrant|Collections|Dashboard|vector search|stack_demo_customer_context/i,
        evidenceMatcher: /stack_demo_customer_context|Collections|Points|Vector/i,
        headers: qdrantHeaders(),
        beforeCapture: async (page) => {
          await seedQdrant(page);
        },
      },
      {
        label: 'Qdrant seeded point API',
        url: `http://qdrant:6333/collections/${demoEvidence.qdrantCollection}/points/1`,
        fileName: 'qdrant-endpoint.jpeg',
        matcher: /stack_demo_customer_context|Acme Field Service|renewal briefing|payload/i,
        evidenceMatcher: /Acme Field Service renewal briefing|Mira Patel|board review/i,
        headers: qdrantHeaders(),
        beforeCapture: async (page) => {
          await seedQdrant(page);
        },
      },
      {
        label: 'OpenSearch seeded search result',
        url: `http://opensearch:9200/${demoEvidence.opensearchIndex}/_doc/renewal-briefing`,
        fileName: 'opensearch-authenticated.jpeg',
        matcher: /Northwind Field Operations|renewal|hits|opensearch/i,
        evidenceMatcher: /Northwind Field Operations|Rae Chen|renewal timing/i,
        headers: opensearchHeaders(),
        beforeCapture: async (page) => {
          await seedOpenSearch(page);
        },
      },
      {
        label: 'Matrix Authentication Service',
        url: `https://matrix-auth.${stackDomain}/.well-known/openid-configuration`,
        fileName: 'matrix-authentication-service-endpoint.jpeg',
        matcher: /issuer|authorization_endpoint|token_endpoint|matrix-auth/i,
        evidenceMatcher: /authorization_endpoint|token_endpoint|jwks_uri/i,
      },
      {
        label: 'Synapse Matrix API',
        url: `https://matrix.${stackDomain}/_matrix/client/v3/login`,
        fileName: 'synapse-api-endpoint.jpeg',
        matcher: /flows|m\.login|org\.matrix/i,
        evidenceMatcher: /m\.login\.sso|org\.matrix\.login/i,
      },
      {
        label: 'LiveKit room service endpoint',
        url: 'http://livekit:7880/',
        fileName: 'livekit-endpoint.jpeg',
        matcher: /\bOK\b/i,
      },
    ];

    for (const capture of directCaptures) {
      test(`${capture.label} screenshot`, async ({ page }) => {
        test.setTimeout(90000);
        await captureDirectSurface(page, capture);
      });
    }
  });

  test.describe('Legacy authenticated browser UI captures', () => {
    test.use({ storageState: authenticatedSessionState });

    test('JupyterHub screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await testForwardAuthService(page, 'JupyterHub', serviceUrl('jupyterhub', '/user-redirect/lab/tree'), /JupyterLab|Notebook|Launcher|Python|platform-notebooks/i, {
        maxPatternRetries: 10,
        retryDelayMs: 3000,
        waitForUrlNotMatch: /keycloak|keycloak-auth/i,
        waitForSelectorVisible: '.jp-LabShell, .jp-Launcher, .jp-NotebookPanel, #jp-main-dock-panel',
        waitForSelectorTimeoutMs: 60000,
        requireSelectorVisible: true,
        screenshotDelayMs: 5000,
        screenshotSuffix: 'authenticated',
        screenshotFullPage: true,
        screenshotQuality: 85,
      });
    });
  });

  test('non-browser modules are classified without generated screenshot cards', () => {
    for (const target of noBrowserScreenshotTargets) {
      expect(target.reason.trim().length, `${target.module} should have a concrete no-screenshot reason`).toBeGreaterThan(40);
    }

    const generatedCardNames = noBrowserScreenshotTargets.map((target) =>
      `${target.module.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-evidence.jpeg`
    );
    for (const fileName of generatedCardNames) {
      expect(fs.existsSync(path.join(screenshotRoot, fileName)), `${fileName} must not be generated as fake screenshot coverage`).toBe(false);
    }
  });
});
