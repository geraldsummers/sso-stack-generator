import { expect, test } from '@playwright/test';
import { domain } from '../shared/oidc';

const matrixOrigin = `https://matrix.${domain}`;
const matrixAuthOrigin = `https://matrix-auth.${domain}`;

test.describe('Matrix Authentication Service compatibility', () => {
  test('serves legacy-compatible login through MAS after delegated auth migration', async ({ request }) => {
    const loginResponse = await request.get(`${matrixOrigin}/_matrix/client/v3/login`);
    expect(loginResponse.ok()).toBeTruthy();
    const login = await loginResponse.json();

    const ssoFlow = (login.flows || []).find((flow: any) => flow?.type === 'm.login.sso');
    expect(ssoFlow).toBeTruthy();
    expect(ssoFlow?.oauth_aware_preferred).toBe(true);
    expect(ssoFlow?.['org.matrix.msc3824.delegated_oidc_compatibility']).toBe(true);
    expect((login.flows || []).some((flow: any) => flow?.type === 'm.login.token')).toBeTruthy();
  });

  test('advertises native Matrix OIDC delegated auth metadata', async ({ request }) => {
    const wellKnownResponse = await request.get(`${matrixOrigin}/.well-known/matrix/client`);
    expect(wellKnownResponse.ok()).toBeTruthy();
    const wellKnown = await wellKnownResponse.json();

    const response = await request.get(`${matrixOrigin}/_matrix/client/v1/auth_metadata`);

    expect(wellKnown['org.matrix.msc2965.authentication']?.issuer).toBe(`${matrixAuthOrigin}/`);
    expect(wellKnown['org.matrix.msc2965.authentication']?.account).toBe(`${matrixAuthOrigin}/account`);
    expect(response.ok()).toBeTruthy();
    const metadata = await response.json();
    expect(metadata.issuer).toBe(`${matrixAuthOrigin}/`);
    expect(metadata.authorization_endpoint).toContain(`${matrixAuthOrigin}/`);
    expect(metadata.token_endpoint).toContain(`${matrixAuthOrigin}/`);
  });

  test('serves MAS health and discovery on the dedicated auth host', async ({ request }) => {
    const healthResponse = await request.get(`${matrixAuthOrigin}/health`);
    expect(healthResponse.ok()).toBeTruthy();

    const discoveryResponse = await request.get(`${matrixAuthOrigin}/.well-known/openid-configuration`);
    expect(discoveryResponse.ok()).toBeTruthy();
    const discovery = await discoveryResponse.json();
    expect(discovery.issuer).toBe(`${matrixAuthOrigin}/`);
  });
});
