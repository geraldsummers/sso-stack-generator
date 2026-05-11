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

  test('Ntfy - Access with forward auth', async ({ page }) => {
    const ntfyOrigin = new URL(serviceUrl('ntfy')).origin;
    await page.addInitScript(() => {
      try {
        Object.defineProperty(Notification, 'permission', {
          configurable: true,
          get: () => 'granted',
        });
      } catch {
        // Ignore browsers that lock Notification.permission.
      }

      try {
        Notification.requestPermission = async () => 'granted';
      } catch {
        // Ignore browsers that lock Notification.requestPermission.
      }
    });
    await page.context().grantPermissions(['notifications'], { origin: ntfyOrigin }).catch(() => {});

    await testForwardAuthService(
      page,
      'Ntfy',
      serviceUrl('ntfy'),
      /ntfy/i, // Title is "ntfy"
      {
        onAfterLoad: async (page) => {
          await page.reload({ waitUntil: 'domcontentloaded' }).catch(() => {});
          await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
          await page.evaluate(() => {
            const patterns = [/notifications are blocked/i, /enable notifications/i];
            for (const node of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
              const text = (node.innerText || node.textContent || '').trim();
              if (!patterns.some((pattern) => pattern.test(text))) {
                continue;
              }
              const banner = node.closest<HTMLElement>('[role="alert"], [role="status"], .banner, .alert, .notification');
              const target = banner ?? node;
              target.style.display = 'none';
              target.setAttribute('aria-hidden', 'true');
            }
          }).catch(() => {});
          await page.waitForTimeout(500);
        },
      }
    );
  });
