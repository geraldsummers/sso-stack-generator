import { expect, test } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { rootUrl } from '../../utils/stack-urls';

type ProfileSummary = {
  profile: string;
  name: string;
  moduleCount: number;
};

const screenshotRoot = process.env.PLAYWRIGHT_SCREENSHOTS_DIR
  || path.resolve(process.cwd(), 'test-results/screenshots/portal-role-dashboards');

function slug(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

test.describe('portal role dashboards', () => {
  test('renders every role dashboard with integrated widgets and exports screenshots', async ({ page, request }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const profiles = await request.get(rootUrl('/api/profiles')).then(async (response) => {
      expect(response.ok()).toBeTruthy();
      return await response.json() as ProfileSummary[];
    });

    expect(profiles.map((profile) => profile.profile)).toEqual([
      'employee',
      'client',
      'team-lead',
      'business-owner',
      'ai-data-analyst',
      'platform-operator-security',
    ]);
    fs.mkdirSync(screenshotRoot, { recursive: true });
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Stack Portal' })).toBeVisible();

    for (const [index, profile] of profiles.entries()) {
      await page.evaluate(async (profileId) => {
        await (window as unknown as { loadDashboard: (id: string) => Promise<void> }).loadDashboard(profileId);
      }, profile.profile);
      await expect(page.getByRole('heading', { name: profile.name })).toBeVisible();
      await expect(page.getByText(`${profile.moduleCount} modules`).first()).toBeVisible();
      await expect(page.locator('[data-visual-kind]')).toHaveCount(3);
      await expect(page.locator('.cockpit-stage')).toBeVisible();
      await expect(page.getByRole('heading', { name: 'Work From Here' })).toBeVisible();
      await expect(page.getByText('Open workflow').first()).toBeVisible();
      await expect(page.getByText('Role-specific live summary with safe metadata only')).toHaveCount(0);

      await page.screenshot({
        path: path.join(screenshotRoot, `${String(index + 1).padStart(2, '0')}-${slug(profile.profile)}.png`),
        fullPage: false,
      });
    }
  });
});
