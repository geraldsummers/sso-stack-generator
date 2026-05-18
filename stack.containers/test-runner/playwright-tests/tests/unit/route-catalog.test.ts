import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  browserRouteCatalog,
  findRoute,
  readCaddyHostsInventory,
  routeUrl,
  routeUrlPattern,
  smokeRoutes,
  uncataloguedHosts,
  visualRoutes,
} from '../../utils/route-catalog';

describe('route-catalog', () => {
  const originalHostsFile = process.env.CADDY_HOSTS_FILE;

  afterEach(() => {
    if (originalHostsFile === undefined) {
      delete process.env.CADDY_HOSTS_FILE;
    } else {
      process.env.CADDY_HOSTS_FILE = originalHostsFile;
    }
    jest.restoreAllMocks();
  });

  it('catalogues every Caddy host exactly once', () => {
    const inventory = readCaddyHostsInventory();
    const cataloguedHosts = browserRouteCatalog.map((route) => route.host);

    expect(new Set(cataloguedHosts).size).toBe(cataloguedHosts.length);
    expect(new Set(inventory).size).toBe(inventory.length);
    expect(uncataloguedHosts()).toEqual([]);
    expect([...cataloguedHosts].sort()).toEqual([...inventory].sort());
  });

  it('keeps non-browser and orphaned routes out of smoke and visual suites', () => {
    const invalidSmokeHosts = smokeRoutes.filter((route) => route.kind === 'non_ui' || route.kind === 'orphaned');
    const invalidVisualHosts = visualRoutes.filter((route) => route.kind === 'non_ui' || route.kind === 'orphaned');

    expect(invalidSmokeHosts).toEqual([]);
    expect(invalidVisualHosts).toEqual([]);
  });

  it('classifies MatrixRTC as a non-UI LiveKit and JWT API route', () => {
    const matrixRtc = findRoute('matrix-rtc');

    expect(matrixRtc.kind).toBe('non_ui');
    expect(matrixRtc.anonymous).toMatchObject({
      kind: 'non_ui',
      reason: expect.stringMatching(/MatrixRTC.*LiveKit.*JWT/i),
    });
    expect(matrixRtc.smoke).toBeUndefined();
    expect(matrixRtc.visual).toBeUndefined();
  });

  it('builds route URLs for apex, www, and service hosts', () => {
    expect(routeUrl(findRoute('apex'))).toBe('https://datamancy.net/');
    expect(routeUrl(findRoute('grafana'), '/explore')).toBe('https://grafana.datamancy.net/explore');
    expect(routeUrl(findRoute('www'), '/docs')).toBe('https://www.datamancy.net/docs');
  });

  it('builds route URL patterns for apex and subdomains', () => {
    expect(routeUrlPattern('apex').test('https://datamancy.net/')).toBe(true);
    expect(routeUrlPattern('apex').test('https://www.datamancy.net/login')).toBe(true);
    expect(routeUrlPattern('grafana').test('https://grafana.datamancy.net/dashboards')).toBe(true);
    expect(routeUrlPattern('grafana').test('https://grafana.example.net/dashboards')).toBe(false);
  });

  it('finds known routes and throws for unknown hosts', () => {
    expect(findRoute('grafana')).toMatchObject({ host: 'grafana', label: 'Grafana' });
    expect(() => findRoute('missing-host')).toThrow("No route catalog entry exists for host 'missing-host'.");
  });

  it('uses Forgejo account settings as the stable authenticated smoke surface', () => {
    const forgejo = findRoute('forgejo');

    expect(forgejo.smoke).toMatchObject({
      path: '/user/settings',
      selector: 'input[name="full_name"], input#full_name, a[href="/repo/create"], .dashboard, a[href="/issues"], a[href="/pulls"]',
      disallowUrlMatcher: /\/user\/login\b/i,
    });
    expect(forgejo.smoke?.matcher.test('Account\nFull name\nEmail Address')).toBe(true);
    expect(forgejo.visual).toMatchObject({
      path: '/user/settings',
      disallowUrlMatcher: /\/user\/login\b/i,
    });
  });

  it('requires service-specific evidence for OIDC service login pages', () => {
    const genericLoginContent = 'Keycloak\nSign in\nLogin\nSingle sign-on\nSSO\nOpenID Connect';
    const oidcLoginHosts = ['bookstack', 'element', 'forgejo', 'mastodon', 'planka', 'vaultwarden'];

    for (const host of oidcLoginHosts) {
      const route = findRoute(host);
      expect(route.anonymous.kind).toBe('service_login');
      if (route.anonymous.kind !== 'service_login') {
        continue;
      }

      expect(route.anonymous.matcher.test(genericLoginContent)).toBe(false);
    }
  });

  it('uses stable visual targets for SOGo and Donetick', () => {
    const sogo = findRoute('sogo');
    const donetick = findRoute('donetick');

    expect(sogo.visual?.pathForUser?.({
      username: 'pw-test',
      email: 'pw-test@datamancy.net',
    })).toBeUndefined();
    expect(sogo.visual?.path).toBe('/SOGo/');
    expect(sogo.visual?.selector).toBeUndefined();
    expect(sogo.visual?.matcher.test('Calendar | webservices Mail')).toBe(true);

    expect(donetick.visual?.matcher.test('Loading... This is taking longer than usual.')).toBe(false);
    expect(donetick.visual?.disallowMatcher?.test('Loading... This is taking longer than usual.')).toBe(true);
    expect(donetick.visual?.matcher.test('All Tasks\nArchived\nThings\nLabels')).toBe(true);
  });

  it('reads host inventory from an explicit file and strips comments', () => {
    const hostsFile = path.join(
      fs.mkdtempSync(path.join(os.tmpdir(), 'route-catalog-')),
      'caddy-hosts.txt'
    );
    fs.writeFileSync(hostsFile, '# comment\napex\n\nsearch\n');
    process.env.CADDY_HOSTS_FILE = hostsFile;

    expect(readCaddyHostsInventory()).toEqual(['apex', 'search']);
  });

  it('reports uncatalogued hosts from the configured inventory file', () => {
    const hostsFile = path.join(
      fs.mkdtempSync(path.join(os.tmpdir(), 'route-catalog-extra-')),
      'caddy-hosts.txt'
    );
    fs.writeFileSync(hostsFile, 'apex\ngrafana\nunknown-host\n');
    process.env.CADDY_HOSTS_FILE = hostsFile;

    expect(uncataloguedHosts()).toEqual(['unknown-host']);
  });

  it('throws when no caddy host inventory can be found', () => {
    const previousHostsFile = process.env.CADDY_HOSTS_FILE;

    jest.isolateModules(() => {
      delete process.env.CADDY_HOSTS_FILE;
      jest.doMock('fs', () => ({
        readFileSync: jest.fn(),
        existsSync: jest.fn(() => false),
      }));

      const isolatedModule = require('../../utils/route-catalog') as {
        readCaddyHostsInventory: () => string[];
      };

      expect(() => isolatedModule.readCaddyHostsInventory()).toThrow(
        'Unable to locate caddy-hosts.txt for Playwright route catalog validation.'
      );
    });

    if (previousHostsFile === undefined) {
      delete process.env.CADDY_HOSTS_FILE;
    } else {
      process.env.CADDY_HOSTS_FILE = previousHostsFile;
    }
    jest.dontMock('fs');
    jest.resetModules();
  });
});
