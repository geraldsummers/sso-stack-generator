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

  test('Session works across multiple forward-auth services', async ({ page }) => {
    test.setTimeout(120000);
    console.log('\n🧪 Testing session persistence across services');

    // Visit multiple services in sequence - should not require re-auth
    const services = [
      {
        name: 'JupyterHub',
        path: serviceUrl('jupyterhub', '/user-redirect/lab'),
        pattern: /JupyterHub|Start My Server|Control Panel|JupyterLab|Notebook|Files|New/i,
        options: {
          waitForUrlNotMatch: /\/spawn-pending\//i,
          waitForSelectorVisible: 'text=/Start My Server|Control Panel|Files|Notebook|JupyterLab/i',
          waitForSelectorTimeoutMs: 30000,
          skipScreenshot: true,
        },
      },
      {
        name: 'Prometheus',
        path: serviceUrl('prometheus'),
        pattern: /Prometheus|Query|Execute|Alerts/i,
        options: { skipScreenshot: true },
      },
      {
        name: 'Stack Portal',
        path: serviceUrl('portal'),
        pattern: /Stack Portal|contract-backed modules|SOGo/i,
        options: { skipScreenshot: true },
      },
    ];

    for (const service of services) {
      console.log(`\n   Visiting ${service.name}...`);
      await testForwardAuthService(page, service.name, service.path, service.pattern, service.options);
      console.log(`   ✅ ${service.name} accessed without re-auth`);
    }

    console.log('\n   ✅ Session persisted across all services\n');
  });
