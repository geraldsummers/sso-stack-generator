import { test, expect } from '@playwright/test';
import type { Locator, Page, Response } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { KeycloakLoginPage } from '../../../pages/KeycloakLoginPage';
import { OIDCLoginPage } from '../../../pages/OIDCLoginPage';
import {
  assertBookStackDisplayName,
  assertElementDisplayName,
  assertForgejoDisplayName,
  assertMastodonDisplayName,
  assertPlankaDisplayName,
  assertVaultwardenDisplayName,
  domain,
  escapeRegex,
  fetchBrowserSessionJson,
  guessBaseDomain,
  normalizedString,
  requireExpectedDisplayName,
  requireStackAdminCredentials,
  resolveStackAdminCredentials,
  screenshotRoot,
  testOIDCService,
  testUser,
  waitForGrafanaShell,
} from '../shared/oidc';
import { resolveStackRegex, serviceUrl } from '../../../utils/stack-urls';
import { logPageTelemetry, setupNetworkLogging } from '../../../utils/telemetry';

test('Grafana - OIDC login flow', async ({ page }) => {
    // Grafana uses forward-auth, not OIDC. Validate access and UI via Keycloak session.
    setupNetworkLogging(page, 'Grafana (forward-auth)');

    let retries = 3;
    while (retries > 0) {
      try {
        await page.goto(serviceUrl('grafana'), { waitUntil: 'domcontentloaded', timeout: 20000 });
        break;
      } catch (error: any) {
        if (error.message?.includes('SSL') || error.message?.includes('ERR_SSL_PROTOCOL_ERROR')) {
          retries--;
          await page.waitForTimeout(2000);
          if (retries === 0) throw error;
        } else {
          throw error;
        }
      }
    }

    if (page.url().includes('keycloak') || page.url().includes('keycloak.') ) {
      const loginPage = new KeycloakLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    await page.waitForURL(
      (url) => {
        const href = url.toString();
        return href.length > 0 && !/auth\.|keycloak/i.test(href);
      },
      { timeout: 30000 }
    ).catch(() => {});
    const settledGrafanaUrl = page.url();
    expect(settledGrafanaUrl.length).toBeGreaterThan(0);
    expect(settledGrafanaUrl).not.toMatch(/auth\.|keycloak/i);
    await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

    const grafanaPattern = /Grafana|Dashboards|Explore|Connections|Data sources|Loki/i;
    const pageTitle = await page.title();
    const pageText = await page.textContent('body').catch(() => '');
    const bodyHtml = await page.locator('body').innerHTML();
    const combined = [pageTitle, pageText, bodyHtml].filter(Boolean).join('\n');
    if (!grafanaPattern.test(combined)) {
      throw new Error('Expected Grafana UI after forward-auth, but UI pattern not found.');
    }
    await waitForGrafanaShell(page);
    await page.setViewportSize({ width: 1280, height: 360 });

  });
