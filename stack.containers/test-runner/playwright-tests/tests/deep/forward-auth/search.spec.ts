import { test, expect } from '@playwright/test';
import {
  authenticatedSessionState,
  testForwardAuthService,
} from '../shared/forward-auth';
import { serviceUrl } from '../../../utils/stack-urls';

test.use({ storageState: authenticatedSessionState });

test('OpenSearch - Access with forward auth', async ({ page }) => {
  test.setTimeout(120_000);
  await testForwardAuthService(
    page,
    'OpenSearch',
    serviceUrl('search'),
    /cluster_name|opensearch|You Know, for Search/i,
    {
      waitForSelectorVisible: 'body',
      waitForSelectorTimeoutMs: 20000,
      onAfterLoad: async (page) => {
        const health = await page.evaluate(async () => {
          const response = await fetch('/_cluster/health');
          return { ok: response.ok, status: response.status, body: await response.json().catch(() => ({})) };
        });
        expect(health.ok, `OpenSearch health returned ${health.status}`).toBeTruthy();
        expect(String((health.body as { status?: unknown }).status || '')).toMatch(/green|yellow|red/i);

        const search = await page.evaluate(async () => {
          const response = await fetch('/knowledge/_search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ size: 1, query: { match_all: {} } }),
          });
          return { ok: response.ok, status: response.status, body: await response.json().catch(() => ({})) };
        });
        expect(search.ok, `OpenSearch _search returned ${search.status}`).toBeTruthy();
      },
    },
  );
});
