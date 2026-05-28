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

  test('qBittorrent - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'qBittorrent',
      serviceUrl('qbittorrent'),
      /qBittorrent.*WebUI|Add Torrent|Transfers|Downloading|Seeding|\bUsername\b|\bPassword\b|JavaScript Required/i,
      {
        requireUI: true,
        waitForSelectorVisible: '#mainWindow, #desktop, #torrentsTable, input[name="username"], input[type="password"], #loginButton',
        waitForSelectorTimeoutMs: 30000,
        disallowPatterns: [
          /Invalid Username or Password/i,
        ],
        disallowUrlPatterns: [
          /keycloak|keycloak-auth|:9091/i,
        ],
        onAfterLoad: async (page) => {
          await expect(page).toHaveTitle(/qBittorrent.*WebUI/i);
          await expect(page).not.toHaveURL(/keycloak|keycloak-auth|:9091/i);
          const fullWebUi = page.locator('#mainWindow, #desktop, #torrentsTable').first();
          const loginInputs = page.locator('input[name="username"], input[type="password"], #loginButton');
          await expect(fullWebUi.or(loginInputs.first())).toBeVisible({ timeout: 30000 });
          await expect(page.locator('body')).not.toContainText(/Invalid Username or Password/i);
        },
      }
    );
  });
