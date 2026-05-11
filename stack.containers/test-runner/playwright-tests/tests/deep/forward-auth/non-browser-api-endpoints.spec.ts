import { test, expect } from '@playwright/test';
import { serviceUrl } from '../../../utils/stack-urls';

function assertNoBrowserSsoRedirect(location: string | null): void {
  const value = (location || '').toLowerCase();
  expect(value).not.toContain('keycloak');
  expect(value).not.toContain('/oauth2/start');
}

test.describe('Non-browser API endpoints', () => {
  test('Element bootstrap endpoint stays app-facing', async ({ request }) => {
    const response = await request.get(serviceUrl('api.element', '/config.json'), { maxRedirects: 0 });
    expect(response.ok()).toBeTruthy();
    const body = await response.json();
    expect(body).toHaveProperty('default_server_config');
  });

  test('Seafile API endpoint does not redirect to browser SSO', async ({ request }) => {
    const response = await request.get(serviceUrl('api.seafile', '/api2/ping/'), { maxRedirects: 0 });
    expect([200, 401, 403]).toContain(response.status());
    assertNoBrowserSsoRedirect(response.headers()['location'] || null);
  });

  test('Vaultwarden API endpoint does not redirect to browser SSO', async ({ request }) => {
    const response = await request.get(serviceUrl('api.vaultwarden', '/api/config'), { maxRedirects: 0 });
    expect([200, 401, 403]).toContain(response.status());
    assertNoBrowserSsoRedirect(response.headers()['location'] || null);
  });

  test('Mastodon app API endpoint does not redirect to browser SSO', async ({ request }) => {
    const response = await request.get(serviceUrl('api.mastodon', '/api/v1/instance'), { maxRedirects: 0 });
    expect([200, 401, 403]).toContain(response.status());
    assertNoBrowserSsoRedirect(response.headers()['location'] || null);
  });

  test('Home Assistant API endpoint does not redirect to browser SSO', async ({ request }) => {
    const response = await request.get(serviceUrl('api.homeassistant', '/api/'), { maxRedirects: 0 });
    expect([200, 401, 403]).toContain(response.status());
    assertNoBrowserSsoRedirect(response.headers()['location'] || null);
  });
});
