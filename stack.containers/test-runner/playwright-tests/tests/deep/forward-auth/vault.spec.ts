import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import {
  authenticatedSessionState,
  domain,
  screenshotRoot,
  seafileOnlyOfficeFixturePath,
  testForwardAuthService,
  waitForGrafanaShell,
  waitForHomeAssistantShell,
} from '../shared/forward-auth';
import { serviceUrl } from '../../../utils/stack-urls';
import { logPageTelemetry, setupNetworkLogging } from '../../../utils/telemetry';

test.use({ storageState: authenticatedSessionState });

  test('Vault - Access with forward auth', async ({ page }) => {
    const vaultwardenSsoIdentifier = process.env.VAULTWARDEN_ORG_ID?.trim() || domain;

    // This endpoint should remain protected by Vaultwarden's OIDC flow.
    await testForwardAuthService(
      page,
      'Vault (Vaultwarden UI)',
      serviceUrl('vaultwarden', `/#/sso?identifier=${vaultwardenSsoIdentifier}`),
      /Single sign-on|Use single sign-on|SSO|Log in|Vaultwarden|Bitwarden|Join organization|Master password/i,
      {
        disallowPatterns: [/My Vault|Search vault/i],
        disallowUrlPatterns: [/#\/vault\b/i],
        maxPatternRetries: 4,
        retryDelayMs: 2000,
        screenshotSuffix: 'protected',
      }
    );
  });
