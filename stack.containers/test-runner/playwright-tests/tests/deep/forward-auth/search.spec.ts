import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import {
  authenticatedSessionState,
  domain,
  screenshotRoot,
  seafileOnlyOfficeFixturePath,
  testForwardAuthService,
  waitForGrafanaShell,
  waitForHomeAssistantShell,
} from '../shared/forward-auth';
import { serviceUrl } from '../../../utils/stack-urls';
import { logPageTelemetry, setupNetworkLogging } from '../../../utils/telemetry';

test.use({ storageState: authenticatedSessionState });

  test('Search - Access with forward auth', async ({ page }) => {
    test.setTimeout(180_000);
    await testForwardAuthService(
      page,
      'Search',
      serviceUrl('search'),
      /webservices Search|Search Knowledge Base|Hybrid|Semantic|Keyword/i,
      {
        waitForSelectorVisible: 'text=/Search Knowledge Base|Hybrid|Semantic|Keyword/i',
        waitForSelectorTimeoutMs: 20000,
        onAfterLoad: async (page) => {
          const searchInput = page.locator('#searchInput');
          const candidateQueries = ['linux', 'australia', 'security', 'wikipedia'];
          await page.getByRole('button', { name: /^hybrid$/i }).click().catch(() => {});

          const payload = await page.evaluate(async ({ requestedQueries }) => {
            const displayResults = (window as Window & { displayResults?: (results: unknown[]) => void }).displayResults;
            const stackBaseDomain = window.location.hostname.split('.').slice(-2).join('.');
            let successfulQueries = 0;

            for (const requestedQuery of requestedQueries as string[]) {
              const response = await fetch('/search', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  query: requestedQuery,
                  mode: 'hybrid',
                  limit: 10,
                }),
              });

              const data = await response.json().catch(() => ({}));
              if (!response.ok) {
                throw new Error(`Search request failed with status ${response.status}`);
              }
              successfulQueries += 1;

              const results = Array.isArray((data as { results?: unknown[] }).results)
                ? ((data as { results: Array<Record<string, unknown>> }).results)
                : [];
              const stackHostedResults = results.filter((result) => {
                const url = String(result.url ?? '');
                return new RegExp(`https?:\\/\\/[^/]*\\.${stackBaseDomain.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&')}`, 'i').test(url);
              });
              const renderedResults = stackHostedResults.length > 0
                ? stackHostedResults.slice(0, 5)
                : results.slice(0, 5);

              if (results.length > 0) {
                if (typeof displayResults === 'function') {
                  displayResults(renderedResults);
                }
                return {
                  query: requestedQuery,
                  results,
                  stackHostedResults,
                  renderedResultsCount: renderedResults.length,
                  successfulQueries,
                };
              }
            }

            if (typeof displayResults === 'function') {
              displayResults([]);
            }
            return {
              query: '',
              results: [],
              stackHostedResults: [],
              renderedResultsCount: 0,
              successfulQueries,
            };
          }, { requestedQueries: candidateQueries });

          expect(Array.isArray(payload.results)).toBeTruthy();
          expect(Array.isArray(payload.stackHostedResults)).toBeTruthy();
          expect(payload.successfulQueries ?? 0).toBeGreaterThan(0);

          const query = payload.query || candidateQueries[0];
          await searchInput.fill(query);
          await expect(searchInput).toHaveValue(query);

          if ((payload.renderedResultsCount ?? 0) > 0) {
            await expect(page.locator('.result').first()).toBeVisible({ timeout: 30000 });
            await expect(page.locator('.result-title, .result-meta, .result-snippet').first()).toBeVisible({ timeout: 30000 });
          }
        },
      }
    );
  });
