import { expect, test } from '@playwright/test';
import { serviceUrl } from '../../utils/stack-urls';

test.describe('Workspaces Boundary', () => {
  test('OIDC discovery is public and anonymous API access is redirected to Keycloak', async ({ request }) => {
    const discoveryResponse = await request.get(serviceUrl('workspaces', '/api/oidc/discovery'));
    expect(discoveryResponse.ok()).toBeTruthy();
    await expect(discoveryResponse.json()).resolves.toMatchObject({
      client_id: 'workspace-cli',
      issuer: expect.stringMatching(/^https:\/\/keycloak\.[^/]+\/realms\/webservices$/),
    });

    const meResponse = await request.get(serviceUrl('workspaces', '/api/me'), { maxRedirects: 0 });
    expect(meResponse.status()).toBe(302);
    expect(meResponse.headers()['location']).toMatch(/^https:\/\/keycloak-auth\.[^/]+\/oauth2\/start\?rd=/);

    const listResponse = await request.get(serviceUrl('workspaces', '/api/workspaces'), { maxRedirects: 0 });
    expect(listResponse.status()).toBe(302);
    expect(listResponse.headers()['location']).toMatch(/^https:\/\/keycloak-auth\.[^/]+\/oauth2\/start\?rd=/);
  });
});
