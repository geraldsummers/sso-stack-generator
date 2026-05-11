import type { TestUser } from './test-user';
import { stackDomain } from './stack-urls';

export type KeycloakUserProfile = {
  username: string;
  email: string;
  givenName: string;
  familyName: string;
  commonName: string;
  displayName: string;
  fullName: string;
};

export type KeycloakManagedUser = TestUser & {
  provider: 'keycloak';
  keycloakUserId?: string;
  stackAdminProfile?: KeycloakUserProfile | null;
};

type KeycloakUserRepresentation = {
  id?: string;
  username?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  enabled?: boolean;
  attributes?: Record<string, string[] | string>;
};

export type KeycloakClientOptions = {
  baseUrl: string;
  realm: string;
  adminUsername: string;
  adminPassword: string;
};

function requireValue(name: string, value: string | undefined): string {
  const normalized = value?.trim();
  if (normalized) {
    return normalized;
  }

  throw new Error(`${name} is required for Keycloak Playwright provisioning.`);
}

function attributeValue(value: string | string[] | undefined): string {
  if (Array.isArray(value)) {
    return value.find((entry) => entry.trim().length > 0)?.trim() || '';
  }
  return value?.trim() || '';
}

function randomSuffix(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

export class KeycloakClient {
  readonly baseUrl: string;
  readonly realm: string;
  readonly adminUsername: string;
  readonly adminPassword: string;
  private accessToken: string | null = null;

  constructor(options: KeycloakClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.realm = options.realm;
    this.adminUsername = options.adminUsername;
    this.adminPassword = options.adminPassword;
  }

  static fromEnvironment(): KeycloakClient {
    return new KeycloakClient({
      baseUrl: requireValue('KEYCLOAK_INTERNAL_URL or KEYCLOAK_URL', process.env.KEYCLOAK_INTERNAL_URL || process.env.KEYCLOAK_URL),
      realm: process.env.KEYCLOAK_REALM?.trim() || 'webservices',
      adminUsername: process.env.KEYCLOAK_ADMIN_USER?.trim() || 'admin',
      adminPassword: requireValue('KEYCLOAK_ADMIN_PASSWORD', process.env.KEYCLOAK_ADMIN_PASSWORD),
    });
  }

  static generateUsername(prefix = 'playwright'): string {
    const compactPrefix = (prefix.toLowerCase().replace(/[^a-z0-9]/g, '') || 'u').slice(0, 2);
    const timestamp = Date.now().toString(36);
    const random = Math.random().toString(36).replace(/[^a-z0-9]/g, '').slice(2, 6);
    return `${compactPrefix}${timestamp}${random}`.slice(0, 16);
  }

  static generatePassword(): string {
    return `PW-${randomSuffix()}-${Math.random().toString(36).slice(2, 10)}!aA1`;
  }

  static buildManagedUser(username: string, email = `${username}@${stackDomain}`): KeycloakManagedUser {
    const givenName = 'Playwright';
    const familyName = 'User';
    const displayName = `Playwright ${username}`;

    return {
      provider: 'keycloak',
      username,
      email,
      password: KeycloakClient.generatePassword(),
      groups: ['users', 'operators'],
      givenName,
      familyName,
      commonName: displayName,
      displayName,
      fullName: displayName,
      managed: true,
    };
  }

  async createManagedUser(user: KeycloakManagedUser): Promise<KeycloakManagedUser> {
    const token = await this.adminToken();
    const existingUser = await this.findUserByUsername(user.username);
    if (existingUser?.id) {
      await this.deleteUser(existingUser.id);
    }

    const createResponse = await fetch(`${this.baseUrl}/admin/realms/${encodeURIComponent(this.realm)}/users`, {
      method: 'POST',
      headers: this.jsonHeaders(token),
      body: JSON.stringify({
        username: user.username,
        email: user.email,
        firstName: user.givenName,
        lastName: user.familyName,
        enabled: true,
        emailVerified: true,
        requiredActions: [],
        groups: user.groups.map((group) => `/${group}`),
        attributes: {
          managed: ['true'],
          managedBy: ['webservices-playwright'],
          displayName: [user.displayName || user.fullName || user.username],
        },
        credentials: [
          {
            type: 'password',
            value: user.password,
            temporary: false,
          },
        ],
      }),
    });

    if (!createResponse.ok) {
      throw new Error(`Keycloak user create failed for ${user.username}: HTTP ${createResponse.status} ${await createResponse.text()}`);
    }

    const location = createResponse.headers.get('location') || '';
    const keycloakUserId = location.split('/').filter(Boolean).pop() || (await this.findUserByUsername(user.username))?.id;
    if (!keycloakUserId) {
      throw new Error(`Keycloak user create did not return a user id for ${user.username}.`);
    }

    return { ...user, keycloakUserId };
  }

  async getUserProfile(username: string): Promise<KeycloakUserProfile | null> {
    const representation = await this.findUserByUsername(username);
    if (!representation) {
      return null;
    }

    const displayName =
      attributeValue(representation.attributes?.displayName)
      || [representation.firstName, representation.lastName].filter(Boolean).join(' ').trim()
      || representation.username
      || username;

    return {
      username: representation.username || username,
      email: representation.email || `${username}@${stackDomain}`,
      givenName: representation.firstName || '',
      familyName: representation.lastName || '',
      commonName: displayName,
      displayName,
      fullName: displayName,
    };
  }

  async cleanupManagedTestUsers(preservedUsers: string[] = []): Promise<string[]> {
    const token = await this.adminToken();
    const preserved = new Set(preservedUsers);
    const usersById = new Map<string, KeycloakUserRepresentation>();
    for (const search of ['pl', 'playwright-']) {
      const response = await fetch(`${this.baseUrl}/admin/realms/${encodeURIComponent(this.realm)}/users?search=${encodeURIComponent(search)}&max=100`, {
        headers: this.authHeaders(token),
      });
      if (!response.ok) {
        throw new Error(`Keycloak managed user search failed: HTTP ${response.status} ${await response.text()}`);
      }
      for (const user of (await response.json()) as KeycloakUserRepresentation[]) {
        if (user.id) {
          usersById.set(user.id, user);
        }
      }
    }

    const removed: string[] = [];

    for (const user of usersById.values()) {
      const username = user.username || '';
      const managedBy = attributeValue(user.attributes?.managedBy);
      const managedUsername = username.startsWith('pl') || username.startsWith('playwright-');
      if (!user.id || !managedUsername || preserved.has(username) || managedBy !== 'webservices-playwright') {
        continue;
      }
      await this.deleteUser(user.id);
      removed.push(username);
    }

    return removed;
  }

  async deleteUser(userIdOrUsername: string): Promise<void> {
    const token = await this.adminToken();
    const userId = (await this.findUserByUsername(userIdOrUsername))?.id || userIdOrUsername;
    const response = await fetch(`${this.baseUrl}/admin/realms/${encodeURIComponent(this.realm)}/users/${encodeURIComponent(userId)}`, {
      method: 'DELETE',
      headers: this.authHeaders(token),
    });
    if (response.status === 404) {
      return;
    }
    if (!response.ok && response.status !== 204) {
      throw new Error(`Keycloak user delete failed for ${userIdOrUsername}: HTTP ${response.status} ${await response.text()}`);
    }
  }

  private async findUserByUsername(username: string): Promise<KeycloakUserRepresentation | null> {
    const token = await this.adminToken();
    const response = await fetch(
      `${this.baseUrl}/admin/realms/${encodeURIComponent(this.realm)}/users?username=${encodeURIComponent(username)}&exact=true`,
      { headers: this.authHeaders(token) },
    );
    if (!response.ok) {
      throw new Error(`Keycloak user lookup failed for ${username}: HTTP ${response.status} ${await response.text()}`);
    }

    const users = (await response.json()) as KeycloakUserRepresentation[];
    return users.find((user) => user.username === username) || null;
  }

  private async adminToken(): Promise<string> {
    if (this.accessToken) {
      return this.accessToken;
    }

    const form = new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: this.adminUsername,
      password: this.adminPassword,
    });
    const response = await fetch(`${this.baseUrl}/realms/master/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: form,
    });
    if (!response.ok) {
      throw new Error(`Keycloak admin token request failed: HTTP ${response.status} ${await response.text()}`);
    }

    const payload = (await response.json()) as { access_token?: string };
    if (!payload.access_token) {
      throw new Error('Keycloak admin token response did not include access_token.');
    }
    this.accessToken = payload.access_token;
    return this.accessToken;
  }

  private authHeaders(token: string): Record<string, string> {
    return { authorization: `Bearer ${token}` };
  }

  private jsonHeaders(token: string): Record<string, string> {
    return {
      ...this.authHeaders(token),
      'content-type': 'application/json',
    };
  }
}
