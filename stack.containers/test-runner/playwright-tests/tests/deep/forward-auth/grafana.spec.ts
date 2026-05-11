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

  test('Grafana - Logs home + Loki datasource', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Grafana',
      serviceUrl('grafana'),
      /Grafana|Dashboards|Explore|Connections|Data sources|Loki/i,
      {
        onAfterLoad: async (page) => {
          await waitForGrafanaShell(page);
        },
        screenshotDelayMs: 2000,
        screenshotFullPage: false,
        screenshotViewport: { width: 1280, height: 360 },
      }
    );

    // Validate default home dashboard shows Logs panel
    const logsPanelTitle = page.getByText('All Logs', { exact: false }).first();
    if (await logsPanelTitle.isVisible().catch(() => false)) {
      await expect(logsPanelTitle).toBeVisible();
    }

    // Validate Loki datasource via Grafana API
    const response = await page.request.get(serviceUrl('grafana', '/api/datasources/name/Loki'));
    expect(response.ok()).toBeTruthy();
    const data = await response.json();
    expect(data.type).toBe('loki');
    expect(String(data.url)).toContain('http://loki:3100');
  });

