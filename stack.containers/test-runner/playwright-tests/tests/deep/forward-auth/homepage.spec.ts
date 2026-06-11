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

  test('Portal - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Stack Portal',
      serviceUrl('portal'),
      /(Stack Portal|contract-backed modules|SOGo)/i
    );

    await expect(
      page.getByRole('link', { name: /SOGo/i }).first(),
      'Portal should advertise SOGo as a client-facing mail/calendar app'
    ).toBeVisible();
    await expect(page.getByText(/Mail, calendar, and contacts/i).first()).toBeVisible();
  });
