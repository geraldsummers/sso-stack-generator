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

  test('Vaultwarden - Access with forward auth', async ({ page }) => {
    // Vaultwarden requires explicit OIDC (SSO_ONLY=true). Validate that forward-auth does not
    // grant access and that we land on Vaultwarden's SSO/login UI.
    await testForwardAuthService(
      page,
      'Vaultwarden (forward-auth)',
      serviceUrl('vaultwarden'),
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

