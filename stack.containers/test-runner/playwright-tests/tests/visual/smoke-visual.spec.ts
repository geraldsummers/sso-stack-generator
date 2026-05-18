import { test } from '@playwright/test';
import { authArtifactPath, loadTestUser } from '../../utils/auth-artifacts';
import { captureVisualSnapshot } from '../../utils/drivers/browser-route-driver';
import { defaultIdentityProvider } from '../../utils/identity-provider';
import { visualRoutes } from '../../utils/route-catalog';

const sessionState = authArtifactPath(defaultIdentityProvider.sessionArtifactName);
const screenshotRoot = process.env.PLAYWRIGHT_SCREENSHOTS_DIR || '/app/test-results/screenshots';

const publicRoutes = visualRoutes.filter((route) => route.kind === 'public');
const authenticatedRoutes = visualRoutes.filter((route) => route.kind !== 'public');

test.describe('Visual Smoke', () => {
  for (const route of publicRoutes) {
    test(`${route.label} snapshot`, async ({ page }) => {
      test.setTimeout(120000);
      await captureVisualSnapshot(page, route, loadTestUser(), screenshotRoot);
    });
  }

  test.describe('Authenticated snapshots', () => {
    test.use({ storageState: sessionState });

    for (const route of authenticatedRoutes) {
      test(`${route.label} snapshot`, async ({ page }) => {
        test.setTimeout(120000);
        await captureVisualSnapshot(page, route, loadTestUser(), screenshotRoot);
      });
    }
  });
});
