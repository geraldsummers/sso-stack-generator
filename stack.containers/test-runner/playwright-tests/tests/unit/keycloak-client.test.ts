import { KeycloakClient } from '../../utils/keycloak-client';

describe('KeycloakClient', () => {
  const originalEnv = process.env;
  let fetchMock: jest.Mock;

  beforeEach(() => {
    process.env = { ...originalEnv };
    fetchMock = jest.fn();
    global.fetch = fetchMock as never;
  });

  afterEach(() => {
    process.env = originalEnv;
    jest.restoreAllMocks();
  });

  it('builds managed browser users without required-action migration state', () => {
    const user = KeycloakClient.buildManagedUser('playwright-demo', 'playwright-demo@example.test');

    expect(user.provider).toBe('keycloak');
    expect(user.username).toBe('playwright-demo');
    expect(user.email).toBe('playwright-demo@example.test');
    expect(user.groups).toEqual(['users', 'operators']);
    expect(user.managed).toBe(true);
    expect(user.password).toMatch(/^PW-/);
  });

  it('creates clients from the runtime environment', () => {
    process.env.KEYCLOAK_INTERNAL_URL = 'http://keycloak:8080/';
    process.env.KEYCLOAK_REALM = 'webservices';
    process.env.KEYCLOAK_ADMIN_USER = 'admin';
    process.env.KEYCLOAK_ADMIN_PASSWORD = 'secret';

    const client = KeycloakClient.fromEnvironment();

    expect(client.baseUrl).toBe('http://keycloak:8080');
    expect(client.realm).toBe('webservices');
  });

  it('rejects incomplete runtime environment configuration', () => {
    delete process.env.KEYCLOAK_INTERNAL_URL;
    delete process.env.KEYCLOAK_URL;
    process.env.KEYCLOAK_ADMIN_PASSWORD = 'secret';

    expect(() => KeycloakClient.fromEnvironment()).toThrow(
      'KEYCLOAK_INTERNAL_URL or KEYCLOAK_URL is required for Keycloak Playwright provisioning.'
    );

    process.env.KEYCLOAK_URL = 'http://keycloak:8080';
    process.env.KEYCLOAK_ADMIN_PASSWORD = '   ';

    expect(() => KeycloakClient.fromEnvironment()).toThrow(
      'KEYCLOAK_ADMIN_PASSWORD is required for Keycloak Playwright provisioning.'
    );
  });

  it('creates a managed user through the Keycloak Admin API', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: { get: () => 'http://keycloak:8080/admin/realms/webservices/users/user-id' },
        text: async () => '',
      });

    const user = await client.createManagedUser(KeycloakClient.buildManagedUser('playwright-demo'));

    expect(user.keycloakUserId).toBe('user-id');
    expect(fetchMock).toHaveBeenCalledWith(
      'http://keycloak:8080/admin/realms/webservices/users',
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"managedBy":["webservices-playwright"]'),
      }),
    );
  });

  it('replaces existing managed users and falls back to lookup when create omits Location', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([{ id: 'old-id', username: 'playwright-demo' }]))
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce({ ok: true, status: 204, text: async () => '' })
      .mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: { get: () => null },
        text: async () => '',
      })
      .mockResolvedValueOnce(okJson([{ id: 'new-id', username: 'playwright-demo' }]));

    const user = await client.createManagedUser(KeycloakClient.buildManagedUser('playwright-demo'));

    expect(user.keycloakUserId).toBe('new-id');
    expect(fetchMock).toHaveBeenCalledWith(
      'http://keycloak:8080/admin/realms/webservices/users/old-id',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('reports managed user creation failures with response text', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce({
        ok: false,
        status: 409,
        headers: { get: () => null },
        text: async () => 'duplicate user',
      });

    await expect(client.createManagedUser(KeycloakClient.buildManagedUser('playwright-demo'))).rejects.toThrow(
      'Keycloak user create failed for playwright-demo: HTTP 409 duplicate user'
    );
  });

  it('loads profiles with display-name attribute fallback behavior', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([{
        username: 'playwright-demo',
        firstName: 'Playwright',
        lastName: 'User',
        attributes: { displayName: ['   ', 'Display From Attribute'] },
      }]));

    const profile = await client.getUserProfile('playwright-demo');

    expect(profile).toEqual({
      username: 'playwright-demo',
      email: 'playwright-demo@datamancy.net',
      givenName: 'Playwright',
      familyName: 'User',
      commonName: 'Display From Attribute',
      displayName: 'Display From Attribute',
      fullName: 'Display From Attribute',
    });
  });

  it('loads profiles from string attributes and name fallback', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([{
        username: 'playwright-demo',
        email: 'playwright-demo@example.test',
        firstName: 'Playwright',
        lastName: 'Fallback',
        attributes: { displayName: '   ' },
      }]));

    const profile = await client.getUserProfile('playwright-demo');

    expect(profile?.displayName).toBe('Playwright Fallback');
    expect(profile?.email).toBe('playwright-demo@example.test');
  });

  it('returns null for missing profiles', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([]));

    await expect(client.getUserProfile('missing-user')).resolves.toBeNull();
  });

  it('only cleans up managed playwright users', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([
        { id: 'managed-id', username: 'plmosm1qtdabc1', attributes: { managedBy: ['webservices-playwright'] } },
        { id: 'real-id', username: 'gerald', attributes: { managedBy: ['webservices-playwright'] } },
        { id: 'foreign-id', username: 'playwright-foreign', attributes: { managedBy: ['other'] } },
      ]))
      .mockResolvedValueOnce(okJson([
        { id: 'legacy-id', username: 'playwright-legacy', attributes: { managedBy: ['webservices-playwright'] } },
      ]))
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce({ ok: true, status: 204, text: async () => '' })
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce({ ok: true, status: 204, text: async () => '' });

    const removed = await client.cleanupManagedTestUsers();

    expect(removed).toEqual(['plmosm1qtdabc1', 'playwright-legacy']);
    expect(fetchMock).toHaveBeenCalledWith(
      'http://keycloak:8080/admin/realms/webservices/users/managed-id',
      expect.objectContaining({ method: 'DELETE' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      'http://keycloak:8080/admin/realms/webservices/users/legacy-id',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('preserves explicitly requested managed users during cleanup', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([
        { id: 'preserved-id', username: 'plkeepme', attributes: { managedBy: ['webservices-playwright'] } },
      ]))
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce(okJson([]));

    await expect(client.cleanupManagedTestUsers(['plkeepme'])).resolves.toEqual([]);
    expect(fetchMock).not.toHaveBeenCalledWith(
      'http://keycloak:8080/admin/realms/webservices/users/preserved-id',
      expect.anything(),
    );
  });

  it('reports cleanup search failures', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce({ ok: false, status: 503, text: async () => 'search unavailable' });

    await expect(client.cleanupManagedTestUsers()).rejects.toThrow(
      'Keycloak managed user search failed: HTTP 503 search unavailable'
    );
  });

  it('reports create responses that do not expose a user id', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: { get: () => null },
        text: async () => '',
      })
      .mockResolvedValueOnce(okJson([]));

    await expect(client.createManagedUser(KeycloakClient.buildManagedUser('playwright-demo'))).rejects.toThrow(
      'Keycloak user create did not return a user id for playwright-demo.'
    );
  });

  it('treats missing users as already deleted', async () => {
    const client = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce({ ok: false, status: 404, text: async () => 'not found' });

    await expect(client.deleteUser('missing-id')).resolves.toBeUndefined();
  });

  it('reports lookup, delete, token, and malformed token failures', async () => {
    const lookupClient = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce({ ok: false, status: 500, text: async () => 'lookup failed' });
    await expect(lookupClient.getUserProfile('playwright-demo')).rejects.toThrow(
      'Keycloak user lookup failed for playwright-demo: HTTP 500 lookup failed'
    );

    fetchMock.mockReset();
    const deleteClient = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock
      .mockResolvedValueOnce(okJson({ access_token: 'token' }))
      .mockResolvedValueOnce(okJson([]))
      .mockResolvedValueOnce({ ok: false, status: 500, text: async () => 'delete failed' });
    await expect(deleteClient.deleteUser('broken-id')).rejects.toThrow(
      'Keycloak user delete failed for broken-id: HTTP 500 delete failed'
    );

    fetchMock.mockReset();
    const tokenClient = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock.mockResolvedValueOnce({ ok: false, status: 401, text: async () => 'bad credentials' });
    await expect(tokenClient.getUserProfile('playwright-demo')).rejects.toThrow(
      'Keycloak admin token request failed: HTTP 401 bad credentials'
    );

    fetchMock.mockReset();
    const malformedTokenClient = new KeycloakClient({
      baseUrl: 'http://keycloak:8080',
      realm: 'webservices',
      adminUsername: 'admin',
      adminPassword: 'secret',
    });
    fetchMock.mockResolvedValueOnce(okJson({}));
    await expect(malformedTokenClient.getUserProfile('playwright-demo')).rejects.toThrow(
      'Keycloak admin token response did not include access_token.'
    );
  });

  it('generates Planka-compatible managed usernames', () => {
    const username = KeycloakClient.generateUsername('playwright');

    expect(username).toMatch(/^[a-zA-Z0-9]+((_|\.)?[a-zA-Z0-9])*$/);
    expect(username.length).toBeLessThanOrEqual(16);
  });
});

function okJson(value: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => value,
    text: async () => JSON.stringify(value),
  };
}
