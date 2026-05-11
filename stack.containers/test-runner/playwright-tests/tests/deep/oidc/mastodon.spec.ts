import { test, expect } from '@playwright/test';
import type { Locator, Page, Response } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { KeycloakLoginPage } from '../../../pages/KeycloakLoginPage';
import { OIDCLoginPage } from '../../../pages/OIDCLoginPage';
import { authArtifactPath } from '../../../utils/auth-artifacts';
import {
  assertBookStackDisplayName,
  assertElementDisplayName,
  assertForgejoDisplayName,
  assertMastodonDisplayName,
  assertPlankaDisplayName,
  assertVaultwardenDisplayName,
  domain,
  escapeRegex,
  fetchBrowserSessionJson,
  guessBaseDomain,
  normalizedString,
  requireExpectedDisplayName,
  requireStackAdminCredentials,
  resolveStackAdminCredentials,
  screenshotRoot,
  testOIDCService,
  testUser,
  waitForGrafanaShell,
} from '../shared/oidc';
import { resolveStackRegex, serviceUrl } from '../../../utils/stack-urls';
import { logPageTelemetry, setupNetworkLogging } from '../../../utils/telemetry';

test.use({ storageState: authArtifactPath('keycloak-session.json') });

type MastodonMediaAttachment = {
  type?: string;
  preview_url?: string;
  url?: string;
};

type MastodonTimelineStatus = {
  id: string;
  url?: string;
  account?: {
    id?: string;
    acct?: string;
  };
  card?: {
    image?: string;
    url?: string;
  };
  media_attachments?: MastodonMediaAttachment[];
};

type MastodonAccount = {
  id: string;
  acct?: string;
};

test('Mastodon - OIDC login flow', async ({ page }) => {
    test.setTimeout(180000);

    const runMastodonLogin = async () => {
      await testOIDCService(
        page,
        'Mastodon',
        serviceUrl('mastodon'),
        /What's on your mind|Compose new post|Publish|Home|Notifications|Profile setup|Save and continue|Display name/i,
        ['Keycloak', 'SSO', 'OpenID', 'OpenID Connect'],
        {
          disallowPatterns: [/Create account|Log in/i, /Invalid state/i],
          disallowUrlPatterns: [/\/(explore|about|public)\b/i],
          loginPath: serviceUrl('mastodon', '/auth/sign_in'),
          loginButtonPatterns: [/log in|sign in|continue with sso|sso|openid/i],
          oidcLinkPatterns: [/sign in with.*(openid|sso)/i, /openid/i, /sso/i],
          authenticatedProbe: async (page) => {
            const bodyText = (await page.textContent('body').catch(() => '')) || '';
            return /Profile setup|Save and continue|What's on your mind|Post|Search or paste URL/i.test(bodyText)
              && !/Log in|Sign in to Mastodon/i.test(bodyText);
          },
          postLogin: async (page) => {
            const escapedUsername = escapeRegex(testUser.username);
            const ownProfileUrl = serviceUrl('mastodon', `/@${encodeURIComponent(testUser.username)}`);
            const ownAccountHeading = page.getByRole('heading', {
              name: new RegExp(`@${escapedUsername}`, 'i'),
            }).first();
            const editProfileLink = page.getByRole('link', { name: /edit profile/i }).first();
            const composeBox = page.getByRole('textbox', { name: /what'?s on your mind\?/i }).first();
            const preferencesLink = page.getByRole('link', { name: /preferences/i }).first();
            const followPeopleHeading = page.getByRole('heading', { name: /follow people to get started/i }).first();

            const hasAuthenticatedUi = async () => {
              const checks = await Promise.all([
                ownAccountHeading.isVisible().catch(() => false),
                editProfileLink.isVisible().catch(() => false),
                composeBox.isVisible().catch(() => false),
                preferencesLink.isVisible().catch(() => false),
                followPeopleHeading.isVisible().catch(() => false),
              ]);
              return checks.some(Boolean);
            };

            const ensureMastodonPage = async () => {
              const currentUrl = page.url();
              const onMastodonDomain = resolveStackRegex(/^https?:\/\/(?:[^/]+\.)?mastodon\.webservices\.net(?:\/|$)/i).test(currentUrl);
              if (!onMastodonDomain) {
                await page.goto(ownProfileUrl, {
                  waitUntil: 'domcontentloaded',
                  timeout: 20000,
                });
              }
              await page.waitForTimeout(1500);
            };

            await ensureMastodonPage();

            const maxAttempts = 6;
            for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
              const authenticatedUi = await hasAuthenticatedUi();

              if (authenticatedUi) {
                await assertMastodonDisplayName(page);
                return;
              }

              if (attempt < maxAttempts) {
                await page.waitForTimeout(5000);
                await ensureMastodonPage();
              }
            }

            throw new Error('Mastodon login did not stabilize on the authenticated account profile.');
          },
        }
      );
    };

    try {
      await runMastodonLogin();
    } catch (error: any) {
      const message = String(error?.message || error);
      const pageContent = await page.content().catch(() => '');
      const currentUrl = page.url();
      const offMastodonDomain = !resolveStackRegex(/^https?:\/\/(?:[^/]+\.)?mastodon\.webservices\.net(?:\/|$)/i).test(currentUrl);
      const isTransient =
        /Invalid state/i.test(message) ||
        /Invalid state/i.test(pageContent) ||
        /could not lookup user subject/i.test(pageContent) ||
        /authorization server encountered an unexpected condition/i.test(pageContent) ||
        /execution context was destroyed/i.test(message) ||
        (/Mastodon profile link is missing/i.test(message) && offMastodonDomain);
      if (!isTransient) {
        throw error;
      }
      console.log('   ⚠️  Mastodon OIDC transient auth error detected, retrying login flow once...');
      await page.context().clearCookies();
      await page.goto(serviceUrl('mastodon', '/auth/sign_in'), { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
      await page.evaluate(() => {
        const storageOwner = globalThis as typeof globalThis & { localStorage?: { clear: () => void }; sessionStorage?: { clear: () => void } };
        storageOwner.localStorage?.clear();
        storageOwner.sessionStorage?.clear();
      }).catch(() => {});
      await runMastodonLogin();
    }
  });

test('Mastodon - federated media images render with real pixels', async ({ page }) => {
  test.setTimeout(180000);

  await testOIDCService(
    page,
    'Mastodon federated media',
    serviceUrl('mastodon'),
    /What's on your mind|Compose new post|Publish|Home|Notifications|Profile setup|Save and continue|Display name/i,
    ['Keycloak', 'SSO', 'OpenID', 'OpenID Connect'],
    {
      disallowPatterns: [/Create account|Log in/i, /Invalid state/i],
      disallowUrlPatterns: [/\/(explore|about|public)\b/i],
      loginPath: serviceUrl('mastodon', '/auth/sign_in'),
      loginButtonPatterns: [/log in|sign in|continue with sso|sso|openid/i],
      oidcLinkPatterns: [/sign in with.*(openid|sso)/i, /openid/i, /sso/i],
      authenticatedProbe: async (page) => {
        const bodyText = (await page.textContent('body').catch(() => '')) || '';
        return /Profile setup|Save and continue|What's on your mind|Post|Search or paste URL/i.test(bodyText)
          && !/Log in|Sign in to Mastodon/i.test(bodyText);
      },
      postLogin: async (page) => {
        const saveAndContinue = page.getByRole('button', { name: /save and continue/i }).first();
        if (await saveAndContinue.isVisible().catch(() => false)) {
          await saveAndContinue.click({ force: true }).catch(() => {});
          await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
        }
      },
      skipScreenshot: true,
    }
  );

  const findImageStatus = async (): Promise<MastodonTimelineStatus | undefined> => {
    const browserFetchJson = async <T>(path: string): Promise<{ ok: boolean; status: number; json?: T }> => {
      return await page.evaluate(async (requestPath) => {
        const response = await fetch(requestPath, {
          credentials: 'same-origin',
          headers: { accept: 'application/json' },
        });
        if (!response.ok) {
          return { ok: false, status: response.status };
        }
        return {
          ok: true,
          status: response.status,
          json: await response.json(),
        };
      }, path);
    };

    const timelineResponse = await browserFetchJson<MastodonTimelineStatus[]>('/api/v1/timelines/home?limit=40');
    if (timelineResponse.ok && timelineResponse.json) {
      const timelineImage = timelineResponse.json.find((status) =>
        status.account?.acct &&
        status.account.acct !== 'arstechnica@mastodon.social' &&
        status.media_attachments?.some((attachment) => attachment.type === 'image')
      );
      if (timelineImage) {
        return timelineImage;
      }
    } else {
      console.log(`   Mastodon home timeline API was not readable from browser session: HTTP ${timelineResponse.status}`);
    }

    const knownFederatedAccounts = [
      'sundogplanets@mastodon.social',
      'internetarchive@mastodon.archive.org',
      'cR0w@infosec.exchange',
    ];

    for (const acct of knownFederatedAccounts) {
      const lookup = await browserFetchJson<MastodonAccount>(`/api/v1/accounts/lookup?acct=${encodeURIComponent(acct)}`);
      if (!lookup.ok || !lookup.json) {
        continue;
      }
      const account = lookup.json;
      if (!account.id) {
        continue;
      }

      const statusesResponse = await browserFetchJson<MastodonTimelineStatus[]>(
        `/api/v1/accounts/${account.id}/statuses?only_media=true&limit=20`
      );
      if (!statusesResponse.ok || !statusesResponse.json) {
        continue;
      }
      const statuses = statusesResponse.json;
      const statusWithImage = statuses.find((status) =>
        status.media_attachments?.some((attachment) => attachment.type === 'image')
      );
      if (statusWithImage) {
        statusWithImage.account = statusWithImage.account || { id: account.id, acct };
        statusWithImage.account.acct = statusWithImage.account.acct || acct;
        return statusWithImage;
      }
    }

    return undefined;
  };

  const statusWithImage = await findImageStatus();
  expect(statusWithImage, 'Mastodon should expose at least one cached federated image status').toBeTruthy();

  const acct = statusWithImage!.account!.acct!;
  const statusUrl = serviceUrl('mastodon', `/@${acct}/${statusWithImage!.id}`);
  await page.goto(statusUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
  await expect(
    page.locator('body'),
    'federated media status page should not render an upstream error page'
  ).not.toContainText(/503 Service Unavailable|Service Unavailable|Bad Gateway|Application error/i, { timeout: 5000 });

  for (const showPattern of [/show media/i, /show sensitive content/i, /^show$/i]) {
    const showButton = page.getByRole('button', { name: showPattern }).first();
    if (await showButton.isVisible().catch(() => false)) {
      await showButton.click({ force: true }).catch(() => {});
      await page.waitForTimeout(1000);
    }
  }

  const loadedMedia = await page.waitForFunction(() => {
    const images = Array.from(document.querySelectorAll<HTMLImageElement>('img')).map((img) => {
      const rect = img.getBoundingClientRect();
      return {
        alt: img.alt || '',
        src: img.currentSrc || img.src || '',
        complete: img.complete,
        naturalWidth: img.naturalWidth,
        naturalHeight: img.naturalHeight,
        width: rect.width,
        height: rect.height,
        visible: rect.width >= 80 && rect.height >= 80,
      };
    });

    return images.filter((img) =>
      img.complete &&
      img.visible &&
      img.naturalWidth >= 80 &&
      img.naturalHeight >= 80 &&
      /media_attachments|system\/cache|system\/media_attachments/i.test(img.src)
    );
  }, undefined, { timeout: 45000 });

  const mediaImages = await loadedMedia.jsonValue() as Array<{
    alt: string;
    src: string;
    naturalWidth: number;
    naturalHeight: number;
    width: number;
    height: number;
  }>;
  expect(mediaImages.length, 'status page should contain at least one loaded media image').toBeGreaterThan(0);

  const mastodonHost = new URL(serviceUrl('mastodon')).hostname;
  const localMediaImages = mediaImages.filter((img) => new URL(img.src).hostname === mastodonHost);
  expect(
    localMediaImages.length,
    'federated media should be served from the local Mastodon cache/origin, not require arbitrary remote image hosts'
  ).toBeGreaterThan(0);

  const screenshotPath = path.join(screenshotRoot, 'mastodon-federated-media-rendered.jpeg');
  fs.mkdirSync(screenshotRoot, { recursive: true });
  await page.screenshot({
    path: screenshotPath,
    type: 'jpeg',
    quality: 90,
    fullPage: false,
  });

  console.log(`   Mastodon federated media status: ${statusUrl}`);
  console.log(`   Loaded media images: ${JSON.stringify(mediaImages, null, 2)}`);
  console.log(`   Screenshot saved: ${screenshotPath}`);
});

test('Mastodon - federated preview card images render with real pixels', async ({ page }) => {
  test.setTimeout(180000);

  await testOIDCService(
    page,
    'Mastodon federated preview card media',
    serviceUrl('mastodon'),
    /What's on your mind|Compose new post|Publish|Home|Notifications|Profile setup|Save and continue|Display name/i,
    ['Keycloak', 'SSO', 'OpenID', 'OpenID Connect'],
    {
      disallowPatterns: [/Create account|Log in/i, /Invalid state/i],
      disallowUrlPatterns: [/\/(explore|about|public)\b/i],
      loginPath: serviceUrl('mastodon', '/auth/sign_in'),
      loginButtonPatterns: [/log in|sign in|continue with sso|sso|openid/i],
      oidcLinkPatterns: [/sign in with.*(openid|sso)/i, /openid/i, /sso/i],
      authenticatedProbe: async (page) => {
        const bodyText = (await page.textContent('body').catch(() => '')) || '';
        return /Profile setup|Save and continue|What's on your mind|Post|Search or paste URL/i.test(bodyText)
          && !/Log in|Sign in to Mastodon/i.test(bodyText);
      },
      postLogin: async (page) => {
        const saveAndContinue = page.getByRole('button', { name: /save and continue/i }).first();
        if (await saveAndContinue.isVisible().catch(() => false)) {
          await saveAndContinue.click({ force: true }).catch(() => {});
          await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
        }
      },
      skipScreenshot: true,
    }
  );

  const browserFetchJson = async <T>(requestPath: string): Promise<{ ok: boolean; status: number; json?: T }> => {
    return await page.evaluate(async (path) => {
      const response = await fetch(path, {
        credentials: 'same-origin',
        headers: { accept: 'application/json' },
      });
      if (!response.ok) {
        return { ok: false, status: response.status };
      }
      return {
        ok: true,
        status: response.status,
        json: await response.json(),
      };
    }, requestPath);
  };

  const knownFederatedAccounts = [
    'sundogplanets@mastodon.social',
    'internetarchive@mastodon.archive.org',
    'briankrebs@infosec.exchange',
  ];

  let statusWithPreviewCard: MastodonTimelineStatus | undefined;
  for (const acct of knownFederatedAccounts) {
    const lookup = await browserFetchJson<MastodonAccount>(`/api/v1/accounts/lookup?acct=${encodeURIComponent(acct)}`);
    if (!lookup.ok || !lookup.json?.id) {
      continue;
    }

    const statusesResponse = await browserFetchJson<MastodonTimelineStatus[]>(
      `/api/v1/accounts/${lookup.json.id}/statuses?limit=40`
    );
    const status = statusesResponse.json?.find((candidate) => candidate.card?.image);
    if (status) {
      status.account = status.account || { id: lookup.json.id, acct };
      status.account.acct = status.account.acct || acct;
      statusWithPreviewCard = status;
      break;
    }
  }

  expect(statusWithPreviewCard, 'Mastodon should expose at least one cached federated preview-card status').toBeTruthy();

  const acct = statusWithPreviewCard!.account!.acct!;
  const statusUrl = serviceUrl('mastodon', `/@${acct}/${statusWithPreviewCard!.id}`);
  await page.goto(statusUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

  const loadedPreviewCards = await page.waitForFunction(() => {
    const images = Array.from(document.querySelectorAll<HTMLImageElement>('img')).map((img) => {
      const rect = img.getBoundingClientRect();
      return {
        alt: img.alt || '',
        src: img.currentSrc || img.src || '',
        complete: img.complete,
        naturalWidth: img.naturalWidth,
        naturalHeight: img.naturalHeight,
        width: rect.width,
        height: rect.height,
        visible: rect.width >= 80 && rect.height >= 80,
      };
    });

    return images.filter((img) =>
      img.complete &&
      img.visible &&
      img.naturalWidth >= 80 &&
      img.naturalHeight >= 80 &&
      /system\/cache\/preview_cards/i.test(img.src)
    );
  }, undefined, { timeout: 45000 });

  const previewCardImages = await loadedPreviewCards.jsonValue() as Array<{
    alt: string;
    src: string;
    naturalWidth: number;
    naturalHeight: number;
    width: number;
    height: number;
  }>;
  expect(previewCardImages.length, 'status page should contain at least one loaded preview card image').toBeGreaterThan(0);

  const mastodonHost = new URL(serviceUrl('mastodon')).hostname;
  const localPreviewCardImages = previewCardImages.filter((img) => new URL(img.src).hostname === mastodonHost);
  expect(
    localPreviewCardImages.length,
    'preview card images should be served from the local Mastodon cache/origin'
  ).toBeGreaterThan(0);

  const screenshotPath = path.join(screenshotRoot, 'mastodon-federated-preview-card-rendered.jpeg');
  fs.mkdirSync(screenshotRoot, { recursive: true });
  await page.screenshot({
    path: screenshotPath,
    type: 'jpeg',
    quality: 90,
    fullPage: false,
  });

  console.log(`   Mastodon federated preview card status: ${statusUrl}`);
  console.log(`   Loaded preview card images: ${JSON.stringify(previewCardImages, null, 2)}`);
  console.log(`   Screenshot saved: ${screenshotPath}`);
});

test('Mastodon - federated profile avatars render with real pixels', async ({ page }) => {
  test.setTimeout(180000);

  await testOIDCService(
    page,
    'Mastodon federated profile avatars',
    serviceUrl('mastodon'),
    /What's on your mind|Compose new post|Publish|Home|Notifications|Profile setup|Save and continue|Display name/i,
    ['Keycloak', 'SSO', 'OpenID', 'OpenID Connect'],
    {
      disallowPatterns: [/Create account|Log in/i, /Invalid state/i],
      disallowUrlPatterns: [/\/(explore|about|public)\b/i],
      loginPath: serviceUrl('mastodon', '/auth/sign_in'),
      loginButtonPatterns: [/log in|sign in|continue with sso|sso|openid/i],
      oidcLinkPatterns: [/sign in with.*(openid|sso)/i, /openid/i, /sso/i],
      authenticatedProbe: async (page) => {
        const bodyText = (await page.textContent('body').catch(() => '')) || '';
        return /Profile setup|Save and continue|What's on your mind|Post|Search or paste URL/i.test(bodyText)
          && !/Log in|Sign in to Mastodon/i.test(bodyText);
      },
      postLogin: async (page) => {
        const saveAndContinue = page.getByRole('button', { name: /save and continue/i }).first();
        if (await saveAndContinue.isVisible().catch(() => false)) {
          await saveAndContinue.click({ force: true }).catch(() => {});
          await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
        }
      },
      skipScreenshot: true,
    }
  );

  const knownFederatedAccounts = [
    'briankrebs@infosec.exchange',
    'arstechnica@mastodon.social',
    'b0rk@jvns.ca',
    'simon@simonwillison.net',
  ];

  let profileUrl: string | undefined;
  for (const acct of knownFederatedAccounts) {
    const lookup = await page.evaluate(async (accountName) => {
      const response = await fetch(`/api/v1/accounts/lookup?acct=${encodeURIComponent(accountName)}`, {
        credentials: 'same-origin',
        headers: { accept: 'application/json' },
      });
      if (!response.ok) {
        return undefined;
      }
      return await response.json() as MastodonAccount;
    }, acct);

    if (lookup?.id) {
      profileUrl = serviceUrl('mastodon', `/@${acct}`);
      break;
    }
  }

  expect(profileUrl, 'Mastodon should resolve at least one known federated account with an avatar').toBeTruthy();
  await page.goto(profileUrl!, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

  const loadedAvatars = await page.waitForFunction(() => {
    const images = Array.from(document.querySelectorAll<HTMLImageElement>('img')).map((img) => {
      const rect = img.getBoundingClientRect();
      return {
        alt: img.alt || '',
        src: img.currentSrc || img.src || '',
        complete: img.complete,
        naturalWidth: img.naturalWidth,
        naturalHeight: img.naturalHeight,
        width: rect.width,
        height: rect.height,
        visible: rect.width >= 32 && rect.height >= 32,
      };
    });

    return images.filter((img) =>
      img.complete &&
      img.visible &&
      img.naturalWidth >= 32 &&
      img.naturalHeight >= 32 &&
      /system\/(?:cache\/)?accounts\/avatars/i.test(img.src)
    );
  }, undefined, { timeout: 45000 });

  const avatarImages = await loadedAvatars.jsonValue() as Array<{
    alt: string;
    src: string;
    naturalWidth: number;
    naturalHeight: number;
    width: number;
    height: number;
  }>;
  expect(avatarImages.length, 'remote profile page should contain at least one loaded avatar image').toBeGreaterThan(0);

  const mastodonHost = new URL(serviceUrl('mastodon')).hostname;
  const localAvatarImages = avatarImages.filter((img) => new URL(img.src).hostname === mastodonHost);
  expect(
    localAvatarImages.length,
    'federated avatars should be served from the local Mastodon cache/origin'
  ).toBeGreaterThan(0);

  const screenshotPath = path.join(screenshotRoot, 'mastodon-federated-avatar-rendered.jpeg');
  fs.mkdirSync(screenshotRoot, { recursive: true });
  await page.screenshot({
    path: screenshotPath,
    type: 'jpeg',
    quality: 90,
    fullPage: false,
  });

  console.log(`   Mastodon federated profile: ${profileUrl}`);
  console.log(`   Loaded avatar images: ${JSON.stringify(avatarImages, null, 2)}`);
  console.log(`   Screenshot saved: ${screenshotPath}`);
});

test('Mastodon - CSP keeps local media origin available', async ({ request }) => {
  const response = await request.get(serviceUrl('mastodon'), {
    maxRedirects: 0,
    timeout: 15000,
  });
  const csp = response.headers()['content-security-policy'] || '';

  expect(csp).toContain('img-src');
  expect(csp).toContain('media-src');
  expect(csp).toContain(`https://mastodon.${domain}/`);
});
