jest.mock('../../utils/telemetry', () => ({
  logPageTelemetry: jest.fn(async () => undefined),
  redactUrlForLogs: jest.fn((url: string) => url),
}));

import { OIDCLoginPage } from '../../pages/OIDCLoginPage';
import { logPageTelemetry } from '../../utils/telemetry';

type FakeLocatorOptions = {
  visible?: boolean | boolean[];
  waitForError?: Error;
};

function createLocator(options: FakeLocatorOptions = {}) {
  const locator: any = {};
  const visibleSequence = Array.isArray(options.visible) ? [...options.visible] : null;

  locator.or = jest.fn(() => locator);
  locator.filter = jest.fn(() => locator);
  locator.first = jest.fn(() => locator);
  locator.isVisible = jest.fn(async () => {
    if (visibleSequence) {
      return visibleSequence.length > 0 ? visibleSequence.shift() : false;
    }
    return options.visible ?? true;
  });
  locator.waitFor = options.waitForError
    ? jest.fn(async () => {
        throw options.waitForError;
      })
    : jest.fn(async () => undefined);
  locator.click = jest.fn(async () => undefined);

  return locator;
}

function createClickPage(options: {
  oidcLocator?: ReturnType<typeof createLocator>;
  signInLocator?: ReturnType<typeof createLocator>;
  hrefLocator?: ReturnType<typeof createLocator>;
  currentUrl?: string;
  waitForUrlTargetUrl?: string;
  waitForUrlError?: Error;
} = {}) {
  const oidcLocator = options.oidcLocator ?? createLocator();
  const signInLocator = options.signInLocator ?? createLocator({ visible: false });
  const hrefLocator = options.hrefLocator ?? createLocator({ visible: false });
  const currentUrl = options.currentUrl ?? 'https://bookstack.datamancy.net/login';
  const waitForUrlTargetUrl = options.waitForUrlTargetUrl ?? currentUrl;

  return {
    url: jest.fn(() => currentUrl),
    waitForURL: options.waitForUrlError
      ? jest.fn(async () => {
          throw options.waitForUrlError;
        })
      : jest.fn(async (predicate?: (url: URL) => boolean) => {
          const targetUrl = new URL(waitForUrlTargetUrl);
          if (predicate && !predicate(targetUrl)) {
            throw new Error('predicate not satisfied');
          }
        }),
    waitForLoadState: jest.fn(async () => undefined),
    getByRole: jest.fn((role: string, roleOptions?: { name?: RegExp }) => {
      if (
        (role === 'link' || role === 'button')
        && roleOptions?.name
        && String(roleOptions.name) === String(/^sign in$|^log in$/i)
      ) {
        return signInLocator;
      }
      return oidcLocator;
    }),
    getByText: jest.fn(() => oidcLocator),
    locator: jest.fn((selector: string) => {
      if (selector.includes('href*="openid"') || selector.includes('href*="oidc"') || selector.includes('href*="oauth"') || selector.includes('href*="sso"')) {
        return hrefLocator;
      }
      return oidcLocator;
    }),
  };
}

function createConsentPage(options: {
  currentUrl?: string;
  consentLocator?: ReturnType<typeof createLocator>;
  redirectedAway?: boolean;
  waitForUrlError?: Error;
} = {}) {
  const consentLocator = options.consentLocator ?? createLocator();
  const currentUrl = options.currentUrl ?? 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo&prompt=consent';
  const redirectedAway = options.redirectedAway ?? false;

  return {
    url: jest.fn(() => currentUrl),
    waitForTimeout: jest.fn(async () => undefined),
    waitForURL: options.waitForUrlError
      ? jest.fn(async () => {
          throw options.waitForUrlError;
        })
      : jest.fn(async (predicate?: (url: URL) => boolean) => {
          const targetUrl = new URL(redirectedAway ? 'https://forgejo.datamancy.net/' : currentUrl);
          if (predicate && !predicate(targetUrl)) {
            throw new Error('predicate not satisfied');
          }
        }),
    getByRole: jest.fn(() => consentLocator),
    getByText: jest.fn(() => consentLocator),
    locator: jest.fn(() => consentLocator),
  };
}

describe('OIDCLoginPage.clickOIDCButton', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'log').mockImplementation(() => undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('clicks a visible OIDC button and records the Keycloak redirect', async () => {
    const oidcLocator = createLocator({ visible: true });
    const page = createClickPage({
      oidcLocator,
      currentUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
      waitForUrlTargetUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak')).resolves.toBeUndefined();
    expect(logPageTelemetry).toHaveBeenCalledWith(page, 'Service Login Page (pre-OIDC)');
    expect(oidcLocator.click).toHaveBeenCalledWith({
      force: true,
      noWaitAfter: true,
    });
    expect(page.waitForURL).toHaveBeenCalledWith(expect.any(Function), { timeout: 15000 });
    expect(console.log).toHaveBeenCalledWith('   ✓ Redirected to Keycloak: https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo\n');
  });

  it('uses Keycloak as the default OIDC button label', async () => {
    const oidcLocator = createLocator({ visible: true });
    const page = createClickPage({
      oidcLocator,
      currentUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
      waitForUrlTargetUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton()).resolves.toBeUndefined();
    expect(page.getByRole).toHaveBeenCalledWith('button', { name: /Keycloak/i });
  });

  it('uses the sign-in hop when the OIDC button is not initially visible', async () => {
    const oidcLocator = createLocator({ visible: [false, true] });
    const signInLocator = createLocator({ visible: true });
    const page = createClickPage({
      oidcLocator,
      signInLocator,
      currentUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
      waitForUrlTargetUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak')).resolves.toBeUndefined();
    expect(signInLocator.click).toHaveBeenCalledWith({ force: true });
    expect(page.waitForLoadState).toHaveBeenCalledWith('domcontentloaded', { timeout: 10000 });
    expect(oidcLocator.click).toHaveBeenCalledWith({
      force: true,
      noWaitAfter: true,
    });
    expect(console.log).toHaveBeenCalledWith('   ✓ Clicked OIDC button: Keycloak after sign-in hop');
  });

  it('tolerates sign-in hop load-state timeouts and still retries the OIDC button', async () => {
    const oidcLocator = createLocator({ visible: [false, true] });
    const signInLocator = createLocator({ visible: true });
    const page = createClickPage({
      oidcLocator,
      signInLocator,
      currentUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
      waitForUrlTargetUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
    });
    page.waitForLoadState = jest.fn(async () => {
      throw new Error('navigation stalled');
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak')).resolves.toBeUndefined();
    expect(signInLocator.click).toHaveBeenCalledWith({ force: true });
    expect(oidcLocator.click).toHaveBeenCalledWith({
      force: true,
      noWaitAfter: true,
    });
  });

  it('falls back to an href-based OIDC link when no named button is available', async () => {
    const oidcLocator = createLocator({ visible: false });
    const signInLocator = createLocator({ visible: false });
    const hrefLocator = createLocator({ visible: true });
    const page = createClickPage({
      oidcLocator,
      signInLocator,
      hrefLocator,
      currentUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
      waitForUrlTargetUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak')).resolves.toBeUndefined();
    expect(hrefLocator.click).toHaveBeenCalledWith({ force: true, noWaitAfter: true });
    expect(console.log).toHaveBeenCalledWith('   ✓ Clicked OIDC button: Keycloak');
  });

  it('falls back through thrown visibility checks before using the href-based OIDC link', async () => {
    const oidcLocator = createLocator();
    oidcLocator.isVisible
      .mockRejectedValueOnce(new Error('primary oidc button probe failed'))
      .mockRejectedValueOnce(new Error('secondary oidc button probe failed'));
    const signInLocator = createLocator();
    signInLocator.isVisible.mockRejectedValue(new Error('sign-in entry point probe failed'));
    const hrefLocator = createLocator({ visible: true });
    const page = createClickPage({
      oidcLocator,
      signInLocator,
      hrefLocator,
      currentUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
      waitForUrlTargetUrl: 'https://keycloak.datamancy.net/realms/webservices/protocol/openid-connect/auth?client_id=demo',
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak')).resolves.toBeUndefined();
    expect(hrefLocator.click).toHaveBeenCalledWith({ force: true, noWaitAfter: true });
  });

  it('throws when no OIDC entry point can be found', async () => {
    const page = createClickPage({
      oidcLocator: createLocator({ visible: false }),
      signInLocator: createLocator({ visible: false }),
      hrefLocator: createLocator({ visible: false }),
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak')).rejects.toThrow('OIDC button/link not found for: Keycloak');
  });

  it('treats href fallback visibility errors as a missing OIDC entry point', async () => {
    const hrefLocator = createLocator();
    hrefLocator.isVisible.mockRejectedValue(new Error('href probe failed'));
    const page = createClickPage({
      oidcLocator: createLocator({ visible: false }),
      signInLocator: createLocator({ visible: false }),
      hrefLocator,
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak')).rejects.toThrow('OIDC button/link not found for: Keycloak');
  });

  it('fails when an OIDC click does not redirect to Keycloak by default', async () => {
    const page = createClickPage({
      oidcLocator: createLocator({ visible: true }),
      currentUrl: 'https://bookstack.datamancy.net/login',
      waitForUrlError: new Error('timeout'),
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak')).rejects.toThrow(
      'OIDC click for "Keycloak" did not redirect to Keycloak'
    );
  });

  it('allows a missing auth redirect only when explicitly requested', async () => {
    const page = createClickPage({
      oidcLocator: createLocator({ visible: true }),
      currentUrl: 'https://bookstack.datamancy.net/books',
      waitForUrlError: new Error('timeout'),
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.clickOIDCButton('Keycloak', { requireAuthRedirect: false })).resolves.toBeUndefined();
    expect(console.log).toHaveBeenCalledWith('   ℹ️  No auth redirect detected; caller allowed non-auth continuation');
  });
});

describe('OIDCLoginPage.handleConsentScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'log').mockImplementation(() => undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('clicks the consent button and waits for redirect back to the relying party', async () => {
    const consentLocator = createLocator();
    const page = createConsentPage({
      consentLocator,
      redirectedAway: true,
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.handleConsentScreen()).resolves.toBeUndefined();
    expect(logPageTelemetry).toHaveBeenCalledWith(page, 'Consent Screen');
    expect(consentLocator.waitFor).toHaveBeenCalledWith({ state: 'visible', timeout: 5000 });
    expect(consentLocator.click).toHaveBeenCalledTimes(1);
    expect(page.waitForURL).toHaveBeenCalledWith(expect.any(Function), { timeout: 10000 });
    expect(console.log).toHaveBeenCalledWith('   ✓ Consent button found');
    expect(console.log).toHaveBeenCalledWith('   ✓ Consent granted\n');
  });

  it('tolerates consent redirect waits timing out after the accept click', async () => {
    const consentLocator = createLocator();
    const page = createConsentPage({
      consentLocator,
      waitForUrlError: new Error('redirect wait timed out'),
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.handleConsentScreen()).resolves.toBeUndefined();
    expect(consentLocator.click).toHaveBeenCalledTimes(1);
    expect(logPageTelemetry).toHaveBeenCalledWith(page, 'Consent Screen');
  });

  it('treats in-flight post-consent redirects as success', async () => {
    const consentLocator = createLocator({
      waitForError: new Error('Timeout waiting for consent button'),
    });
    const page = createConsentPage({
      consentLocator,
      redirectedAway: true,
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.handleConsentScreen()).resolves.toBeUndefined();
    expect(logPageTelemetry).toHaveBeenCalledWith(page, 'Consent Screen');
    expect(logPageTelemetry).not.toHaveBeenCalledWith(page, 'Consent Screen Error');
  });

  it('still fails when consent button is missing and no redirect occurs', async () => {
    const consentLocator = createLocator({
      waitForError: new Error('Timeout waiting for consent button'),
    });
    const page = createConsentPage({
      consentLocator,
      redirectedAway: false,
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.handleConsentScreen()).rejects.toThrow('Failed to handle consent screen');
    expect(logPageTelemetry).toHaveBeenCalledWith(page, 'Consent Screen Error');
  });

  it('logs the no-consent path when the flow is already complete', async () => {
    const page = createConsentPage({
      currentUrl: 'https://forgejo.datamancy.net/',
      redirectedAway: true,
    });

    const oidcPage = new OIDCLoginPage(page as never);

    await expect(oidcPage.handleConsentScreen()).resolves.toBeUndefined();
    expect(logPageTelemetry).toHaveBeenCalledWith(page, 'Post-Login (No Consent)');
    expect(console.log).toHaveBeenCalledWith('   ℹ️  No consent screen (already granted or not required)\n');
  });
});
