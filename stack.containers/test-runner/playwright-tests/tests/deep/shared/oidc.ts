/**
 * Tests for services using OIDC authentication with Keycloak.
 *
 * These services have explicit OIDC client configurations and handle
 * the OAuth2/OIDC flow themselves (not just forward-auth).
 *
 * Services tested:
 * - Grafana
 * - Mastodon
 * - Forgejo
 * - BookStack
 * - Planka
 * - Element (Matrix Web)
 */

import { test, expect } from '@playwright/test';
import type { Locator, Page, Response } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { KeycloakLoginPage } from '../../../pages/KeycloakLoginPage';
import { OIDCLoginPage } from '../../../pages/OIDCLoginPage';
import {
  lazyTestUser,
  requireStackAdminCredentials,
  resolveStackAdminCredentials,
} from '../../../utils/auth-artifacts';
export { requireStackAdminCredentials, resolveStackAdminCredentials } from '../../../utils/auth-artifacts';
import { resolveStackRegex, serviceUrl, stackDomain } from '../../../utils/stack-urls';
import { defaultIdentityProvider } from '../../../utils/identity-provider';
import { logPageTelemetry, setupNetworkLogging } from '../../../utils/telemetry';

export const testUser = lazyTestUser();

export const guessBaseDomain = (hostname: string) => {
  const parts = hostname.split('.').filter(Boolean);
  return parts.length >= 2 ? parts.slice(-2).join('.') : hostname;
};

type ExpectedIdentity = {
  username: string;
  email: string;
  displayName: string;
};

export const escapeRegex = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
export const normalizedString = (value: unknown) => (typeof value === 'string' ? value.trim() : '');
export const screenshotRoot = process.env.PLAYWRIGHT_SCREENSHOTS_DIR || '/app/test-results/screenshots';
export const domain = stackDomain;
export const stackAdminUsername = () => normalizedString(resolveStackAdminCredentials()?.username);

export function toExpectedIdentity(candidate: any): ExpectedIdentity {
  const username = normalizedString(candidate?.username);
  const email = normalizedString(candidate?.email);
  const displayName =
    normalizedString(candidate?.displayName)
    || normalizedString(candidate?.fullName)
    || normalizedString(candidate?.commonName)
    || [normalizedString(candidate?.givenName), normalizedString(candidate?.familyName)].filter(Boolean).join(' ').trim()
    || username;

  return {
    username,
    email,
    displayName,
  };
}

export function resolveExpectedIdentity(usernameHint?: string): ExpectedIdentity {
  const normalizedHint = normalizedString(usernameHint).toLowerCase();
  const managedIdentity = toExpectedIdentity(testUser);
  const stackAdminIdentity = toExpectedIdentity(testUser?.stackAdminProfile);

  if (normalizedHint) {
    if (
      stackAdminIdentity.username
      && (stackAdminIdentity.username.toLowerCase() === normalizedHint
        || stackAdminUsername().toLowerCase() === normalizedHint)
    ) {
      return stackAdminIdentity;
    }

    if (managedIdentity.username && managedIdentity.username.toLowerCase() === normalizedHint) {
      return managedIdentity;
    }
  }

  if (!normalizedHint) {
    return managedIdentity;
  }

  const fallbackUsername = normalizedString(usernameHint);
  return {
    username: fallbackUsername,
    email: '',
    displayName: fallbackUsername,
  };
}

export async function waitForGrafanaShell(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const text = document.body?.innerText ?? '';
    const hasShell = /Grafana|Last 24 hours|Refresh/i.test(text);
    const stillLoading = /Loading plugin panel/i.test(text);
    return hasShell && !stillLoading;
  }, undefined, { timeout: 45000 });
}

export function requireExpectedDisplayName(serviceName: string, usernameHint?: string): string {
  const resolved = resolveExpectedIdentity(usernameHint).displayName.trim();
  expect(
    resolved,
    `${serviceName} display-name propagation test requires a non-empty display name for the authenticated OIDC user`
  ).toBeTruthy();
  return resolved;
}

export function expectPropagatedDisplayName(
  serviceName: string,
  actualValue: unknown,
  context: string,
  usernameHint?: string
): void {
  expect(
    normalizedString(actualValue),
    `${serviceName} ${context} should equal the human-readable OIDC display name`
  ).toBe(requireExpectedDisplayName(serviceName, usernameHint));
}

export function expectPropagatedEmail(
  serviceName: string,
  actualValue: unknown,
  context: string,
  usernameHint?: string
): void {
  const expectedEmail = resolveExpectedIdentity(usernameHint).email;
  if (!expectedEmail) {
    return;
  }
  expect(
    normalizedString(actualValue),
    `${serviceName} ${context} should match the authenticated test user email`
  ).toBe(expectedEmail);
}

export async function fetchBrowserSessionJson(page: Page, serviceName: string, url: string, context: string): Promise<any> {
  const response = await page.evaluate(async (targetUrl) => {
    const result = await fetch(targetUrl, {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    });
    const text = await result.text();
    return {
      ok: result.ok,
      status: result.status,
      statusText: result.statusText,
      text,
    };
  }, url);
  expect(
    response.ok,
    `${serviceName} ${context} request failed (${response.status} ${response.statusText})`
  ).toBeTruthy();
  try {
    return JSON.parse(response.text);
  } catch (error) {
    throw new Error(
      `${serviceName} ${context} returned non-JSON content: ${response.text.slice(0, 300)} (${String(error)})`
    );
  }
}

export async function assertMastodonDisplayName(page: Page): Promise<void> {
  const expectedName = requireExpectedDisplayName('Mastodon');
  const escapedUsername = escapeRegex(normalizedString(testUser.username));
  const accountLink = page.getByRole('link', {
    name: new RegExp(`${escapeRegex(expectedName)}\\s*@${escapedUsername}`, 'i'),
  }).first();
  if (await accountLink.isVisible().catch(() => false)) {
    await expect(accountLink).toBeVisible({ timeout: 10000 });
    return;
  }

  const displayNameInput = page.locator('input#display_name, input[name="display_name"]').first();
  if (await displayNameInput.isVisible().catch(() => false)) {
    await expect(displayNameInput).toHaveValue(expectedName, { timeout: 10000 });
    return;
  }

  const bodyText = (await page.textContent('body').catch(() => '')) || '';
  expect(
    bodyText,
    'Mastodon should visibly render the propagated display name in the authenticated UI'
  ).toMatch(new RegExp(escapeRegex(expectedName), 'i'));
}

export async function assertForgejoDisplayName(page: Page): Promise<void> {
  await page.goto(serviceUrl('forgejo', '/user/settings'), {
    waitUntil: 'domcontentloaded',
    timeout: 20000,
  }).catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});

  const fullNameInput = page.locator('input[name="full_name"], input#full_name').first();
  await expect(fullNameInput).toBeVisible({ timeout: 15000 });
  await expect(fullNameInput).toHaveValue(requireExpectedDisplayName('Forgejo'), { timeout: 15000 });

  const emailInput = page.locator('input[name="email"], input#email').first();
  if (await emailInput.isVisible().catch(() => false)) {
    await expect(emailInput).toHaveValue(normalizedString(testUser.email), { timeout: 15000 });
  }
}

export async function assertBookStackDisplayName(page: Page): Promise<void> {
  const candidatePaths = [
    serviceUrl('bookstack', '/my-account/profile'),
    serviceUrl('bookstack', '/preferences/profile'),
  ];

  for (const candidatePath of candidatePaths) {
    await page.goto(candidatePath, { waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});

    const nameInput = page.locator('input[name="name"], input#name').first();
    const nameVisible = await nameInput.isVisible().catch(() => false);
    if (!nameVisible) {
      continue;
    }

    await expect(nameInput).toHaveValue(requireExpectedDisplayName('BookStack'), { timeout: 15000 });
    const emailInput = page.locator('input[name="email"], input#email').first();
    if (await emailInput.isVisible().catch(() => false)) {
      await expect(emailInput).toHaveValue(normalizedString(testUser.email), { timeout: 15000 });
    }
    return;
  }

  const bodySnippet = ((await page.textContent('body').catch(() => '')) || '').slice(0, 400);
  throw new Error(`BookStack did not expose an account profile name field after login. Body=${bodySnippet}`);
}

export async function extractPlankaSessionIdentity(page: Page): Promise<{ name: string; username: string; email: string }> {
  return page.evaluate(() => {
    const visited = new Set<unknown>();
    const scoreCandidate = (value: Record<string, unknown>) => {
      const keys = Object.keys(value);
      return keys.filter((key) => /name|username|email/i.test(key)).length;
    };

    const extractString = (value: unknown) => (typeof value === 'string' ? value.trim() : '');

    const visit = (value: unknown): { score: number; candidate: { name: string; username: string; email: string } | null } => {
      if (!value || visited.has(value)) {
        return { score: 0, candidate: null };
      }

      if (typeof value === 'string') {
        try {
          return visit(JSON.parse(value));
        } catch {
          return { score: 0, candidate: null };
        }
      }

      if (typeof value !== 'object') {
        return { score: 0, candidate: null };
      }

      visited.add(value);

      if (Array.isArray(value)) {
        return value.reduce(
          (best, entry) => {
            const next = visit(entry);
            return next.score > best.score ? next : best;
          },
          { score: 0, candidate: null as { name: string; username: string; email: string } | null }
        );
      }

      const record = value as Record<string, unknown>;
      const directCandidate = {
        name: extractString(record.name ?? record.displayName ?? record.fullName),
        username: extractString(record.username ?? record.login),
        email: extractString(record.email),
      };

      let best = {
        score: directCandidate.name || directCandidate.username || directCandidate.email ? scoreCandidate(record) : 0,
        candidate:
          directCandidate.name || directCandidate.username || directCandidate.email
            ? directCandidate
            : null,
      };

      for (const nestedValue of Object.values(record)) {
        const next = visit(nestedValue);
        if (next.score > best.score) {
          best = next;
        }
      }

      return best;
    };

    let best = { score: 0, candidate: { name: '', username: '', email: '' } };
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const key = window.localStorage.key(i);
      if (!key) continue;
      const value = window.localStorage.getItem(key);
      const next = visit(value);
      if (next.score > best.score && next.candidate) {
        best = { score: next.score, candidate: next.candidate };
      }
    }

    return best.candidate;
  });
}

export async function assertPlankaDisplayName(page: Page, token: string, usernameHint: string): Promise<void> {
  const serviceName = 'Planka';
  const apiBase = serviceUrl('planka', '/api');
  const expectedIdentity = resolveExpectedIdentity(usernameHint);

  if (token) {
    const commonOptions = {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
    };

    const userMeResponse = await page.request.get(`${apiBase}/users/me`, commonOptions).catch(() => null);
    if (userMeResponse?.ok()) {
      const payload = await userMeResponse.json();
      const user = payload?.item ?? payload;
      expectPropagatedDisplayName(serviceName, user?.name, 'current user name', expectedIdentity.username);
      expect(normalizedString(user?.username), 'Planka should preserve the expected username').toBe(expectedIdentity.username);
      expectPropagatedEmail(serviceName, user?.email, 'current user email', expectedIdentity.username);
      return;
    }

    const usersResponse = await page.request.get(`${apiBase}/users`, commonOptions).catch(() => null);
    if (usersResponse?.ok()) {
      const payload = await usersResponse.json();
      const users = payload?.items ?? payload?.included ?? payload ?? [];
      const currentUser = Array.isArray(users)
        ? users.find((entry: any) => normalizedString(entry?.username) === expectedIdentity.username)
        : null;
      if (currentUser) {
        expectPropagatedDisplayName(serviceName, currentUser?.name, 'users list entry name', expectedIdentity.username);
        expectPropagatedEmail(serviceName, currentUser?.email, 'users list entry email', expectedIdentity.username);
        return;
      }
    }
  }

  const sessionIdentity = await extractPlankaSessionIdentity(page);
  if (normalizedString(sessionIdentity?.name)) {
    expectPropagatedDisplayName(serviceName, sessionIdentity?.name, 'session identity name', expectedIdentity.username);
  } else {
    const bodyText = (await page.textContent('body').catch(() => '')) || '';
    expect(
      bodyText,
      'Planka should render the authenticated user display name in the visible UI when session storage omits it'
    ).toMatch(new RegExp(escapeRegex(requireExpectedDisplayName(serviceName, expectedIdentity.username)), 'i'));
  }
  const resolvedSessionUsername = normalizedString(sessionIdentity?.username);
  expect(
    resolvedSessionUsername === '' || resolvedSessionUsername === expectedIdentity.username,
    'Planka should preserve the expected username in the session identity when that field is exposed'
  ).toBeTruthy();
}

export async function assertElementDisplayName(
  page: Page,
  homeserverUrl: string,
  accessToken: string,
  matrixUserId: string
): Promise<void> {
  const response = await page.request.get(
    `${homeserverUrl}/_matrix/client/v3/profile/${encodeURIComponent(matrixUserId)}/displayname`,
    {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
      },
    }
  );
  expect(
    response.ok(),
    `Element Matrix profile display name request failed (${response.status()} ${response.statusText()})`
  ).toBeTruthy();
  const payload = await response.json();
  expectPropagatedDisplayName('Element (Matrix Web)', payload?.displayname, 'Matrix profile display name');
}

export async function assertVaultwardenDisplayName(page: Page): Promise<void> {
  const expectedIdentity = resolveExpectedIdentity();
  const expectedVaultwardenNames = [
    expectedIdentity.displayName,
    expectedIdentity.username,
  ].map(normalizedString).filter(Boolean);

  const payload = await fetchBrowserSessionJson(
    page,
    'Vaultwarden',
    serviceUrl('vaultwarden', '/api/sync?excludeDomains=true'),
    'sync payload'
  ).catch(() => null);

  if (payload) {
    const profile = payload?.Profile ?? payload?.profile ?? null;
    const profileName = normalizedString(profile?.Name ?? profile?.name);
    if (profile) {
      expect(
        expectedVaultwardenNames,
        'Vaultwarden sync profile name should match the OIDC display name or username'
      ).toContain(profileName);
      expectPropagatedEmail('Vaultwarden', profile?.Email ?? profile?.email, 'sync profile email');
      return;
    }
  }

  const candidateRoutes = [
    serviceUrl('vaultwarden', '/#/settings/account'),
    serviceUrl('vaultwarden', '/#/settings/account/profile'),
  ];

  for (const candidateRoute of candidateRoutes) {
    await page.goto(candidateRoute, { waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
    await page.waitForTimeout(1500);

    const nameInput = page
      .locator('input[name="name"], input#name, input[formcontrolname="name"]')
      .or(page.getByLabel(/^name$/i))
      .first();
    if (await nameInput.isVisible().catch(() => false)) {
      await expect.poll(
        async () => expectedVaultwardenNames.includes(
          normalizedString(await nameInput.inputValue().catch(() => ''))
        ),
        {
          message: 'Vaultwarden account name should match the OIDC display name or username',
          timeout: 15000,
        }
      ).toBeTruthy();
      const emailInput = page
        .locator('input[name="email"], input#email, input[formcontrolname="email"]')
        .or(page.getByLabel(/^email$/i))
        .first();
      if (await emailInput.isVisible().catch(() => false)) {
        await expect(emailInput).toHaveValue(normalizedString(testUser.email), { timeout: 15000 });
      }
      return;
    }
  }

  throw new Error('Vaultwarden did not expose a profile name field or sync profile after OIDC login.');
}

/**
 * Helper function to test OIDC service access with proper assertions
 */
export async function testOIDCService(
  page: Page,
  serviceName: string,
  servicePath: string,
  uiPattern: RegExp,
  oidcButtonNames: string[] = ['Keycloak'],
  options: {
    disallowPatterns?: RegExp[];
    disallowUrlPatterns?: RegExp[];
    loginPath?: string;
    loginButtonPatterns?: RegExp[];
    ssoIdentifier?: string;
    ssoEmail?: string;
    skipSsoEmail?: boolean;
    oidcLinkPatterns?: RegExp[];
    oidcIssuer?: string;
    preLogin?: (page: Page) => Promise<void>;
    postLogin?: (page: Page) => Promise<void>;
    authenticatedProbe?: (page: Page) => Promise<boolean>;
    authenticatedRecoveryPath?: string;
    uiPatternOverride?: RegExp;
    skipScreenshot?: boolean;
    screenshotSelector?: string;
    screenshotFullPage?: boolean;
    authUsername?: string;
    authPassword?: string;
    authTotpSecret?: string;
  } = {}
) {
  console.log(`\n🧪 Testing ${serviceName} OIDC login`);

  setupNetworkLogging(page, serviceName);

  // Retry logic for transient connectivity/edge-proxy startup errors.
  let retries = 5;

  while (retries > 0) {
    try {
      await page.goto(servicePath, { waitUntil: 'domcontentloaded', timeout: 15000 });
      break; // Success
    } catch (error: any) {
      const message = String(error?.message || error);
      const isTransient =
        /SSL|ERR_SSL_PROTOCOL_ERROR/i.test(message) ||
        /Timeout|timed out|Navigation timeout/i.test(message) ||
        /ERR_CONNECTION|ERR_ABORTED|ERR_HTTP2_PROTOCOL_ERROR/i.test(message);
      if (isTransient) {
        console.log(`   ⚠️  Transient navigation error, retrying... (${6 - retries}/5)`);
        retries--;
        await page.waitForTimeout(3000);
        if (retries === 0) throw error;
      } else {
        throw error;
      }
    }
  }

  await logPageTelemetry(page, `${serviceName} Login Page`);

  if (options.preLogin) {
    await options.preLogin(page);
    await logPageTelemetry(page, `${serviceName} Login Page (post-preLogin)`);
  }

  const oidcPage = new OIDCLoginPage(page);

  const loginButtonPatterns = options.loginButtonPatterns ?? [/sign in|log in|login/i];

  const handleSsoIdentifierIfPresent = async () => {
    if (!options.ssoIdentifier) {
      return;
    }
    const ssoHeader = page.locator('h1', { hasText: /single sign-on/i });
    const ssoInput = page
      .getByLabel(/sso identifier/i)
      .or(page.locator('input[placeholder*="SSO"]'))
      .or(page.locator('input[id*="bit-input"]'))
      .or(page.locator('input').first());
    const continueButton = page.getByRole('button', { name: /continue/i });
    const continueFallback = page.locator('button[type="submit"]').first();

    const shouldHandle = await ssoHeader.first().isVisible().catch(() => false)
      || /\/sso\b/i.test(page.url());
    if (!shouldHandle) {
      return;
    }

    await ssoInput.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
    if (!(await ssoInput.isVisible().catch(() => false))) {
      return;
    }

    const currentValue = await ssoInput.inputValue().catch(() => '');
    if (!currentValue) {
      await ssoInput.fill(options.ssoIdentifier);
    }

    if (await continueButton.first().isVisible().catch(() => false)) {
      await continueButton.first().click();
    } else if (await continueFallback.isVisible().catch(() => false)) {
      await continueFallback.click();
    } else {
      await ssoInput.press('Enter').catch(() => {});
    }

    await page.waitForURL((url) => !/\/sso\b/i.test(url.toString()), { timeout: 20000 }).catch(() => {});
    await page.waitForTimeout(1000);
  };

  const handleSsoEmailIfPresent = async () => {
    if (!options.ssoEmail || options.skipSsoEmail) {
      return false;
    }
    const ssoEmailCandidates = [
      page.locator('input.vw-email-sso'),
      page.locator('input[type="email"]').nth(1),
      page.locator('input[type="email"]').first(),
    ];
    const ssoButton = page
      .getByRole('button', { name: /use single sign-on|single sign-on|sso/i })
      .or(page.locator('button:has-text("Use single sign-on")'))
      .or(page.locator('button:has-text("Single sign-on")'))
      .or(page.locator('button:has-text("SSO")'));

    let ssoEmailInput = ssoEmailCandidates[0];
    let inputVisible = await ssoEmailInput.first().isVisible().catch(() => false);
    if (!inputVisible) {
      for (const candidate of ssoEmailCandidates.slice(1)) {
        if (await candidate.first().isVisible().catch(() => false)) {
          ssoEmailInput = candidate;
          inputVisible = true;
          break;
        }
      }
    }
    const buttonVisible = await ssoButton.first().isVisible().catch(() => false);
    if (!inputVisible && !buttonVisible) {
      return false;
    }

    if (buttonVisible && !inputVisible) {
      await ssoButton.first().click({ force: true });
      await page.waitForTimeout(500);
    }

    let inputNowVisible = await ssoEmailInput.first().isVisible().catch(() => false);
    if (!inputNowVisible) {
      for (const candidate of ssoEmailCandidates) {
        if (await candidate.first().isVisible().catch(() => false)) {
          ssoEmailInput = candidate;
          inputNowVisible = true;
          break;
        }
      }
    }
    if (inputNowVisible) {
      const currentValue = await ssoEmailInput.first().inputValue().catch(() => '');
      if (!currentValue) {
        await ssoEmailInput.first().fill(options.ssoEmail, { force: true });
      }
    }

    if (await ssoButton.first().isVisible().catch(() => false)) {
      await ssoButton.first().click({ force: true });
    } else if (inputNowVisible) {
      await ssoEmailInput.first().press('Enter').catch(() => {});
    }

    await page.waitForURL((url) => /auth\.|keycloak|identity\/connect\/authorize|#\/sso\b|\/sso\b/i.test(url.toString()), { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);
    return /auth\.|keycloak|identity\/connect\/authorize|#\/sso\b|\/sso\b/i.test(page.url());
  };

  const tryOidcLinkPatterns = async () => {
    const patterns = options.oidcLinkPatterns ?? [];
    for (const pattern of patterns) {
      const link = page.getByRole('link', { name: pattern }).or(
        page.getByRole('button', { name: pattern })
      );
      if (await link.first().isVisible().catch(() => false)) {
        await link.first().click();
        await page.waitForTimeout(1500);
        return true;
      }
    }
    return false;
  };

  const tryOidcHrefFallback = async () => {
    const link = page.locator(
      'a[href*="openid"], a[href*="oidc"], a[href*="oauth"], a[href*="sso"]'
    ).first();
    if (await link.isVisible().catch(() => false)) {
      await link.click();
      await page.waitForTimeout(1500);
      return true;
    }
    return false;
  };

  const tryOpenIdFormFallback = async () => {
    if (!options.oidcIssuer) {
      return false;
    }
    const openIdInput = page.locator('input[name="openid"], input#openid').first();
    const signInButton = page.getByRole('button', { name: /sign in|log in/i }).first();
    if (await openIdInput.isVisible().catch(() => false)) {
      await openIdInput.fill(options.oidcIssuer);
      if (await signInButton.isVisible().catch(() => false)) {
        await signInButton.click();
        await page.waitForTimeout(1500);
        return true;
      }
    }
    return false;
  };

  const handleMatrixContinueIfPresent = async (waitFor: boolean = false) => {
    const deadline = Date.now() + (waitFor ? 15000 : 0);
    const continueButton = page.getByRole('button', { name: /^continue$/i })
      .or(page.getByRole('link', { name: /^continue$/i }))
      .or(page.locator('button:has-text("Continue")'))
      .or(page.locator('a:has-text("Continue")'))
      .first();

    do {
      try {
        await continueButton.waitFor({ state: 'visible', timeout: waitFor ? 15000 : 1000 });
        await continueButton.click({ force: true });
        await page.waitForTimeout(1500);
        return true;
      } catch {
        const jsClicked = await page.evaluate(() => {
          const link = document.querySelector('a[href*="loginToken"], a[href*="logintoken"]') as HTMLAnchorElement | null;
          if (!link) return false;
          link.click();
          return true;
        }).catch(() => false);
        if (jsClicked) {
          await page.waitForTimeout(1500);
          return true;
        }
        const directHref = await page.locator('a:has-text("Continue")').first().getAttribute('href').catch(() => null);
        if (directHref) {
          await page.goto(directHref, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
          await page.waitForTimeout(1500);
          return true;
        }
      }
      if (!waitFor) {
        break;
      }
      await page.waitForTimeout(500);
    } while (Date.now() < deadline);

    return false;
  };

  await handleSsoIdentifierIfPresent();
  const ssoEmailHandled = await handleSsoEmailIfPresent();

  // Try to find and click OIDC button
  let buttonFound = false;
  if (ssoEmailHandled) {
    buttonFound = true;
  }
  for (const buttonName of oidcButtonNames) {
    try {
      await oidcPage.clickOIDCButton(buttonName);
      buttonFound = true;
      break;
    } catch (error) {
      continue;
    }
  }

  if (!buttonFound) {
    if (await tryOidcLinkPatterns()) {
      buttonFound = true;
    } else if (await tryOidcHrefFallback()) {
      buttonFound = true;
    } else if (await tryOpenIdFormFallback()) {
      buttonFound = true;
    }
  }

  if (!buttonFound) {
    console.log('   ℹ️  OIDC button not found - attempting to reach login screen...');

    let navigatedToLogin = false;

    // Try clicking a login button/link on the current page
    for (const pattern of loginButtonPatterns) {
      const loginTarget = page.getByRole('link', { name: pattern }).or(
        page.getByRole('button', { name: pattern })
      );
      const hasLoginTarget = await loginTarget.first().isVisible().catch(() => false);
      if (hasLoginTarget) {
        await loginTarget.first().click();
        await page.waitForTimeout(1500);
        navigatedToLogin = true;
        break;
      }
    }

    // Fallback to explicit login path if provided
    if (!navigatedToLogin && options.loginPath) {
      await page.goto(options.loginPath, { waitUntil: 'domcontentloaded', timeout: 15000 });
      navigatedToLogin = true;
    }

    if (navigatedToLogin) {
      await logPageTelemetry(page, `${serviceName} Login Page (post-nav)`);

      await handleSsoIdentifierIfPresent();
      const ssoEmailHandledAfterNav = await handleSsoEmailIfPresent();
      if (ssoEmailHandledAfterNav) {
        buttonFound = true;
      }

      if (!buttonFound) {
        for (const buttonName of oidcButtonNames) {
          try {
            await oidcPage.clickOIDCButton(buttonName);
            buttonFound = true;
            break;
          } catch (error) {
            continue;
          }
        }
      }

      if (!buttonFound) {
        if (await tryOidcLinkPatterns()) {
          buttonFound = true;
        } else if (await tryOidcHrefFallback()) {
          buttonFound = true;
        } else if (await tryOpenIdFormFallback()) {
          buttonFound = true;
        }
      }
    }

  if (!buttonFound) {
    console.log('   ℹ️  OIDC button still not found - might already be logged in...');
  }
}

  if (!buttonFound && !defaultIdentityProvider.isAuthUrl(page.url())) {
    const authenticated = (await options.authenticatedProbe?.(page).catch(() => false)) ?? false;
    if (!authenticated) {
      const bodySnippet = ((await page.textContent('body').catch(() => '')) || '').replace(/\s+/g, ' ').slice(0, 300);
      throw new Error(
        `${serviceName} did not expose an OIDC login entrypoint and no authenticated session was present. `
        + `URL=${page.url()}, bodySnippet=${bodySnippet}`
      );
    }
  }

  // Some SPAs require entering an SSO identifier after navigation
  await handleSsoIdentifierIfPresent();

  // If on Keycloak, login.
  if (defaultIdentityProvider.isAuthUrl(page.url())) {
    const authUsername = options.authUsername ?? testUser.username;
    const authPassword = options.authPassword ?? testUser.password;
    const keycloakPage = new KeycloakLoginPage(page);
    await keycloakPage.login(authUsername, authPassword);

    // Handle consent if shown
    await oidcPage.handleConsentScreen();
  }

  await handleMatrixContinueIfPresent();

  const isAuthUrl = (href: string) => defaultIdentityProvider.isAuthUrl(href);

  const probeAuthenticatedSession = async (context: string) => {
    if (!options.authenticatedProbe) {
      return false;
    }

    try {
      const authenticated = await options.authenticatedProbe(page);
      if (authenticated) {
        console.log(`   ✅ ${serviceName} authenticated session confirmed via ${context}`);
      }
      return authenticated;
    } catch (error: any) {
      console.log(`   ⚠️  ${serviceName} authenticated probe failed during ${context}: ${String(error?.message || error)}`);
      return false;
    }
  };

  const recoverAuthenticatedSession = async (context: string) => {
    if (await probeAuthenticatedSession(context)) {
      return true;
    }

    if (!options.authenticatedRecoveryPath) {
      return false;
    }

    console.log(`   ⚠️  ${serviceName} attempting authenticated recovery via ${options.authenticatedRecoveryPath} (${context})`);
    await page.goto(options.authenticatedRecoveryPath, {
      waitUntil: 'domcontentloaded',
      timeout: 20000,
    }).catch(() => {});
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});

    if (await probeAuthenticatedSession(`${context} navigation`)) {
      return true;
    }

    return false;
  };

  await page.waitForURL((url) => !isAuthUrl(url.toString()), { timeout: 20000 }).catch(() => {});

  // Some providers can bounce through multiple OIDC consent redirects.
  // Keep consuming consent screens while still on auth host before enforcing final redirect.
  for (let i = 0; i < 3 && isAuthUrl(page.url()); i++) {
    await oidcPage.handleConsentScreen().catch(() => {});
    await page.waitForURL((url) => !isAuthUrl(url.toString()), { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);
  }

  const shouldCheckMatrix = page.url().includes('/_synapse/client/oidc/callback')
    || page.url().includes('matrix.')
    || /continue to your account/i.test(await page.title().catch(() => ''));
  if (shouldCheckMatrix) {
    const continued = await handleMatrixContinueIfPresent(true);
    if (continued) {
      await page.waitForURL(
        (url) => {
          const href = url.toString();
          return !href.includes('/_synapse/client/oidc/callback') && !href.includes('matrix.');
        },
        { timeout: 20000 }
      ).catch(() => {});
    }
  }

  const recoveredAfterAuth = await recoverAuthenticatedSession('post-auth redirect');

  // CRITICAL ASSERTION: Must NOT be on auth host unless we already proved the
  // target service session is usable and recovered back onto the app.
  if (!recoveredAfterAuth) {
    await expect.poll(() => isAuthUrl(page.url()), { timeout: 15000 }).toBeFalsy();
  }

  if (options.postLogin) {
    await options.postLogin(page);
  }

  await logPageTelemetry(page, `${serviceName} Dashboard`);

  if (page.url().includes('/_synapse/client/oidc/callback') || /continue to your account/i.test(await page.title().catch(() => ''))) {
    const continueLink = page.locator('a:has-text("Continue")').first();
    await continueLink.waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
    const continueHref = await continueLink.getAttribute('href').catch(() => null);
    if (continueHref) {
      await page.goto(continueHref, { waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
    } else {
      await continueLink.click({ force: true }).catch(() => {});
    }
    await page.waitForTimeout(1500);
    await page.waitForURL(
      (url) => {
        const href = url.toString();
        return !href.includes('/_synapse/client/oidc/callback') && !href.includes('matrix.');
      },
      { timeout: 20000 }
    ).catch(() => {});
  }

  // ENHANCED: Verify we're on the CORRECT service page, not just "not auth"
  const body = page.locator('body');
  let hasContent = false;
  for (let attempt = 1; attempt <= 5; attempt += 1) {
    if (page.isClosed()) {
      break;
    }
    hasContent = await body.isVisible().catch(() => false);
    if (hasContent) {
      break;
    }
    await page.waitForLoadState('domcontentloaded', { timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(500);
  }
  if (!hasContent && page.isClosed()) {
    throw new Error(`Browser page closed before ${serviceName} UI validation completed.`);
  }
  expect(hasContent).toBeTruthy();

  // Verify service-specific UI pattern to confirm correct page
  // Retry pattern matching to handle slow-loading SPAs
  const effectiveUiPattern = options.uiPatternOverride ?? uiPattern;
  let matchesPattern = false;
  let pageTitle = '';
  let bodyHTML = '';
  let pageText = '';
  const maxPatternRetries = /planka/i.test(serviceName) ? 8 : 5;
  const defaultDisallowPatterns: RegExp[] = [
    /Consent Request/i,
    /Powered by Keycloak/i,
    /Login - Keycloak/i,
  ];
  const disallowPatterns = [...defaultDisallowPatterns, ...(options.disallowPatterns ?? [])];
  const disallowUrlPatterns = options.disallowUrlPatterns ?? [];
  let disallowedMatch: RegExp | null = null;
  let disallowedUrl: RegExp | null = null;

  for (let i = 0; i < maxPatternRetries; i++) {
    if (page.isClosed()) {
      break;
    }

    if (isAuthUrl(page.url())) {
      const recovered = await recoverAuthenticatedSession('late auth-host linger');
      if (recovered) {
        await page.waitForTimeout(1200);
      }
    }

    try {
      pageTitle = await page.title();
      pageText = (await page.textContent('body').catch(() => '')) || '';
      bodyHTML = await body.innerHTML();
    } catch (error: any) {
      const message = String(error?.message || error);
      const transientNavigationError =
        /execution context was destroyed/i.test(message)
        || /target page, context or browser has been closed/i.test(message)
        || /cannot find context with specified id/i.test(message);

      if (transientNavigationError && i < maxPatternRetries - 1 && !page.isClosed()) {
        console.log(`   ⚠️  ${serviceName} UI check raced with navigation; retrying... (${i + 1}/${maxPatternRetries})`);
        await page.waitForTimeout(1200);
        continue;
      }

      throw error;
    }

    if (/element/i.test(serviceName) && /verify this device/i.test(pageText)) {
      const skipButton = page.getByRole('button', { name: /skip verification/i }).first();
      if (await skipButton.isVisible().catch(() => false)) {
        await skipButton.click({ force: true }).catch(() => {});
      } else {
        const closeButton = page.locator(
          'button[aria-label="Close"], button[aria-label="Close dialog"], button:has-text("Close")'
        ).first();
        if (await closeButton.isVisible().catch(() => false)) {
          await closeButton.click({ force: true }).catch(() => {});
        } else {
          await page.keyboard.press('Escape').catch(() => {});
        }
      }
      await page.waitForTimeout(1500);
      continue;
    }

    if (/element/i.test(serviceName) && /are you sure\?/i.test(pageText)) {
      const verifyLaterButton = page.getByRole('button', { name: /i'?ll verify later|verify later/i }).first();
      if (await verifyLaterButton.isVisible().catch(() => false)) {
        await verifyLaterButton.click({ force: true }).catch(() => {});
      } else {
        const goBackButton = page.getByRole('button', { name: /go back/i }).first();
        if (await goBackButton.isVisible().catch(() => false)) {
          await goBackButton.click({ force: true }).catch(() => {});
        } else {
          await page.keyboard.press('Escape').catch(() => {});
        }
      }
      await page.waitForTimeout(1500);
      continue;
    }

    if (/element/i.test(serviceName) && /setting up keys|loading…|loading\.\.\./i.test(pageText)) {
      console.log(`   ⏳ Element is still initializing encryption keys... (${i + 1}/${maxPatternRetries})`);
      const setupDialog = page.locator('text=/setting up keys/i').first();
      if (await setupDialog.isVisible().catch(() => false)) {
        await setupDialog.waitFor({ state: 'hidden', timeout: 60000 }).catch(() => {});
      } else {
        await page.waitForTimeout(4000);
      }
      continue;
    }

    if (/continue to your account/i.test(pageText) || page.url().includes('/_synapse/client/oidc/callback') || page.url().includes('matrix.')) {
      const continued = await handleMatrixContinueIfPresent(true);
      if (continued) {
        await page.waitForTimeout(1500);
        continue;
      }
    }
    matchesPattern = effectiveUiPattern.test(pageText || bodyHTML || pageTitle);
    disallowedMatch = disallowPatterns.find((pattern) =>
      pattern.test([pageTitle, pageText, bodyHTML].filter(Boolean).join('\n'))
    ) ?? null;
    disallowedUrl = disallowUrlPatterns.find((pattern) => pattern.test(page.url())) ?? null;

    if (disallowedMatch || disallowedUrl) {
      if (/element/i.test(serviceName)) {
        const onElementLogin = /#\/(?:login|welcome)\b/i.test(page.url());
        const elementIdentityWarning = /cannot reach identity server/i.test(pageText);
        if (onElementLogin && (elementIdentityWarning || disallowedUrl || disallowedMatch)) {
          const elementSsoButton = page.getByRole('button', {
            name: /continue with keycloak sso|sign in with sso|continue with sso|single sign-on|sso/i,
          }).or(
            page.getByRole('link', {
              name: /continue with keycloak sso|sign in with sso|continue with sso|single sign-on|sso/i,
            })
          ).first();
          if (await elementSsoButton.isVisible().catch(() => false)) {
            console.log(`   ⚠️  Element returned to login screen; retrying SSO... (${i + 1}/${maxPatternRetries})`);
            await elementSsoButton.click({ force: true }).catch(() => {});
            await page.waitForTimeout(2500);
            continue;
          }
        }
      }

      if (/planka/i.test(serviceName)) {
        const onKeycloakConsent =
          /consent request|the above application is requesting the following permissions/i.test(pageText)
          || /\/consent\/|\/decision/i.test(page.url())
          || /powered by keycloak/i.test(pageText);
        if (onKeycloakConsent) {
          console.log(`   ⚠️  Planka remained on consent screen; retrying consent handling... (${i + 1}/${maxPatternRetries})`);
          await oidcPage.handleConsentScreen().catch(() => {});
          await page.waitForURL((url) => !isAuthUrl(url.toString()), { timeout: 15000 }).catch(() => {});
          await page.waitForTimeout(1500);
          continue;
        }

        const onPlankaCallback = /\/oidc-callback\b/i.test(page.url());
        if (onPlankaCallback) {
          console.log(`   ⚠️  Planka remained on OIDC callback; forcing app reload... (${i + 1}/${maxPatternRetries})`);
          await page.waitForTimeout(1200);
          await page.goto(serviceUrl('planka'), { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
          await page.waitForTimeout(1200);
          continue;
        }

        const plankaUnknownError = /unknown error|try again later/i.test(pageText);
        const onPlankaLogin = /\/login\b/i.test(page.url());
        if (onPlankaLogin && (plankaUnknownError || disallowedUrl)) {
          console.log(`   ⚠️  Planka returned to login with transient OIDC error; retrying SSO... (${i + 1}/${maxPatternRetries})`);

          const waitForPlankaRedirect = async () =>
            page.waitForURL((url) => {
              const href = url.toString();
              return isAuthUrl(href) || !/\/login\b/i.test(href);
            }, { timeout: 7000 }).then(() => true).catch(() => false);

          const clickPlankaLoginAndWait = async (locator: Locator) => {
            if (!(await locator.first().isVisible().catch(() => false))) {
              return false;
            }
            await locator.first().click({ force: true }).catch(() => {});
            await page.waitForTimeout(600);
            return waitForPlankaRedirect();
          };

          const namedSsoButton = page.getByRole('button', { name: /log in with sso|sso|oidc/i });
          const genericLoginButton = page.locator('main button');
          const directSsoLink = page.locator(
            'a[href*="openid"], a[href*="oidc"], a[href*="oauth"], a[href*="sso"], a[href*="auth"]'
          );

          if (await clickPlankaLoginAndWait(namedSsoButton)) {
            continue;
          }
          if (await clickPlankaLoginAndWait(genericLoginButton)) {
            continue;
          }
          if (await clickPlankaLoginAndWait(directSsoLink)) {
            continue;
          }

          const spinnerOnlyState = await page.locator(
            'main button.loading, main button[disabled], main button[aria-busy="true"], main button:has(i.loading.icon), main button i[class*="spinner"], main button .spinner'
          ).first().isVisible().catch(() => false);
          if (spinnerOnlyState) {
            console.log('   ⚠️  Planka login button stuck in spinner state; resetting login page...');
          }

          // Planka can occasionally leave the login action in a spinner-only state.
          // Hard-reset the login page before retrying to force a fresh OIDC request.
          const plankaLoginPath = options.loginPath ?? serviceUrl('planka', '/login');
          await page.goto(plankaLoginPath, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
          await page.evaluate(() => {
            const storageOwner = globalThis as typeof globalThis & { sessionStorage?: { clear: () => void } };
            storageOwner.sessionStorage?.clear();
          }).catch(() => {});
          await page.waitForTimeout(1500);
          continue;
        }
      }

      if (/bookstack/i.test(serviceName)) {
        const isBookStackDashboard = (content: string) =>
          /\bBooks\b|\bShelves\b|My Recently Viewed|Recent Activity|Recently Updated Pages|My Account|Logout/i.test(content);

        const onBookStackError =
          /an error occurred|unknown error occurred/i.test(pageText)
          || /\/oidc\/callback\b/i.test(page.url());
        if (onBookStackError) {
          console.log(`   ⚠️  BookStack hit transient OIDC callback error; retrying login flow... (${i + 1}/${maxPatternRetries})`);

          const homeUrl = new URL('/', page.url()).toString();
          await page.goto(homeUrl, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
          await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
          const homeTitle = await page.title().catch(() => '');
          const homeText = (await page.textContent('body').catch(() => '')) || '';
          const homeCombined = [homeTitle, homeText].filter(Boolean).join('\n');
          if (isBookStackDashboard(homeCombined) && !/\/login\b/i.test(page.url())) {
            console.log(`   ✅ BookStack session recovered after callback error; continuing...`);
            continue;
          }

          const loginUrl = new URL('/login', page.url()).toString();
          await page.goto(loginUrl, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
          await page.waitForTimeout(700);

          const oidcRetryButton = page
            .locator('#oidc-login')
            .or(page.getByRole('button', { name: /login with keycloak|keycloak|oidc|sso/i }))
            .or(page.getByRole('link', { name: /login with keycloak|keycloak|oidc|sso/i }))
            .first();

          if (await oidcRetryButton.isVisible().catch(() => false)) {
            await oidcRetryButton.click({ force: true }).catch(() => {});
            await page.waitForTimeout(2500);
            continue;
          }
        }
      }

      if (disallowedUrl && options.ssoIdentifier) {
        await handleSsoIdentifierIfPresent();
      }
      if (i < maxPatternRetries - 1) {
        console.log(`   ⏳ Detected disallowed state for ${serviceName}, waiting for redirect... (${i + 1}/${maxPatternRetries})`);
        await page.waitForTimeout(2000);
        continue;
      }
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
      await page.waitForTimeout(2000); // Wait 2 seconds before retry
    }
  }

  if (!matchesPattern) {
    if (await probeAuthenticatedSession('ui pattern fallback')) {
      matchesPattern = true;
    }
  }

  if (!matchesPattern) {
    console.log(`   ⚠️  Pattern match failed for ${serviceName}`);
    console.log(`   Title: "${pageTitle}"`);
    console.log(`   Pattern: ${effectiveUiPattern}`);
    console.log(`   Body length: ${bodyHTML.length} chars`);
    throw new Error(`Expected ${serviceName} page but UI pattern not found. Pattern: ${effectiveUiPattern}, Title: "${pageTitle}"`);
  }

  // Guard against late redirects/races that can happen immediately before capture.
  await expect.poll(() => isAuthUrl(page.url()), { timeout: 10000 }).toBeFalsy();
  const finalTitle = await page.title().catch(() => '');
  const finalPageText = (await page.textContent('body').catch(() => '')) || '';
  const finalCombined = [finalTitle, finalPageText].filter(Boolean).join('\n');
  const finalDisallowedMatch = disallowPatterns.find((pattern) => pattern.test(finalCombined)) ?? null;
  if (finalDisallowedMatch) {
    throw new Error(
      `Refusing to capture ${serviceName} screenshot because disallowed content is still visible: ${finalDisallowedMatch}`
    );
  }

  if (!options.skipScreenshot) {
    // Capture screenshot for manual validation (compressed to prevent 5MB+ files)
    const normalizedServiceName = serviceName
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '');
    const screenshotName = `${normalizedServiceName}-oidc-authenticated.jpg`;
    fs.mkdirSync(screenshotRoot, { recursive: true });
    const screenshotPath = path.join(screenshotRoot, screenshotName);
    if (options.screenshotSelector) {
      const target = page.locator(options.screenshotSelector).first();
      const targetVisible = await target.isVisible().catch(() => false);
      if (targetVisible) {
        await target.screenshot({
          path: screenshotPath,
          type: 'jpeg',
          quality: 85,
        });
      } else {
        console.log(`   ⚠️  Screenshot target '${options.screenshotSelector}' not visible; falling back to page screenshot`);
        await page.screenshot({
          path: screenshotPath,
          type: 'jpeg',
          quality: 85,
          fullPage: options.screenshotFullPage ?? true,
        });
      }
    } else {
      await page.screenshot({
        path: screenshotPath,
        type: 'jpeg',
        quality: 85,
        fullPage: options.screenshotFullPage ?? true,
      });
    }
    console.log(`   📸 Screenshot saved: ${screenshotName}`);
    console.log(`   👀 REVIEW SCREENSHOT to verify correct page loaded`);
  }

  console.log(`   ✅ ${serviceName} OIDC login successful\n`);
}
