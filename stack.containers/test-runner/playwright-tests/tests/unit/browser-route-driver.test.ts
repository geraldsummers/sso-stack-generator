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

function createLocator(options: { visible?: boolean | boolean[] } = {}) {
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
  });

  describe('captureVisualSnapshot', () => {
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
  });
});
