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

test('BookStack - OIDC login flow', async ({ page }) => {
    test.setTimeout(180000);
    await testOIDCService(
      page,
      'BookStack',
      serviceUrl('bookstack'),
      /\bBooks\b|\bShelves\b|Recently (Created|Updated)|My Recently Viewed|Recent Activity|Recently Updated Pages/i,
      ['Keycloak', 'Login with SSO', 'SSO'],
      {
        disallowPatterns: [/An Error Occurred|unknown error occurred/i, /\bLog in\b/i],
        disallowUrlPatterns: [/\/login\b/i],
        postLogin: async (page) => {
          await assertBookStackDisplayName(page);

          const booksLink = page.getByRole('link', { name: /^books$/i }).first();
          if (await booksLink.isVisible().catch(() => false)) {
            await booksLink.click({ force: true });
          } else {
            await page.goto(serviceUrl('bookstack', '/books'), {
              waitUntil: 'domcontentloaded',
              timeout: 15000,
            });
          }

          await page.waitForURL(/\/books(?:\?.*)?$/i, { timeout: 15000 }).catch(() => {});
          await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
          await page.locator('body').waitFor({ state: 'visible', timeout: 10000 });
        },
      }
    );
  });

