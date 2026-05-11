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

  test('Homepage - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Homepage',
      serviceUrl('homepage'),
      /(Homepage|AI & Development|Collaboration|System)/i // Title is "Homepage" and has service group buttons
    );

    await expect(
      page.getByRole('link', { name: /SOGo/i }).first(),
      'Homepage should advertise SOGo as a client-facing mail/calendar app'
    ).toBeVisible();
    await expect(page.getByText(/Mail, calendar, and contacts/i).first()).toBeVisible();
  });
