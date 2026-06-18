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
