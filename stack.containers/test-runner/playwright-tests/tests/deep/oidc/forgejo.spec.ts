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

test('Forgejo - OIDC login flow', async ({ page }) => {
    const forgejoBaseDomain = guessBaseDomain(new URL(serviceUrl('forgejo')).hostname);
    await testOIDCService(
      page,
      'Forgejo',
      serviceUrl('forgejo'),
      /Dashboard|Your Repositories|New Repository|Issues|Pull Requests|Repositories/i,
      ['Keycloak', 'OpenID', 'OpenID Connect', 'OIDC'],
      {
        disallowUrlPatterns: [/\/user\/login\b/i],
        loginPath: serviceUrl('forgejo', '/user/login'),
        loginButtonPatterns: [/sign in|log in/i],
        oidcLinkPatterns: [/keycloak/i, /openid/i, /oidc/i],
        oidcIssuer: `https://keycloak.${forgejoBaseDomain}`,
        authenticatedRecoveryPath: serviceUrl('forgejo', '/user/settings'),
        authenticatedProbe: async (page) => {
          const fullNameInput = page.locator('input[name="full_name"], input#full_name').first();
          if (await fullNameInput.isVisible().catch(() => false)) {
            return true;
          }

          const bodyText = (await page.textContent('body').catch(() => '')) || '';
          return /Full name|Account|Email Address|Repositories|Dashboard/i.test(bodyText);
        },
        postLogin: async (page) => {
          await assertForgejoDisplayName(page);
        },
      }
    );
  });
