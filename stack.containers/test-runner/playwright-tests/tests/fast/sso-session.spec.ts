import { test } from '@playwright/test';
import { authArtifactPath, loadTestUser } from '../../utils/auth-artifacts';
import { assertSmokeContract } from '../../utils/drivers/browser-route-driver';
import { defaultIdentityProvider } from '../../utils/identity-provider';
import { smokeRoutes } from '../../utils/route-catalog';

const sessionState = authArtifactPath(defaultIdentityProvider.sessionArtifactName);
const authenticatedRoutes = smokeRoutes.filter((route) => route.kind !== 'public');

test.describe('Shared Session SSO', () => {
  test.use({ storageState: sessionState });

  for (const route of authenticatedRoutes) {
    test(`${route.label} accepts the shared ${defaultIdentityProvider.label} session`, async ({ browser }) => {
      const user = loadTestUser();
      const context = await browser.newContext({ storageState: sessionState });
      const page = await context.newPage();

      try {
        await assertSmokeContract(page, route, user);
      } finally {
        await context.close();
      }
    });
  }
});
