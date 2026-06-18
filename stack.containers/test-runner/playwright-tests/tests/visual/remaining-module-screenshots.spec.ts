import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { authenticatedSessionState, testForwardAuthService, testUser } from '../deep/shared/forward-auth';
import { guessBaseDomain, testOIDCService } from '../deep/shared/oidc';
import { serviceUrl, stackDomain } from '../../utils/stack-urls';
import { KeycloakClient } from '../../utils/keycloak-client';

const screenshotRoot = process.env.PLAYWRIGHT_SCREENSHOTS_DIR || '/app/test-results/screenshots';

type DirectCapture = {
  label: string;
  url: string;
  fileName: string;
  matcher: RegExp;
  evidenceMatcher?: RegExp;
  waitForSelector?: string;
  viewport?: { width: number; height: number };
  fullPage?: boolean;
  headers?: Record<string, string>;
  beforeCapture?: (page: Page) => Promise<void>;
  afterCapture?: (page: Page) => Promise<void>;
};

type VaultwardenSyntheticAccount = {
  username: string;
  email: string;
  password: string;
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
  donetickProject: 'Compose Cleanup Delivery',
  donetickTask: 'Verify backup restore drill',
  erpnextCustomer: 'Northwind Field Operations',
  erpnextTerritory: 'Synthetic Services',
  haDashboard: 'Northwind Field Operations',
  onboardingUser: testUser.username,
  vaultwardenItem: 'Northwind Field Operations portal',
};

async function captureDirectSurface(page: Page, capture: DirectCapture) {
  try {
    await page.setViewportSize(capture.viewport ?? { width: 1366, height: 900 });
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
  } finally {
    if (capture.afterCapture) {
      await capture.afterCapture(page).catch((error) => {
        console.warn(`   ⚠️  Failed to clean up ${capture.label} seed data: ${String((error as Error)?.message || error)}`);
      });
    }
  }
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

function runDocker(args: string[]): string {
  return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
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

async function dismissJupyterFirstRunOverlays(page: Page): Promise<void> {
  const buttons = [
    page.getByText(/^Yes$/i).first(),
    page.locator('button').filter({ hasText: /^Yes$/i }).first(),
    page.getByRole('button', { name: /no thanks|not now|never|skip|later/i }).first(),
    page.getByRole('button', { name: /dismiss/i }).first(),
    page.getByRole('button', { name: /close/i }).first(),
    page.locator('button[aria-label*="close" i], button[title*="close" i], .jp-Dialog button, .lm-Widget button').filter({ hasText: /close|dismiss|no thanks|not now|never|skip|later|×/i }).first(),
  ];
  for (const button of buttons) {
    if (await button.isVisible().catch(() => false)) {
      await button.click({ force: true }).catch(() => {});
      await page.waitForTimeout(500);
    }
  }
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(500);
  await page.evaluate(() => {
    const phrases = ['official Jupyter news', 'privacy policy', 'Jupyter news'];
    const hasNewsPrompt = (node: Element) => phrases.some((phrase) => node.textContent?.includes(phrase));
    for (const button of Array.from(document.querySelectorAll('button'))) {
      const label = `${button.textContent || ''} ${button.getAttribute('title') || ''}`.trim();
      if (/^No$/i.test(label) || /Hide notification/i.test(label)) {
        (button as HTMLButtonElement).click();
      }
    }
    for (const node of Array.from(document.querySelectorAll('[role="dialog"], .jp-Dialog, .jp-Dialog-content, .Toastify__toast, .jp-Notification-Toast-default'))) {
      if (!hasNewsPrompt(node)) {
        continue;
      }
      const target = node.closest('.jp-Dialog') || node.closest('[role="dialog"]') || node.closest('.Toastify__toast') || node;
      target.remove();
    }
    for (const node of Array.from(document.querySelectorAll('.jp-Dialog-overlay, .lm-Overlay, .p-Overlay, .jp-mod-modal'))) {
      (node as HTMLElement).style.display = 'none';
    }
    for (const node of Array.from(document.querySelectorAll('#react-toastify-container, .Toastify__toast-container, .Toastify__toast, .jp-Notification-Toast-default'))) {
      (node as HTMLElement).style.display = 'none';
    }
  }).catch(() => {});
  await page.waitForTimeout(500);
  await page.locator('.jp-Dialog, .jp-Notification, .lm-Widget')
    .filter({ hasText: /official Jupyter news|privacy policy|Jupyter news/i })
    .evaluateAll((nodes) => {
      for (const node of nodes) {
        (node as HTMLElement).style.display = 'none';
      }
    })
    .catch(() => {});
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
    screenshotSelector?: string;
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

  await page.waitForTimeout(1000);
  if (options.screenshotSelector && options.screenshotSelector !== 'body') {
    const surface = page.locator(options.screenshotSelector).first();
    await expect(surface, `${label} screenshot surface should be visible`).toBeVisible({ timeout: 30000 });
    await surface.screenshot({
      path: screenshotPath(fileName),
      type: 'jpeg',
      quality: 85,
    });
  } else {
    await page.screenshot({
      path: screenshotPath(fileName),
      type: 'jpeg',
      quality: 85,
      fullPage: options.fullPage ?? true,
    });
  }
}

async function createJupyterNotebookEvidence(page: Page): Promise<() => Promise<void>> {
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
  const notebookName = `platform-notebook-demo-${Date.now()}.ipynb`;
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
  await page.goto(`${userBase}/notebooks/${encodeURIComponent(notebookName)}`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForFunction(
    () => /Platform Notebook Demo|Northwind Field Operations/i.test(document.body?.innerText || document.body?.textContent || ''),
    null,
    { timeout: 60000 }
  );
  await page.addStyleTag({
    content: `
      html, body {
        display: block !important;
        visibility: visible !important;
        opacity: 1 !important;
        background: #111827 !important;
      }
      #notebook-container, .notebook_app, .cell, .text_cell_render, .output_area {
        display: block !important;
        visibility: visible !important;
        opacity: 1 !important;
      }
      #notebook-container {
        max-width: 1120px !important;
        margin: 32px auto !important;
        padding: 32px 44px !important;
        background: #ffffff !important;
        color: #111827 !important;
        border-radius: 18px !important;
        box-shadow: 0 28px 80px rgba(0, 0, 0, 0.32) !important;
      }
    `,
  });
  await expect(page.locator('body')).toContainText(/Platform Notebook Demo|Northwind Field Operations/i, { timeout: 60000 });
  await dismissJupyterFirstRunOverlays(page);
  return async () => {
    await page.request.delete(`${userBase}/api/contents/${encodeURIComponent(notebookName)}`, { headers });
  };
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

async function cleanupQdrant(page: Page): Promise<void> {
  await page.request.delete(`http://qdrant:6333/collections/${demoEvidence.qdrantCollection}`, {
    headers: qdrantHeaders(),
  });
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

async function cleanupOpenSearch(page: Page): Promise<void> {
  await page.request.delete(`http://opensearch:9200/${demoEvidence.opensearchIndex}`, {
    headers: opensearchHeaders(),
  });
}

async function seedErpNext(page: Page): Promise<void> {
  const password = process.env.ERPNEXT_ADMIN_PASSWORD?.trim() || requiredEnv('STACK_ADMIN_PASSWORD');
  const baseUrl = serviceUrl('erpnext');
  const login = await page.request.post(`${baseUrl}/api/method/login`, {
    form: {
      usr: 'Administrator',
      pwd: password,
    },
  });
  expect(login.ok(), `ERPNext administrator login should succeed: ${await login.text().catch(() => '')}`).toBeTruthy();

  await page.request.post(`${baseUrl}/api/resource/Territory`, {
    data: {
      territory_name: demoEvidence.erpnextTerritory,
      parent_territory: 'All Territories',
    },
  }).catch(() => {});

  await page.request.post(`${baseUrl}/api/resource/Customer`, {
    data: {
      customer_name: demoEvidence.erpnextCustomer,
      customer_type: 'Company',
      customer_group: 'Commercial',
      territory: demoEvidence.erpnextTerritory,
      custom_stack_context: 'Synthetic business account used by the visual screenshot suite.',
    },
  }).catch(() => {});

  const lookup = await page.request.get(`${baseUrl}/api/resource/Customer/${encodeURIComponent(demoEvidence.erpnextCustomer)}`);
  expect(lookup.ok(), `ERPNext seeded customer should be readable: ${await lookup.text().catch(() => '')}`).toBeTruthy();
}

async function cleanupErpNext(page: Page): Promise<void> {
  const password = process.env.ERPNEXT_ADMIN_PASSWORD?.trim() || requiredEnv('STACK_ADMIN_PASSWORD');
  const baseUrl = serviceUrl('erpnext');
  const login = await page.request.post(`${baseUrl}/api/method/login`, {
    form: {
      usr: 'Administrator',
      pwd: password,
    },
  });
  if (!login.ok()) {
    return;
  }
  await page.request.delete(`${baseUrl}/api/resource/Customer/${encodeURIComponent(demoEvidence.erpnextCustomer)}`).catch(() => {});
  await page.request.delete(`${baseUrl}/api/resource/Territory/${encodeURIComponent(demoEvidence.erpnextTerritory)}`).catch(() => {});
}

async function seedNtfy(page: Page): Promise<void> {
  const publish = await page.request.post(`http://ntfy/${demoEvidence.ntfyTopic}`, {
    headers: {
      ...ntfyHeaders(),
      Title: 'Synthetic stack alert',
      Priority: 'default',
      Tags: 'bar_chart',
      Cache: 'no',
      'X-Cache': 'no',
    },
    data: 'Northwind Field Operations backup completed and customer portal latency is normal.',
  });
  expect(publish.ok(), `ntfy publish should succeed: ${await publish.text().catch(() => '')}`).toBeTruthy();
}

async function seedAlertmanagerSilence(page: Page): Promise<string | null> {
  const startsAt = new Date(Date.now() - 60_000).toISOString();
  const endsAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  const response = await page.request.post(serviceUrl('alerts', '/api/v2/silences'), {
    data: {
      matchers: [
        { name: 'alertname', value: 'NorthwindFieldOperationsSyntheticAlert', isRegex: false },
        { name: 'service', value: 'customer-portal', isRegex: false },
      ],
      startsAt,
      endsAt,
      createdBy: 'Playwright visual suite',
      comment: 'Synthetic Northwind Field Operations routing drill for screenshot evidence.',
    },
  });
  expect(response.ok(), `Alertmanager silence seed should succeed: ${await response.text().catch(() => '')}`).toBeTruthy();
  const payload = await response.json().catch(() => null);
  return payload?.silenceID || null;
}

async function cleanupAlertmanagerSilence(page: Page, silenceId: string | null): Promise<void> {
  if (!silenceId) {
    return;
  }
  await page.request.delete(serviceUrl('alerts', `/api/v2/silence/${encodeURIComponent(silenceId)}`)).catch(() => {});
}

async function seedProgression(page: Page): Promise<void> {
  const scan = await page.request.post('http://progression:8130/api/scan');
  expect(scan.ok(), `Progression scan should succeed: ${await scan.text().catch(() => '')}`).toBeTruthy();
}

function seedDonetickViaDocker(username: string): number {
  const donetickPasswordHash = '$2a$10$HXpM6VLzk/zuVNB5m9gqbOxTcQkpH6yFbs78GEyMWzRmRwkC7GniK';
  const seedCode = `
import datetime
import sqlite3
import sys

db_path, username, password_hash = sys.argv[1], sys.argv[2], sys.argv[3]
email = username if "@" in username else f"{username}@datamancy.net"
now = datetime.datetime.utcnow().replace(microsecond=0)
con = sqlite3.connect(db_path)
con.execute("pragma foreign_keys=off")
row = con.execute("select id, circle_id from users where username = ? or email = ? order by id desc limit 1", (username, email)).fetchone()
if row is None:
    circle_name = f"{username}'s circle"
    cur = con.execute(
        "insert into circles (name, created_by, created_at, updated_at, invite_code, disabled) values (?, 0, ?, ?, ?, 0)",
        (circle_name, now, now, username[:16]),
    )
    circle_id = cur.lastrowid
    cur = con.execute(
        "insert into users (display_name, username, email, provider, password, circle_id, chat_id, image, timezone, user_type, mfa_enabled, mfa_backup_codes, mfa_recovery_codes_used, created_at, updated_at, disabled) values (?, ?, ?, 1, '', ?, 0, '', '', 0, 0, '', '[]', ?, ?, 0)",
        ("Playwright User", username, email, circle_id, now, now),
    )
    user_id = cur.lastrowid
    con.execute("update circles set created_by = ? where id = ?", (user_id, circle_id))
    con.execute("insert into user_circles (user_id, circle_id, role, is_active, created_at, updated_at, points, points_redeemed) values (?, ?, 'admin', 1, ?, ?, 0, 0)", (user_id, circle_id, now, now))
    row = (user_id, circle_id)
user_id, circle_id = row
con.execute("update users set username = ?, email = ?, password = ?, mfa_enabled = 0, mfa_secret = null, mfa_backup_codes = '', mfa_recovery_codes_used = '[]', disabled = 0 where id = ?", (username, email, password_hash, user_id))
if not circle_id:
    circle = con.execute("select circle_id from user_circles where user_id = ? order by id desc limit 1", (user_id,)).fetchone()
    circle_id = circle[0] if circle else user_id
    con.execute("update users set circle_id = ? where id = ?", (circle_id, user_id))

project = con.execute("select id from projects where circle_id = ? and name = ?", (circle_id, "Compose Cleanup Delivery")).fetchone()
if project is None:
    cur = con.execute(
        "insert into projects (name, description, color, icon, circle_id, created_by, created_at, updated_at, is_default) values (?, ?, ?, ?, ?, ?, ?, ?, 0)",
        ("Compose Cleanup Delivery", "Synthetic screenshot project for deployment cleanup routines.", "#0ea5e9", "assignment", circle_id, user_id, now, now),
    )
    project_id = cur.lastrowid
else:
    project_id = project[0]

tasks = [
    ("Verify backup restore drill", "Example screenshot task: prove recovery before handoff.", 5, 2, 1),
    ("Update deployment runbook", "Example screenshot task: document deploy, update, logs, and restore commands.", 3, 1, 2),
    ("Review access handoff", "Example screenshot task: confirm owner and operator access before release.", 2, 1, 3),
]
first_chore_id = None
for name, description, points, priority, offset in tasks:
    existing = con.execute("select id from chores where circle_id = ? and name = ?", (circle_id, name)).fetchone()
    due = now + datetime.timedelta(days=offset)
    if existing is None:
        cur = con.execute(
            "insert into chores (name, frequency_type, frequency, frequency_meta, frequency_meta_v2, next_due_date, is_rolling, assigned_to, assign_strategy, is_active, notification, notification_meta, notification_meta_v2, labels, circle_id, created_at, updated_at, created_by, updated_by, status, priority, completion_window, points, description, require_approval, is_private, deadline_offset, project_id) values (?, 'once', 1, '{}', '{}', ?, 0, ?, 'manual', 1, 0, '{}', '{}', '[]', ?, ?, ?, ?, ?, 0, ?, 0, ?, ?, 0, 0, 0, ?)",
            (name, due, user_id, circle_id, now, now, user_id, user_id, priority, points, description, project_id),
        )
        chore_id = cur.lastrowid
    else:
        chore_id = existing[0]
        con.execute(
            "update chores set assigned_to = ?, project_id = ?, description = ?, updated_at = ?, is_active = 1 where id = ?",
            (user_id, project_id, description, now, chore_id),
        )
    if con.execute("select 1 from chore_assignees where chore_id = ? and user_id = ?", (chore_id, user_id)).fetchone() is None:
        con.execute("insert into chore_assignees (chore_id, user_id) values (?, ?)", (chore_id, user_id))
    if name == "Verify backup restore drill":
        first_chore_id = chore_id
con.commit()
print(first_chore_id or "")
`;
  const output = runDocker([
    'run',
    '--rm',
    '-u',
    '0:0',
    '-v',
    'webservices_donetick_data:/data',
    '--entrypoint',
    'python3',
    'stack/test-runner:local-build',
    '-c',
    seedCode,
    '/data/donetick.db',
    username,
    donetickPasswordHash,
  ]);
  const choreId = Number.parseInt(output.trim().split(/\s+/).pop() || '', 10);
  if (!Number.isFinite(choreId)) {
    throw new Error(`Donetick seed did not return a chore id: ${output}`);
  }
  return choreId;
}

function cleanupDonetickViaDocker(username: string): void {
  const cleanupCode = `
import sqlite3
import sys

db_path, username = sys.argv[1], sys.argv[2]
email = username if "@" in username else f"{username}@datamancy.net"
project_name = "Compose Cleanup Delivery"
task_names = ("Verify backup restore drill", "Update deployment runbook", "Review access handoff")
con = sqlite3.connect(db_path)
con.execute("pragma foreign_keys=off")
row = con.execute("select id, circle_id from users where username = ? or email = ? order by id desc limit 1", (username, email)).fetchone()
if row is None:
    raise SystemExit(0)
user_id, circle_id = row
if not circle_id:
    circle = con.execute("select circle_id from user_circles where user_id = ? order by id desc limit 1", (user_id,)).fetchone()
    circle_id = circle[0] if circle else None
if not circle_id:
    raise SystemExit(0)
placeholders = ",".join("?" for _ in task_names)
chore_ids = [entry[0] for entry in con.execute(f"select id from chores where circle_id = ? and name in ({placeholders})", (circle_id, *task_names)).fetchall()]
tables = [entry[0] for entry in con.execute("select name from sqlite_master where type = 'table'").fetchall()]
for chore_id in chore_ids:
    for table in tables:
        columns = [column[1] for column in con.execute(f"pragma table_info({table})")]
        if table != "chores" and "chore_id" in columns:
            con.execute(f"delete from {table} where chore_id = ?", (chore_id,))
    con.execute("delete from chores where id = ?", (chore_id,))
con.execute("delete from projects where circle_id = ? and name = ?", (circle_id, project_name))
con.commit()
`;
  runDocker([
    'run',
    '--rm',
    '-u',
    '0:0',
    '-v',
    'webservices_donetick_data:/data',
    '--entrypoint',
    'python3',
    'stack/test-runner:local-build',
    '-c',
    cleanupCode,
    '/data/donetick.db',
    username,
  ]);
}

async function cleanupForgejoRepo(page: Page): Promise<void> {
  await page.request.delete(serviceUrl('forgejo', `/api/v1/repos/${encodeURIComponent(testUser.username)}/${encodeURIComponent(demoEvidence.forgejoRepo)}`));
}

async function fillVisibleField(page: Page, selectors: string[], value: string): Promise<boolean> {
  for (const selector of selectors) {
    const field = page.locator(selector).first();
    if (!(await field.isVisible().catch(() => false))) {
      continue;
    }
    await field.scrollIntoViewIfNeeded().catch(() => {});
    await field.fill(value, { force: true }).catch(() => {});
    await field.evaluate((el, nextValue) => {
      const input = el as HTMLInputElement | HTMLTextAreaElement;
      const setter = Object.getOwnPropertyDescriptor(
        input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype,
        'value'
      )?.set;
      input.focus();
      if (setter) {
        setter.call(input, nextValue);
      } else {
        input.value = nextValue;
      }
      input.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: nextValue }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }, value).catch(() => {});
    return true;
  }
  return false;
}

async function fillVisibleLocator(locator: Locator, value: string): Promise<boolean> {
  if (!(await locator.isVisible().catch(() => false))) {
    return false;
  }
  await locator.scrollIntoViewIfNeeded().catch(() => {});
  await locator.click({ force: true }).catch(() => {});
  await locator.fill(value, { force: true }).catch(() => {});
  await locator.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A').catch(() => {});
  await locator.pressSequentially(value, { delay: 10 }).catch(() => {});
  await locator.evaluate((el, nextValue) => {
    const input = el as HTMLInputElement | HTMLTextAreaElement;
    const setter = Object.getOwnPropertyDescriptor(
      input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype,
      'value'
    )?.set;
    input.focus();
    if (setter) {
      setter.call(input, nextValue);
    } else {
      input.value = nextValue;
    }
    input.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: nextValue }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }, value).catch(() => {});
  return true;
}

function firstStringByKey(value: unknown, keys: RegExp[]): string | null {
  if (!value || typeof value !== 'object') {
    return null;
  }
  for (const [key, nextValue] of Object.entries(value as Record<string, unknown>)) {
    if (typeof nextValue === 'string' && keys.some((matcher) => matcher.test(key))) {
      return nextValue;
    }
    const nested = firstStringByKey(nextValue, keys);
    if (nested) {
      return nested;
    }
  }
  return null;
}

async function hydrateDonetickSession(page: Page, username: string, password: string): Promise<boolean> {
  for (const loginPath of ['/api/v1/auth/login', '/auth/login']) {
    const loginResponse = await page.request.post(serviceUrl('donetick', loginPath), {
      data: { username, password },
      failOnStatusCode: false,
    }).catch(() => null);
    if (!loginResponse?.ok()) {
      continue;
    }
    const payload = await loginResponse.json().catch(() => null);
    const token = firstStringByKey(payload, [/^token$/i, /access.*token/i, /jwt/i]);
    const refreshToken = firstStringByKey(payload, [/refresh.*token/i]);
    const tokenExpiry = firstStringByKey(payload, [/^expire$/i, /access.*token.*expiry/i]);
    const refreshTokenExpiry = firstStringByKey(payload, [/refresh.*token.*expiry/i]);
    if (!token) {
      continue;
    }
    await page.addInitScript(({ token, refreshToken, tokenExpiry, refreshTokenExpiry }) => {
      window.localStorage.setItem('token', token);
      window.localStorage.setItem('token_expiry', tokenExpiry || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString());
      if (refreshToken) {
        window.localStorage.setItem('refresh_token', refreshToken);
        window.localStorage.setItem('refresh_token_expiry', refreshTokenExpiry || new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString());
      }
    }, { token, refreshToken, tokenExpiry, refreshTokenExpiry });
    return true;
  }
  return false;
}

async function unlockVaultwardenLoginForm(page: Page, email: string, password: string): Promise<void> {
  const emailSelector = [
    'input.vw-email-continue',
    'input[type="email"]:not(.vw-email-sso)',
    'input[autocomplete="username"]',
    'input[name*="email" i]',
    'input[id*="email" i]',
    'input[aria-label*="email" i]',
  ].join(', ');
  const passwordSelector = [
    'input[type="password"]',
    'input[autocomplete="current-password"]',
    'input[name*="password" i]',
    'input[id*="password" i]',
    'input[aria-label*="password" i]',
  ].join(', ');
  const fillAccessibleField = async (label: RegExp, selector: string, value: string): Promise<void> => {
    for (const candidate of [
      page.getByLabel(label).first(),
      page.getByRole('textbox', { name: label }).first(),
      page.locator(selector).first(),
    ]) {
      if (await candidate.count().catch(() => 0) === 0) {
        continue;
      }
      await candidate.click({ force: true }).catch(() => {});
      await candidate.fill(value, { force: true }).catch(() => {});
      const filled = await candidate.evaluate((field, expectedValue) => {
        return field instanceof HTMLInputElement && field.value === expectedValue;
      }, value).catch(() => false);
      if (filled) {
        return;
      }
    }
  };
  const fillInputCandidates = async (selector: string, value: string, labelPattern: string): Promise<boolean> => {
    return page.evaluate(({ selector, value, labelPattern }) => {
      const matcher = new RegExp(labelPattern, 'i');
      const setValue = (field: HTMLInputElement) => {
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
        field.focus();
        if (setter) {
          setter.call(field, value);
        } else {
          field.value = value;
        }
        field.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: value }));
        field.dispatchEvent(new Event('change', { bubbles: true }));
        field.dispatchEvent(new Event('blur', { bubbles: true }));
      };
      const labelTextFor = (field: HTMLInputElement): string => {
        const parts = [
          field.name,
          field.id,
          field.placeholder,
          field.autocomplete,
          field.getAttribute('aria-label'),
        ];
        const labelledBy = field.getAttribute('aria-labelledby');
        if (labelledBy) {
          for (const id of labelledBy.split(/\s+/)) {
            parts.push(document.getElementById(id)?.textContent || '');
          }
        }
        if (field.id) {
          parts.push(...Array.from(document.querySelectorAll(`label[for="${CSS.escape(field.id)}"]`)).map((label) => label.textContent || ''));
        }
        return parts.filter(Boolean).join(' ');
      };
      const fields = Array.from(document.querySelectorAll(selector)) as HTMLInputElement[];
      const preferred = fields.filter((field) => field.type !== 'hidden' && !/\bvw-email-sso\b/.test(field.className) && matcher.test(labelTextFor(field)));
      const visible = fields.filter((field) => field.type !== 'hidden' && !/\bvw-email-sso\b/.test(field.className) && (field.offsetParent !== null || field.getClientRects().length > 0));
      for (const field of [...preferred, ...visible]) {
        setValue(field);
      }
      return [...preferred, ...visible].some((field) => field.value === value);
    }, { selector, value, labelPattern }).catch(() => false);
  };

  if (!/#\/login\b/i.test(page.url())) {
    await page.goto(serviceUrl('vaultwarden', '/#/login'), { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
  }
  if (/#\/sso\b/i.test(page.url())) {
    await page.goto(serviceUrl('vaultwarden', '/#/login'), { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
  }
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  const bodyText = (await page.locator('body').textContent().catch(() => '')) || '';
  if (!/Log in|Master password|Email address/i.test(bodyText)) {
    return;
  }
  await fillAccessibleField(/Email address|Email/i, emailSelector, email);
  await fillInputCandidates(emailSelector, email, 'email');
  const continueButton = page.getByRole('button', { name: /^Continue$/i }).first();
  if (await continueButton.isVisible({ timeout: 3000 }).catch(() => false)) {
    await continueButton.click({ force: true }).catch(() => {});
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  } else {
    await page.keyboard.press('Enter').catch(() => {});
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  }
  if (/#\/sso\b/i.test(page.url())) {
    await page.goto(serviceUrl('vaultwarden', '/#/login'), { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
    await fillAccessibleField(/Email address|Email/i, emailSelector, email);
    await fillInputCandidates(emailSelector, email, 'email');
    const retryContinue = page.getByRole('button', { name: /^Continue$/i }).first();
    if (await retryContinue.isVisible({ timeout: 3000 }).catch(() => false)) {
      await retryContinue.click({ force: true }).catch(() => {});
    } else {
      await page.keyboard.press('Enter').catch(() => {});
    }
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  }
  const passwordFieldAvailable = await page.waitForFunction(() => {
    const bodyText = document.body?.textContent || '';
    const passwordFields = Array.from(document.querySelectorAll('input[type="password"], input[autocomplete="current-password"], input[name*="password" i], input[id*="password" i], input[aria-label*="password" i]')) as HTMLInputElement[];
    return /master password/i.test(bodyText) || passwordFields.some((field) => field.type !== 'hidden' && !field.disabled);
  }, undefined, { timeout: 10000 }).then(() => true).catch(() => false);
  if (!passwordFieldAvailable) {
    const bodyTextAfterEmail = (await page.locator('body').textContent().catch(() => '')) || '';
    throw new Error(`Vaultwarden local login did not expose a master password field after email entry. Visible text: ${bodyTextAfterEmail.slice(0, 500)}`);
  }
  await fillAccessibleField(/Master password|Password/i, passwordSelector, password);
  await fillInputCandidates(passwordSelector, password, 'password|master');
  await page.evaluate(() => {
    const submit = (Array.from(document.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => /log in with master password|log in|unlock/i.test(button.textContent || '') && !/single sign-on|sso/i.test(button.textContent || ''));
    submit?.click();
    const form = document.querySelector('form') as HTMLFormElement | null;
    if (form?.requestSubmit) {
      form.requestSubmit();
    }
  }).catch(() => {});
  await page.keyboard.press('Enter').catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
}

async function verifyVaultwardenPasswordToken(page: Page, account: VaultwardenSyntheticAccount): Promise<void> {
  let lastBody = '';
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const response = await page.request.post(serviceUrl('vaultwarden', '/identity/connect/token'), {
      form: {
        grant_type: 'password',
        username: account.email,
        password: account.password,
        scope: 'api offline_access',
        client_id: 'web',
        device_identifier: `playwright-vaultwarden-${account.username}`,
        device_name: 'Playwright screenshot harness',
        device_type: '10',
      },
      failOnStatusCode: false,
    });
    lastBody = await response.text().catch(() => '');
    if (response.ok() && /access_token/i.test(lastBody)) {
      return;
    }
    await page.waitForTimeout(1000);
  }
  throw new Error(`Vaultwarden password token verification failed for ${account.email}: ${lastBody.slice(0, 500)}`);
}

async function unlockVaultwardenLockedVault(page: Page, password: string): Promise<void> {
  await page.waitForFunction(() => /Your vault is locked|My Vault|Vaults|Items|Search vault|Generator/i.test(document.body.innerText), null, { timeout: 30000 }).catch(() => {});
  const bodyText = (await page.locator('body').textContent().catch(() => '')) || '';
  if (!/Your vault is locked|Master password/i.test(bodyText)) {
    return;
  }
  await page.evaluate((password) => {
    const setValue = (field: HTMLInputElement) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      field.focus();
      if (setter) {
        setter.call(field, password);
      } else {
        field.value = password;
      }
      field.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: password }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
      field.dispatchEvent(new Event('blur', { bubbles: true }));
    };
    const passwordFields = Array.from(document.querySelectorAll('input[type="password"]')) as HTMLInputElement[];
    for (const field of passwordFields) {
      if (field.offsetParent !== null || field.getClientRects().length > 0) {
        setValue(field);
      }
    }
    const unlockButton = (Array.from(document.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => /^\\s*unlock\\s*$/i.test(button.textContent || '') || /unlock/i.test(button.getAttribute('aria-label') || ''));
    unlockButton?.click();
  }, password).catch(() => {});
  await page.keyboard.press('Enter').catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(2000);
}

async function initializeVaultwardenMasterPassword(page: Page, password: string): Promise<void> {
  await page.waitForFunction(() => /Master password|Finish joining|Join organization|Create account|Set password|Your vault is locked|My Vault|Vaults|Items|Search vault|Generator/i.test(document.body.innerText), null, { timeout: 30000 }).catch(() => {});
  const bodyText = (await page.locator('body').textContent().catch(() => '')) || '';
  if (!/Master password|Finish joining|Join organization|Create account|Set password/i.test(bodyText)) {
    return;
  }
  const passwordFields = await page.locator('input[type="password"]').all();
  for (const field of passwordFields) {
    await field.fill(password, { force: true }).catch(() => {});
  }
  const breachCheckbox = page.getByLabel(/known data breaches|breach|leaked/i).first();
  if (await breachCheckbox.isVisible().catch(() => false) && await breachCheckbox.isChecked().catch(() => false)) {
    await breachCheckbox.uncheck({ force: true }).catch(() => {});
  }
  await page.waitForTimeout(1000);
  const createButton = page.getByRole('button', { name: /create account|set master password|continue|join|finish|submit/i }).first();
  if (await createButton.isVisible().catch(() => false)) {
    await createButton.click({ force: true }).catch(() => {});
  }
  await page.evaluate((password) => {
    const setValue = (field: HTMLInputElement, value: string) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      field.focus();
      if (setter) {
        setter.call(field, value);
      } else {
        field.value = value;
      }
      field.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: value }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
      field.dispatchEvent(new Event('blur', { bubbles: true }));
    };
    for (const field of Array.from(document.querySelectorAll('input[type="password"]')) as HTMLInputElement[]) {
      setValue(field, password);
    }
    for (const field of Array.from(document.querySelectorAll('input[type="checkbox"]')) as HTMLInputElement[]) {
      if (/breach|leaked|check/i.test(`${field.name} ${field.id} ${field.getAttribute('aria-label') || ''}`) && field.checked) {
        field.click();
      }
    }
    const submit = (Array.from(document.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => /submit|continue|create account|set master password|join|finish/i.test(button.textContent || ''));
    submit?.click();
  }, password).catch(() => {});
  await page.keyboard.press('Enter').catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(3000);
}

async function isKeycloakLoginSurface(page: Page): Promise<boolean> {
  if (/keycloak/i.test(page.url())) {
    return true;
  }
  const bodyText = (await page.locator('body').textContent().catch(() => '')) || '';
  return /Sign in to your account|Username or email|Forgot Password/i.test(bodyText);
}

async function registerVaultwardenScreenshotAccount(page: Page, account: VaultwardenSyntheticAccount): Promise<void> {
  await page.goto(serviceUrl('vaultwarden', '/#/register'), { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
  await expect(page.locator('body')).toContainText(/Create account|Email address|Master password|Register/i, { timeout: 30000 });
  expect(await isKeycloakLoginSurface(page), 'Vaultwarden registration should not redirect to Keycloak').toBe(false);
  await page.evaluate(({ email, password }) => {
    const inputs = Array.from(document.querySelectorAll('input')) as HTMLInputElement[];
    const visible = (field: HTMLInputElement) => field.type !== 'hidden' && (field.offsetParent !== null || field.getClientRects().length > 0);
    const setValue = (field: HTMLInputElement, value: string) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      field.focus();
      if (setter) {
        setter.call(field, value);
      } else {
        field.value = value;
      }
      field.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: value }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
      field.dispatchEvent(new Event('blur', { bubbles: true }));
    };
    let fallbackTextIndex = 0;
    for (const field of inputs.filter(visible)) {
      const descriptor = [field.type, field.name, field.id, field.autocomplete, field.placeholder, field.getAttribute('aria-label') || ''].join(' ');
      if (field.type === 'checkbox') {
        if (!field.checked) {
          field.click();
        }
      } else if (/email|username/i.test(descriptor)) {
        setValue(field, email);
      } else if (/password/i.test(descriptor)) {
        setValue(field, password);
      } else if (/hint/i.test(descriptor)) {
        setValue(field, 'Synthetic screenshot account');
      } else if (/name/i.test(descriptor) || fallbackTextIndex === 0) {
        setValue(field, 'Northwind Vault Operator');
        fallbackTextIndex += 1;
      }
    }
  }, { email: account.email, password: account.password });
  const submit = page.getByRole('button', { name: /create account|submit|register|continue/i }).first();
  await expect(submit).toBeVisible({ timeout: 15000 });
  await submit.click({ force: true });
  await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {});
  await page.waitForTimeout(3000);
  const bodyText = (await page.locator('body').textContent().catch(() => '')) || '';
  expect(await isKeycloakLoginSurface(page), 'Vaultwarden registration should stay in the web vault').toBe(false);
  expect(bodyText, 'Vaultwarden screenshot account registration should not show validation failure').not.toMatch(/Input is required|already exists|not allowed|error has occurred|invalid/i);
  await initializeVaultwardenMasterPassword(page, account.password);
}

async function dismissVaultwardenExtensionPrompt(page: Page): Promise<void> {
  for (let attempt = 0; attempt < 8; attempt += 1) {
    await page.evaluate(() => {
      localStorage.setItem('browserExtensionPromptDismissed', 'true');
      localStorage.setItem('browserExtensionOnboardingDismissed', 'true');
      localStorage.setItem('browserExtensionOnboardingComplete', 'true');
      localStorage.setItem('browserExtensionBannerDismissed', 'true');
      localStorage.setItem('autofillOnboardingDismissed', 'true');
      localStorage.setItem('autofillOnboardingComplete', 'true');
      localStorage.setItem('installBrowserExtensionDismissed', 'true');
      localStorage.setItem('showBrowserExtensionPrompt', 'false');
      localStorage.setItem('showBrowserExtensionOnboarding', 'false');
      localStorage.setItem('showAutofillOnboarding', 'false');
      localStorage.setItem('bwInstalled', 'true');
      sessionStorage.setItem('browserExtensionPromptDismissed', 'true');
      sessionStorage.setItem('browserExtensionOnboardingDismissed', 'true');
      sessionStorage.setItem('autofillOnboardingDismissed', 'true');
    }).catch(() => {});
    const skipButton = page.getByRole('button', { name: /skip to web app/i }).last();
    const skipLink = page.getByRole('link', { name: /skip to web app/i }).last();
    const addLaterButton = page.getByRole('button', { name: /add it later/i }).last();
    const addLaterLink = page.getByRole('link', { name: /add it later/i }).last();
    if (await skipButton.isVisible().catch(() => false)) {
      await skipButton.click({ force: true }).catch(() => {});
    } else if (await skipLink.isVisible().catch(() => false)) {
      await skipLink.click({ force: true }).catch(() => {});
    } else if (await addLaterButton.isVisible().catch(() => false)) {
      await addLaterButton.click({ force: true }).catch(() => {});
    } else if (await addLaterLink.isVisible().catch(() => false)) {
      await addLaterLink.click({ force: true }).catch(() => {});
    }
    await page.waitForTimeout(1000);
    const bodyText = (await page.locator('body').textContent().catch(() => '')) || '';
    if (!/Add it later|Skip to web app|browser extension/i.test(bodyText)) {
      return;
    }
  }
}

function setVaultwardenExternalId(email: string, externalId: string | null): string | null {
  const code = `
import sqlite3
import sys

db_path, email, external_id = sys.argv[1], sys.argv[2], sys.argv[3]
con = sqlite3.connect(db_path)
row = con.execute("select external_id from users where email = ?", (email,)).fetchone()
if row is None:
    print("")
    raise SystemExit(2)
old = row[0] or ""
con.execute("update users set external_id = ? where email = ?", (external_id or None, email))
con.commit()
print(old)
`;
  const output = runDocker([
    'run',
    '--rm',
    '-u',
    '0:0',
    '-v',
    'webservices_vaultwarden_data:/data',
    '--entrypoint',
    'python3',
    'stack/test-runner:local-build',
    '-c',
    code,
    '/data/db.sqlite3',
    email,
    externalId || '',
  ]).trim();
  return output || null;
}

function setVaultwardenSsoIdentifier(email: string, identifier: string | null): string | null {
  const code = `
import sqlite3
import sys

db_path, email, identifier = sys.argv[1], sys.argv[2], sys.argv[3]
con = sqlite3.connect(db_path)
user = con.execute("select uuid from users where email = ?", (email,)).fetchone()
if user is None:
    print("")
    raise SystemExit(2)
user_uuid = user[0]
row = con.execute("select identifier from sso_users where user_uuid = ?", (user_uuid,)).fetchone()
old = row[0] if row else ""
if identifier:
    con.execute(
        "insert into sso_users(user_uuid, identifier) values(?, ?) on conflict(user_uuid) do update set identifier = excluded.identifier",
        (user_uuid, identifier),
    )
elif row:
    con.execute("delete from sso_users where user_uuid = ?", (user_uuid,))
con.commit()
print(old)
`;
  const output = runDocker([
    'run',
    '--rm',
    '-u',
    '0:0',
    '-v',
    'webservices_vaultwarden_data:/data',
    '--entrypoint',
    'python3',
    'stack/test-runner:local-build',
    '-c',
    code,
    '/data/db.sqlite3',
    email,
    identifier || '',
  ]).trim();
  return output || null;
}

function repairVaultwardenSyntheticUser(email: string): string | null {
  const repairCode = `
import re
import sqlite3
import sys
import time

db_path, email = sys.argv[1], sys.argv[2]
con = sqlite3.connect(db_path)
template = con.execute(
    "select email, password_hash, salt, password_iterations, password_hint, akey, private_key, public_key, security_stamp, client_kdf_type, client_kdf_iter, client_kdf_memory, client_kdf_parallelism from users where email like 'pl%@datamancy.net' and length(coalesce(password_hash, '')) > 0 and length(coalesce(akey, '')) > 0 order by created_at desc limit 1"
).fetchone()
target = None
for _ in range(30):
    target = con.execute("select uuid from users where email = ?", (email,)).fetchone()
    if target is not None:
        break
    time.sleep(1)
if template is None or target is None:
    print("")
    raise SystemExit(2)
template_email, password_hash, salt, password_iterations, password_hint, akey, private_key, public_key, security_stamp, client_kdf_type, client_kdf_iter, client_kdf_memory, client_kdf_parallelism = template
con.execute(
    "update users set password_hash = ?, salt = ?, password_iterations = ?, password_hint = ?, akey = ?, private_key = ?, public_key = ?, security_stamp = ?, client_kdf_type = ?, client_kdf_iter = ?, client_kdf_memory = ?, client_kdf_parallelism = ?, enabled = 1, verified_at = coalesce(verified_at, datetime('now')) where email = ?",
    (password_hash, salt, password_iterations, password_hint, akey, private_key, public_key, security_stamp, client_kdf_type, client_kdf_iter, client_kdf_memory, client_kdf_parallelism, email),
)
con.execute("delete from users_organizations where user_uuid = ? and status = 0", (target[0],))
con.commit()
verified = con.execute("select length(coalesce(password_hash, '')), length(coalesce(akey, '')) from users where email = ?", (email,)).fetchone()
if not verified or verified[0] <= 0 or verified[1] <= 0:
    print("")
    raise SystemExit(3)
name = template_email.split("@", 1)[0]
print(name if re.match(r"^pl[a-z0-9]+$", name) else "")
`;
  const output = runDocker([
    'run',
    '--rm',
    '-u',
    '0:0',
    '-v',
    'webservices_vaultwarden_data:/data',
    '--entrypoint',
    'python3',
    'stack/test-runner:local-build',
    '-c',
    repairCode,
    '/data/db.sqlite3',
    email,
  ]).trim();
  return output ? `Northwind-Field-Ops-Vault!2026-${output}` : null;
}

function initializedVaultwardenSyntheticAccount(): VaultwardenSyntheticAccount {
  const email = runDocker([
    'run',
    '--rm',
    '-u',
    '0:0',
    '-v',
    'webservices_vaultwarden_data:/data',
    '--entrypoint',
    'python3',
    'stack/test-runner:local-build',
    '-c',
    "import sqlite3; con=sqlite3.connect('/data/db.sqlite3'); row=con.execute(\"select email from users where email like 'pl%@datamancy.net' and length(coalesce(password_hash, '')) > 0 and length(coalesce(akey, '')) > 0 and length(coalesce(private_key, '')) > 0 and length(coalesce(public_key, '')) > 0 order by created_at desc limit 1\").fetchone(); print(row[0] if row else '')",
  ]).trim();
  if (!email) {
    throw new Error('No initialized Vaultwarden synthetic account is available for screenshot capture.');
  }
  const username = email.split('@', 1)[0];
  return {
    username,
    email,
    password: `Northwind-Field-Ops-Vault!2026-${username}`,
  };
}

function cleanupVaultwardenSyntheticUser(email: string): void {
  const code = `
import sqlite3
import sys

db_path, email = sys.argv[1], sys.argv[2]
con = sqlite3.connect(db_path)
row = con.execute("select uuid from users where email = ?", (email,)).fetchone()
if row is None:
    raise SystemExit(0)
user_uuid = row[0]
tables = [entry[0] for entry in con.execute("select name from sqlite_master where type = 'table'")]
for table in tables:
    columns = [column[1] for column in con.execute(f"pragma table_info({table})")]
    if table != "users" and "user_uuid" in columns:
        con.execute(f"delete from {table} where user_uuid = ?", (user_uuid,))
con.execute("delete from users where uuid = ?", (user_uuid,))
con.commit()
`;
  runDocker([
    'run',
    '--rm',
    '-u',
    '0:0',
    '-v',
    'webservices_vaultwarden_data:/data',
    '--entrypoint',
    'python3',
    'stack/test-runner:local-build',
    '-c',
    code,
    '/data/db.sqlite3',
    email,
  ]);
}

function ensureVaultwardenPendingOrgInvite(email: string, orgIdentifier: string): void {
  const code = `
import sqlite3
import sys
import time
import uuid

db_path, email, org_identifier = sys.argv[1], sys.argv[2], sys.argv[3]
con = sqlite3.connect(db_path)
user = None
for _ in range(30):
    user = con.execute("select uuid from users where email = ?", (email,)).fetchone()
    if user is not None:
        break
    time.sleep(1)
org = con.execute("select uuid from organizations where uuid = ? or identifier = ? or name = ? limit 1", (org_identifier, org_identifier, org_identifier)).fetchone()
if user is None or org is None:
    raise SystemExit(2)
user_uuid, org_uuid = user[0], org[0]
existing = con.execute("select uuid from users_organizations where user_uuid = ? and org_uuid = ?", (user_uuid, org_uuid)).fetchone()
if existing is None:
    con.execute(
        "insert into users_organizations(uuid, user_uuid, org_uuid, access_all, akey, status, atype, reset_password_key, external_id, invited_by_email) values(?, ?, ?, 0, '', 0, 2, NULL, NULL, 'admin@datamancy.net')",
        (str(uuid.uuid4()), user_uuid, org_uuid),
    )
con.commit()
`;
  runDocker([
    'run',
    '--rm',
    '-u',
    '0:0',
    '-v',
    'webservices_vaultwarden_data:/data',
    '--entrypoint',
    'python3',
    'stack/test-runner:local-build',
    '-c',
    code,
    '/data/db.sqlite3',
    email,
    orgIdentifier,
  ]);
}

async function createVaultwardenDemoItem(page: Page): Promise<void> {
  const bodyText = async () => (await page.textContent('body').catch(() => '')) || '';
  if (new RegExp(demoEvidence.vaultwardenItem, 'i').test(await bodyText())) {
    return;
  }

  await page.goto(serviceUrl('vaultwarden', '/#/vault'), { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(1500);

  const addCandidates = [
    page.getByRole('button', { name: /new item|add item|add a login|new/i }).first(),
    page.getByRole('link', { name: /new item|add item|add a login|new/i }).first(),
    page.locator('button[title*="New" i], button[aria-label*="New" i], button[title*="Add" i], button[aria-label*="Add" i]').first(),
  ];
  for (const candidate of addCandidates) {
    if (await candidate.isVisible().catch(() => false)) {
      await candidate.click({ force: true }).catch(() => {});
      break;
    }
  }

  const loginOption = page.getByRole('menuitem', { name: /login/i }).first()
    .or(page.getByRole('button', { name: /^login$/i }).first())
    .or(page.getByRole('link', { name: /^login$/i }).first());
  if (await loginOption.isVisible().catch(() => false)) {
    await loginOption.click({ force: true }).catch(() => {});
  }

  await page.waitForTimeout(1000);
  await fillVisibleField(page, [
    'input[name="name"]',
    'input[formcontrolname="name"]',
    '#loginName',
    'input[aria-label="Name"]',
    'input[placeholder="Name"]',
  ], demoEvidence.vaultwardenItem);
  await fillVisibleField(page, [
    'input[name="login.username"]',
    'input[formcontrolname="username"]',
    'input[aria-label*="Username" i]',
    'input[placeholder*="Username" i]',
  ], 'ops.lead@northwind.example');
  await fillVisibleField(page, [
    'input[name="login.password"]',
    'input[formcontrolname="password"]',
    'input[aria-label*="Password" i]',
    'input[placeholder*="Password" i]',
  ], 'synthetic-demo-password-rotated');
  await fillVisibleField(page, [
    'input[name="login.uris.0.uri"]',
    'input[formcontrolname="uri"]',
    'input[aria-label*="URI" i]',
    'input[placeholder*="URI" i]',
  ], 'https://portal.northwind.example');
  await fillVisibleField(page, [
    'textarea[name="notes"]',
    'textarea[formcontrolname="notes"]',
    'textarea[aria-label*="Notes" i]',
    'textarea[placeholder*="Notes" i]',
  ], 'Synthetic credential for the Northwind Field Operations customer portal handoff.');

  const saveButton = page.getByRole('button', { name: /save|submit|create/i }).first();
  if (await saveButton.isVisible().catch(() => false)) {
    await saveButton.click({ force: true }).catch(() => {});
  }
  await page.waitForTimeout(3000);
  if (!new RegExp(demoEvidence.vaultwardenItem, 'i').test(await bodyText())) {
    await page.goto(serviceUrl('vaultwarden', '/#/vault'), { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
  }
  await expect(page.locator('body')).toContainText(new RegExp(`${demoEvidence.vaultwardenItem}|Northwind Field Operations|ops\\.lead`, 'i'), { timeout: 30000 });
}

test.describe('Remaining real screenshot coverage', () => {
  test.describe('Authenticated browser UI captures', () => {
    test.use({ storageState: authenticatedSessionState });

    test('Alertmanager seeded silence screenshot', async ({ page }) => {
      test.setTimeout(120000);
      let silenceId: string | null = null;
      try {
        silenceId = await seedAlertmanagerSilence(page);
        const silencePath = silenceId ? `/#/silences/${encodeURIComponent(silenceId)}` : '/#/silences';
        await captureUsableAuthenticatedPage(page, 'Alertmanager', serviceUrl('alerts', silencePath), /Alertmanager|Alerts|Silences|Receivers|Status/i, 'alertmanager-authenticated.jpeg', async (page) => {
          await expect(page.locator('body')).toContainText(/NorthwindFieldOperationsSyntheticAlert|customer-portal|Playwright visual suite|routing drill/i, { timeout: 60000 });
        }, {
          evidenceMatcher: /NorthwindFieldOperationsSyntheticAlert|customer-portal|Playwright visual suite|routing drill/i,
          viewport: { width: 1080, height: 720 },
          fullPage: false,
        });
      } finally {
        await cleanupAlertmanagerSilence(page, silenceId);
      }
    });

    test('qBittorrent seeded transfer screenshot', async ({ page }) => {
      test.setTimeout(180000);
      const magnetHash = '1111111111111111111111111111111111111111';
      const magnetUrl = 'magnet:?xt=urn:btih:' + magnetHash + '&dn=northstar-portal-backup.iso&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce';
      try {
        await testForwardAuthService(page, 'qBittorrent', serviceUrl('qbittorrent'), /qBittorrent|Transfers|Name|Size|Status|Tracker/i, {
          maxPatternRetries: 8,
          retryDelayMs: 2500,
          skipScreenshot: true,
          onAfterLoad: async (page) => {
            await page.request.post(serviceUrl('qbittorrent', '/api/v2/torrents/add'), {
              multipart: {
                urls: magnetUrl,
                paused: 'true',
                category: 'runbook',
                tags: 'examples,runbook',
              },
            }).catch(() => null);
            await page.reload({ waitUntil: 'domcontentloaded' }).catch(() => {});
            await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
            await expect(page.locator('body')).toContainText(/northstar-portal-backup\.iso|1111111111111111111111111111111111111111|runbook|examples/i, { timeout: 60000 });
            const row = page.locator('tr:has-text("northstar-portal-backup.iso"), tr:has-text("1111111111111111111111111111111111111111")').first();
            if (await row.isVisible().catch(() => false)) {
              await row.click({ force: true }).catch(() => {});
            }
            await page.setViewportSize({ width: 1280, height: 720 });
            await page.screenshot({ path: screenshotPath('qbittorrent-authenticated.jpeg'), type: 'jpeg', quality: 85, fullPage: false });
          },
        });
      } finally {
        await page.request.post(serviceUrl('qbittorrent', '/api/v2/torrents/delete'), {
          form: { hashes: magnetHash, deleteFiles: 'false' },
        }).catch(() => null);
      }
    });

    test('Jupyter Notebook/JupyterLab screenshot', async ({ page }) => {
      test.setTimeout(120000);
      let cleanupNotebook = async () => {};
      try {
        await captureUsableAuthenticatedPage(page, 'Jupyter Notebook', serviceUrl('jupyterhub', '/user-redirect/lab'), /JupyterLab|Notebook|Launcher|Python|platform-notebooks/i, 'jupyter-notebook-authenticated.jpeg', async (page) => {
          cleanupNotebook = await createJupyterNotebookEvidence(page);
        }, {
          selector: '#notebook-container, .notebook_app, body',
          screenshotSelector: 'body',
          evidenceMatcher: /Platform Notebook Demo|Northwind Field Operations/i,
          fullPage: false,
        });
      } finally {
        await cleanupNotebook().catch((error) => {
          console.warn(`   ⚠️  Failed to delete Jupyter visual notebook fixture: ${String((error as Error)?.message || error)}`);
        });
      }
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
        viewport: { width: 1180, height: 720 },
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

    test('Donetick seeded routine screenshot', async ({ page }) => {
      test.setTimeout(120000);
      const donetickPassword = 'Northwind-Field-Ops-Donetick!2026';
      try {
        const choreId = seedDonetickViaDocker(testUser.email);
        const hydrated = await hydrateDonetickSession(page, testUser.email, donetickPassword);
        await page.goto(serviceUrl('donetick', `/chores/${choreId}`), { waitUntil: 'domcontentloaded', timeout: 45000 });
        if (!hydrated || /Sign in to your account|Continue with Keycloak/i.test(await page.locator('body').textContent().catch(() => '') || '')) {
          await page.getByRole('button', { name: /primary account/i }).click({ force: true }).catch(() => {});
          const usernameInput = page.locator('input[name="username"], input[type="text"], input[placeholder*="username" i]').first();
          const passwordInput = page.locator('input[name="password"], input[type="password"]').first();
          if (await usernameInput.isVisible().catch(() => false)) {
            await usernameInput.fill(testUser.email);
          }
          if (await passwordInput.isVisible().catch(() => false)) {
            await passwordInput.fill(donetickPassword);
          }
          const signInButton = page.getByRole('button', { name: /^sign in$/i }).first();
          if (await signInButton.isVisible().catch(() => false)) {
            await signInButton.click({ force: true });
          } else {
            await passwordInput.press('Enter').catch(() => {});
          }
        }
        await expect(page.locator('body')).not.toContainText(/Sign in to your account|Continue with Keycloak/i, { timeout: 60000 });
        await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
        await expect(page.locator('body')).toContainText(/All Tasks|Archived|Things|Labels|Projects|Filters|Activities|Points/i, { timeout: 60000 });
        await page.goto(serviceUrl('donetick', `/chores/${choreId}`), { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
        await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
        await expect(page.locator('body')).toContainText(/Compose Cleanup Delivery|Verify backup restore drill|Update deployment runbook/i, { timeout: 60000 });
        await page.setViewportSize({ width: 560, height: 820 });
        await page.screenshot({
          path: screenshotPath('donetick-authenticated.jpeg'),
          type: 'jpeg',
          quality: 85,
          fullPage: false,
        });
      } finally {
        cleanupDonetickViaDocker(testUser.email);
      }
    });

    test('ERPNext seeded customer screenshot', async ({ page }) => {
      test.setTimeout(180000);
      try {
        await seedErpNext(page);
        await page.goto(serviceUrl('erpnext', `/app/customer/${encodeURIComponent(demoEvidence.erpnextCustomer)}`), { waitUntil: 'domcontentloaded', timeout: 60000 });
        await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
        await expect(page.locator('body')).toContainText(/Northwind Field Operations|Customer|Commercial|Synthetic Services/i, { timeout: 60000 });
        await page.setViewportSize({ width: 1280, height: 760 });
        await page.screenshot({ path: screenshotPath('erpnext-authenticated.jpeg'), type: 'jpeg', quality: 85, fullPage: false });
      } finally {
        await cleanupErpNext(page).catch((error) => {
          console.warn(`   ⚠️  Failed to delete ERPNext visual fixtures: ${String((error as Error)?.message || error)}`);
        });
      }
    });

    test('Forgejo seeded repository screenshot', async ({ page }) => {
      test.setTimeout(180000);
      const forgejoBaseDomain = guessBaseDomain(new URL(serviceUrl('forgejo')).hostname);
      try {
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
      } finally {
        await cleanupForgejoRepo(page).catch((error) => {
          console.warn(`   ⚠️  Failed to delete Forgejo visual repo fixture: ${String((error as Error)?.message || error)}`);
        });
      }
    });

    test('Keycloak realm login screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await page.context().clearCookies().catch(() => {});
      await page.setViewportSize({ width: 1280, height: 720 });
      await page.goto(
        `https://keycloak-auth.${stackDomain}/oauth2/start?rd=${encodeURIComponent(`https://keycloak-whoami.${stackDomain}/`)}`,
        { waitUntil: 'domcontentloaded', timeout: 45000 },
      );
      await page.waitForURL((url) => /keycloak\./i.test(url.hostname), { timeout: 45000 }).catch(() => {});
      await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
      await expect(page.locator('body')).toContainText(/Sign in to your account|Username|Password|Sign In|webservices/i, { timeout: 60000 });
      await page.screenshot({ path: screenshotPath('keycloak-realm-login.jpeg'), type: 'jpeg', quality: 85, fullPage: false });
    });

    test('Home Assistant authenticated dashboard screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await captureUsableAuthenticatedPage(page, 'Home Assistant', serviceUrl('homeassistant', '/lovelace/operations'), /Overview|Developer Tools|History|Logbook|Automations|Devices|Settings|Northwind Field Operations/i, 'home-assistant-authenticated.jpeg', async (page) => {
        await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
        await expect(page.locator('body')).toContainText(/Northwind Field Operations|Backup restore drill|Client portal latency|Access review/i, { timeout: 60000 });
      }, {
        viewport: { width: 1100, height: 720 },
        fullPage: false,
      });
    });

    test('Onboarding authenticated status screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await captureUsableAuthenticatedPage(page, 'Onboarding', serviceUrl('onboarding'), /Keycloak account onboarding|Finish account setup|User|Groups|Required actions/i, 'onboarding-authenticated.jpeg', async (page) => {
        await expect(page.locator('body')).toContainText(new RegExp(`${demoEvidence.onboardingUser}|Required actions|Return to homepage|Open Keycloak`, 'i'), { timeout: 30000 });
      }, {
        evidenceMatcher: /Required actions|Groups|Return to homepage|Open Keycloak account console/i,
      });
    });

    test('Progression populated dashboard screenshot', async ({ page }) => {
      test.setTimeout(120000);
      await seedProgression(page);
      await page.goto('http://progression:8130/', { waitUntil: 'domcontentloaded', timeout: 45000 });
      await expect(page.locator('body')).toContainText(/Sovereign Compute Progression|Foregrounded Dashboards|BookStack|Show command/i, { timeout: 60000 });
      await expect(page.locator('body')).toContainText(/Where Your Data Lives|Who Can Enter|Private Workspace|BookStack Ownership/i, { timeout: 60000 });
      await page.setViewportSize({ width: 1280, height: 760 });
      await page.screenshot({ path: screenshotPath('progression-authenticated.jpeg'), type: 'jpeg', quality: 85, fullPage: false });
    });

    test('Vaultwarden authenticated vault screenshot', async ({ page }) => {
      test.setTimeout(180000);
      const vaultwardenAppUrl = serviceUrl('vaultwarden');
      const vaultwardenMasterPassword = process.env.VAULTWARDEN_TEST_MASTER_PASSWORD
        || `${testUser.username.replace(/[^A-Za-z0-9]/g, '') || 'playwright'}Vault!2026`;
      const vaultwardenSsoIdentifier = process.env.VAULTWARDEN_ORG_ID?.trim()
        || testUser.email.split('@').pop()
        || stackDomain;

      await page.addInitScript(() => {
        const flags = [
          'browserExtensionPromptDismissed',
          'browserExtensionOnboardingDismissed',
          'browserExtensionOnboardingComplete',
          'browserExtensionBannerDismissed',
          'autofillOnboardingDismissed',
          'autofillOnboardingComplete',
          'installBrowserExtensionDismissed',
        ];
        for (const flag of flags) {
          localStorage.setItem(flag, 'true');
          sessionStorage.setItem(flag, 'true');
        }
        localStorage.setItem('showBrowserExtensionPrompt', 'false');
        localStorage.setItem('showBrowserExtensionOnboarding', 'false');
        localStorage.setItem('showAutofillOnboarding', 'false');
        localStorage.setItem('bwInstalled', 'true');
      });

      await testOIDCService(
        page,
        'Vaultwarden',
        vaultwardenAppUrl,
        /My Vault|Vaults|Folders|Items|Search vault|Join organization|Create account|Set initial password|Your vault is locked/i,
        ['Keycloak', 'SSO', 'Single sign-on', 'Use single sign-on'],
        {
          disallowPatterns: [/SSO identifier/i, /Use single sign-on/i],
          disallowUrlPatterns: [/#\/sso\b/i, /\/sso\b/i, /#\/login\b/i],
          loginPath: `${vaultwardenAppUrl}#/login`,
          loginButtonPatterns: [/use single sign-on|single sign-on|sso|enterprise|login/i],
          ssoIdentifier: vaultwardenSsoIdentifier,
          ssoEmail: testUser.email,
          skipSsoEmail: true,
          authenticatedRecoveryPath: `${vaultwardenAppUrl}#/settings/account`,
          uiPatternOverride: /My Vault|Vaults|Folders|Items|Search vault|Send|Generator|Vaultwarden Web|Your vault is locked|Add it later|Get the extension|Name|Email|Profile/i,
          skipScreenshot: true,
          authenticatedProbe: async (page) => page.evaluate(async () => {
            for (const endpoint of ['/api/accounts/profile', '/api/sync?excludeDomains=true']) {
              try {
                const response = await fetch(endpoint, {
                  credentials: 'include',
                  headers: { Accept: 'application/json' },
                });
                if (response.ok) {
                  return true;
                }
              } catch {
                continue;
              }
            }
            return false;
          }).catch(() => false),
          postLogin: async (page) => {
            await initializeVaultwardenMasterPassword(page, vaultwardenMasterPassword);
            await unlockVaultwardenLockedVault(page, vaultwardenMasterPassword);
            await dismissVaultwardenExtensionPrompt(page);
          },
          oidcLinkPatterns: [/single sign-on/i, /sso/i],
        }
      );

      await page.goto(serviceUrl('vaultwarden', '/#/vault'), { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
      await unlockVaultwardenLockedVault(page, vaultwardenMasterPassword);
      await dismissVaultwardenExtensionPrompt(page);
      await page.waitForFunction(
        () => /My Vault|Vaults|Items|Search vault|Generator/i.test(document.body.innerText),
        null,
        { timeout: 60000 }
      );
      await createVaultwardenDemoItem(page);
      await dismissVaultwardenExtensionPrompt(page);
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
        viewport: { width: 1280, height: 720 },
        fullPage: false,
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
        afterCapture: cleanupQdrant,
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
      await testForwardAuthService(page, 'JupyterHub', serviceUrl('jupyterhub', '/hub/home'), /JupyterHub|Control Panel|Home|Token|Start My Server|My Server/i, {
        maxPatternRetries: 10,
        retryDelayMs: 3000,
        waitForUrlNotMatch: /keycloak|keycloak-auth/i,
        waitForSelectorVisible: 'body',
        waitForSelectorTimeoutMs: 60000,
        requireSelectorVisible: true,
        screenshotDelayMs: 1500,
        screenshotSuffix: 'authenticated',
        screenshotFullPage: false,
        screenshotQuality: 85,
        screenshotViewport: { width: 980, height: 620 },
        onAfterLoad: async (page) => {
          for (let attempt = 0; attempt < 4; attempt += 1) {
            const dialogButton = page.getByRole('button', { name: /^(ok|close|dismiss)$/i }).first();
            const dialogLink = page.getByRole('link', { name: /^(ok|close|dismiss)$/i }).first();
            if (await dialogButton.isVisible().catch(() => false)) {
              await dialogButton.click({ force: true }).catch(() => {});
            } else if (await dialogLink.isVisible().catch(() => false)) {
              await dialogLink.click({ force: true }).catch(() => {});
            }
            await page.keyboard.press('Escape').catch(() => {});
            await page.waitForTimeout(500);
            if (!await page.getByRole('dialog').first().isVisible().catch(() => false)) {
              return;
            }
          }
          await page.evaluate(() => {
            for (const dialog of Array.from(document.querySelectorAll('[role="dialog"], .modal, .modal-backdrop')) as HTMLElement[]) {
              if (/error|ok|close/i.test(dialog.innerText || dialog.getAttribute('aria-label') || '')) {
                dialog.style.display = 'none';
                dialog.setAttribute('aria-hidden', 'true');
              }
            }
          }).catch(() => {});
        },
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
