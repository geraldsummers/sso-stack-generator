import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { KeycloakLoginPage } from '../../../pages/KeycloakLoginPage';
import { defaultIdentityProvider } from '../../../utils/identity-provider';
import { serviceUrl } from '../../../utils/stack-urls';
import { testUser } from '../shared/oidc';

type JellyfinSession = {
  accessToken: string;
  userId: string;
};

const jellyfinOrigin = serviceUrl('jellyfin').replace(/\/$/, '');

async function extractJellyfinSession(page: Page): Promise<JellyfinSession> {
  const session = await page.evaluate(() => {
    type Candidate = {
      AccessToken?: string;
      accessToken?: string;
      UserId?: string;
      userId?: string;
      Id?: string;
      id?: string;
      Servers?: unknown[];
    };

    const visited = new Set<unknown>();
    const parseMaybeJson = (value: unknown): unknown => {
      if (typeof value !== 'string') {
        return value;
      }
      try {
        return JSON.parse(value);
      } catch {
        return value;
      }
    };

    const visit = (value: unknown): JellyfinSession | null => {
      const parsed = parseMaybeJson(value);
      if (!parsed || visited.has(parsed)) {
        return null;
      }
      if (typeof parsed !== 'object') {
        return null;
      }

      visited.add(parsed);
      const candidate = parsed as Candidate;
      const accessToken = candidate.AccessToken || candidate.accessToken;
      const userId = candidate.UserId || candidate.userId || candidate.Id || candidate.id;
      if (typeof accessToken === 'string' && accessToken.length >= 16 && typeof userId === 'string' && userId) {
        return { accessToken, userId };
      }

      if (Array.isArray(candidate.Servers)) {
        for (const server of candidate.Servers) {
          const found = visit(server);
          if (found) {
            return found;
          }
        }
      }

      for (const nested of Object.values(parsed as Record<string, unknown>)) {
        const found = visit(nested);
        if (found) {
          return found;
        }
      }
      return null;
    };

    for (const storage of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index);
        if (!key) {
          continue;
        }
        const found = visit(storage.getItem(key));
        if (found) {
          return found;
        }
      }
    }

    return null;
  });

  expect(session, 'Jellyfin should persist an authenticated API token after external-route SSO').toBeTruthy();
  return session!;
}

async function fetchJellyfinApi(page: Page, session: JellyfinSession, path: string) {
  return page.evaluate(async ({ accessToken, path }) => {
    const response = await fetch(path, {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'X-Emby-Token': accessToken,
      },
    });
    return {
      ok: response.ok,
      status: response.status,
      statusText: response.statusText,
      text: await response.text(),
    };
  }, { accessToken: session.accessToken, path });
}

test('Jellyfin external Caddy route exposes native-client discovery without edge auth', async ({ request }) => {
  const response = await request.get(`${jellyfinOrigin}/System/Info/Public`, {
    maxRedirects: 0,
  });

  expect(response.ok(), 'Jellyfin native clients require public server discovery through the external route').toBeTruthy();
  const payload = await response.json() as { ProductName?: string; StartupWizardCompleted?: boolean };
  expect(payload.ProductName).toBe('Jellyfin Server');
  expect(payload.StartupWizardCompleted).toBe(true);
});

test('Jellyfin external Caddy route blocks password login for native clients', async ({ request }) => {
  const response = await request.post(`${jellyfinOrigin}/Users/AuthenticateByName`, {
    data: {
      Username: 'gerald',
      Pw: 'password',
    },
    maxRedirects: 0,
  });

  expect(response.status(), 'Jellyfin password login should remain disabled in favour of SSO/Quick Connect').toBe(403);
  await expect(response.text()).resolves.toMatch(/Jellyfin password login is disabled; use Keycloak SSO/i);
});

test('Jellyfin external Caddy route supports SSO app and authenticated API access', async ({ page }) => {
  test.setTimeout(120000);

  await page.goto(`${jellyfinOrigin}/sso/OID/start/keycloak`, {
    waitUntil: 'domcontentloaded',
    timeout: 30000,
  });

  if (defaultIdentityProvider.isAuthUrl(page.url())) {
    await new KeycloakLoginPage(page).login(testUser.username, testUser.password);
  }

  await page.waitForURL((url) => !defaultIdentityProvider.isAuthUrl(url.toString()), { timeout: 45000 });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  expect(new URL(page.url()).origin).toBe(jellyfinOrigin);
  await expect(page.locator('body')).toContainText(/Jellyfin|Home|Favorites|Latest|Next Up|The Simpsons/i, {
    timeout: 45000,
  });

  const session = await extractJellyfinSession(page);
  const userResponse = await fetchJellyfinApi(page, session, `/Users/${encodeURIComponent(session.userId)}`);
  expect(userResponse.ok, `Jellyfin authenticated user API failed (${userResponse.status} ${userResponse.statusText})`)
    .toBeTruthy();

  const itemResponse = await fetchJellyfinApi(
    page,
    session,
    `/Users/${encodeURIComponent(session.userId)}/Items?Limit=1`
  );
  expect(itemResponse.ok, `Jellyfin authenticated item API failed (${itemResponse.status} ${itemResponse.statusText})`)
    .toBeTruthy();

  const userPayload = JSON.parse(userResponse.text) as { Id?: string; Name?: string };
  expect(userPayload.Id).toBe(session.userId);
  expect(userPayload.Name, 'Jellyfin authenticated API should return the SSO-created user').toBeTruthy();
});
