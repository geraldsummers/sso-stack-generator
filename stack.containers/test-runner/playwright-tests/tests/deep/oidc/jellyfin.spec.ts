import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import {
  screenshotRoot,
  testUser,
} from '../shared/oidc';
import { KeycloakLoginPage } from '../../../pages/KeycloakLoginPage';
import { defaultIdentityProvider } from '../../../utils/identity-provider';
import { serviceUrl } from '../../../utils/stack-urls';

type JellyfinSession = {
  accessToken: string;
  userId: string;
  serverId?: string;
};

type JellyfinEpisode = {
  id: string;
  name: string;
  seriesName?: string;
  seasonName?: string;
};

type VideoFrameMetrics = {
  ok: boolean;
  currentTime: number;
  duration: number;
  videoWidth: number;
  videoHeight: number;
  readyState: number;
  paused: boolean;
  nonBlackRatio: number;
  colorRatio: number;
  error?: string;
};

const jellyfinBaseUrl = serviceUrl('jellyfin');

async function extractJellyfinSession(page: Page): Promise<JellyfinSession> {
  const session = await page.evaluate(() => {
    type Candidate = {
      AccessToken?: string;
      accessToken?: string;
      UserId?: string;
      userId?: string;
      Id?: string;
      id?: string;
      Servers?: unknown[];
      serverId?: string;
      ServerId?: string;
    };

    const visited = new Set<unknown>();
    const parseMaybeJson = (value: unknown): unknown => {
      if (typeof value !== 'string') {
        return value;
      }
      try {
        return JSON.parse(value);
      } catch {
        return value;
      }
    };

    const visit = (value: unknown): JellyfinSession | null => {
      const parsed = parseMaybeJson(value);
      if (!parsed || visited.has(parsed)) {
        return null;
      }
      if (typeof parsed === 'object') {
        visited.add(parsed);
        const candidate = parsed as Candidate;
        const accessToken = candidate.AccessToken || candidate.accessToken;
        const userId = candidate.UserId || candidate.userId || candidate.Id || candidate.id;
        if (typeof accessToken === 'string' && accessToken.length >= 16 && typeof userId === 'string' && userId) {
          return {
            accessToken,
            userId,
            serverId: typeof candidate.ServerId === 'string'
              ? candidate.ServerId
              : typeof candidate.serverId === 'string'
                ? candidate.serverId
                : undefined,
          };
        }
        if (Array.isArray(candidate.Servers)) {
          for (const server of candidate.Servers) {
            const found = visit(server);
            if (found) {
              return found;
            }
          }
        }
        for (const nested of Object.values(parsed as Record<string, unknown>)) {
          const found = visit(nested);
          if (found) {
            return found;
          }
        }
      }
      return null;
    };

    for (const storage of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index);
        if (!key) {
          continue;
        }
        const found = visit(storage.getItem(key));
        if (found) {
          return found;
        }
      }
    }

    return null;
  });

  expect(session, 'Jellyfin should persist an authenticated API token after OIDC login').toBeTruthy();
  return session!;
}

async function findSimpsonsEpisode(page: Page, session: JellyfinSession): Promise<JellyfinEpisode> {
  const episode = await page.evaluate(async ({ accessToken, userId }) => {
    const response = await fetch(
      `/Users/${encodeURIComponent(userId)}/Items?Recursive=true&IncludeItemTypes=Episode&SearchTerm=Simpsons&Limit=50&Fields=Path,MediaSources,MediaStreams,SeriesName,SeasonName,IndexNumber,ParentIndexNumber`,
      {
        credentials: 'include',
        headers: {
          Accept: 'application/json',
          'X-Emby-Token': accessToken,
        },
      }
    );
    if (!response.ok) {
      return {
        error: `Jellyfin item search failed: ${response.status} ${response.statusText}`,
      };
    }
    const payload = await response.json() as {
      Items?: Array<{
        Id?: string;
        Name?: string;
        SeriesName?: string;
        SeasonName?: string;
        Path?: string;
        IndexNumber?: number;
        ParentIndexNumber?: number;
      }>;
    };
    const items = payload.Items ?? [];
    const preferred = items.find((item) =>
      item.Id
      && /simpsons/i.test([item.SeriesName, item.Name, item.Path].filter(Boolean).join(' '))
      && (item.ParentIndexNumber === 1 || /season 0?1/i.test([item.SeasonName, item.Path].filter(Boolean).join(' ')))
    ) ?? items.find((item) => item.Id && /simpsons/i.test([item.SeriesName, item.Name, item.Path].filter(Boolean).join(' ')));

    if (!preferred?.Id) {
      return {
        error: `No Simpsons episode found in Jellyfin. Candidates: ${items.map((item) => item.Name).slice(0, 10).join(', ')}`,
      };
    }

    return {
      id: preferred.Id,
      name: preferred.Name ?? preferred.Id,
      seriesName: preferred.SeriesName,
      seasonName: preferred.SeasonName,
    };
  }, session);

  expect('error' in episode ? episode.error : '', 'Jellyfin should expose a Simpsons episode to the authenticated user').toBe('');
  return episode as JellyfinEpisode;
}

async function openEpisodeAndStartPlayback(
  page: Page,
  episode: JellyfinEpisode,
  session: JellyfinSession
): Promise<void> {
  const detailsUrl = `${jellyfinBaseUrl}web/#/details?id=${encodeURIComponent(episode.id)}${session.serverId ? `&serverId=${encodeURIComponent(session.serverId)}` : ''}`;
  await page.goto(detailsUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});

  const playButton = page
    .getByRole('button', { name: /^play$/i })
    .or(page.getByRole('button', { name: /play .*|resume/i }))
    .or(page.locator('button[title="Play"], button[aria-label="Play"], .btnPlay, .button-play').first())
    .first();
  await expect(playButton, `Jellyfin details page should expose a Play button for ${episode.name}`).toBeVisible({ timeout: 30000 });
  await playButton.click({ force: true });

  const fromBeginning = page
    .getByRole('button', { name: /from beginning|start over/i })
    .or(page.getByRole('menuitem', { name: /from beginning|start over/i }))
    .first();
  if (await fromBeginning.isVisible().catch(() => false)) {
    await fromBeginning.click({ force: true });
  }
}

async function waitForVisibleVideo(page: Page): Promise<VideoFrameMetrics> {
  await page.locator('video').first().waitFor({ state: 'attached', timeout: 45000 });
  await page.evaluate(() => {
    const video = document.querySelector('video') as HTMLVideoElement | null;
    if (video && video.paused) {
      void video.play().catch(() => {});
    }
  });

  await expect.poll(async () => page.evaluate(() => {
    const video = document.querySelector('video') as HTMLVideoElement | null;
    return video ? video.currentTime : 0;
  }), {
    message: 'Jellyfin video should advance playback time',
    timeout: 60000,
  }).toBeGreaterThan(3);

  let latestMetrics: VideoFrameMetrics = {
    ok: false,
    currentTime: 0,
    duration: 0,
    videoWidth: 0,
    videoHeight: 0,
    readyState: 0,
    paused: true,
    nonBlackRatio: 0,
    colorRatio: 0,
    error: 'playback frame metrics were not sampled',
  };

  await expect.poll(async () => {
    latestMetrics = await page.evaluate((): VideoFrameMetrics => {
    const video = document.querySelector('video') as HTMLVideoElement | null;
    if (!video) {
      return {
        ok: false,
        currentTime: 0,
        duration: 0,
        videoWidth: 0,
        videoHeight: 0,
        readyState: 0,
        paused: true,
        nonBlackRatio: 0,
        colorRatio: 0,
        error: 'no video element',
      };
    }

    const canvas = document.createElement('canvas');
    canvas.width = Math.min(160, Math.max(1, video.videoWidth));
    canvas.height = Math.min(90, Math.max(1, video.videoHeight));
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) {
      return {
        ok: false,
        currentTime: video.currentTime,
        duration: video.duration,
        videoWidth: video.videoWidth,
        videoHeight: video.videoHeight,
        readyState: video.readyState,
        paused: video.paused,
        nonBlackRatio: 0,
        colorRatio: 0,
        error: 'canvas context unavailable',
      };
    }

    try {
      context.drawImage(video, 0, 0, canvas.width, canvas.height);
      const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
      let sampled = 0;
      let nonBlack = 0;
      let colorful = 0;
      for (let index = 0; index < data.length; index += 4 * 8) {
        const red = data[index];
        const green = data[index + 1];
        const blue = data[index + 2];
        const alpha = data[index + 3];
        if (alpha < 16) {
          continue;
        }
        sampled += 1;
        const max = Math.max(red, green, blue);
        const min = Math.min(red, green, blue);
        const luminance = (red + green + blue) / 3;
        if (luminance > 18) {
          nonBlack += 1;
        }
        if (max - min > 14) {
          colorful += 1;
        }
      }

      const nonBlackRatio = sampled ? nonBlack / sampled : 0;
      const colorRatio = sampled ? colorful / sampled : 0;
      return {
        ok: video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA
          && video.videoWidth > 0
          && video.videoHeight > 0
          && video.currentTime > 3
          && nonBlackRatio > 0.12
          && colorRatio > 0.015,
        currentTime: video.currentTime,
        duration: video.duration,
        videoWidth: video.videoWidth,
        videoHeight: video.videoHeight,
        readyState: video.readyState,
        paused: video.paused,
        nonBlackRatio,
        colorRatio,
      };
    } catch (error) {
      return {
        ok: false,
        currentTime: video.currentTime,
        duration: video.duration,
        videoWidth: video.videoWidth,
        videoHeight: video.videoHeight,
        readyState: video.readyState,
        paused: video.paused,
        nonBlackRatio: 0,
        colorRatio: 0,
        error: String(error),
      };
    }
    });
    return latestMetrics.ok;
  }, {
    message: 'Jellyfin playback should render non-black, colorful video frames',
    timeout: 60000,
  }).toBeTruthy();

  return latestMetrics;
}

async function captureVideoFrame(page: Page): Promise<Buffer> {
  const dataUrl = await page.evaluate(() => {
    const video = document.querySelector('video') as HTMLVideoElement | null;
    if (!video || video.videoWidth <= 0 || video.videoHeight <= 0) {
      throw new Error('Cannot capture Jellyfin video frame because no decoded video dimensions are available.');
    }

    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const context = canvas.getContext('2d');
    if (!context) {
      throw new Error('Cannot capture Jellyfin video frame because a canvas context was unavailable.');
    }
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    return canvas.toDataURL('image/jpeg', 0.85);
  });
  const encoded = dataUrl.replace(/^data:image\/jpeg;base64,/, '');
  return Buffer.from(encoded, 'base64');
}

test('Jellyfin - OIDC login streams Simpsons video with visible frames', async ({ page }) => {
  test.setTimeout(240000);

  await page.goto(serviceUrl('jellyfin', '/sso/OID/start/keycloak'), {
    waitUntil: 'domcontentloaded',
    timeout: 30000,
  });

  if (defaultIdentityProvider.isAuthUrl(page.url())) {
    await new KeycloakLoginPage(page).login(testUser.username, testUser.password);
  }

  for (let attempt = 0; attempt < 3 && defaultIdentityProvider.isAuthUrl(page.url()); attempt += 1) {
    const bodyText = (await page.textContent('body').catch(() => '')) || '';
    expect(bodyText, 'Keycloak should not reject the managed Jellyfin playback test user').not.toMatch(/invalid username|invalid password/i);

    const consentButton = page
      .getByRole('button', { name: /accept|authorize|allow|yes/i })
      .or(page.locator('button[type="submit"]').filter({ hasText: /accept|authorize|allow|yes/i }))
      .first();
    if (await consentButton.isVisible().catch(() => false)) {
      await consentButton.click({ force: true });
    } else {
      await page.waitForTimeout(1000);
    }
  }

  await page.waitForURL((url) => !defaultIdentityProvider.isAuthUrl(url.toString()), { timeout: 45000 });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  await expect(page.locator('body')).toContainText(/Jellyfin|Home|Favorites|Latest|Next Up|The Simpsons/i, { timeout: 45000 });

  const session = await extractJellyfinSession(page);
  await page.evaluate((userId) => {
    localStorage.setItem('preferFmp4HlsContainer', 'false');
    localStorage.setItem(`${userId}-preferFmp4HlsContainer`, 'false');
  }, session.userId);
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  const episode = await findSimpsonsEpisode(page, session);
  await openEpisodeAndStartPlayback(page, episode, session);
  const metrics = await waitForVisibleVideo(page);

  fs.mkdirSync(screenshotRoot, { recursive: true });
  fs.writeFileSync(path.join(screenshotRoot, 'jellyfin-video-playback.jpg'), await captureVideoFrame(page));

  expect(metrics.videoWidth, 'Jellyfin video element should expose decoded frame width').toBeGreaterThan(0);
  expect(metrics.videoHeight, 'Jellyfin video element should expose decoded frame height').toBeGreaterThan(0);
});
