import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { logPageTelemetry, redactUrlForLogs, savePageHTML, setupNetworkLogging } from '../../utils/telemetry';

type FakeElementOptions = {
  attributes?: Record<string, string>;
  text?: string | null;
  tagName?: string;
  throwOnAttribute?: boolean;
  throwOnText?: boolean;
  throwOnEvaluate?: boolean;
};

function createElement(options: FakeElementOptions = {}) {
  const {
    attributes = {},
    text = '',
    tagName = 'div',
    throwOnAttribute = false,
    throwOnText = false,
    throwOnEvaluate = false,
  } = options;

  return {
    getAttribute: jest.fn(async (name: string) => {
      if (throwOnAttribute) {
        throw new Error(`cannot read ${name}`);
      }
      return attributes[name] ?? null;
    }),
    textContent: jest.fn(async () => {
      if (throwOnText) {
        throw new Error('cannot read text');
      }
      return text;
    }),
    evaluate: jest.fn(async (callback?: (element: { tagName: string }) => unknown) => {
      if (throwOnEvaluate) {
        throw new Error('cannot evaluate');
      }
      if (callback) {
        return callback({ tagName: tagName.toUpperCase() });
      }
      return tagName;
    }),
  };
}

function createPage(options: {
  url?: string;
  title?: string;
  titleError?: Error;
  html?: string;
  locators?: Record<string, unknown[]>;
} = {}) {
  const handlers: Record<string, (payload: unknown) => void> = {};
  const locators = options.locators ?? {};

  return {
    url: jest.fn(() => options.url ?? 'https://grafana.datamancy.net/login'),
    title: options.titleError
      ? jest.fn(async () => {
          throw options.titleError;
        })
      : jest.fn(async () => options.title ?? 'Example Page'),
    locator: jest.fn((selector: string) => ({
      all: jest.fn(async () => locators[selector] ?? []),
    })),
    content: jest.fn(async () => options.html ?? '<html><body>snapshot</body></html>'),
    on: jest.fn((event: string, handler: (payload: unknown) => void) => {
      handlers[event] = handler;
    }),
    __handlers: handlers,
  };
}

describe('telemetry', () => {
  let logSpy: jest.SpyInstance;
  let warnSpy: jest.SpyInstance;

  beforeEach(() => {
    logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('logs rich page telemetry across populated page sections', async () => {
    const inputs = Array.from({ length: 22 }, (_, index) =>
      createElement({
        attributes: {
          type: index === 0 ? 'email' : 'text',
          name: `field-${index}`,
          id: `input-${index}`,
          placeholder: index === 0 ? 'Email address' : '',
          'aria-label': index === 0 ? 'Email' : '',
          class: index === 0 ? 'x'.repeat(60) : '',
        },
      })
    );
    inputs[1] = createElement({ throwOnAttribute: true });

    const buttons = Array.from({ length: 16 }, (_, index) =>
      createElement({
        attributes: {
          type: index === 0 ? 'submit' : 'button',
          id: index === 0 ? 'submit-button' : '',
          'aria-label': index === 0 ? 'Submit form' : '',
        },
        text: `Button ${index}`,
      })
    );
    buttons[1] = createElement({ throwOnText: true });

    const links = [
      ...Array.from({ length: 10 }, (_, index) =>
        createElement({
          attributes: { href: `/doc/${index}` },
          text: `Link ${index}`,
        })
      ),
      createElement({ attributes: { href: '/ignored' }, text: '   ' }),
    ];

    const headings = [
      createElement({ tagName: 'h1', text: 'Primary heading' }),
      createElement({ tagName: 'h2', text: 'Secondary heading' }),
      createElement({ tagName: 'h3', text: '   ' }),
      createElement({ throwOnEvaluate: true }),
    ];

    const roles = [
      createElement({ attributes: { role: 'banner' } }),
      createElement({ attributes: { role: 'navigation' } }),
      createElement({ attributes: { role: 'navigation' } }),
      createElement({ throwOnAttribute: true }),
    ];

    const page = createPage({
      title: 'Grafana',
      locators: {
        input: inputs,
        button: buttons,
        a: links,
        'h1, h2, h3': headings,
        '[role]': roles,
      },
    });

    await logPageTelemetry(page as never, 'Login');

    const output = logSpy.mock.calls.flat().join('\n');
    expect(output).toContain('Login Telemetry');
    expect(output).toContain('URL:   https://grafana.datamancy.net/login');
    expect(output).toContain('Title: "Grafana"');
    expect(output).toContain('Inputs (22):');
    expect(output).toContain('[1] (could not extract)');
    expect(output).toContain('placeholder="Email address"');
    expect(output).toContain('aria-label="Email"');
    expect(output).toContain('class="xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx..."');
    expect(output).toContain('... and 2 more');
    expect(output).toContain('Buttons (16):');
    expect(output).toContain('id="submit-button"');
    expect(output).toContain('aria-label="Submit form"');
    expect(output).toContain('... and 1 more');
    expect(output).toContain('Links (showing first 10 of 11):');
    expect(output).toContain('"Link 0"');
    expect(output).toContain('Headings (4):');
    expect(output).toContain('<h1> Primary heading');
    expect(output).toContain('ARIA Roles: banner(1), navigation(2)');
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('logs empty sections without warnings when no matching elements exist', async () => {
    const page = createPage({
      title: 'Blank',
      locators: {
        input: [],
        button: [],
        a: [],
        'h1, h2, h3': [],
        '[role]': [],
      },
    });

    await logPageTelemetry(page as never);

    const output = logSpy.mock.calls.flat().join('\n');
    expect(output).toContain('Page Telemetry');
    expect(output).toContain('Inputs: (none)');
    expect(output).toContain('Buttons: (none)');
    expect(output).toContain('Links: (none)');
    expect(output).not.toContain('Headings (');
    expect(output).not.toContain('ARIA Roles:');
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('covers default attribute fallbacks and short class rendering', async () => {
    const page = createPage({
      locators: {
        input: [createElement({ attributes: { class: 'short-class' } })],
        button: [createElement({ text: null })],
        a: [createElement({ text: null })],
        'h1, h2, h3': [createElement({ tagName: 'h2', text: null })],
        '[role]': [createElement()],
      },
    });

    await logPageTelemetry(page as never, 'Fallbacks');

    const output = logSpy.mock.calls.flat().join('\n');
    expect(output).toContain('Fallbacks Telemetry');
    expect(output).toContain('[0] type="text" name="" id=""');
    expect(output).toContain('class="short-class"');
    expect(output).toContain('Buttons (1):');
    expect(output).toContain('[0] type="" text=""');
    expect(output).toContain('Links (showing first 10 of 1):');
    expect(output).not.toContain('"<undefined>"');
    expect(output).toContain('Headings (1):');
    expect(output).toContain('ARIA Roles: (1)');
  });

  it('warns and finishes cleanly when telemetry extraction throws at the top level', async () => {
    const page = createPage({
      titleError: new Error('page crashed'),
    });

    await logPageTelemetry(page as never, 'Broken');

    const output = logSpy.mock.calls.flat().join('\n');
    expect(output).toContain('Broken Telemetry');
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('Telemetry extraction error: Error: page crashed'));
  });

  it('saves page HTML snapshots and creates the snapshot directory on demand', async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'telemetry-html-'));
    const previousCwd = process.cwd();
    const page = createPage({
      html: '<html><body>captured</body></html>',
    });

    process.chdir(tempDir);

    try {
      await savePageHTML(page as never, 'sample.html');

      const snapshotPath = path.join(tempDir, 'test-results/html-snapshots/sample.html');
      expect(fs.existsSync(snapshotPath)).toBe(true);
      expect(fs.readFileSync(snapshotPath, 'utf-8')).toBe('<html><body>captured</body></html>');
      expect(logSpy).toHaveBeenCalledWith(
        expect.stringContaining('HTML saved: test-results/html-snapshots/sample.html')
      );
    } finally {
      process.chdir(previousCwd);
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it('logs only document network traffic when network logging is enabled', () => {
    const page = createPage();
    setupNetworkLogging(page as never, 'AUTH');

    expect(page.on).toHaveBeenNthCalledWith(1, 'request', expect.any(Function));
    expect(page.on).toHaveBeenNthCalledWith(2, 'response', expect.any(Function));

    page.__handlers.request({
      resourceType: () => 'document',
      method: () => 'GET',
      url: () => 'https://keycloak-auth.datamancy.net/',
    });
    page.__handlers.request({
      resourceType: () => 'image',
      method: () => 'GET',
      url: () => 'https://keycloak-auth.datamancy.net/logo.svg',
    });
    page.__handlers.response({
      request: () => ({ resourceType: () => 'document' }),
      status: () => 302,
      url: () => 'https://keycloak-auth.datamancy.net/',
    });
    page.__handlers.response({
      request: () => ({ resourceType: () => 'xhr' }),
      status: () => 200,
      url: () => 'https://keycloak-auth.datamancy.net/api',
    });

    const output = logSpy.mock.calls.flat().join('\n');
    expect(output).toContain('AUTH REQUEST: GET https://keycloak-auth.datamancy.net/');
    expect(output).toContain('AUTH RESPONSE: 302 https://keycloak-auth.datamancy.net/');
    expect(output).not.toContain('logo.svg');
    expect(output).not.toContain('/api');
  });

  it('redacts token-like URL parameters from telemetry output', () => {
    const safeUrl = redactUrlForLogs(
      'https://vaultwarden.datamancy.net/identity/connect/authorize?client_id=web&ssoToken=jwt-value&state=opaque&code_challenge=pkce-secret#%2Fcallback?loginToken=fragment-token'
    );

    expect(safeUrl).toContain('client_id=web');
    expect(safeUrl).toContain('ssoToken=REDACTED');
    expect(safeUrl).toContain('state=REDACTED');
    expect(safeUrl).toContain('code_challenge=REDACTED');
    expect(safeUrl).toContain('loginToken=REDACTED');
    expect(safeUrl).not.toContain('jwt-value');
    expect(safeUrl).not.toContain('opaque');
    expect(safeUrl).not.toContain('pkce-secret');
    expect(safeUrl).not.toContain('fragment-token');
  });

  it('redacts token-like URL parameters from bare hash callback fragments', () => {
    const safeUrl = redactUrlForLogs(
      'https://planka.datamancy.net/oidc-callback#state=opaque-state&session_state=opaque-session&code=auth-code&safe=value'
    );

    expect(safeUrl).toContain('safe=value');
    expect(safeUrl).toContain('state=REDACTED');
    expect(safeUrl).toContain('session_state=REDACTED');
    expect(safeUrl).toContain('code=REDACTED');
    expect(safeUrl).not.toContain('opaque-state');
    expect(safeUrl).not.toContain('opaque-session');
    expect(safeUrl).not.toContain('auth-code');
  });

  it('uses the default empty prefix for network logging', () => {
    const page = createPage();
    setupNetworkLogging(page as never);

    page.__handlers.request({
      resourceType: () => 'document',
      method: () => 'GET',
      url: () => 'https://portal.datamancy.net/',
    });
    page.__handlers.response({
      request: () => ({ resourceType: () => 'document' }),
      status: () => 200,
      url: () => 'https://portal.datamancy.net/',
    });

    const output = logSpy.mock.calls.flat().join('\n');
    expect(output).toContain('REQUEST: GET https://portal.datamancy.net/');
    expect(output).toContain('RESPONSE: 200 https://portal.datamancy.net/');
  });
});
