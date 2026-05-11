import { test, expect } from '@playwright/test';
import type { Locator, Page, Response } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { KeycloakLoginPage } from '../../../pages/KeycloakLoginPage';
import { OIDCLoginPage } from '../../../pages/OIDCLoginPage';
import {
  assertBookStackDisplayName,
  assertElementDisplayName,
  expectPropagatedDisplayName,
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

test('Element MatrixRTC discovery is internal LiveKit only', async ({ request }) => {
    const wellKnownResponse = await request.get(`https://${domain}/.well-known/matrix/client`);
    expect(wellKnownResponse.ok()).toBe(true);
    const wellKnown = await wellKnownResponse.json();

    expect(wellKnown['org.matrix.msc4143.rtc_foci']).toEqual([
      {
        type: 'livekit',
        livekit_service_url: `https://matrix-rtc.${domain}/livekit/jwt`,
      },
    ]);
    expect(JSON.stringify(wellKnown).toLowerCase()).not.toContain('jitsi');

    const elementConfigResponse = await request.get(serviceUrl('element', '/config.json'));
    expect(elementConfigResponse.ok()).toBe(true);
    const elementConfig = await elementConfigResponse.json();
    const elementConfigText = JSON.stringify(elementConfig);
    expect(elementConfig.element_call).toMatchObject({
      use_exclusively: true,
    });
    expect(elementConfigText).not.toMatch(/jitsi|meet\.jit\.si|scalar|vector\.im|riot\.im/i);
});

test('Element (Matrix Web) - OIDC login flow', async ({ page }) => {
    test.setTimeout(180000);
    const homeserverUrl = `https://matrix.${domain}`;
    await testOIDCService(
      page,
      'Element (Matrix Web)',
      serviceUrl('element'),
      /All rooms|Home|People|Rooms|Explore|Settings|Chats|Start chat|Recents|Room|Start a chat|Create a room|People|Setting up keys/i,
      ['Keycloak', 'Continue with Keycloak SSO', 'Continue with SSO', 'SSO', 'Single sign-on', 'Sign in with SSO'],
      {
        disallowPatterns: [/Welcome to Element/i, /Sign in/i, /Create Account/i],
        disallowUrlPatterns: [/#\/welcome\b/i, /#\/login\b/i],
        loginPath: serviceUrl('element', '/#/login'),
        loginButtonPatterns: [/sign in|log in|continue with keycloak sso|continue with sso|sso|openid/i],
        oidcLinkPatterns: [/continue with keycloak sso/i, /sign in with sso/i, /sso/i, /single sign-on/i, /keycloak/i],
        authenticatedRecoveryPath: serviceUrl('element'),
        authenticatedProbe: async (page) => {
          const state = await page.evaluate(() => {
            const snapshot: Record<string, string> = {};
            for (let i = 0; i < window.localStorage.length; i += 1) {
              const key = window.localStorage.key(i);
              if (!key) continue;
              snapshot[key] = window.localStorage.getItem(key) || '';
            }

            return {
              userId: snapshot.mx_user_id || snapshot.mx_userid || '',
              homeserverUrl: snapshot.mx_hs_url || '',
              profileDisplayName: snapshot.mx_profile_displayname || '',
              hasAccessToken: snapshot.mx_has_access_token === 'true' || Boolean(snapshot.mx_access_token),
              href: window.location.href,
              bodyText: document.body?.innerText || '',
            };
          }).catch(() => ({
            userId: '',
            homeserverUrl: '',
            profileDisplayName: '',
            hasAccessToken: false,
            href: '',
            bodyText: '',
          }));
          const loginShell = /#\/(?:login|welcome)\b/i.test(state.href)
            || /Sign in\s+Homeserver|New here\?|Welcome to Element/i.test(state.bodyText);
          if (loginShell) {
            return false;
          }

          return Boolean(
            (state.userId && state.homeserverUrl)
            || (state.profileDisplayName && state.hasAccessToken)
            || /\b(All rooms|People|Rooms|Explore|Settings|Chats|Start chat|Recents|Create a room|Setting up keys|Verify this device)\b|Are you sure\?/i.test(state.bodyText)
          );
        },
        uiPatternOverride: /Matrix ID|All rooms|People|Rooms|Explore|Settings|Chats|Start chat|Recents|Start a chat|Create a room|Setting up keys/i,
        screenshotFullPage: false,
        preLogin: async (page) => {
          const signInLink = page.getByRole('link', { name: /sign in/i }).first();
          const signInButton = page.getByRole('button', { name: /sign in/i }).first();
          if (await signInLink.isVisible().catch(() => false)) {
            await signInLink.click({ force: true });
            await page.waitForTimeout(1000);
          } else if (await signInButton.isVisible().catch(() => false)) {
            await signInButton.click({ force: true });
            await page.waitForTimeout(1000);
          }

          const homeserverInput = page.locator(
            'input[placeholder*="matrix"], input[name*="home"], input[id*="home"]'
          ).first();
          const continueButton = page.getByRole('button', { name: /continue|next|submit/i }).first();
          if (await homeserverInput.isVisible().catch(() => false)) {
            const currentValue = await homeserverInput.inputValue().catch(() => '');
            if (!currentValue) {
              await homeserverInput.fill(homeserverUrl);
            }
            if (await continueButton.isVisible().catch(() => false)) {
              await continueButton.click({ force: true });
            } else {
              await homeserverInput.press('Enter').catch(() => {});
            }
            await page.waitForTimeout(1500);
          }

          const keycloakSsoButton = page.getByRole('button', { name: /continue with keycloak sso/i }).first();
          await keycloakSsoButton.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {});
          if (await keycloakSsoButton.isVisible().catch(() => false)) {
            await keycloakSsoButton.click({ force: true, noWaitAfter: true });
            await page.waitForURL(
              (url) => /keycloak|keycloak-auth/.test(url.toString()),
              { timeout: 15000 }
            ).catch(() => {});
            return;
          }

          const anySsoButton = page.getByRole('button', {
            name: /sign in with sso|continue with sso|single sign-on|sso/i,
          }).or(
            page.getByRole('link', {
              name: /sign in with sso|continue with sso|single sign-on|sso/i,
            })
          ).first();
          await anySsoButton.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
          if (await anySsoButton.isVisible().catch(() => false)) {
            await anySsoButton.click({ force: true, noWaitAfter: true });
            await page.waitForURL(
              (url) => /keycloak|keycloak-auth/.test(url.toString()),
              { timeout: 15000 }
            ).catch(() => {});
          }
        },
        postLogin: async (page) => {
          const expectedElementDisplayName = requireExpectedDisplayName('Element (Matrix Web)');
          const masCreateAccountButton = page.getByRole('button', { name: /create account/i }).first();
          const masImportHeading = page.getByRole('heading', { name: /import your data/i }).first();
          await masImportHeading.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
          if (await masCreateAccountButton.isVisible().catch(() => false)) {
            await masCreateAccountButton.click({ force: true });
            await page.waitForURL(
              (url) => !url.toString().includes('/upstream/link/'),
              { timeout: 60000 }
            ).catch(() => {});
            await page.waitForLoadState('domcontentloaded', { timeout: 15000 }).catch(() => {});
          }

          const masContinueButton = page.getByRole('button', { name: /^continue$/i }).first();
          const masContinueHeading = page.getByRole('heading', { name: /continue to element\./i }).first();
          await masContinueHeading.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
          if (await masContinueButton.isVisible().catch(() => false)) {
            await masContinueButton.click({ force: true });
            await page.waitForURL(
              (url) => /element\./i.test(url.hostname) || url.toString().includes('loginToken='),
              { timeout: 60000 }
            ).catch(() => {});
            await page.waitForLoadState('domcontentloaded', { timeout: 15000 }).catch(() => {});
          }

          if (page.url().includes('loginToken=')) {
            const progress = page.getByRole('progressbar').first();
            if (await progress.isVisible().catch(() => false)) {
              await progress.waitFor({ state: 'hidden', timeout: 30000 }).catch(() => {});
            }
            await page.waitForURL(
              (url) => !url.toString().includes('loginToken='),
              { timeout: 60000 }
            ).catch(() => {});
          }

          const setupKeysDialog = page.locator('text=/setting up keys/i').first();
          if (await setupKeysDialog.isVisible().catch(() => false)) {
            await setupKeysDialog.waitFor({ state: 'hidden', timeout: 90000 }).catch(() => {});
            await page.waitForTimeout(1500);
          }

          const verifyLaterButton = page.getByRole('button', { name: /i'?ll verify later|verify later/i }).first();
          const areYouSureHeading = page.locator('text=/are you sure\\?/i').first();
          await areYouSureHeading.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
          if ((await areYouSureHeading.isVisible().catch(() => false)) || (await verifyLaterButton.isVisible().catch(() => false))) {
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
          }

          const verifyHeading = page.locator('text=/verify this device/i').first();
          await verifyHeading.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
          if (await verifyHeading.isVisible().catch(() => false)) {
            const skipButton = page.getByRole('button', { name: /skip verification/i }).first();
            if (await skipButton.isVisible().catch(() => false)) {
              await skipButton.click({ force: true }).catch(() => {});
              await page.waitForTimeout(1500);
            } else {
              const closeButton = page.locator(
                'button[aria-label="Close"], button[aria-label="Close dialog"], button:has-text("Close")'
              ).first();
              if (await closeButton.isVisible().catch(() => false)) {
                await closeButton.click({ force: true }).catch(() => {});
              } else {
                await page.keyboard.press('Escape').catch(() => {});
              }
              await page.waitForTimeout(1500);
            }
          }

          const cleanupButtons = [
            page.getByRole('button', { name: /dismiss/i }).first(),
            page.getByRole('button', { name: /^ok$/i }).first(),
            page.getByRole('button', { name: /got it/i }).first(),
          ];
          for (const button of cleanupButtons) {
            if (await button.isVisible().catch(() => false)) {
              await button.click({ force: true }).catch(() => {});
              await page.waitForTimeout(400);
            }
          }

          const localStorageSession = await page.evaluate(() => {
            const snapshot: Record<string, string> = {};
            for (let i = 0; i < window.localStorage.length; i += 1) {
              const key = window.localStorage.key(i);
              if (!key) continue;
              snapshot[key] = window.localStorage.getItem(key) || '';
            }

            return {
              userId: snapshot.mx_user_id || snapshot.mx_userid || '',
              accessToken: snapshot.mx_access_token || '',
              homeserverUrl: snapshot.mx_hs_url || '',
              profileDisplayName: snapshot.mx_profile_displayname || '',
              snapshot,
            };
          });

          let matrixUserId = localStorageSession.userId;
          let matrixHomeserverUrl = localStorageSession.homeserverUrl;
          const matrixAccessToken = normalizedString(localStorageSession.accessToken);
          const profileDisplayName = normalizedString(localStorageSession.profileDisplayName);

          if (matrixAccessToken && (!matrixUserId || !matrixHomeserverUrl)) {
            const whoAmIResponse = await page.request.get(`${homeserverUrl}/_matrix/client/v3/account/whoami`, {
              headers: {
                Authorization: `Bearer ${matrixAccessToken}`,
              },
            });
            if (whoAmIResponse.ok()) {
              const whoAmI = await whoAmIResponse.json();
              matrixUserId = matrixUserId || whoAmI.user_id || '';
              matrixHomeserverUrl = matrixHomeserverUrl || homeserverUrl;
            }
          }

          if (!matrixUserId || !matrixHomeserverUrl) {
            const snapshotKeys = Object.keys(localStorageSession.snapshot).sort().join(', ');
            throw new Error(`Element session data was incomplete. localStorage keys=${snapshotKeys}`);
          }

          const normalizeUrl = (value: string) => value.replace(/\/+$/, '');
          expect(normalizeUrl(matrixHomeserverUrl)).toBe(normalizeUrl(homeserverUrl));
          expect(matrixUserId).toMatch(new RegExp(`:matrix\\.${escapeRegex(domain)}$`, 'i'));

          if (matrixAccessToken) {
            await assertElementDisplayName(page, homeserverUrl, matrixAccessToken, matrixUserId);
          } else if (profileDisplayName) {
            expectPropagatedDisplayName(
              'Element (Matrix Web)',
              profileDisplayName,
              'localStorage profile display name',
            );
          } else {
            const bodyText = (await page.textContent('body').catch(() => '')) || '';
            expect(
              bodyText,
              'Element should visibly render the propagated display name in the authenticated UI when the client keeps tokens outside localStorage'
            ).toMatch(new RegExp(`Welcome\\s+${escapeRegex(expectedElementDisplayName)}|${escapeRegex(expectedElementDisplayName)}`, 'i'));
          }

          await page.evaluate(({ matrixUserId: userId, matrixHomeserverUrl: hsUrl }) => {
            const existing = document.getElementById('__element-server-evidence');
            if (existing) {
              existing.remove();
            }

            const banner = document.createElement('div');
            banner.id = '__element-server-evidence';
            banner.innerHTML = `<strong>Homeserver</strong>: ${hsUrl}<br /><strong>Matrix ID</strong>: ${userId}`;
            banner.style.position = 'fixed';
            banner.style.right = '16px';
            banner.style.bottom = '16px';
            banner.style.zIndex = '2147483647';
            banner.style.padding = '12px 14px';
            banner.style.background = 'rgba(15, 23, 42, 0.92)';
            banner.style.color = '#f8fafc';
            banner.style.border = '1px solid rgba(148, 163, 184, 0.45)';
            banner.style.borderRadius = '10px';
            banner.style.boxShadow = '0 12px 30px rgba(15, 23, 42, 0.35)';
            banner.style.fontFamily = 'system-ui, sans-serif';
            banner.style.fontSize = '14px';
            banner.style.lineHeight = '1.4';
            document.body.appendChild(banner);
          }, { matrixUserId, matrixHomeserverUrl });

          await page.waitForTimeout(1200);
        },
      }
    );
  });
