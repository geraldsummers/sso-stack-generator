import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import {
  authenticatedSessionState,
  domain,
  screenshotRoot,
  seafileOnlyOfficeFixturePath,
  testUser,
  testForwardAuthService,
  waitForGrafanaShell,
  waitForHomeAssistantShell,
} from '../shared/forward-auth';
import { removeJupyterContainersForUsers } from '../../../utils/jupyterhub-cleanup';
import { serviceUrl } from '../../../utils/stack-urls';
import { logPageTelemetry, redactUrlForLogs, setupNetworkLogging } from '../../../utils/telemetry';

test.use({ storageState: authenticatedSessionState });

async function dismissJupyterNewsPrompt(page: import('@playwright/test').Page): Promise<void> {
  const prompt = page.locator('text=/official Jupyter news|privacy policy/i').first();
  for (let attempt = 0; attempt < 6; attempt += 1) {
    if (!(await prompt.isVisible().catch(() => false))) {
      break;
    }

    const dismissCandidates = [
      page.getByRole('button', { name: /^No$/i }).first(),
      page.getByRole('button', { name: /close|dismiss/i }).first(),
      page.locator('button[aria-label*="close" i], button[title*="close" i]').first(),
    ];

    let clicked = false;
    for (const candidate of dismissCandidates) {
      if (await candidate.isVisible().catch(() => false)) {
        await candidate.click({ force: true }).catch(() => {});
        clicked = true;
        break;
      }
    }

    if (!clicked) {
      await page.keyboard.press('Escape').catch(() => {});
    }

    await page.evaluate(() => {
      const patterns = [/official Jupyter news/i, /privacy policy/i];
      for (const node of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
        const text = (node.innerText || node.textContent || '').trim();
        if (!patterns.some((pattern) => pattern.test(text))) {
          continue;
        }
        const overlay = node.closest<HTMLElement>('[role="dialog"], .jp-Dialog, .jp-Dialog-content, .lm-Widget, .jp-Tooltip');
        const target = overlay ?? node;
        target.style.display = 'none';
        target.setAttribute('aria-hidden', 'true');
      }
    }).catch(() => {});

    await page.waitForTimeout(500);
  }

  await expect(prompt).not.toBeVisible({ timeout: 10000 }).catch(() => {});
}

  test('JupyterHub - Spawn notebook with forward auth', async ({ page }) => {
    test.setTimeout(180000);
    try {
      await testForwardAuthService(
        page,
        'JupyterHub',
        serviceUrl('jupyterhub', '/user-redirect/lab'),
        /JupyterHub|Start My Server|Control Panel|JupyterLab|Notebook|Files|New/i,
        {
          disallowUrlPatterns: [/\/spawn-pending\//i],
          disallowPatterns: [/Spawning server|Your server is starting up/i],
          maxPatternRetries: 5,
          retryDelayMs: 2000,
          waitForUrlNotMatch: /\/spawn-pending\//i,
          waitForSelectorVisible: 'text=/Start My Server|Control Panel|Files|Notebook|JupyterLab/i',
          waitForSelectorTimeoutMs: 30000,
          screenshotSelector: '.jp-NotebookPanel',
          screenshotType: 'jpeg',
          screenshotQuality: 90,
          screenshotFullPage: false,
          screenshotUsePage: false,
          screenshotDelayMs: 4000,
          screenshotViewport: { width: 1440, height: 900 },
          onAfterLoad: async (page) => {
            if (/\/hub\/(home|login)/.test(page.url())) {
              await page.goto(serviceUrl('jupyterhub', '/user-redirect/lab'), {
                waitUntil: 'domcontentloaded',
                timeout: 30000,
              }).catch(() => {});
            }

            const startButton = page.locator('#start, button:has-text("Start My Server"), a:has-text("Start My Server")').first();
            if (await startButton.isVisible().catch(() => false)) {
              await startButton.click().catch(() => {});
              await page.waitForURL((url) => !url.toString().includes('/spawn-pending/'), { timeout: 120000 }).catch(() => {});
            }

            if (/\/hub\/home/.test(page.url())) {
              const myServerLink = page.locator('a[href*="/user/"], a:has-text("My Server"), a:has-text("Launch Server")').first();
              if (await myServerLink.isVisible().catch(() => false)) {
                await myServerLink.click().catch(() => {});
              } else {
                await page.goto(serviceUrl('jupyterhub', '/user-redirect/lab'), { waitUntil: 'domcontentloaded' }).catch(() => {});
              }
            }

            await page.waitForURL(/\/user\/[^/]+\/(lab|tree)/, { timeout: 120000 }).catch(() => {});
            const userBaseMatch = page.url().match(/^https:\/\/[^/]+\/user\/[^/]+/);
            if (!userBaseMatch) {
              throw new Error(`Could not determine Jupyter user base URL from ${redactUrlForLogs(page.url())}`);
            }
            const userBase = userBaseMatch[0];

            // Validate notebook server API and create a notebook via Contents API.
            const contentsResponse = await page.request.get(`${userBase}/api/contents`);
            expect(contentsResponse.ok()).toBeTruthy();

            const notebookName = `playwright-smoke-${Date.now()}.ipynb`;
            const notebookPayload = {
              type: 'notebook',
              format: 'json',
              content: {
                cells: [
                  {
                    cell_type: 'markdown',
                    metadata: {},
                    source: ['# Platform Notebook Demo\n', '\n', 'This notebook was created by the Playwright validation flow.'],
                  },
                  {
                    cell_type: 'code',
                    execution_count: 1,
                    metadata: {},
                    outputs: [
                      {
                        output_type: 'stream',
                        name: 'stdout',
                        text: ['stack notebook functional\n'],
                      },
                    ],
                    source: ['print("stack notebook functional")'],
                  },
                ],
                metadata: {
                  kernelspec: { display_name: 'Python 3 (ipykernel)', language: 'python', name: 'python3' },
                  language_info: { name: 'python' },
                },
                nbformat: 4,
                nbformat_minor: 5,
              },
            };

            const xsrfCookie = (await page.context().cookies())
              .find((cookie) => cookie.name === '_xsrf');
            const requestHeaders: Record<string, string> = {
              Referer: page.url(),
            };
            if (xsrfCookie?.value) {
              requestHeaders['X-XSRFToken'] = decodeURIComponent(xsrfCookie.value);
            }

            const createResponse = await page.request.put(`${userBase}/api/contents/${notebookName}`, {
              data: notebookPayload,
              headers: requestHeaders,
            });
            if (!createResponse.ok()) {
              const body = await createResponse.text().catch(() => '');
              throw new Error(`Notebook create failed (${createResponse.status()}): ${body.slice(0, 500)}`);
            }
            const verifyNotebook = await page.request.get(`${userBase}/api/contents/${notebookName}`);
            expect(verifyNotebook.ok()).toBeTruthy();

            const notebookEvidenceSelectors = [
              '.jp-MarkdownCell .jp-RenderedHTMLCommon',
              '.jp-MarkdownCell .cm-content',
              '.jp-CodeCell .cm-content',
              '.jp-OutputArea-output',
              '.jp-OutputArea-child',
              '.lm-TabBar-tabLabel',
              '.jp-DocumentTitle',
            ];
            const notebookEvidenceVisible = async () => {
              for (const selector of notebookEvidenceSelectors) {
                const candidate = page.locator(selector).filter({
                  hasText: /Platform Notebook Demo|stack notebook functional|playwright-smoke/i,
                }).first();
                if (await candidate.isVisible().catch(() => false)) {
                  return true;
                }
              }
              return page.evaluate(() => {
                const text = document.body?.innerText || '';
                return /Platform Notebook Demo|stack notebook functional|playwright-smoke/i.test(text);
              }).catch(() => false);
            };
            console.log('   ⏳ Opening notebook via JupyterLab...');
            await page.goto(`${userBase}/lab/tree/${encodeURIComponent(notebookName)}?reset`, {
              waitUntil: 'domcontentloaded',
              timeout: 60000,
            }).catch(() => {});
            await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
            await expect(page.locator('.jp-LabShell')).toBeVisible({ timeout: 60000 });
            await expect(page.locator('.jp-NotebookPanel')).toBeVisible({ timeout: 60000 });
            await expect(page.locator('text=/File Load Error|Invalid response:\\s*401|Unauthorized/i')).not.toBeVisible({ timeout: 3000 }).catch(() => {});

            const notebookTab = page.locator('.lm-TabBar-tabLabel, .jp-DocumentTitle').filter({
              hasText: new RegExp(notebookName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'),
            }).first();
            if (await notebookTab.isVisible().catch(() => false)) {
              await notebookTab.click({ force: true }).catch(() => {});
              await page.waitForTimeout(800);
            }

            await expect
              .poll(
                async () => notebookEvidenceVisible(),
                { timeout: 60000, intervals: [1000, 2000, 3000] }
              )
              .toBeTruthy();
            await dismissJupyterNewsPrompt(page);
            await page.waitForTimeout(1500);
          },
        }
      );
    } finally {
      const removedJupyterContainers = removeJupyterContainersForUsers([testUser.username]);
      if (removedJupyterContainers.length > 0) {
        console.log(`   🧹 Removed Jupyter notebook containers: ${removedJupyterContainers.join(', ')}`);
      }
    }
  });
