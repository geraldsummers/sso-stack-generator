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

  test('Kopia - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Kopia',
      serviceUrl('kopia'),
      /Kopia|Snapshots|Repository|Policies/i // Look for Kopia UI elements or title
    );
  });
