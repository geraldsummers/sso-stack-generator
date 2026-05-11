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
      /qBittorrent|Add Torrent|Transfers/i, // Look for qBittorrent UI elements
      {
        requireUI: true,
        waitForSelectorVisible: '#mainWindow, #desktop, #torrentsTable',
        waitForSelectorTimeoutMs: 30000,
        disallowPatterns: [
          /Username/i,
          /Password/i,
          /^Login$/im,
          /Invalid Username or Password/i,
        ],
        disallowUrlPatterns: [
          /keycloak|keycloak-auth|:9091/i,
          /\/login\b/i,
        ],
        onAfterLoad: async (page) => {
          await expect(page).toHaveTitle(/qBittorrent.*WebUI/i);
          await expect(page).not.toHaveURL(/keycloak|keycloak-auth|:9091|\/login\b/i);
          const loginInputs = page.locator('input[name="username"], input[type="password"], #loginButton');
          expect(await loginInputs.first().isVisible().catch(() => false)).toBeFalsy();
          await expect(page.locator('#mainWindow, #desktop, #torrentsTable').first()).toBeVisible({ timeout: 30000 });
        },
      }
    );
  });
