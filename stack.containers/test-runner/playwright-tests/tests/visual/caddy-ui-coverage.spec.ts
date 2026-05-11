import { expect, test } from '@playwright/test';
import { authenticatedSessionState, testForwardAuthService } from '../deep/shared/forward-auth';
import { browserRouteCatalog, visualRoutes } from '../../utils/route-catalog';
import { rootUrl } from '../../utils/stack-urls';

const explicitVisualCoverageHosts = [
  'apex',
  'element',
  'homeassistant',
  'jupyterhub',
  'kopia',
  'mastodon',
  'ntfy',
  'planka',
  'prometheus',
  'qbittorrent',
  'search',
  'seafile',
  'vaultwarden',
] as const;

const excludedVisualCoverageHosts = new Set(['www']);
const genericVisualHosts = new Set(visualRoutes.map((route) => route.host));
const allCoveredVisualHosts = new Set<string>([
  ...genericVisualHosts,
  ...explicitVisualCoverageHosts,
]);

const browserUiHosts = browserRouteCatalog
  .filter((route) => route.kind !== 'non_ui' && route.kind !== 'orphaned')
  .map((route) => route.host)
  .filter((host) => !excludedVisualCoverageHosts.has(host));

test.describe('Caddy UI Visual Coverage', () => {
  test('every browser UI route exposed by Caddy has screenshot coverage', () => {
    const uncoveredHosts = browserUiHosts.filter((host) => !allCoveredVisualHosts.has(host));
    const explicitlyCoveredButMissingHosts = explicitVisualCoverageHosts.filter(
      (host) => !browserUiHosts.includes(host)
    );

    expect(
      uncoveredHosts,
      `Browser UI hosts missing screenshot coverage: ${uncoveredHosts.join(', ')}`
    ).toEqual([]);
    expect(
      explicitlyCoveredButMissingHosts,
      `Explicit visual coverage hosts missing from the browser route catalog: ${explicitlyCoveredButMissingHosts.join(', ')}`
    ).toEqual([]);
  });

  test.describe('Explicit Visual Snapshots', () => {
    test.use({ storageState: authenticatedSessionState });

    test('Apex homepage snapshot', async ({ page }) => {
      await testForwardAuthService(
        page,
        'Apex Homepage',
        rootUrl('/'),
        /(Homepage|AI & Development|Collaboration|System)/i,
        {
          screenshotSuffix: 'authenticated',
          screenshotFullPage: true,
        }
      );
    });
  });
});
