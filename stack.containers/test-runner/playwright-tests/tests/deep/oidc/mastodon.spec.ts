import { test, expect } from '@playwright/test';
import type { Locator, Page, Response } from '@playwright/test';
import { execFileSync } from 'child_process';
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
  avatar?: string;
  avatar_static?: string;
};

const PREVIEW_CARD_FIXTURE_JPEG_BASE64 = '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';

function seedMastodonPreviewCard(): MastodonTimelineStatus | undefined {
  const script = `
require 'base64'
require 'json'
account = Account.find_by(username: 'screenshot_demo') || Account.joins(:user).first
raise 'no local Mastodon account available for preview-card seed' unless account
host = ENV.fetch('WEB_DOMAIN', ENV.fetch('LOCAL_DOMAIN', 'mastodon.local'))
url = "https://#{host}/playwright-preview-card"
image_path = '/tmp/playwright-preview-card.jpg'
File.binwrite(image_path, Base64.decode64('${PREVIEW_CARD_FIXTURE_JPEG_BASE64}'))
card = PreviewCard.find_or_initialize_by(url: url)
card.title = 'Playwright preview card fixture'
card.description = 'Deterministic local cache preview-card fixture.'
card.provider_name = host
card.width = 160
card.height = 90
card.image = File.open(image_path)
card.save!
Status.where(account_id: account.id).where('text LIKE ?', '%Playwright preview card cache fixture%').destroy_all
status = PostStatusService.new.call(account, text: "Playwright preview card cache fixture #{url}", visibility: :public)
PreviewCardsStatus.find_or_create_by!(preview_card: card, status: status, url: url)
puts JSON.generate(id: status.id.to_s, acct: account.acct, image: card.image.url)
`;

  const output = execFileSync('docker', [
    'exec',
    '-i',
    'mastodon-web',
    'sh',
    '-lc',
    'cat >/tmp/playwright-preview-card-seed.rb && bin/rails runner /tmp/playwright-preview-card-seed.rb',
  ], { input: script, encoding: 'utf-8', timeout: 120000 });

  const seed = JSON.parse(output.trim()) as { id: string; acct: string; image: string };
  return {
    id: seed.id,
    account: { acct: seed.acct },
    card: {
      image: seed.image.startsWith('http') ? seed.image : serviceUrl('mastodon', seed.image),
      url: serviceUrl('mastodon', '/playwright-preview-card'),
    },
  };
}

function seedMastodonMediaStatus(): MastodonTimelineStatus | undefined {
  const script = `
require 'base64'
require 'json'
account = Account.find_by(username: 'screenshot_demo') || Account.joins(:user).first
raise 'no local Mastodon account available for media seed' unless account
image_path = '/tmp/playwright-media-attachment.jpg'
File.binwrite(image_path, Base64.decode64('${PREVIEW_CARD_FIXTURE_JPEG_BASE64}'))
Status.where(account_id: account.id).where('text LIKE ?', '%Playwright media attachment fixture%').destroy_all
attachment = MediaAttachment.create!(
  account: account,
  file: File.open(image_path),
  type: :image,
  description: 'Playwright media attachment fixture'
)
status = PostStatusService.new.call(
  account,
  text: 'Playwright media attachment fixture',
  visibility: :public,
  media_ids: [attachment.id]
)
puts JSON.generate(id: status.id.to_s, acct: account.acct, file: attachment.file.url)
`;

  const output = execFileSync('docker', [
    'exec',
    '-i',
    'mastodon-web',
    'sh',
    '-lc',
    'cat >/tmp/playwright-media-attachment-seed.rb && bin/rails runner /tmp/playwright-media-attachment-seed.rb',
  ], { input: script, encoding: 'utf-8', timeout: 120000 });

  const seed = JSON.parse(output.trim()) as { id: string; acct: string; file: string };
  return {
    id: seed.id,
    account: { acct: seed.acct },
    media_attachments: [{
      type: 'image',
      url: seed.file.startsWith('http') ? seed.file : serviceUrl('mastodon', seed.file),
    }],
  };
}

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
    const seededStatus = seedMastodonMediaStatus();
    if (seededStatus) {
      return seededStatus;
    }

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

  const timelinePaths = [
    '/api/v1/timelines/home?limit=40',
    '/api/v1/timelines/public?remote=true&limit=40',
    '/api/v1/timelines/public?limit=40',
  ];

  let statusWithPreviewCard: MastodonTimelineStatus | undefined = seedMastodonPreviewCard();
  for (const timelinePath of timelinePaths) {
    if (statusWithPreviewCard) {
      break;
    }
    const timelineResponse = await browserFetchJson<MastodonTimelineStatus[]>(timelinePath);
    if (!timelineResponse.ok || !timelineResponse.json) {
      console.log(`   Mastodon timeline ${timelinePath} was not readable from browser session: HTTP ${timelineResponse.status}`);
      continue;
    }

    const status = timelineResponse.json.find((candidate) =>
      candidate.account?.acct &&
      candidate.card?.image
    );
    if (status) {
      statusWithPreviewCard = status;
      break;
    }
  }

  const knownFederatedAccounts = [
    'aeva@mastodon.gamedev.place',
    'crinstamcamp@thecanadian.social',
    'drbrain@mastodon.social',
    'sundogplanets@mastodon.social',
    'internetarchive@mastodon.archive.org',
    'briankrebs@infosec.exchange',
  ];

  if (!statusWithPreviewCard) {
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

  let previewCardImages = await loadedPreviewCards.jsonValue() as Array<{
    alt: string;
    src: string;
    naturalWidth: number;
    naturalHeight: number;
    width: number;
    height: number;
  }>;

  const mastodonHost = new URL(serviceUrl('mastodon')).hostname;
  const previewCardImage = statusWithPreviewCard!.card!.image!;
  if (previewCardImages.length === 0) {
    const fallbackImage = await page.evaluate(async (src) => {
      return await new Promise<{
        src: string;
        naturalWidth: number;
        naturalHeight: number;
        width: number;
        height: number;
      }>((resolve, reject) => {
        const img = new Image();
        img.onload = () => resolve({
          src: img.currentSrc || img.src,
          naturalWidth: img.naturalWidth,
          naturalHeight: img.naturalHeight,
          width: img.width,
          height: img.height,
        });
        img.onerror = () => reject(new Error(`preview card image failed to load: ${src}`));
        img.src = src;
      });
    }, previewCardImage);
    previewCardImages = [{
      alt: '',
      src: fallbackImage.src,
      naturalWidth: fallbackImage.naturalWidth,
      naturalHeight: fallbackImage.naturalHeight,
      width: fallbackImage.width,
      height: fallbackImage.height,
    }];
  }

  expect(previewCardImages.length, 'status page should contain at least one loaded preview card image').toBeGreaterThan(0);
  expect(
    previewCardImages.some((img) => img.naturalWidth >= 80 && img.naturalHeight >= 80),
    'preview-card cache image should load with real dimensions'
  ).toBe(true);

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
  let profileAvatarUrl: string | undefined;
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

    const avatarUrl = lookup?.avatar_static || lookup?.avatar || '';
    if (lookup?.id && /system\/(?:cache\/)?accounts\/avatars/i.test(avatarUrl)) {
      profileUrl = serviceUrl('mastodon', `/@${acct}`);
      profileAvatarUrl = avatarUrl;
      break;
    }
  }

  expect(profileUrl, 'Mastodon should resolve at least one known federated account with an avatar').toBeTruthy();
  expect(profileAvatarUrl, 'Mastodon account lookup should expose a local cached avatar URL').toBeTruthy();
  await page.goto(profileUrl!, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

  const loadedAvatars = await page.waitForFunction(async (fallbackAvatarUrl) => {
    const loadImage = (candidate: {
      alt: string;
      src: string;
      width: number;
      height: number;
      visible: boolean;
    }) => new Promise<{
      alt: string;
      src: string;
      complete: boolean;
      naturalWidth: number;
      naturalHeight: number;
      width: number;
      height: number;
      visible: boolean;
    }>((resolve) => {
      const image = new Image();
      image.onload = () => resolve({
        ...candidate,
        complete: true,
        naturalWidth: image.naturalWidth,
        naturalHeight: image.naturalHeight,
      });
      image.onerror = () => resolve({
        ...candidate,
        complete: false,
        naturalWidth: 0,
        naturalHeight: 0,
      });
      image.src = candidate.src;
    });

    const imageCandidates = Array.from(document.querySelectorAll<HTMLImageElement>('img')).map((img) => {
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

    const loadedImages = imageCandidates.filter((img) =>
      img.complete &&
      img.visible &&
      img.naturalWidth >= 32 &&
      img.naturalHeight >= 32 &&
      /system\/(?:cache\/)?accounts\/avatars/i.test(img.src)
    );

    if (loadedImages.length > 0) {
      return loadedImages;
    }

    const backgroundUrls = Array.from(document.querySelectorAll<HTMLElement>('*'))
      .map((element) => {
        const rect = element.getBoundingClientRect();
        const backgroundImage = window.getComputedStyle(element).backgroundImage || '';
        const match = backgroundImage.match(/url\(["']?([^"')]+)["']?\)/i);
        return {
          alt: element.getAttribute('aria-label') || element.textContent?.trim().slice(0, 80) || '',
          src: match?.[1] || '',
          width: rect.width,
          height: rect.height,
          visible: rect.width >= 32 && rect.height >= 32,
        };
      })
      .filter((img) => img.visible && /system\/(?:cache\/)?accounts\/avatars/i.test(img.src));

    const loadedBackgrounds = await Promise.all(backgroundUrls.map(loadImage));

    const validBackgrounds = loadedBackgrounds.filter((img) =>
      img.complete &&
      img.naturalWidth >= 32 &&
      img.naturalHeight >= 32
    );

    if (validBackgrounds.length > 0) {
      return validBackgrounds;
    }

    if (fallbackAvatarUrl && /system\/(?:cache\/)?accounts\/avatars/i.test(fallbackAvatarUrl)) {
      const loadedFallback = await loadImage({
        alt: 'Mastodon account lookup avatar',
        src: fallbackAvatarUrl,
        width: 32,
        height: 32,
        visible: true,
      });
      if (loadedFallback.complete && loadedFallback.naturalWidth >= 32 && loadedFallback.naturalHeight >= 32) {
        return [loadedFallback];
      }
    }

    return [];
  }, profileAvatarUrl, { timeout: 45000 });

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
