import { expect, Page, test } from '@playwright/test';
import * as fs from 'fs';
import * as http from 'http';
import * as path from 'path';
import type { AddressInfo } from 'net';

type TaskDefinition = {
  id: string;
  track: string;
  stage: string;
  title: string;
  summary: string;
  requires?: string[];
  surfaces: {
    now: string;
    why: string;
    deeper?: string[];
  };
  commands?: {
    guided?: string[];
    verify?: string[];
  };
  evidence: {
    required?: string[];
  };
  reward: {
    name: string;
    capability: string;
  };
  riskReduction?: Record<string, string>;
};

type DashboardDefinition = {
  id: string;
  title: string;
  stage: string;
  density: string;
  foregroundWhen?: string[];
  recommendedAfter?: string[];
  panels?: {
    beginner?: string[];
    operator?: string[];
    expert?: string[];
  };
  commands?: string[];
  forbiddenByDefault?: string[];
};

type TaskView = TaskDefinition & {
  status: 'complete' | 'available' | 'blocked';
  missingEvidence: string[];
  blockedBy: string[];
  rewardUnlocked: boolean;
};

type DashboardView = DashboardDefinition & {
  foregrounded: boolean;
};

type ProgressionView = {
  generatedAt: string;
  primaryNextTask: TaskView | null;
  tasks: TaskView[];
  dashboards: DashboardView[];
  rewardsUnlocked: string[];
  actual: { generatedAt: string; claims: Record<string, boolean>; facts: Record<string, unknown> };
  verified: { generatedAt: string; claims: Record<string, boolean>; facts: Record<string, unknown> };
  progress: { generatedAt: string; stackRewards: string[]; userEvents: string[] };
};

const repoRoot = path.resolve(__dirname, '../../../../..');
function repoFixturePath(relativePath: string): string {
  const candidates = [
    path.join(repoRoot, relativePath),
    path.join('/app/repo-fixtures', relativePath),
  ];
  const found = candidates.find((candidate) => fs.existsSync(candidate));
  if (!found) {
    throw new Error(`missing progression visual fixture ${relativePath}; tried ${candidates.join(', ')}`);
  }
  return found;
}

const progressionHtmlPath = repoFixturePath('stack.kotlin/progression/src/main/resources/static/index.html');
const taskCatalogPath = repoFixturePath('stack.config/progression/tasks/bookstack-mvp.json');
const dashboardCatalogPath = repoFixturePath('stack.config/progression/dashboards/bookstack-mvp.json');
const screenshotRoot = process.env.PLAYWRIGHT_SCREENSHOTS_DIR || path.resolve(process.cwd(), 'test-results/screenshots');
const screenshotDir = path.join(screenshotRoot, 'progression');

function readCatalogs(): { tasks: TaskDefinition[]; dashboards: DashboardDefinition[] } {
  const tasks = JSON.parse(fs.readFileSync(taskCatalogPath, 'utf-8')).tasks as TaskDefinition[];
  const dashboards = JSON.parse(fs.readFileSync(dashboardCatalogPath, 'utf-8')).dashboards as DashboardDefinition[];
  return { tasks, dashboards };
}

function buildView(claims: Record<string, boolean>): ProgressionView {
  const { tasks, dashboards } = readCatalogs();
  const completed = new Set<string>();
  const taskViews: TaskView[] = tasks.map((task) => {
    const missingEvidence = (task.evidence.required || []).filter((claim) => claims[claim] !== true);
    const blockedBy = (task.requires || []).filter((requiredTaskId) => !completed.has(requiredTaskId));
    const evidenceComplete = missingEvidence.length === 0;
    const status: TaskView['status'] = evidenceComplete ? 'complete' : blockedBy.length === 0 ? 'available' : 'blocked';
    if (evidenceComplete) {
      completed.add(task.id);
    }
    return {
      ...task,
      commands: task.commands || {},
      riskReduction: task.riskReduction || {},
      status,
      missingEvidence,
      blockedBy,
      rewardUnlocked: evidenceComplete,
    };
  });

  const firstIncomplete = taskViews.find((task) => task.status !== 'complete');
  const dashboardViews: DashboardView[] = dashboards.map((dashboard, index) => {
    const foregroundWhen = dashboard.foregroundWhen || [];
    const recommendedAfter = dashboard.recommendedAfter || [];
    const foregrounded =
      (foregroundWhen.length === 0 && index === 0) ||
      foregroundWhen.some((claimOrTask) => completed.has(claimOrTask) || claims[claimOrTask] === true) ||
      recommendedAfter.includes(firstIncomplete?.id || '');
    return {
      ...dashboard,
      panels: dashboard.panels || {},
      commands: dashboard.commands || [],
      forbiddenByDefault: dashboard.forbiddenByDefault || [],
      foregrounded,
    };
  });

  const rewardsUnlocked = taskViews
    .filter((task) => task.rewardUnlocked)
    .map((task) => task.reward.name)
    .filter((reward, index, rewards) => rewards.indexOf(reward) === index);

  return {
    generatedAt: '2026-05-28T00:00:00Z',
    primaryNextTask: taskViews.find((task) => task.status === 'available') || null,
    tasks: taskViews,
    dashboards: dashboardViews,
    rewardsUnlocked,
    actual: { generatedAt: '2026-05-28T00:00:00Z', claims, facts: {} },
    verified: { generatedAt: '2026-05-28T00:00:00Z', claims, facts: {} },
    progress: { generatedAt: '2026-05-28T00:00:00Z', stackRewards: rewardsUnlocked, userEvents: [] },
  };
}

async function startProgressionServer(view: ProgressionView): Promise<{ url: string; close: () => Promise<void> }> {
  const html = fs.readFileSync(progressionHtmlPath, 'utf-8');
  const server = http.createServer((request, response) => {
    const requestUrl = request.url || '/';
    if (request.method === 'GET' && (requestUrl === '/' || requestUrl.startsWith('/?'))) {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(html);
      return;
    }

    if (request.method === 'GET' && requestUrl.startsWith('/api/progress')) {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify(view));
      return;
    }

    if (request.method === 'POST' && requestUrl.startsWith('/api/scan')) {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ status: 'ok' }));
      return;
    }

    response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    response.end('not found');
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${address.port}/`,
    close: () => new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve())),
  };
}

async function openProgression(page: Page, view: ProgressionView, viewport: { width: number; height: number }): Promise<{ close: () => Promise<void> }> {
  const server = await startProgressionServer(view);
  await page.setViewportSize(viewport);
  await page.goto(server.url, { waitUntil: 'networkidle' });
  await expect(page.getByRole('heading', { name: 'Sovereign Compute Progression' })).toBeVisible();
  await expect(page.getByTestId('primary-next-action')).toBeVisible();
  return server;
}

async function saveScreenshot(page: Page, filename: string): Promise<string> {
  fs.mkdirSync(screenshotDir, { recursive: true });
  const screenshotPath = path.join(screenshotDir, filename);
  await page.screenshot({ path: screenshotPath, fullPage: true });
  return screenshotPath;
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, 'page should not have horizontal overflow').toBeLessThanOrEqual(1);
}

async function expectNoControlTextClipping(page: Page): Promise<void> {
  const clipped = await page.evaluate(() => {
    const selectors = 'button, .status-chip, .status-pill, .risk-chip, .panel-chip';
    return Array.from(document.querySelectorAll<HTMLElement>(selectors))
      .filter((element) => {
        const style = window.getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
      })
      .filter((element) => element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1)
      .map((element) => element.textContent?.trim() || element.tagName);
  });
  expect(clipped, 'controls and chips should not clip text').toEqual([]);
}

async function expectNoTopLevelCardOverlap(page: Page): Promise<void> {
  const overlaps = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll<HTMLElement>('[data-visual-card]'))
      .filter((element) => {
        const style = window.getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
      })
      .map((element, index) => {
        const rect = element.getBoundingClientRect();
        return {
          index,
          label: element.textContent?.trim().replace(/\s+/g, ' ').slice(0, 48) || element.tagName,
          left: rect.left,
          top: rect.top,
          right: rect.right,
          bottom: rect.bottom,
        };
      });

    const results: string[] = [];
    for (let i = 0; i < cards.length; i += 1) {
      for (let j = i + 1; j < cards.length; j += 1) {
        const a = cards[i];
        const b = cards[j];
        const horizontal = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        const vertical = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (horizontal > 1 && vertical > 1) {
          results.push(`${a.index}:${a.label} overlaps ${b.index}:${b.label}`);
        }
      }
    }
    return results;
  });
  expect(overlaps, 'top-level visual cards should not overlap').toEqual([]);
}

async function expectNoVisibleCommands(page: Page): Promise<void> {
  const visibleCommands = await page.evaluate(() => Array.from(document.querySelectorAll<HTMLElement>('.command'))
    .filter((element) => {
      const style = window.getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    })
    .map((element) => element.textContent?.trim() || 'command'));
  expect(visibleCommands, 'stackctl commands should be hidden until explicitly revealed').toEqual([]);
}

const freshClaims: Record<string, boolean> = {};

const bookstackMappedClaims: Record<string, boolean> = {
  'actual.route.progression.exists': true,
  'actual.service.bookstack.defined': true,
  'actual.route.bookstack.exists': true,
  'actual.persistence.bookstack.volume_mapped': true,
  'actual.persistence.bookstack.database_mapped': true,
};

const restoreProvenClaims: Record<string, boolean> = {
  ...bookstackMappedClaims,
  'verified.access.bookstack.route_defined': true,
  'verified.access.bookstack.oidc_client_defined': true,
  'verified.access.bookstack.oauth_configured': true,
  'verified.access.bookstack.anonymous_denied': true,
  'verified.restore.bookstack.backup_artifact_found': true,
  'verified.restore.bookstack.database_imported': true,
  'verified.restore.bookstack.healthcheck_passed': true,
  'verified.restore.bookstack.cleanup_completed': true,
};

test.describe('Progression Dashboard Visual Fidelity', () => {
  test('fresh workspace keeps one clear next action and no raw internals', async ({ page }) => {
    const server = await openProgression(page, buildView(freshClaims), { width: 1440, height: 900 });
    try {
      await expect(page.getByRole('heading', { name: 'Open your private workspace' })).toBeVisible();
      await expect.poll(() => page.locator('[data-visual-card]').count()).toBeLessThanOrEqual(4);
      await expect(page.locator('[data-visual-card="primary"]')).toHaveCount(1);
      await expectNoVisibleCommands(page);

      const bodyText = await page.locator('body').innerText();
      expect(bodyText).not.toMatch(/raw yaml|raw logs|container ids|port table|verified\.access|actual\.route/i);

      await expectNoHorizontalOverflow(page);
      await expectNoControlTextClipping(page);
      await expectNoTopLevelCardOverlap(page);
      await saveScreenshot(page, 'fresh-workspace-desktop.png');
    } finally {
      await server.close();
    }
  });

  test('BookStack ownership state shows evidence gaps without unlocking access reward', async ({ page }) => {
    const server = await openProgression(page, buildView(bookstackMappedClaims), { width: 1440, height: 900 });
    try {
      await expect(page.getByRole('heading', { name: 'Protect BookStack with central login' })).toBeVisible();
      await expect(page.getByText('First Door Secured')).toHaveCount(0);
      await expect(page.getByText('Access BookStack Route Defined')).toBeVisible();
      await expect(page.getByText('And 1 more evidence claim(s).')).toBeVisible();
      await expectNoVisibleCommands(page);
      expect(await page.locator('body').innerText()).not.toMatch(/\bBookstack\b|\bOidc\b|\bOauth\b/);

      await page.getByRole('button', { name: 'Show command' }).click();
      await expect(page.getByTestId('revealed-command')).toContainText('stackctl verify access.bookstack');

      await expectNoHorizontalOverflow(page);
      await expectNoControlTextClipping(page);
      await expectNoTopLevelCardOverlap(page);
      await saveScreenshot(page, 'bookstack-evidence-gap-command-revealed.png');
    } finally {
      await server.close();
    }
  });

  test('mobile layout keeps controls readable and cards stacked', async ({ page }) => {
    const server = await openProgression(page, buildView(bookstackMappedClaims), { width: 390, height: 844 });
    try {
      await expect(page.getByRole('heading', { name: 'Protect BookStack with central login' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Show command' })).toBeVisible();
      await expectNoVisibleCommands(page);
      await expectNoHorizontalOverflow(page);
      await expectNoControlTextClipping(page);
      await expectNoTopLevelCardOverlap(page);
      await saveScreenshot(page, 'bookstack-evidence-gap-mobile.png');
    } finally {
      await server.close();
    }
  });

  test('ownership path drawer is available without foregrounding expert commands', async ({ page }) => {
    const server = await openProgression(page, buildView(bookstackMappedClaims), { width: 1280, height: 900 });
    try {
      await page.getByText('Show full ownership path').click();
      await expect(page.getByRole('heading', { name: 'Map BookStack state' })).toBeVisible();
      await expect(page.getByText('Expert state and commands stay hidden')).toBeVisible();
      await expectNoVisibleCommands(page);
      await expectNoHorizontalOverflow(page);
      await expectNoControlTextClipping(page);
      await expectNoTopLevelCardOverlap(page);
      await saveScreenshot(page, 'ownership-path-drawer.png');
    } finally {
      await server.close();
    }
  });

  test('restore-proven state foregrounds real capability proof and next shell step', async ({ page }) => {
    const server = await openProgression(page, buildView(restoreProvenClaims), { width: 1440, height: 900 });
    try {
      await expect(page.getByRole('heading', { name: 'Reveal BookStack commands' })).toBeVisible();
      await expect(page.getByText('Restore Proven')).toBeVisible();
      await expect(page.locator('#access-proof')).toHaveText('Proven');
      await expect(page.locator('#restore-proof')).toHaveText('Proven');
      const bodyText = await page.locator('body').innerText();
      expect(bodyText).not.toContain('`stackctl`');
      expect(bodyText).not.toMatch(/\bStackctl\b/);
      await expectNoVisibleCommands(page);
      await expectNoHorizontalOverflow(page);
      await expectNoControlTextClipping(page);
      await expectNoTopLevelCardOverlap(page);
      await saveScreenshot(page, 'restore-proven-shell-next.png');
    } finally {
      await server.close();
    }
  });
});
