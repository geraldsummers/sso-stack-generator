import { test } from '@playwright/test';
import { assertSmokeContract } from '../../utils/drivers/browser-route-driver';
import { smokeRoutes } from '../../utils/route-catalog';
import { withManagedBrowserUser } from '../../utils/managed-browser-user';

const publicRoutes = smokeRoutes.filter((route) => route.kind === 'public');
const authenticatedRoutes = smokeRoutes.filter((route) => route.kind !== 'public');
const anonymousUser = {
  username: 'anonymous',
  password: '',
  email: '',
  groups: [],
};

test.describe('App Smoke', () => {
  test.describe.configure({ mode: 'serial' });

  for (const route of publicRoutes) {
    test(`${route.label} public shell renders without authentication`, async ({ page }) => {
      await assertSmokeContract(page, route, anonymousUser);
    });
  }

  test('Authenticated routes render for one isolated managed user session', async ({ page }) => {
    test.setTimeout(Math.max(120_000, authenticatedRoutes.length * 45_000));
    await withManagedBrowserUser('pw', async (user) => {
      for (const route of authenticatedRoutes) {
        await test.step(`${route.label} renders`, async () => {
          await assertSmokeContract(page, route, user);
        });
      }
    });
  });
});
