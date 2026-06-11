/**
 * Tests for services protected by the shared Keycloak edge auth gateway.
 *
 * These services rely on Caddy's forward_auth directive pointing to the
 * Keycloak oauth2-proxy gateway. The saved Keycloak-backed browser session
 * works across all protected routes.
 *
 * Services tested:
 * - JupyterHub
 * - Open-WebUI
 * - Prometheus
 * - Vaultwarden
 * - Homepage
 * - Ntfy
 * - Home Assistant
 * - Kopia (Backup)
 * - Seafile
 * - Search
 * - Pipeline Monitor
 * - Vault
 */

import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { KeycloakLoginPage } from '../../../pages/KeycloakLoginPage';
import {
  authArtifactPath,
  lazyTestUser,
  requireStackAdminCredentials,
} from '../../../utils/auth-artifacts';
import { defaultIdentityProvider } from '../../../utils/identity-provider';
export { requireStackAdminCredentials } from '../../../utils/auth-artifacts';
import { serviceUrl, stackDomain } from '../../../utils/stack-urls';
import { logPageTelemetry, savePageHTML, setupNetworkLogging } from '../../../utils/telemetry';

export const testUser = lazyTestUser();
export const authenticatedSessionState = authArtifactPath(defaultIdentityProvider.sessionArtifactName);
export const seafileOnlyOfficeFixturePath = path.join(__dirname, '../../../fixtures/seafile-onlyoffice-demo.docx');
export const screenshotRoot = process.env.PLAYWRIGHT_SCREENSHOTS_DIR || '/app/test-results/screenshots';
export const domain = stackDomain;
const embeddedSeafileOnlyOfficeFixtureBase64 =
  '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';

export function readSeafileOnlyOfficeFixture(): Buffer {
  if (fs.existsSync(seafileOnlyOfficeFixturePath)) {
    return fs.readFileSync(seafileOnlyOfficeFixturePath);
  }

  console.log(`   ⚠️  Seafile OnlyOffice fixture missing at ${seafileOnlyOfficeFixturePath}; using embedded fallback.`);
  return Buffer.from(embeddedSeafileOnlyOfficeFixtureBase64, 'base64');
}

export async function waitForGrafanaShell(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const text = document.body?.innerText ?? '';
    const hasShell = /Grafana|Last 24 hours|Refresh/i.test(text);
    const stillLoading = /Loading plugin panel/i.test(text);
    return hasShell && !stillLoading;
  }, undefined, { timeout: 45000 });
}

export async function waitForHomeAssistantShell(page: Page): Promise<void> {
  const deadline = Date.now() + 45000;
  const shellLocators = [
    page.getByText(/^Overview$/i).first(),
    page.getByText(/^Developer tools$/i).first(),
    page.getByText(/^Settings$/i).first(),
    page.getByRole('heading', { name: /Welcome Home/i }).first(),
  ];

  while (Date.now() < deadline) {
    for (const locator of shellLocators) {
      if (await locator.isVisible().catch(() => false)) {
        return;
      }
    }
    await page.waitForTimeout(500);
  }

  throw new Error('Timed out waiting for Home Assistant shell markers');
}

/**
 * Helper function to test forward auth service access with proper assertions
 */
export async function testForwardAuthService(
  page: Page,
  serviceName: string,
  servicePath: string,
  uiPattern: RegExp,
  options: {
    requireUI?: boolean;
    disallowPatterns?: RegExp[];
    disallowUrlPatterns?: RegExp[];
    maxPatternRetries?: number;
    retryDelayMs?: number;
    waitForSelector?: string;
    waitForSelectorVisible?: string;
    waitForSelectorTimeoutMs?: number;
    requireSelectorVisible?: boolean;
    waitForUrlNotMatch?: RegExp;
    waitForUrlMatch?: RegExp;
    clickIfVisibleSelector?: string;
    screenshotSelector?: string;
    screenshotType?: 'jpeg' | 'png';
    screenshotQuality?: number;
    screenshotFullPage?: boolean;
    screenshotDelayMs?: number;
    screenshotUsePage?: boolean;
    screenshotViewport?: { width: number; height: number };
    screenshotSuffix?: string;
    skipScreenshot?: boolean;
    onAfterLoad?: (page: Page) => Promise<void>;
  } = {}
) {
  console.log(`\n🧪 Testing ${serviceName} forward auth`);
  const normalizedServiceName = serviceName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');

  setupNetworkLogging(page, serviceName);

  // Retry logic for SSL errors and timeouts
  let retries = 3;
  let lastError;

  let navResponse;
  while (retries > 0) {
    try {
      navResponse = await page.goto(servicePath, { waitUntil: 'domcontentloaded', timeout: 30000 });
      break; // Success, exit retry loop
    } catch (error: any) {
      lastError = error;
      if (error.message?.includes('SSL') || error.message?.includes('ERR_SSL_PROTOCOL_ERROR') || error.message?.includes('Timeout')) {
        console.log(`   ⚠️  SSL/timeout error, retrying... (${4 - retries}/3)`);
        retries--;
        await page.waitForTimeout(3000); // Wait 3 seconds before retry
        if (retries === 0) {
          throw error; // Give up after 3 retries
        }
      } else {
        throw error; // Not an SSL/timeout error, don't retry
      }
    }
  }

  // Handle auth redirect if the saved browser state expired.
  if (defaultIdentityProvider.isAuthUrl(page.url())) {
    console.log('   ⚠️  Auth state expired, logging in again...');
    const loginPage = new KeycloakLoginPage(page);
    await loginPage.login(testUser.username, testUser.password);
  }

  // Handle OIDC consent screens (some clients still require explicit consent).
  for (let i = 0; i < 3; i++) {
    if (!defaultIdentityProvider.isConsentUrl(page.url())) {
      break;
    }
    console.log('   ⚠️  Consent screen detected, accepting...');
    const acceptButton = page.locator('#openid-consent-accept, button:has-text(\"Accept\")').first();
    if (await acceptButton.isVisible().catch(() => false)) {
      await acceptButton.click().catch(() => {});
      await page.waitForTimeout(1500);
      await page.waitForLoadState('domcontentloaded', { timeout: 15000 }).catch(() => {});
      await page.waitForURL((url) => !defaultIdentityProvider.isConsentUrl(url.toString()), { timeout: 10000 }).catch(() => {});
    } else {
      break;
    }
  }

  if (options.waitForUrlMatch) {
    await page.waitForURL(options.waitForUrlMatch, { timeout: 30000 }).catch(() => {});
  }

  if (options.waitForUrlNotMatch) {
    await page.waitForURL((url) => !options.waitForUrlNotMatch!.test(url.toString()), { timeout: 60000 }).catch(() => {});
  }

  if (options.waitForSelector) {
    const waitPromise = page.waitForSelector(options.waitForSelector, { timeout: 10000 });
    if (options.requireSelectorVisible) {
      await waitPromise;
    } else {
      await waitPromise.catch(() => {});
    }
  }

  if (options.waitForSelectorVisible) {
    const timeout = options.waitForSelectorTimeoutMs ?? 15000;
    const waitPromise = page.waitForSelector(options.waitForSelectorVisible, { state: 'visible', timeout });
    if (options.requireSelectorVisible) {
      await waitPromise;
    } else {
      await waitPromise.catch(() => {});
    }
  }

  if (options.clickIfVisibleSelector) {
    const clickTarget = page.locator(options.clickIfVisibleSelector).first();
    if (await clickTarget.isVisible().catch(() => false)) {
      await clickTarget.click().catch(() => {});
      await page.waitForTimeout(1000);
    }
  }

  // If the UI is still loading, give the app a moment to finish first paint.
  if (options.waitForSelectorVisible) {
    await page.waitForTimeout(options.screenshotDelayMs ?? 3000);
  }

  if (options.screenshotViewport) {
    await page.setViewportSize(options.screenshotViewport);
  }

  await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

  if (options.onAfterLoad) {
    await options.onAfterLoad(page);
  }

  await logPageTelemetry(page, `${serviceName} Main Page`);

  // Check for 400/500 errors
  const status = navResponse?.status?.();
  if (typeof status === 'number' && status >= 400) {
    throw new Error(`${serviceName} returned HTTP ${status} during authenticated navigation.`);
  }

  // ENHANCED: Verify we're on the CORRECT service page, not just "not auth"
  const body = page.locator('body');
  await expect(body).toBeAttached({ timeout: 10000 });

  // Verify page has meaningful content (not just an empty body)
  let bodyHTML = await body.innerHTML();
  if (options.requireUI !== false) {
    expect(bodyHTML.length).toBeGreaterThan(10);
  }

  const disallowPatterns = options.disallowPatterns ?? [];
  const disallowUrlPatterns = options.disallowUrlPatterns ?? [];

  // Check for service-specific UI pattern to confirm correct page
  if (options.requireUI !== false && uiPattern) {
    // Retry pattern matching to handle slow-loading SPAs
    let matchesPattern = false;
    let pageTitle = '';
    const maxPatternRetries = options.maxPatternRetries ?? 5;
    const retryDelayMs = options.retryDelayMs ?? 2000;
    let disallowedMatch: RegExp | null = null;
    let disallowedUrl: RegExp | null = null;

    for (let i = 0; i < maxPatternRetries; i++) {
      pageTitle = await page.title();
      const currentPageText = (await page.textContent('body').catch(() => null)) ?? '';
      bodyHTML = await body.innerHTML();
      const combinedContent = [pageTitle, currentPageText, bodyHTML].filter(Boolean).join('\n');
      matchesPattern = uiPattern.test(combinedContent);
      disallowedMatch = disallowPatterns.find((pattern) =>
        pattern.test([pageTitle, currentPageText, bodyHTML].filter(Boolean).join('\n'))
      ) ?? null;
      disallowedUrl = disallowUrlPatterns.find((pattern) => pattern.test(page.url())) ?? null;

      if (disallowedMatch || disallowedUrl) {
        if (i < maxPatternRetries - 1) {
          console.log(`   ⏳ Detected disallowed state for ${serviceName}, waiting for redirect... (${i + 1}/${maxPatternRetries})`);
          await page.waitForTimeout(retryDelayMs);
          continue;
        }
        await savePageHTML(page, `${normalizedServiceName}-disallowed.html`).catch(() => {});
        const reason = disallowedUrl
          ? `URL matched disallowed pattern: ${disallowedUrl}`
          : `Page content matched disallowed pattern: ${disallowedMatch}`;
        throw new Error(`Expected authenticated ${serviceName} page but found disallowed state. ${reason}`);
      }

      if (matchesPattern) {
        break; // Pattern found, exit retry loop
      }

      if (i < maxPatternRetries - 1) {
        console.log(`   ⏳ Waiting for ${serviceName} UI to render... (${i + 1}/${maxPatternRetries})`);
        await page.waitForTimeout(retryDelayMs); // Wait before retry
      }
    }

    if (!matchesPattern) {
      console.log(`   ⚠️  Pattern match failed for ${serviceName}`);
      console.log(`   Title: "${pageTitle}"`);
      console.log(`   Pattern: ${uiPattern}`);
      console.log(`   Body length: ${bodyHTML.length} chars`);
      throw new Error(`Expected service page for ${serviceName} but UI pattern not found. Pattern: ${uiPattern}, Title: "${pageTitle}"`);
    }
  }

  await expect(page).not.toHaveURL(/keycloak|keycloak-auth|\/realms\/[^/]+\/protocol\/openid-connect\/auth|\/auth\/(authorize|login_flow|login)/i);
  const finalTitle = await page.title();
  const finalText = (await page.textContent('body').catch(() => null)) ?? '';
  const finalHtml = await body.innerHTML();
  const finalCombinedContent = [finalTitle, finalText, finalHtml].filter(Boolean).join('\n');
  const finalDisallowedMatch = disallowPatterns.find((pattern) => pattern.test(finalCombinedContent));
  const finalDisallowedUrl = disallowUrlPatterns.find((pattern) => pattern.test(page.url()));
  if (finalDisallowedMatch || finalDisallowedUrl) {
    await savePageHTML(page, `${normalizedServiceName}-final-disallowed.html`).catch(() => {});
    const reason = finalDisallowedUrl
      ? `URL matched disallowed pattern: ${finalDisallowedUrl}`
      : `Page content matched disallowed pattern: ${finalDisallowedMatch}`;
    throw new Error(`Expected final authenticated ${serviceName} page but found disallowed state. ${reason}`);
  }
  if (options.requireUI !== false && uiPattern && !uiPattern.test(finalCombinedContent)) {
    await savePageHTML(page, `${normalizedServiceName}-final-missing-ui.html`).catch(() => {});
    throw new Error(`Expected final authenticated ${serviceName} page but UI pattern disappeared. Pattern: ${uiPattern}`);
  }
  await savePageHTML(page, `${normalizedServiceName}-authenticated.html`).catch(() => {});

  if (!options.skipScreenshot) {
    // Capture screenshot for manual validation (compressed to prevent 5MB+ files)
    const screenshotBase = `${normalizedServiceName}-${options.screenshotSuffix ?? 'authenticated'}`;
    const screenshotType = options.screenshotType ?? 'jpeg';
    const screenshotName = `${screenshotBase}.${screenshotType}`;
    const screenshotPath = path.join(screenshotRoot, screenshotName);
    if (options.screenshotUsePage) {
      await page.screenshot({
        path: screenshotPath,
        type: screenshotType,
        quality: screenshotType === 'jpeg' ? (options.screenshotQuality ?? 85) : undefined,
        fullPage: options.screenshotFullPage ?? true
      });
    } else if (options.screenshotSelector) {
      const target = page.locator(options.screenshotSelector).first();
      const visible = await target.isVisible().catch(() => false);
      if (visible) {
        await target.screenshot({
          path: screenshotPath,
          type: screenshotType,
          quality: screenshotType === 'jpeg' ? (options.screenshotQuality ?? 85) : undefined
        });
      } else {
        console.log(`   ⚠️  Screenshot selector not visible (${options.screenshotSelector}); falling back to full page.`);
        await page.screenshot({
          path: screenshotPath,
          type: screenshotType,
          quality: screenshotType === 'jpeg' ? (options.screenshotQuality ?? 85) : undefined,
          fullPage: options.screenshotFullPage ?? true
        });
      }
    } else {
      await page.screenshot({
        path: screenshotPath,
        type: screenshotType,
        quality: screenshotType === 'jpeg' ? (options.screenshotQuality ?? 85) : undefined,
        fullPage: options.screenshotFullPage ?? true
      });
    }
    console.log(`   📸 Screenshot saved: ${screenshotName}`);
    console.log(`   👀 REVIEW SCREENSHOT to verify correct page loaded`);
  }

  console.log(`   ✅ ${serviceName} accessed successfully\n`);
}
