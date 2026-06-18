const mockKeycloakLogin = jest.fn(async (page: any, username: string, password: string) => {
  if (page.__onKeycloakLogin) {
    await page.__onKeycloakLogin(username, password);
  }
});
const mockClickOIDCButton = jest.fn(async (page: any, label?: string) => {
  if (page.__onOidcClick) {
    await page.__onOidcClick(label);
  }
});
const mockHandleConsentScreen = jest.fn(async (page: any) => {
  if (page.__onConsent) {
    await page.__onConsent();
  }
});
const mockFindRoute = jest.fn();
const mockRouteUrl = jest.fn((route: any, path?: string) => `https://${route.host}.datamancy.net${path ?? route.path ?? ''}`);
const mockRouteUrlPattern = jest.fn((host: string) => new RegExp(`^https://${host}\\.datamancy\\.net(?:/|$)`));

jest.mock('@playwright/test', () => {
  const mockExpect: any = (actual: any) => ({
    toBe: (expected: any) => {
      (global as any).expect(actual).toBe(expected);
    },
    toBeVisible: async () => {
      const visible = await actual.isVisible();
      (global as any).expect(visible).toBe(true);
    },
    toContainText: async () => undefined,
  });

  mockExpect.poll = (callback: () => unknown | Promise<unknown>) => ({
    toBe: async (expected: any) => {
      const value = await callback();
      (global as any).expect(value).toBe(expected);
    },
  });

  return {
    expect: mockExpect,
  };
});

jest.mock('../../pages/KeycloakLoginPage', () => ({
  KeycloakLoginPage: jest.fn().mockImplementation((page: any) => ({
    login: (username: string, password: string) => mockKeycloakLogin(page, username, password),
  })),
}));

jest.mock('../../pages/OIDCLoginPage', () => ({
  OIDCLoginPage: jest.fn().mockImplementation((page: any) => ({
    clickOIDCButton: (label?: string) => mockClickOIDCButton(page, label),
    handleConsentScreen: () => mockHandleConsentScreen(page),
  })),
}));

jest.mock('../../utils/route-catalog', () => ({
  findRoute: (host: string) => mockFindRoute(host),
  routeUrl: (route: any, path?: string) => mockRouteUrl(route, path),
  routeUrlPattern: (host: string) => mockRouteUrlPattern(host),
}));

import * as fs from 'fs';
import { KeycloakLoginPage } from '../../pages/KeycloakLoginPage';
import { OIDCLoginPage } from '../../pages/OIDCLoginPage';
import type { BrowserRoute } from '../../utils/route-catalog';
import {
  assertAnonymousContract,
  assertSmokeContract,
  captureVisualSnapshot,
  isBookStackTransientOidcErrorState,
} from '../../utils/drivers/browser-route-driver';

function createLocator(options: {
  visible?: boolean | boolean[];
  innerText?: string;
  innerTextError?: Error;
} = {}) {
  const locator: any = {};
  const visibleSequence = Array.isArray(options.visible) ? [...options.visible] : null;

  locator.or = jest.fn(() => locator);
  locator.first = jest.fn(() => locator);
  locator.isVisible = jest.fn(async () => {
    if (visibleSequence) {
      return visibleSequence.length > 0 ? visibleSequence.shift() : false;
    }
    return options.visible ?? true;
  });
  locator.click = jest.fn(async () => undefined);
  if (options.innerTextError) {
    locator.innerText = jest.fn(async () => {
      throw options.innerTextError;
    });
  } else if (typeof options.innerText === 'string') {
    locator.innerText = jest.fn(async () => options.innerText);
  }

  return locator;
}

function createIndexedLocator(visibleByIndex: boolean[]) {
  const locator = createLocator({ visible: false });
  locator.count = jest.fn(async () => visibleByIndex.length);
  locator.nth = jest.fn((index: number) => createLocator({ visible: visibleByIndex[index] ?? false }));
  return locator;
}

function createPage(options: {
  currentUrl?: string;
  title?: string;
  bodyText?: string;
  gotoErrors?: Error[];
  locators?: Record<string, any>;
  onGoto?: (url: string, page: any) => Promise<void> | void;
} = {}) {
  const state = {
    currentUrl: options.currentUrl ?? 'about:blank',
    title: options.title ?? '',
    bodyText: options.bodyText ?? '',
    gotoErrors: [...(options.gotoErrors ?? [])],
  };
  const defaultLocator = createLocator({ visible: false });

  const page: any = {
    __setUrl: (value: string) => {
      state.currentUrl = value;
    },
    __setBody: (value: string) => {
      state.bodyText = value;
    },
    __setTitle: (value: string) => {
      state.title = value;
    },
    goto: jest.fn(async (url: string) => {
      if (state.gotoErrors.length > 0) {
        throw state.gotoErrors.shift();
      }
      state.currentUrl = url;
      await options.onGoto?.(url, page);
    }),
    waitForLoadState: jest.fn(async () => undefined),
    waitForTimeout: jest.fn(async () => undefined),
    title: jest.fn(async () => state.title),
    textContent: jest.fn(async (selector: string) => (selector === 'body' ? state.bodyText : '')),
    locator: jest.fn((selector: string) => options.locators?.[selector] ?? defaultLocator),
    getByRole: jest.fn(() => defaultLocator),
    setExtraHTTPHeaders: jest.fn(async () => undefined),
    waitForURL: jest.fn(async (predicate?: (url: URL) => boolean) => {
      const current = new URL(state.currentUrl);
      if (predicate && !predicate(current)) {
        throw new Error('url predicate not satisfied');
      }
    }),
    url: jest.fn(() => state.currentUrl),
    screenshot: jest.fn(async () => undefined),
    reload: jest.fn(async () => undefined),
    evaluate: jest.fn(async (fn: any, arg?: any) => fn(arg)),
  };

  return page;
}

function createRoute(overrides: Record<string, any> = {}): BrowserRoute {
  return {
    host: 'demo',
    label: 'Demo Route',
    kind: 'public',
    anonymous: { kind: 'public_page', matcher: /Demo Ready/ },
    ownership: { route: true, smoke: true, visual: true, deep: true },
    ...overrides,
  } as BrowserRoute;
}

const user = {
  username: 'gerald',
  password: 'secret',
  email: 'gerald@datamancy.net',
  groups: ['users'],
};

describe('browser-route-driver', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'log').mockImplementation(() => undefined);
    mockFindRoute.mockImplementation((host: string) => createRoute({ host, label: `${host} route` }));
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  describe('isBookStackTransientOidcErrorState', () => {
    it('detects BookStack callback error pages by content', () => {
      expect(
        isBookStackTransientOidcErrorState(
          'BookStack\nAn Error Occurred\nAn unknown error occurred',
          'https://bookstack.datamancy.net/books'
        )
      ).toBe(true);
    });

    it('detects BookStack callback error pages by callback URL', () => {
      expect(
        isBookStackTransientOidcErrorState(
          'BookStack',
          'https://bookstack.datamancy.net/oidc/callback?code=abc123'
        )
      ).toBe(true);
    });

    it('does not flag normal authenticated BookStack pages', () => {
      expect(
        isBookStackTransientOidcErrorState(
          'BookStack\nBooks\nShelves\nRecently Updated Pages',
          'https://bookstack.datamancy.net/books'
        )
      ).toBe(false);
    });
  });

  describe('assertAnonymousContract', () => {
    it('retries transient SSL failures and validates a public page contract', async () => {
      const page = createPage({
        gotoErrors: [new Error('net::ERR_SSL_PROTOCOL_ERROR')],
        onGoto: (_url, currentPage) => {
          currentPage.__setBody('Demo Ready');
        },
      });
      const route = createRoute({
        host: 'public-demo',
        label: 'Public Demo',
        anonymous: { kind: 'public_page', matcher: /Demo Ready/ },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();

      expect(page.goto).toHaveBeenCalledTimes(2);
      expect(page.waitForTimeout).toHaveBeenCalledWith(1500);
    });

    it('tolerates load-state and content probe failures while matching body text', async () => {
      const bodyLocator = createLocator({ innerText: 'Demo Ready' });
      const page = createPage({
        locators: {
          body: bodyLocator,
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setBody('ignored fallback body');
        },
      });
      page.waitForLoadState = jest.fn(async () => {
        throw new Error('load state unavailable');
      });
      page.title = jest.fn(async () => {
        throw new Error('title unavailable');
      });
      page.textContent = jest.fn(async () => {
        throw new Error('body text unavailable');
      });
      const route = createRoute({
        host: 'public-demo',
        label: 'Public Demo',
        anonymous: { kind: 'public_page', matcher: /Demo Ready/ },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();

      expect(page.waitForLoadState).toHaveBeenCalledWith('domcontentloaded', { timeout: 10000 });
      expect(bodyLocator.innerText).toHaveBeenCalledWith({ timeout: 1000 });
    });

    it('fails blank service-login pages after bounded failed readiness navigations', async () => {
      const page = createPage();
      let gotoCalls = 0;
      page.goto = jest.fn(async (url: string) => {
        gotoCalls += 1;
        if (gotoCalls === 1) {
          page.__setUrl(url);
          page.__setTitle('');
          page.__setBody('');
          return;
        }
        throw new Error('service still starting');
      });
      page.waitForLoadState = jest.fn(async () => {
        throw new Error('load state unavailable');
      });
      const route = createRoute({
        host: 'bookstack',
        label: 'BookStack',
        anonymous: {
          kind: 'service_login',
          matcher: /BookStack|Login/,
        },
      });

      await expect(assertAnonymousContract(page, route)).rejects.toThrow(
        'BookStack anonymous login page remained blank after bounded readiness navigation.'
      );

      expect(page.waitForLoadState).toHaveBeenCalledWith('domcontentloaded', { timeout: 10000 });
      expect(page.waitForLoadState).toHaveBeenCalledWith('networkidle', { timeout: 10000 });
      expect(page.goto).toHaveBeenCalledTimes(13);
    });

    it('accepts forward-auth routes that land on the Keycloak boundary', async () => {
      const page = createPage({
        locators: {
          'input[name="username"], input[autocomplete="username"], #username-textfield, #username': createLocator(),
          'input[name="password"], input[type="password"], #password-textfield, #password': createLocator(),
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://keycloak.datamancy.net/?rd=https%3A%2F%2Fgrafana.datamancy.net%2F');
          currentPage.__setBody('Keycloak Sign in Username Password');
        },
      });
      const route = createRoute({
        host: 'grafana',
        label: 'Grafana',
        anonymous: { kind: 'forward_auth' },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();
    });

    it('accepts service-login routes that redirect to auth when explicitly allowed', async () => {
      const page = createPage({
        locators: {
          'input[name="username"], input[autocomplete="username"], #username-textfield, #username': createLocator(),
          'input[name="password"], input[type="password"], #password-textfield, #password': createLocator(),
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://keycloak.datamancy.net/?rd=https%3A%2F%2Fbookstack.datamancy.net%2Flogin');
          currentPage.__setBody('Keycloak Sign in Username Password');
        },
      });
      const route = createRoute({
        host: 'bookstack',
        label: 'BookStack',
        anonymous: {
          kind: 'service_login',
          matcher: /BookStack|Login/,
          allowAuthRedirect: true,
        },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();
    });

    it('waits for service-login pages to settle before matching anonymous content', async () => {
      const page = createPage({
        title: 'BookStack',
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://bookstack.datamancy.net/login');
        },
      });
      const route = createRoute({
        host: 'bookstack',
        label: 'BookStack',
        anonymous: {
          kind: 'service_login',
          matcher: /BookStack|Login/,
          allowAuthRedirect: true,
        },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();

      expect(page.waitForLoadState).toHaveBeenCalledWith('domcontentloaded', { timeout: 10000 });
      expect(page.waitForLoadState).toHaveBeenCalledWith('networkidle', { timeout: 10000 });
    });

    it('re-navigates blank service-login pages until content renders', async () => {
      let visits = 0;
      const page = createPage({
        onGoto: (_url, currentPage) => {
          visits += 1;
          currentPage.__setUrl('https://bookstack.datamancy.net/login');
          currentPage.__setTitle('');
          currentPage.__setBody('');
          if (visits >= 3) {
            currentPage.__setTitle('BookStack Login');
            currentPage.__setBody('BookStack Login');
          }
        },
      });
      const route = createRoute({
        host: 'bookstack',
        label: 'BookStack',
        anonymous: {
          kind: 'service_login',
          matcher: /BookStack|Login/,
          allowAuthRedirect: true,
        },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();

      expect(page.goto).toHaveBeenCalledTimes(3);
      expect(page.waitForTimeout).toHaveBeenCalledWith(2500);
    });

    it('fails service-login routes that redirect to auth when the contract forbids it', async () => {
      const page = createPage({
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://keycloak.datamancy.net/?rd=https%3A%2F%2Fservice.datamancy.net%2Flogin');
        },
      });
      const route = createRoute({
        host: 'service',
        label: 'Service',
        anonymous: {
          kind: 'service_login',
          matcher: /Sign in/,
        },
      });

      await expect(assertAnonymousContract(page, route)).rejects.toThrow(
        'Service unexpectedly redirected to Keycloak instead of rendering its service login page.'
      );
    });

    it('validates canonical redirects that terminate on another public page', async () => {
      const page = createPage({
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://apex.datamancy.net/');
          currentPage.__setBody('Target Public');
        },
      });
      const route = createRoute({
        host: 'www-apex',
        label: 'www apex',
        anonymous: {
          kind: 'canonical_redirect',
          targetHost: 'apex',
          followup: 'public_page',
          matcher: /Target Public/,
        },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();
      expect(mockFindRoute).toHaveBeenCalledWith('apex');
    });

    it('validates canonical redirects that terminate on Keycloak', async () => {
      const page = createPage({
        locators: {
          'input[name="username"], input[autocomplete="username"], #username-textfield, #username': createLocator(),
          'input[name="password"], input[type="password"], #password-textfield, #password': createLocator(),
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://keycloak.datamancy.net/?rd=https%3A%2F%2Fportal.datamancy.net%2F');
          currentPage.__setBody('Keycloak Sign in Username Password');
        },
      });
      const route = createRoute({
        host: 'www-homepage',
        label: 'www homepage',
        anonymous: {
          kind: 'canonical_redirect',
          targetHost: 'portal',
          followup: 'forward_auth',
        },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();
    });

    it('returns immediately for non-ui anonymous contracts', async () => {
      const page = createPage();
      const route = createRoute({
        host: 'qdrant',
        label: 'Qdrant',
        anonymous: { kind: 'non_ui' },
      });

      await expect(assertAnonymousContract(page, route)).resolves.toBeUndefined();
    });

    it('rejects orphaned anonymous contracts with the catalog reason', async () => {
      const page = createPage();
      const route = createRoute({
        host: 'orphaned',
        label: 'Orphaned Service',
        anonymous: { kind: 'orphaned', reason: 'not in generated routes' },
      });

      await expect(assertAnonymousContract(page, route)).rejects.toThrow(
        'Orphaned Service is marked orphaned and must be removed from Caddy exposure or catalogued correctly: not in generated routes'
      );
    });

    it('rejects unsupported anonymous contract kinds', async () => {
      const page = createPage();
      const route = createRoute({
        host: 'unknown',
        label: 'Unknown Service',
        anonymous: { kind: 'mystery' },
      });

      await expect(assertAnonymousContract(page, route)).rejects.toThrow(
        'Unsupported anonymous contract for route unknown'
      );
    });
  });

  describe('assertSmokeContract', () => {
    it('validates public smoke routes', async () => {
      const page = createPage({
        locators: {
          '#ready': createLocator({ visible: true }),
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://status.datamancy.net/');
          currentPage.__setBody('Demo Ready');
        },
      });
      const route = createRoute({
        host: 'status',
        label: 'Status',
        kind: 'public',
        smoke: {
          path: '/',
          matcher: /Demo Ready/,
          selector: '#ready',
          disallowMatcher: /Error/,
          disallowUrlMatcher: /auth\./,
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
    });

    it('uses indexed selector matches across smoke selector alternatives', async () => {
      const indexedLocator = createIndexedLocator([false, true]);
      const page = createPage({
        locators: {
          '.missing': indexedLocator,
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://status.datamancy.net/');
          currentPage.__setBody('Ready Content');
        },
      });
      const route = createRoute({
        host: 'status',
        label: 'Status',
        kind: 'public',
        smoke: {
          matcher: /Ready Content/,
          selector: '.missing, text=Ready Content',
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(indexedLocator.count).toHaveBeenCalledTimes(1);
      expect(indexedLocator.nth).toHaveBeenCalledWith(0);
      expect(indexedLocator.nth).toHaveBeenCalledWith(1);
    });

    it('reports missing smoke selectors when indexed selector count fails', async () => {
      const nowValues = [0, 0, 1, 70001];
      jest.spyOn(Date, 'now').mockImplementation(() => nowValues.shift() ?? 70001);
      const indexedLocator = createLocator({ visible: false });
      indexedLocator.count = jest.fn(async () => {
        throw new Error('selector count unavailable');
      });
      indexedLocator.nth = jest.fn();
      const page = createPage({
        locators: {
          '#ready': indexedLocator,
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://status.datamancy.net/');
          currentPage.__setBody('Ready Content');
        },
      });
      const route = createRoute({
        host: 'status',
        label: 'Status',
        kind: 'public',
        smoke: {
          matcher: /Ready Content/,
          selector: '#ready',
        },
      });

      await expect(assertSmokeContract(page, route, user)).rejects.toThrow(
        'Status authenticated page did not satisfy smoke contract'
      );
      expect(indexedLocator.count).toHaveBeenCalledTimes(1);
      expect(indexedLocator.nth).not.toHaveBeenCalled();
    });

    it('continues scanning indexed smoke selectors when one visibility probe fails', async () => {
      const firstMatch = createLocator({ visible: false });
      firstMatch.isVisible = jest.fn(async () => {
        throw new Error('detached selector');
      });
      const secondMatch = createLocator({ visible: true });
      const indexedLocator = createLocator({ visible: false });
      indexedLocator.count = jest.fn(async () => 2);
      indexedLocator.nth = jest.fn((index: number) => (index === 0 ? firstMatch : secondMatch));
      const page = createPage({
        locators: {
          '#ready': indexedLocator,
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://status.datamancy.net/');
          currentPage.__setBody('Ready Content');
        },
      });
      const route = createRoute({
        host: 'status',
        label: 'Status',
        kind: 'public',
        smoke: {
          matcher: /Ready Content/,
          selector: '#ready',
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(firstMatch.isVisible).toHaveBeenCalledTimes(1);
      expect(secondMatch.isVisible).toHaveBeenCalledTimes(1);
    });

    it('reports stuck smoke pages with a redacted content summary after bounded recovery', async () => {
      const nowValues = [0, 0, 1, 7000, 7000, 70001];
      jest.spyOn(Date, 'now').mockImplementation(() => nowValues.shift() ?? 70001);
      const page = createPage({
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://status.datamancy.net/dashboard?token=secret');
          currentPage.__setBody('Loading... taking longer than usual');
        },
      });
      const route = createRoute({
        host: 'status',
        label: 'Status',
        kind: 'public',
        smoke: {
          path: '/dashboard',
          matcher: /Ready Content/,
        },
      });

      await expect(assertSmokeContract(page, route, user)).rejects.toThrow(
        'Status authenticated page did not satisfy smoke contract at https://status.datamancy.net/dashboard?token=REDACTED; content: Loading... taking longer than usual'
      );
      expect(page.goto).toHaveBeenCalledTimes(2);
      expect(page.waitForTimeout).toHaveBeenCalledWith(1000);
    });

    it('logs into forward-auth smoke routes when they initially land on Keycloak', async () => {
      const page = createPage({
        locators: {
          'text=All Logs': createLocator({ visible: true }),
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://keycloak.datamancy.net/?rd=https%3A%2F%2Fgrafana.datamancy.net%2Fd%2Flogs-home%2Flogs');
          currentPage.__setBody('Keycloak Sign in');
        },
      });
      page.__onKeycloakLogin = async () => {
        page.__setUrl('https://grafana.datamancy.net/d/logs-home/logs');
        page.__setBody('All Logs Loki Refresh');
      };
      const route = createRoute({
        host: 'grafana',
        label: 'Grafana',
        kind: 'forward_auth',
        smoke: {
          path: '/d/logs-home/logs',
          matcher: /All Logs|Loki/,
          selector: 'text=All Logs',
          disallowMatcher: /Failed to load/,
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(KeycloakLoginPage).toHaveBeenCalledTimes(1);
      expect(mockKeycloakLogin).toHaveBeenCalledWith(page, 'gerald', 'secret');
    });

    it('returns to the smoke path when forward-auth login leaves the browser on Keycloak', async () => {
      let visits = 0;
      const page = createPage({
        locators: {
          'text=All Logs': createLocator({ visible: true }),
        },
        onGoto: (_url, currentPage) => {
          visits += 1;
          if (visits === 1) {
            currentPage.__setUrl('https://keycloak.datamancy.net/?rd=https%3A%2F%2Fgrafana.datamancy.net%2Fd%2Flogs-home%2Flogs');
            currentPage.__setBody('Keycloak Sign in');
            return;
          }
          currentPage.__setUrl('https://grafana.datamancy.net/d/logs-home/logs');
          currentPage.__setBody('All Logs Loki Refresh');
        },
      });
      page.__onKeycloakLogin = async () => undefined;
      const route = createRoute({
        host: 'grafana',
        label: 'Grafana',
        kind: 'forward_auth',
        smoke: {
          path: '/d/logs-home/logs',
          matcher: /All Logs|Loki/,
          selector: 'text=All Logs',
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(page.goto).toHaveBeenCalledWith('https://grafana.datamancy.net/d/logs-home/logs', {
        waitUntil: 'domcontentloaded',
        timeout: 30000,
      });
      expect(page.goto).toHaveBeenCalledTimes(2);
    });

    it('skips OIDC login when the authenticated page is already ready', async () => {
      const page = createPage({
        locators: {
          'input[name="full_name"]': createLocator({ visible: true }),
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://forgejo.datamancy.net/user/settings');
          currentPage.__setBody('Account Profile');
        },
      });
      const route = createRoute({
        host: 'forgejo',
        label: 'Forgejo',
        kind: 'oidc_login',
        smoke: {
          path: '/user/settings',
          matcher: /Account|Profile/,
          selector: 'input[name="full_name"]',
          loginLabel: 'Keycloak',
          disallowMatcher: /Sign in/,
          disallowUrlMatcher: /\/user\/login\b/i,
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(OIDCLoginPage).not.toHaveBeenCalled();
      expect(KeycloakLoginPage).not.toHaveBeenCalled();
    });

    it('completes service-led OIDC login flows with a consent screen', async () => {
      let settingsVisits = 0;
      const page = createPage({
        locators: {
          'input[name="full_name"]': createLocator({ visible: [false, true] }),
        },
        onGoto: (_url, currentPage) => {
          settingsVisits += 1;
          currentPage.__setUrl('https://forgejo.datamancy.net/user/settings');
          currentPage.__setBody(settingsVisits === 1 ? 'Sign in with Keycloak' : 'Account Profile');
        },
      });
      page.__onOidcClick = async () => {
        page.__setUrl('https://keycloak.datamancy.net/consent/openid/decision?flow=oidc');
      };
      page.__onConsent = async () => {
        page.__setUrl('https://forgejo.datamancy.net/user/settings');
        page.__setBody('Account Profile');
      };
      const route = createRoute({
        host: 'forgejo',
        label: 'Forgejo',
        kind: 'oidc_login',
        smoke: {
          path: '/user/settings',
          matcher: /Account|Profile/,
          selector: 'input[name="full_name"]',
          loginLabel: 'Keycloak',
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(mockClickOIDCButton).toHaveBeenCalledWith(page, 'Keycloak');
      expect(mockHandleConsentScreen).toHaveBeenCalledWith(page);
    });

    it('uses an explicit OIDC start path before provider login', async () => {
      let settingsVisits = 0;
      const page = createPage({
        locators: {
          'input[name="full_name"]': createLocator({ visible: true }),
        },
        onGoto: (url, currentPage) => {
          if (url === 'https://forgejo.datamancy.net/oauth/start') {
            currentPage.__setUrl('https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=forgejo');
            currentPage.__setBody('Keycloak Sign in');
            return;
          }
          if (url === 'https://forgejo.datamancy.net/user/settings') {
            settingsVisits += 1;
          }
          currentPage.__setUrl('https://forgejo.datamancy.net/user/settings');
          currentPage.__setBody(settingsVisits > 1 ? 'Account Profile' : 'Sign in with Keycloak');
        },
      });
      page.__onKeycloakLogin = async () => {
        page.__setUrl('https://forgejo.datamancy.net/user/settings');
        page.__setBody('Account Profile');
      };
      const route = createRoute({
        host: 'forgejo',
        label: 'Forgejo',
        kind: 'oidc_login',
        smoke: {
          path: '/user/settings',
          oidcStartPath: '/oauth/start',
          matcher: /Account|Profile/,
          selector: 'input[name="full_name"]',
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(mockRouteUrl).toHaveBeenCalledWith(route, '/oauth/start');
      expect(mockKeycloakLogin).toHaveBeenCalledWith(page, 'gerald', 'secret');
      expect(mockClickOIDCButton).not.toHaveBeenCalled();
    });

    it('recovers the transient BookStack OIDC callback error and continues to the smoke page', async () => {
      let bookPathVisits = 0;
      const page = createPage({
        locators: {
          'a[href="/books"]': createLocator({ visible: true }),
          '#oidc-login': createLocator({ visible: true }),
        },
        onGoto: (url, currentPage) => {
          if (url === 'https://bookstack.datamancy.net/books') {
            bookPathVisits += 1;
            if (bookPathVisits === 1) {
              currentPage.__setUrl('https://keycloak.datamancy.net/?rd=https%3A%2F%2Fbookstack.datamancy.net%2Fbooks');
              currentPage.__setBody('Keycloak Sign in');
            } else {
              currentPage.__setUrl('https://bookstack.datamancy.net/books');
              currentPage.__setBody('Books Shelves Recently Updated Pages');
            }
            return;
          }

          if (url === 'https://bookstack.datamancy.net/') {
            currentPage.__setUrl(url);
            currentPage.__setBody('Books Shelves Recently Updated Pages');
          }
        },
      });
      page.__onKeycloakLogin = async () => {
        page.__setUrl('https://bookstack.datamancy.net/oidc/callback?code=abc123');
        page.__setBody('An Error Occurred\nAn unknown error occurred');
      };
      const route = createRoute({
        host: 'bookstack',
        label: 'BookStack',
        kind: 'oidc_login',
        smoke: {
          path: '/books',
          matcher: /Books|Shelves|Recently Updated Pages/,
          selector: 'a[href="/books"]',
          loginLabel: 'Keycloak',
          disallowMatcher: /Login with Keycloak/,
          disallowUrlMatcher: /\/login\b/i,
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(mockKeycloakLogin).toHaveBeenCalledWith(page, 'gerald', 'secret');
      expect(page.goto).toHaveBeenCalledWith('https://bookstack.datamancy.net/', {
        waitUntil: 'domcontentloaded',
        timeout: 15000,
      });
    });

    it('retries the BookStack OIDC button when callback recovery lands back on login', async () => {
      let bookPathVisits = 0;
      const retryButton = createLocator({ visible: true });
      const page = createPage({
        locators: {
          'a[href="/books"]': createLocator({ visible: true }),
          '#oidc-login': retryButton,
        },
        onGoto: (url, currentPage) => {
          if (url === 'https://bookstack.datamancy.net/books') {
            bookPathVisits += 1;
            if (bookPathVisits === 1) {
              currentPage.__setUrl('https://keycloak.datamancy.net/?rd=https%3A%2F%2Fbookstack.datamancy.net%2Fbooks');
              currentPage.__setBody('Keycloak Sign in');
            } else {
              currentPage.__setUrl('https://bookstack.datamancy.net/books');
              currentPage.__setBody('Books Shelves Recently Updated Pages');
            }
            return;
          }

          if (url === 'https://bookstack.datamancy.net/') {
            currentPage.__setUrl('https://bookstack.datamancy.net/login');
            currentPage.__setBody('Login with Keycloak');
            return;
          }

          if (url === 'https://bookstack.datamancy.net/login') {
            currentPage.__setUrl(url);
            currentPage.__setBody('Login with Keycloak');
          }
        },
      });
      page.__onKeycloakLogin = async () => {
        page.__setUrl('https://bookstack.datamancy.net/oidc/callback?code=abc123');
        page.__setBody('An Error Occurred');
      };
      const route = createRoute({
        host: 'bookstack',
        label: 'BookStack',
        kind: 'oidc_login',
        smoke: {
          path: '/books',
          matcher: /Books|Shelves|Recently Updated Pages/,
          selector: 'a[href="/books"]',
          loginLabel: 'Keycloak',
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(retryButton.click).toHaveBeenCalledWith({ force: true });
      expect(page.goto).toHaveBeenCalledWith('https://bookstack.datamancy.net/login', {
        waitUntil: 'domcontentloaded',
        timeout: 15000,
      });
    });

    it('rejects unsupported smoke route kinds', async () => {
      const page = createPage();
      const route = createRoute({
        host: 'api',
        label: 'API',
        kind: 'non_ui',
        smoke: {
          matcher: /never/,
        },
      });

      await expect(assertSmokeContract(page, route, user)).rejects.toThrow(
        "API has unsupported smoke route kind 'non_ui'."
      );
    });

    it('rejects routes that are not part of the smoke suite', async () => {
      const page = createPage();
      const route = createRoute({
        host: 'docs',
        label: 'Docs',
        smoke: undefined,
      });

      await expect(assertSmokeContract(page, route, user)).rejects.toThrow(
        'Docs is not part of the smoke suite.'
      );
    });

    it('requires Playwright header support when smoke headers are configured', async () => {
      const page = createPage({
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://status.datamancy.net/');
          currentPage.__setBody('Demo Ready');
        },
      });
      delete page.setExtraHTTPHeaders;
      const route = createRoute({
        host: 'status',
        label: 'Status',
        kind: 'public',
        smoke: {
          matcher: /Demo Ready/,
          headers: {
            'X-Test': 'true',
          },
        },
      });

      await expect(assertSmokeContract(page, route, user)).rejects.toThrow(
        'Playwright page does not support per-route HTTP headers.'
      );
    });

    it('uses user-specific smoke paths when provided', async () => {
      const page = createPage({
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://profile.datamancy.net/users/gerald');
          currentPage.__setBody('Profile for gerald');
        },
      });
      const route = createRoute({
        host: 'profile',
        label: 'Profile',
        kind: 'public',
        smoke: {
          matcher: /Profile for gerald/,
          pathForUser: ({ username }: { username: string }) => `/users/${username}`,
        },
      });

      await expect(assertSmokeContract(page, route, user)).resolves.toBeUndefined();
      expect(mockRouteUrl).toHaveBeenCalledWith(route, '/users/gerald');
    });
  });

  describe('captureVisualSnapshot', () => {
    it('rejects routes that are not part of the visual suite', async () => {
      const page = createPage();
      const route = createRoute({
        host: 'text-only',
        label: 'Text Only',
        visual: undefined,
      });

      await expect(captureVisualSnapshot(page, route, user, '/tmp/screenshots')).rejects.toThrow(
        'Text Only is not part of the visual suite.'
      );
    });

    it('reuses the smoke flow and writes a screenshot into the visual output directory', async () => {
      const screenshotRoot = fs.mkdtempSync('/tmp/webservices-visual-test-');
      const page = createPage({
        locators: {
          '#visual-ready': createLocator({ visible: true }),
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://visual.datamancy.net/');
          currentPage.__setBody('Visual Ready');
        },
      });
      const route = createRoute({
        host: 'visual',
        label: 'Visual',
        kind: 'public',
        smoke: {
          matcher: /placeholder/,
        },
        visual: {
          fileStem: 'visual-home',
          path: '/',
          matcher: /Visual Ready/,
          selector: '#visual-ready',
          headers: {
            'User-Agent': 'Visual Test Browser',
          },
          quality: 90,
          fullPage: false,
        },
      });

      await expect(captureVisualSnapshot(page, route, user, screenshotRoot)).resolves.toBe(
        `${screenshotRoot}/visual/visual-home.jpeg`
      );

      expect(page.screenshot).toHaveBeenCalledWith({
        path: `${screenshotRoot}/visual/visual-home.jpeg`,
        type: 'jpeg',
        quality: 90,
        fullPage: false,
      });
      expect(page.setExtraHTTPHeaders).toHaveBeenCalledWith({
        'User-Agent': 'Visual Test Browser',
      });
    });

    it('prepares and cleans up ChatGPT connector visual demo data', async () => {
      const screenshotRoot = fs.mkdtempSync('/tmp/webservices-chatgpt-visual-test-');
      const fetchMock = jest.fn()
        .mockResolvedValueOnce({
          ok: true,
          text: async () => JSON.stringify({ id: 'agent-1' }),
        })
        .mockResolvedValueOnce({
          ok: true,
          text: async () => JSON.stringify({ id: 'token-1' }),
        })
        .mockResolvedValueOnce({
          ok: true,
          text: async () => '',
        });
      const originalFetch = (global as any).fetch;
      (global as any).fetch = fetchMock;
      const page = createPage({
        locators: {
          '#agent-accounts': createLocator({ visible: true }),
        },
        onGoto: (_url, currentPage) => {
          currentPage.__setUrl('https://chatgpt-connector.datamancy.net/');
          currentPage.__setBody('Connector Ready');
        },
      });
      const route = createRoute({
        host: 'chatgpt-connector',
        label: 'ChatGPT Connector',
        kind: 'public',
        visual: {
          fileStem: 'chatgpt-connector',
          path: '/',
          matcher: /Connector Ready/,
          selector: '#agent-accounts',
        },
      });

      try {
        await expect(captureVisualSnapshot(page, route, user, screenshotRoot)).resolves.toBe(
          `${screenshotRoot}/visual/chatgpt-connector.jpeg`
        );
      } finally {
        (global as any).fetch = originalFetch;
      }

      expect(page.reload).toHaveBeenCalledWith({ waitUntil: 'domcontentloaded' });
      expect(fetchMock).toHaveBeenCalledWith('/api/agent-accounts', expect.objectContaining({ method: 'POST' }));
      expect(fetchMock).toHaveBeenCalledWith('/api/agent-accounts/agent-1/tokens', expect.objectContaining({ method: 'POST' }));
      expect(fetchMock).toHaveBeenCalledWith('/api/agent-accounts/agent-1/close', expect.objectContaining({ method: 'POST' }));
    });
  });
});
