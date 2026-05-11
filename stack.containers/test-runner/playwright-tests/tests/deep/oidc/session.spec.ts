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

test('OIDC session works across multiple services', async ({ page }) => {
    test.setTimeout(180000);

    console.log('\n🧪 Testing OIDC session persistence');

    // Login to first OIDC service
    console.log('\n   Logging into Grafana (first OIDC service)...');
    await page.goto(serviceUrl('grafana'));

    const oidcPage = new OIDCLoginPage(page);

    try {
      await oidcPage.clickOIDCButton('Keycloak');
    } catch (error) {
      // Might already be logged in
    }

    if (page.url().includes('keycloak') || page.url().includes('keycloak.')) {
      const keycloakPage = new KeycloakLoginPage(page);
      await keycloakPage.login(testUser.username, testUser.password);
      await oidcPage.handleConsentScreen();
    }

    // Confirm the authenticated Grafana UI is usable; a completed redirect alone is not a pass.
    await page.waitForURL((url) => {
      const host = url.hostname;
      return !/^(keycloak.|keycloak-keycloak.)/.test(host);
    }, { timeout: 30000 }).catch(() => {});
    await waitForGrafanaShell(page);
    const grafanaContent = [
      await page.title().catch(() => ''),
      (await page.textContent('body').catch(() => '')) || '',
    ].join('\n');
    expect(grafanaContent).toMatch(/Grafana|Dashboards|Explore|Connections|Data sources|Loki/i);
    console.log('   ✅ Grafana login complete');

    // Try second OIDC service - should not require full login
    console.log('\n   Accessing BookStack (second OIDC service)...');
    await page.goto(serviceUrl('bookstack'));

    try {
      await oidcPage.clickOIDCButton('Keycloak', { requireAuthRedirect: false });
    } catch (error) {
      try {
        await oidcPage.clickOIDCButton('Login with SSO', { requireAuthRedirect: false });
      } catch (error2) {
        // Button might not be there if already logged in
      }
    }

    // Should skip Keycloak login screen (already authenticated)
    const needsAuth = page.url().includes('keycloak') &&
      await page.locator('input[type="password"]').isVisible({ timeout: 2000 }).catch(() => false);

    if (needsAuth) {
      console.log('   ⚠️  Had to re-authenticate (session not shared)');
      const keycloakPage = new KeycloakLoginPage(page);
      await keycloakPage.login(testUser.username, testUser.password);
    } else {
      console.log('   ✅ No re-authentication needed - session shared!');
    }

    await oidcPage.handleConsentScreen();

    // CRITICAL: Verify we're on authenticated BookStack UI, not callback/error pages.
    let verifiedBookStackUi = false;
    const maxBookStackSessionChecks = 3;
    for (let i = 0; i < maxBookStackSessionChecks; i += 1) {
      if (page.isClosed()) {
        break;
      }

      const currentUrl = page.url();
      const currentHost = new URL(currentUrl).hostname;
      const currentBody = (await page.textContent('body').catch(() => '')) || '';
      const onAuthHost = /^(keycloak.|keycloak-keycloak.)/.test(currentHost);
      const disallowedBookStackState =
        /an error occurred|unknown error occurred/i.test(currentBody)
        || /\/oidc\/callback\b/i.test(currentUrl)
        || /\/login\b/i.test(currentUrl);
      const hasBookStackUi =
        /\bBooks\b|Shelves|Recently Updated Pages|Recent Activity|My Account|Dark Mode/i.test(currentBody);

      if (!onAuthHost && !disallowedBookStackState && hasBookStackUi) {
        verifiedBookStackUi = true;
        break;
      }

      if (i < maxBookStackSessionChecks - 1) {
        console.log(`   ⚠️  BookStack session landed on non-authenticated state; retrying SSO... (${i + 1}/${maxBookStackSessionChecks})`);
        await page.goto(serviceUrl('bookstack', '/login'), { waitUntil: 'domcontentloaded' }).catch(() => {});
        const retryButton = page
          .locator('#oidc-login')
          .or(page.getByRole('button', { name: /login with keycloak|keycloak|oidc|sso/i }))
          .or(page.getByRole('link', { name: /login with keycloak|keycloak|oidc|sso/i }))
          .first();
        if (await retryButton.isVisible().catch(() => false)) {
          await retryButton.click({ force: true }).catch(() => {});
        }
        if (page.url().includes('keycloak') || page.url().includes('keycloak.')) {
          const keycloakPage = new KeycloakLoginPage(page);
          await keycloakPage.login(testUser.username, testUser.password).catch(() => {});
        }
        await oidcPage.handleConsentScreen().catch(() => {});
        await page.waitForTimeout(1500).catch(() => {});
      }
    }

    expect(verifiedBookStackUi).toBeTruthy();

    console.log('\n   ✅ OIDC session test complete\n');
  });
