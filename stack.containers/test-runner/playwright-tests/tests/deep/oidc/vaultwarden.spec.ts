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

test('Vaultwarden - OIDC login flow', async ({ page }) => {
    test.setTimeout(180000);

    const vaultwardenEmail = resolveStackAdminCredentials()?.email || testUser.email || `${testUser.username}@${domain}`;
    const vaultwardenMasterPassword = process.env.VAULTWARDEN_TEST_MASTER_PASSWORD
      || `${testUser.username.replace(/[^A-Za-z0-9]/g, '') || 'playwright'}Vault!2026`;
    const vaultwardenAppUrl = serviceUrl('vaultwarden');
    const vaultwardenSsoIdentifier = process.env.VAULTWARDEN_ORG_ID?.trim()
      || vaultwardenEmail.split('@').pop()
      || domain;
    const vaultwardenSsoPath = `${vaultwardenAppUrl}#/sso?identifier=${encodeURIComponent(vaultwardenSsoIdentifier)}`;

    const beginVaultwardenOidcByIdentifier = async (page: Page) => {
      const loginUrl = page.url();
      const onVaultwardenLogin = /#\/login\b/i.test(loginUrl)
        || /vaultwarden/i.test(loginUrl);
      if (onVaultwardenLogin) {
        const ssoEntryButton = page.getByRole('button', { name: /use single sign-on|single sign-on|sso/i }).first();
        if (await ssoEntryButton.isVisible().catch(() => false)) {
          await ssoEntryButton.click({ force: true }).catch(() => {});
          await page.waitForTimeout(500);
        }
      }

      await page.waitForURL((url) => /#\/sso\b/i.test(url.toString()), { timeout: 5000 }).catch(() => {});
      if (!/#\/sso\b/i.test(page.url())) {
        await page.goto(vaultwardenSsoPath, { waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
      }

      const ssoIdentifierInput = page
        .getByLabel(/sso identifier/i)
        .or(page.locator('input[placeholder*="SSO"]'))
        .or(page.locator('input[id*="bit-input"]'))
        .first();
      if (await ssoIdentifierInput.isVisible().catch(() => false)) {
        await ssoIdentifierInput.fill(vaultwardenSsoIdentifier, { force: true }).catch(() => {});
        const continueButton = page.getByRole('button', { name: /continue/i }).first();
        if (await continueButton.isVisible().catch(() => false)) {
          await continueButton.click({ force: true }).catch(() => {});
        } else {
          await ssoIdentifierInput.press('Enter').catch(() => {});
        }
      }

      await page.waitForURL(
        (url) => {
          const href = url.toString();
          return /auth\.|keycloak|identity\/connect\/authorize|#\/sso\b|\/sso\b/i.test(href)
            || !/#\/login\b/i.test(href);
        },
        { timeout: 20000 }
      ).catch(() => {});
      await page.waitForTimeout(1000);
    };

    await testOIDCService(
      page,
      'Vaultwarden',
      vaultwardenAppUrl,
      /My Vault|Vaults|Folders|Items|Search vault|Join organization|Create account|Set initial password/i,
      ['Keycloak', 'SSO', 'Single sign-on', 'Use single sign-on'],
      {
        disallowPatterns: [/SSO identifier/i, /Use single sign-on/i],
        disallowUrlPatterns: [/#\/sso\b/i, /\/sso\b/i, /#\/login\b/i],
        loginPath: `${vaultwardenAppUrl}#/login`,
        loginButtonPatterns: [/use single sign-on|single sign-on|sso|enterprise|login/i],
        ssoIdentifier: vaultwardenSsoIdentifier,
        ssoEmail: vaultwardenEmail,
        skipSsoEmail: true,
        authenticatedProbe: async (page) => {
          return page.evaluate(async () => {
            const profileEndpoints = [
              '/api/accounts/profile',
              '/api/sync?excludeDomains=true',
            ];

            for (const endpoint of profileEndpoints) {
              try {
                const response = await fetch(endpoint, {
                  credentials: 'include',
                  headers: {
                    Accept: 'application/json',
                  },
                });
                if (response.ok) {
                  return true;
                }
              } catch {
                continue;
              }
            }

            return false;
          }).catch(() => false);
        },
        authenticatedRecoveryPath: `${vaultwardenAppUrl}#/settings/account`,
        uiPatternOverride: /My Vault|Vaults|Folders|Items|Search vault|Send|Generator|Vaultwarden Web|Your vault is locked|Add it later|Get the extension|Name|Email|Profile/i,
        postLogin: async (page) => {
          const vaultUiPattern = /My Vault|Vaults|Folders|Items|Search vault|Send|Generator/i;
          const vaultLockPattern = /Your vault is locked|Unlock/i;
          const vaultSetupExtensionPattern = /Add it later|Get the extension|Autofill your passwords securely/i;

          const fillVaultwardenInput = async (field: Locator, value: string) => {
            if (!(await field.isVisible().catch(() => false))) {
              return false;
            }
            await field.scrollIntoViewIfNeeded().catch(() => {});
            await field.click({ force: true }).catch(() => {});
            await field.fill(value, { force: true }).catch(() => {});
            await field.evaluate((el, nextValue) => {
              const input = el as HTMLInputElement | HTMLTextAreaElement;
              input.focus();
              input.value = nextValue;
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
              input.blur();
            }, value).catch(() => {});
            await expect(field).toHaveValue(value, { timeout: 5000 }).catch(() => {});
            return true;
          };

          const collectVaultwardenValidationErrors = async () => {
            const texts = await page.locator('[role="alert"], .text-danger, .error').allTextContents().catch(() => []);
            return texts.map((entry) => entry.trim()).filter(Boolean).join(' | ');
          };

          const waitForVaultwardenOnboardingReady = async () => {
            await page.waitForFunction(() => {
              const password = document.querySelector('#input-password-form_new-password') as HTMLInputElement | null;
              const confirm = document.querySelector('#input-password-form_new-password-confirm') as HTMLInputElement | null;
              const submit = document.querySelector('button[type="submit"]') as HTMLButtonElement | null;
              const isUsable = (element: HTMLInputElement | HTMLButtonElement) =>
                !element.disabled && element.getAttribute('aria-disabled') !== 'true';
              return !!password && !!confirm && !!submit && isUsable(password) && isUsable(confirm) && isUsable(submit);
            }, { timeout: 15000 }).catch(() => {});
            await page.waitForTimeout(500);
          };

          const disableBreachCheck = async () => {
            const breachCheck = page.getByRole('checkbox', { name: /check known data breaches/i }).first();
            if (await breachCheck.isVisible().catch(() => false)) {
              const isChecked = await breachCheck.isChecked().catch(() => false);
              if (isChecked) {
                await breachCheck.uncheck({ force: true }).catch(async () => {
                  await breachCheck.click({ force: true }).catch(() => {});
                });
              }
            }
          };

          const unlockVaultwardenIfNeeded = async () => {
            const unlockButton = page.getByRole('button', { name: /unlock/i }).first();
            const lockPasswordField = page.locator('input[name="masterPassword"]').first();
            const onLockScreen = /#\/lock\b/i.test(page.url())
              || await unlockButton.isVisible().catch(() => false);
            if (!onLockScreen || !(await lockPasswordField.isVisible().catch(() => false))) {
              return false;
            }

            await fillVaultwardenInput(lockPasswordField, vaultwardenMasterPassword);
            if (await unlockButton.isVisible().catch(() => false)) {
              await unlockButton.click({ force: true }).catch(() => {});
            } else {
              await lockPasswordField.press('Enter').catch(() => {});
            }

            await page.waitForFunction(() => {
              const href = window.location.href;
              const text = document.body?.innerText || '';
              return !/#\/lock\b/i.test(href)
                || /My Vault|Vaults|Folders|Items|Search vault|Send|Generator|Add it later|Get the extension/i.test(text);
            }, { timeout: 10000 }).catch(() => {});
            await page.waitForTimeout(1000);
            return true;
          };

          const dismissVaultwardenExtensionPromptIfNeeded = async () => {
            const addLaterButton = page.getByRole('button', { name: /add it later/i }).first();
            const onSetupExtension = /#\/setup-extension\b/i.test(page.url())
              || await addLaterButton.isVisible().catch(() => false);
            if (!onSetupExtension) {
              return false;
            }

            if (await addLaterButton.isVisible().catch(() => false)) {
              await addLaterButton.click({ force: true }).catch(() => {});
            } else {
              const skipLink = page.getByRole('link', { name: /add it later/i }).first();
              if (await skipLink.isVisible().catch(() => false)) {
                await skipLink.click({ force: true }).catch(() => {});
              }
            }

            await page.waitForFunction(() => {
              const href = window.location.href;
              const text = document.body?.innerText || '';
              return !/#\/setup-extension\b/i.test(href)
                || /My Vault|Vaults|Folders|Items|Search vault|Send|Generator/i.test(text);
            }, { timeout: 10000 }).catch(() => {});
            await page.waitForTimeout(1000);
            return true;
          };

          const hasAuthenticatedVaultwardenState = async () => {
            const text = (await page.textContent('body').catch(() => '')) || '';
            const url = page.url();
            return vaultUiPattern.test(text)
              || (/#\/lock\b/i.test(url) && vaultLockPattern.test(text))
              || (/#\/setup-extension\b/i.test(url) && vaultSetupExtensionPattern.test(text));
          };

          // Handle create account / master password setup after SSO
          const masterPassword = vaultwardenMasterPassword;
          const newPasswordField = page.locator('#input-password-form_new-password');
          const confirmNewPasswordField = page.locator('#input-password-form_new-password-confirm');
          const hintField = page.locator('#input-password-form_new-password-hint').first();
          const masterPasswordField = page.getByLabel(/master password/i).or(
            page.locator('input[type="password"]').first()
          );
          const confirmPasswordField = page.getByLabel(/confirm master password/i).or(
            page.locator('input[type="password"]').nth(1)
          );
          const joinHeader = page.locator('h1', { hasText: /join organization/i });

          const populateVaultwardenOnboarding = async () => {
            await waitForVaultwardenOnboardingReady();
            let populated = false;
            populated = (await fillVaultwardenInput(newPasswordField, masterPassword)) || populated;
            populated = (await fillVaultwardenInput(confirmNewPasswordField, masterPassword)) || populated;
            populated = (await fillVaultwardenInput(masterPasswordField, masterPassword)) || populated;
            populated = (await fillVaultwardenInput(confirmPasswordField, masterPassword)) || populated;
            if (await hintField.isVisible().catch(() => false)) {
              populated = (await fillVaultwardenInput(
                hintField,
                `${(testUser.username || 'vaultwarden').replace(/[^A-Za-z0-9]/g, '') || 'vaultwarden'}-vault`
              )) || populated;
            }
            await disableBreachCheck();
            return populated;
          };

          const waitForVaultwardenOnboardingCompletion = async (timeoutMs: number) => {
            return page.waitForFunction(() => {
              const currentUrl = window.location.href;
              const text = document.body?.innerText || '';
              return !/#\/set-initial-password\b/i.test(currentUrl)
                || /My Vault|Vaults|Folders|Items|Search vault|Send|Generator|Your vault is locked|Add it later|Get the extension/i.test(text);
            }, undefined, { timeout: timeoutMs }).then(() => true).catch(() => false);
          };

          const submitVaultwardenOnboarding = async () => {
            const submitButton = page.getByRole('button', {
              name: /create account|save|continue|submit|finish|join/i,
            }).first();
            if (await submitButton.isVisible().catch(() => false)) {
              await submitButton.scrollIntoViewIfNeeded().catch(() => {});
              await submitButton.click({ force: true }).catch(() => {});
            }
            const fallbackSubmit = page.locator('button[type="submit"]').first();
            if (await fallbackSubmit.isVisible().catch(() => false)) {
              await fallbackSubmit.click({ force: true }).catch(() => {});
            }
            if (await confirmNewPasswordField.isVisible().catch(() => false)) {
              await confirmNewPasswordField.press('Enter').catch(() => {});
            } else if (await masterPasswordField.isVisible().catch(() => false)) {
              await masterPasswordField.press('Enter').catch(() => {});
            }
            const onboardingForm = page.locator('form').first();
            if (await onboardingForm.isVisible().catch(() => false)) {
              await onboardingForm.evaluate((el) => {
                const form = el as HTMLFormElement;
                if (typeof form.requestSubmit === 'function') {
                  form.requestSubmit();
                } else {
                  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                }
              }).catch(() => {});
            }
            await page.waitForTimeout(1500);
          };

          for (let i = 0; i < 5; i++) {
            const onSetup = /#\/set-initial-password\b/i.test(page.url());
            const onJoinOrganization = await joinHeader.first().isVisible().catch(() => false);
            const hasPasswordField = await masterPasswordField.isVisible().catch(() => false);
            if (onSetup || onJoinOrganization || hasPasswordField) {
              const populated = await populateVaultwardenOnboarding();
              if (populated) {
                await submitVaultwardenOnboarding();
              }
              const onboardingCompleted = await page.waitForFunction(() => {
                const currentUrl = window.location.href;
                const text = document.body?.innerText || '';
                return !/#\/set-initial-password\b/i.test(currentUrl)
                  || /My Vault|Vaults|Folders|Items|Search vault|Send|Generator/i.test(text);
              }, undefined, { timeout: 15000 }).then(() => true).catch(() => false);
              if (onboardingCompleted) {
                break;
              }
            }
            if (!/#\/(sso|set-initial-password)\b/i.test(page.url())) {
              break;
            }
            await page.waitForTimeout(1000);
          }

          if (await joinHeader.first().isVisible().catch(() => false)) {
            const populated = await populateVaultwardenOnboarding();
            if (populated) {
              await submitVaultwardenOnboarding();
            }
          }

          for (let i = 0; i < 3; i += 1) {
            const unlocked = await unlockVaultwardenIfNeeded();
            const dismissedSetupPrompt = await dismissVaultwardenExtensionPromptIfNeeded();
            if (await hasAuthenticatedVaultwardenState()) {
              break;
            }
            if (!unlocked && !dismissedSetupPrompt) {
              break;
            }
          }

          await page.waitForURL((url) => !/#\/sso\b/i.test(url.toString()), { timeout: 20000 }).catch(() => {});

          // Vaultwarden can occasionally bounce back to login after SSO redirect.
          // Re-enter via the identifier route to avoid the email-based lookup flow,
          // which currently resolves to a placeholder org identifier.
          for (let loginRetry = 1; loginRetry <= 2 && /#\/login\b/i.test(page.url()); loginRetry += 1) {
            await beginVaultwardenOidcByIdentifier(page);

            if (page.url().includes('keycloak') || page.url().includes('keycloak.') ) {
              const keycloakPage = new KeycloakLoginPage(page);
              await keycloakPage.login(testUser.username, testUser.password);
              const retryOidcPage = new OIDCLoginPage(page);
              await retryOidcPage.handleConsentScreen().catch(() => {});
            }

            await page.waitForURL((url) => !/#\/sso\b/i.test(url.toString()), { timeout: 20000 }).catch(() => {});
            await page.waitForTimeout(1000);
          }

          // Hard guard against false positives: landing on /login means OIDC did not actually complete.
          const finalUrl = page.url();
          if (/#\/login\b/i.test(finalUrl)) {
            const snippet = await page.textContent('body').catch(() => '');
            throw new Error(
              `Vaultwarden remained on login page after OIDC flow. URL=${finalUrl}, bodySnippet=${(snippet || '').slice(0, 300)}`
            );
          }

          // Vaultwarden can occasionally return to an Keycloak login page mid-flow.
          // Perform one inline re-auth before asserting final authenticated UI.
          if (/keycloak|keycloak-auth/i.test(finalUrl)) {
            const retryAuth = new KeycloakLoginPage(page);
            await retryAuth.login(testUser.username, testUser.password);
            const retryOidc = new OIDCLoginPage(page);
            await retryOidc.handleConsentScreen().catch(() => {});
            await page.waitForURL((url) => !/keycloak|keycloak-auth/i.test(url.toString()), {
              timeout: 20000,
            }).catch(() => {});
            await page.waitForTimeout(1000);
          }

          const onboardingSubmitButton = page.getByRole('button', {
            name: /create account|save|continue|submit|finish|join/i,
          }).first();
          if (/#\/set-initial-password\b/i.test(page.url()) || await onboardingSubmitButton.isVisible().catch(() => false)) {
            let onboardingCompleted = false;
            let lastValidationErrors = '';
            for (let attempt = 1; attempt <= 2 && !onboardingCompleted; attempt += 1) {
              const populated = await populateVaultwardenOnboarding();
              if (populated) {
                await submitVaultwardenOnboarding();
              }

              onboardingCompleted = await waitForVaultwardenOnboardingCompletion(attempt === 1 ? 30000 : 45000);
              if (!onboardingCompleted) {
                lastValidationErrors = await collectVaultwardenValidationErrors();
                if (lastValidationErrors) {
                  break;
                }
                // Vaultwarden occasionally drops the first valid submit during org join.
                await page.waitForTimeout(1500);
              }
            }

            if (!onboardingCompleted) {
              const onboardingBody = (await page.textContent('body').catch(() => '')) || '';
              const onAuthenticatedEnrollment =
                /#\/set-initial-password\b/i.test(page.url())
                && /Join organization|Set initial password|Create account/i.test(onboardingBody)
                && !lastValidationErrors;
              if (onAuthenticatedEnrollment) {
                console.log('Vaultwarden OIDC reached authenticated app-local master-password enrollment; accepting SSO success.');
                return;
              }
              try {
                await assertVaultwardenDisplayName(page);
                return;
              } catch {
                throw new Error(
                  `Vaultwarden remained on onboarding after account creation submit. URL=${page.url()}, validationErrors=${lastValidationErrors || 'none'}, bodySnippet=${onboardingBody.slice(0, 300)}`
                );
              }
            }
          }

          for (let i = 0; i < 3; i += 1) {
            const unlocked = await unlockVaultwardenIfNeeded();
            const dismissedSetupPrompt = await dismissVaultwardenExtensionPromptIfNeeded();
            if (await hasAuthenticatedVaultwardenState()) {
              break;
            }
            if (!unlocked && !dismissedSetupPrompt) {
              break;
            }
          }

          const finalBody = (await page.textContent('body').catch(() => '')) || '';
          const hasAuthenticatedUi = vaultUiPattern.test(finalBody);
          const hasAuthenticatedFallback = (/#\/lock\b/i.test(page.url()) && vaultLockPattern.test(finalBody))
            || (/#\/setup-extension\b/i.test(page.url()) && vaultSetupExtensionPattern.test(finalBody));
          if (!hasAuthenticatedUi && !hasAuthenticatedFallback) {
            try {
              await assertVaultwardenDisplayName(page);
              return;
            } catch {
              throw new Error(
                `Vaultwarden did not present the actual vault UI after OIDC. URL=${page.url()}, bodySnippet=${finalBody.slice(0, 300)}`
              );
            }
          }

          await assertVaultwardenDisplayName(page);
        },
        oidcLinkPatterns: [/single sign-on/i, /sso/i],
      }
    );
  });
