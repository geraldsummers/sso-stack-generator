import { expect, Locator, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { KeycloakLoginPage } from '../../pages/KeycloakLoginPage';
import { OIDCLoginPage } from '../../pages/OIDCLoginPage';
import { defaultIdentityProvider } from '../identity-provider';
import type { BrowserTestUser } from '../auth-artifacts';
import { findRoute, routeUrl, routeUrlPattern } from '../route-catalog';
import { redactUrlForLogs } from '../telemetry';

import type { AnonymousContract, BrowserRoute, SmokeContract, VisualContract } from '../route-catalog';

function isIdentityAuthUrl(value: string): boolean {
  return defaultIdentityProvider.isAuthUrl(value);
}

async function gotoWithRetry(page: Page, url: string): Promise<void> {
  let lastError: unknown = null;

  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
      await page.waitForLoadState('domcontentloaded', { timeout: 10000 }).catch(() => {});
      return;
    } catch (error) {
      lastError = error;
      const message = String((error as Error)?.message || error);
      const retryable = /SSL|ERR_SSL_PROTOCOL_ERROR|Timeout/i.test(message);
      if (!retryable || attempt === 3) {
        throw error;
      }
      await page.waitForTimeout(1500);
    }
  }

  throw lastError;
}

async function applySmokeHeaders(page: Page, smoke: SmokeContract): Promise<void> {
  if (!smoke.headers || Object.keys(smoke.headers).length === 0) {
    return;
  }

  if (typeof page.setExtraHTTPHeaders !== 'function') {
    throw new Error('Playwright page does not support per-route HTTP headers.');
  }

  await page.setExtraHTTPHeaders(smoke.headers);
}

async function combinedPageContent(page: Page): Promise<string> {
  const body = page.locator('body').first();
  const [title, bodyInnerText, bodyText] = await Promise.all([
    page.title().catch(() => ''),
    typeof (body as unknown as { innerText?: unknown }).innerText === 'function'
      ? body.innerText({ timeout: 1000 }).catch(() => '')
      : Promise.resolve(''),
    page.textContent('body').catch(() => ''),
  ]);

  return [title, bodyInnerText || '', bodyText || ''].filter(Boolean).join('\n');
}

async function expectPageMatcher(page: Page, matcher: RegExp, description: string): Promise<void> {
  await expect
    .poll(async () => matcher.test(await combinedPageContent(page)), {
      timeout: 20000,
      message: `${description} should match ${matcher}`,
    })
    .toBe(true);
}

async function expectPageNotMatcher(page: Page, matcher: RegExp, description: string): Promise<void> {
  await expect
    .poll(async () => !matcher.test(await combinedPageContent(page)), {
      timeout: 5000,
      message: `${description} should not match ${matcher}`,
    })
    .toBe(true);
}

async function waitForServiceLoginContent(page: Page, route: BrowserRoute, contract: Extract<AnonymousContract, { kind: 'service_login' }>): Promise<void> {
  const targetUrl = routeUrl(route, contract.path);
  let content = '';

  for (let attempt = 1; attempt <= 12; attempt += 1) {
    await page.waitForLoadState('domcontentloaded', { timeout: 10000 }).catch(() => {});
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});

    if (isIdentityAuthUrl(page.url())) {
      return;
    }

    content = (await combinedPageContent(page)).trim();
    if (content.length > 0) {
      return;
    }

    if (attempt === 1) {
      console.log(`   ⚠️  ${route.label} anonymous login page rendered blank during startup; waiting for service page readiness...`);
    }
    await page.waitForTimeout(2500);
    await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
  }

  throw new Error(`${route.label} anonymous login page remained blank after bounded readiness navigation.`);
}

function smokeSelectorLocator(page: Page, selector: string): Locator {
  const alternatives = selector.includes(', text=')
    ? selector.split(/,\s*(?=text=)/).map((part) => part.trim()).filter(Boolean)
    : [selector];

  return alternatives
    .map((part) => page.locator(part))
    .reduce((combined, locator) => combined.or(locator));
}

async function isAnySmokeSelectorVisible(page: Page, selector: string): Promise<boolean> {
  const locator = smokeSelectorLocator(page, selector);
  const countFn = (locator as unknown as { count?: () => Promise<number> }).count;
  const nthFn = (locator as unknown as { nth?: (index: number) => Locator }).nth;

  if (typeof countFn !== 'function' || typeof nthFn !== 'function') {
    return locator.first().isVisible().catch(() => false);
  }

  const count = await countFn.call(locator).catch(() => 0);
  for (let index = 0; index < Math.min(count, 50); index += 1) {
    if (await nthFn.call(locator, index).isVisible().catch(() => false)) {
      return true;
    }
  }

  return false;
}

async function isSmokeReady(page: Page, smoke: SmokeContract): Promise<boolean> {
  if (smoke.selector) {
    const selectorVisible = await isAnySmokeSelectorVisible(page, smoke.selector);
    if (!selectorVisible) {
      return false;
    }
  }

  const content = await combinedPageContent(page);
  if (!smoke.matcher.test(content)) {
    return false;
  }

  if (smoke.disallowMatcher?.test(content)) {
    return false;
  }

  if (smoke.disallowUrlMatcher?.test(page.url())) {
    return false;
  }

  return true;
}

async function expectIdentityLogin(page: Page): Promise<void> {
  await expect
    .poll(() => isIdentityAuthUrl(page.url()), {
      timeout: 20000,
      message: `Expected request to land on the ${defaultIdentityProvider.label} login boundary.`,
    })
    .toBe(true);

  const username = page.locator('input[name="username"], input[autocomplete="username"], #username-textfield, #username').first();
  const password = page.locator('input[name="password"], input[type="password"], #password-textfield, #password').first();
  await expect(username).toBeVisible({ timeout: 15000 });
  await expect(password).toBeVisible({ timeout: 15000 });
}

async function loginWithDefaultProvider(page: Page, user: BrowserTestUser): Promise<void> {
  const authLogin = new KeycloakLoginPage(page);
  await authLogin.login(user.username, user.password);
}

async function waitForSmokeReady(page: Page, smoke: SmokeContract, route: BrowserRoute): Promise<void> {
  const deadline = Date.now() + 60000;
  let nextRecoveryAt = Date.now() + 7000;
  let lastContent = '';

  while (Date.now() < deadline) {
    await page.waitForLoadState('domcontentloaded', { timeout: 5000 }).catch(() => {});
    await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {});

    if (await isSmokeReady(page, smoke).catch(() => false)) {
      return;
    }

    lastContent = await combinedPageContent(page);
    const contentLooksStuck = lastContent.trim().length === 0 || /\bLoading\.\.\.|\btaking longer than usual\b/i.test(lastContent);
    if (contentLooksStuck && smoke.path && Date.now() >= nextRecoveryAt) {
      await gotoWithRetry(page, routeUrl(route, smoke.path)).catch(() => {});
      nextRecoveryAt = Date.now() + 7000;
    }

    await page.waitForTimeout(1000);
  }

  const summary = lastContent.trim().replace(/\s+/g, ' ').slice(0, 240) || '<empty page>';
  throw new Error(`${route.label} authenticated page did not satisfy smoke contract at ${redactUrlForLogs(page.url())}; content: ${summary}`);
}

function smokePathForUser(smoke: SmokeContract, user: BrowserTestUser): string | undefined {
  return smoke.pathForUser?.({ username: user.username, email: user.email }) ?? smoke.path;
}

export function isBookStackTransientOidcErrorState(content: string, url: string): boolean {
  return /an error occurred|unknown error occurred/i.test(content) || /\/oidc\/callback\b/i.test(url);
}

async function recoverBookStackTransientOidcError(page: Page): Promise<boolean> {
  const content = await combinedPageContent(page);
  if (!isBookStackTransientOidcErrorState(content, page.url())) {
    return false;
  }

  console.log('   ⚠️  BookStack hit transient OIDC callback error; attempting session recovery...');

  const homeUrl = new URL('/', page.url()).toString();
  await page.goto(homeUrl, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});

  const homeContent = await combinedPageContent(page);
  const recoveredOnDashboard =
    /\bBooks\b|\bShelves\b|My Recently Viewed|Recent Activity|Recently Updated Pages|My Account|Logout/i.test(homeContent)
    && !/\/login\b/i.test(page.url());
  if (recoveredOnDashboard) {
    console.log('   ✅ BookStack session recovered after callback error.');
    return true;
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
    return true;
  }

  return false;
}

async function completeOidcLogin(page: Page, route: BrowserRoute, loginLabel: string, user: BrowserTestUser): Promise<void> {
  const oidcLogin = new OIDCLoginPage(page);
  const oidcStartPath = route.smoke?.oidcStartPath;

  if (oidcStartPath && !defaultIdentityProvider.isAuthUrl(page.url())) {
    await gotoWithRetry(page, routeUrl(route, oidcStartPath));
  } else if (!defaultIdentityProvider.isAuthUrl(page.url())) {
    if (route.anonymous.kind === 'service_login') {
      await waitForServiceLoginContent(page, route, route.anonymous).catch(() => {});
    }
    await oidcLogin.clickOIDCButton(loginLabel, { requireAuthRedirect: false });
  }

  if (defaultIdentityProvider.isConsentUrl(page.url())) {
    await oidcLogin.handleConsentScreen();
  } else if (defaultIdentityProvider.isAuthUrl(page.url())) {
    await loginWithDefaultProvider(page, user);
  }

  if (defaultIdentityProvider.isConsentUrl(page.url())) {
    await oidcLogin.handleConsentScreen();
  }

  await page.waitForURL((url) => !defaultIdentityProvider.isAuthUrl(url.toString()), { timeout: 30000 }).catch(() => {});

  if (route.host === 'bookstack') {
    await recoverBookStackTransientOidcError(page);
  }
}

async function assertAnonymousForwardAuth(page: Page): Promise<void> {
  await expectIdentityLogin(page);
}

async function assertAnonymousServiceLogin(page: Page, route: BrowserRoute, contract: Extract<AnonymousContract, { kind: 'service_login' }>): Promise<void> {
  const onAuthBoundary = defaultIdentityProvider.isAuthUrl(page.url());
  if (onAuthBoundary) {
    if (!contract.allowAuthRedirect) {
      throw new Error(`${route.label} unexpectedly redirected to ${defaultIdentityProvider.label} instead of rendering its service login page.`);
    }
    await expectIdentityLogin(page);
    return;
  }

  await expect
    .poll(() => routeUrlPattern(route.host).test(page.url()), {
      timeout: 15000,
      message: `Expected ${route.label} anonymous visit to stay on ${route.host}.${new URL(routeUrl(route)).hostname.split('.').slice(-2).join('.')}`,
    })
    .toBe(true);
  await waitForServiceLoginContent(page, route, contract);
  if (isIdentityAuthUrl(page.url())) {
    if (!contract.allowAuthRedirect) {
      throw new Error(`${route.label} unexpectedly redirected to ${defaultIdentityProvider.label} instead of rendering its service login page.`);
    }
    await expectIdentityLogin(page);
    return;
  }
  await expectPageMatcher(page, contract.matcher, `${route.label} anonymous login page`);
}

export async function assertAnonymousContract(page: Page, route: BrowserRoute): Promise<void> {
  const contract = route.anonymous;
  const visitPath = 'path' in contract ? contract.path : undefined;
  await gotoWithRetry(page, routeUrl(route, visitPath));

  switch (contract.kind) {
    case 'public_page':
      await expectPageMatcher(page, contract.matcher, `${route.label} public page`);
      return;
    case 'forward_auth':
      await assertAnonymousForwardAuth(page);
      return;
    case 'service_login':
      await assertAnonymousServiceLogin(page, route, contract);
      return;
    case 'canonical_redirect': {
      const targetRoute = findRoute(contract.targetHost);
      await expect
        .poll(() => !/^https:\/\/www\./i.test(page.url()), {
          timeout: 15000,
          message: `${route.label} should not remain on the www host.`,
        })
        .toBe(true);
      if (contract.followup === 'forward_auth') {
        await expectIdentityLogin(page);
      } else if (contract.matcher) {
        await expectPageMatcher(page, contract.matcher, `${route.label} redirected page`);
      }
      if (contract.followup === 'public_page') {
        await expect
          .poll(() => routeUrlPattern(targetRoute.host).test(page.url()), {
            timeout: 15000,
            message: `${route.label} should redirect to ${targetRoute.label}.`,
          })
          .toBe(true);
      }
      return;
    }
    case 'non_ui':
      return;
    case 'orphaned':
      throw new Error(
        `${route.label} is marked orphaned and must be removed from Caddy exposure or catalogued correctly: ${contract.reason}`
      );
    default:
      throw new Error(`Unsupported anonymous contract for route ${route.host}`);
  }
}

export async function assertSmokeContract(page: Page, route: BrowserRoute, user: BrowserTestUser): Promise<void> {
  if (!route.smoke) {
    throw new Error(`${route.label} is not part of the smoke suite.`);
  }

  await applySmokeHeaders(page, route.smoke);
  const targetPath = smokePathForUser(route.smoke, user);
  await gotoWithRetry(page, routeUrl(route, targetPath));

  if (route.kind === 'public') {
    await waitForSmokeReady(page, route.smoke, route);
    return;
  }

  if (route.kind === 'forward_auth') {
    if (isIdentityAuthUrl(page.url())) {
      await loginWithDefaultProvider(page, user);
      if (isIdentityAuthUrl(page.url())) {
        await gotoWithRetry(page, routeUrl(route, route.smoke.path));
      }
    }

    await waitForSmokeReady(page, route.smoke, route);
    return;
  }

  if (route.kind === 'oidc_login') {
    const loginLabel = route.smoke.loginLabel || defaultIdentityProvider.label;
    const alreadyReady = await isSmokeReady(page, route.smoke).catch(() => false);

    if (!alreadyReady) {
      await completeOidcLogin(page, route, loginLabel, user);
      if (targetPath) {
        await gotoWithRetry(page, routeUrl(route, targetPath));
      }
    }

    await waitForSmokeReady(page, route.smoke, route);
    return;
  }

  throw new Error(`${route.label} has unsupported smoke route kind '${route.kind}'.`);
}

export async function captureVisualSnapshot(
  page: Page,
  route: BrowserRoute,
  user: BrowserTestUser,
  screenshotRoot: string,
): Promise<string> {
  const visual = route.visual as VisualContract | undefined;
  if (!visual) {
    throw new Error(`${route.label} is not part of the visual suite.`);
  }

  const effectiveSmoke: SmokeContract = {
    matcher: visual.matcher,
    path: visual.path,
    selector: visual.selector,
    loginLabel: visual.loginLabel,
    disallowMatcher: visual.disallowMatcher,
    disallowUrlMatcher: visual.disallowUrlMatcher,
    headers: visual.headers,
    oidcStartPath: visual.oidcStartPath,
    pathForUser: visual.pathForUser,
  };

  await assertSmokeContract(page, { ...route, smoke: effectiveSmoke }, user);

  const screenshotDir = path.join(screenshotRoot, 'visual');
  fs.mkdirSync(screenshotDir, { recursive: true });
  const screenshotPath = path.join(screenshotDir, `${visual.fileStem}.jpeg`);
  await page.screenshot({
    path: screenshotPath,
    type: 'jpeg',
    quality: visual.quality ?? 85,
    fullPage: visual.fullPage ?? true,
  });

  return screenshotPath;
}
