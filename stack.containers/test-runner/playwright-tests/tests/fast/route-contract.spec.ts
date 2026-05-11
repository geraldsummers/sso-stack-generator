import { expect, test } from '@playwright/test';
import { assertAnonymousContract } from '../../utils/drivers/browser-route-driver';
import { findRoute, readCaddyHostsInventory, routeContractRoutes, routeUrl, uncataloguedHosts } from '../../utils/route-catalog';

const anonymousRoutes = routeContractRoutes.filter(
  (route) => route.anonymous.kind !== 'non_ui'
);

test.describe('Route Contract', () => {
  test.describe.configure({ mode: 'parallel' });

  test('catalog covers every Caddy host explicitly', () => {
    const caddyHosts = readCaddyHostsInventory();
    const missingHosts = uncataloguedHosts();

    expect(missingHosts, `Uncatalogued Caddy hosts: ${missingHosts.join(', ')}`).toEqual([]);
    expect(new Set(caddyHosts).size, 'Caddy host inventory should not contain duplicates.').toBe(caddyHosts.length);
  });

  test('MatrixRTC root is boundary-safe and does not redirect to Keycloak', async ({ page }) => {
    const response = await page.goto(routeUrl(findRoute('matrix-rtc')), { waitUntil: 'domcontentloaded' });
    const body = await page.locator('body').innerText().catch(() => '');

    expect(response?.status(), 'MatrixRTC root should not serve a browser app or auth portal.').toBe(404);
    expect(page.url()).not.toMatch(/keycloak|keycloak-auth/i);
    expect(body).not.toMatch(/Keycloak|Sign in|Username|Password/i);
  });

  for (const route of anonymousRoutes) {
    test(`${route.label} anonymous contract`, async ({ page }) => {
      await assertAnonymousContract(page, route);
    });
  }
});
