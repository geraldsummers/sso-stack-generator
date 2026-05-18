import { expect, test } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';
import { Client } from 'pg';
import {
  AudioSource,
  LocalAudioTrack,
  Room,
  RoomEvent,
  TrackSource,
} from '@livekit/rtc-node';
import { RoomServiceClient } from 'livekit-server-sdk';
import { domain } from '../shared/oidc';

type MatrixOpenIdToken = {
  access_token: string;
  expires_in: number;
  matrix_server_name: string;
  token_type: string;
};

type MatrixRtcJwtResponse = {
  jwt: string;
  url: string;
};

type LiveKitParticipant = {
  identity?: string;
  tracks?: Array<{
    name?: string;
    type?: number;
    source?: number;
  }>;
};

const homeserverUrl = `https://matrix.${domain}`;
const matrixRtcJwtUrl = `https://matrix-rtc.${domain}/livekit/jwt`;
const liveKitApiUrl = process.env.LIVEKIT_INTERNAL_API_URL || 'http://livekit:7880';
const roomAlias = `#voice-lounge:matrix.${domain}`;
const roomBotUserId = `@roombot:matrix.${domain}`;

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is required for the Element Call LiveKit e2e test`);
  }
  return value;
}

function timeout<T>(ms: number, label: string): Promise<T> {
  return new Promise((_, reject) => {
    setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms);
  });
}

async function fetchRoombotAccessToken(): Promise<string> {
  const client = new Client({
    host: process.env.MATRIX_POSTGRES_HOST || 'postgres',
    port: Number(process.env.MATRIX_POSTGRES_PORT || '5432'),
    database: process.env.MATRIX_POSTGRES_DB || 'synapse',
    user: process.env.MATRIX_POSTGRES_USER || 'synapse',
    password: requireEnv('MATRIX_POSTGRES_PASSWORD'),
  });

  await client.connect();
  try {
    const result = await client.query<{ token: string }>(
      'SELECT token FROM access_tokens WHERE user_id = $1 ORDER BY id DESC LIMIT 1',
      [roomBotUserId],
    );
    const token = result.rows[0]?.token || '';
    expect(token, `Synapse access token for ${roomBotUserId}`).toBeTruthy();
    return token;
  } finally {
    await client.end();
  }
}

async function assertMatrixTokenBelongsToRoombot(
  request: APIRequestContext,
  matrixAccessToken: string,
): Promise<void> {
  const response = await request.get(`${homeserverUrl}/_matrix/client/v3/account/whoami`, {
    headers: {
      Authorization: `Bearer ${matrixAccessToken}`,
    },
  });

  const body = await response.text();
  expect(response.ok(), `Matrix roombot token whoami failed: ${response.status()} ${body}`).toBe(true);
  const whoAmI = JSON.parse(body) as { user_id?: string };
  expect(whoAmI.user_id).toBe(roomBotUserId);
}

async function resolveRoomId(request: APIRequestContext): Promise<string> {
  const response = await request.get(
    `${homeserverUrl}/_matrix/client/v3/directory/room/${encodeURIComponent(roomAlias)}`
  );
  const body = await response.text();
  expect(response.ok(), `Matrix room alias lookup failed: ${response.status()} ${body}`).toBe(true);
  const payload = JSON.parse(body) as { room_id?: string };
  expect(payload.room_id, 'voice-lounge room id').toBeTruthy();
  return payload.room_id || '';
}

async function requestOpenIdToken(
  request: APIRequestContext,
  matrixUserId: string,
  matrixAccessToken: string,
): Promise<MatrixOpenIdToken> {
  const response = await request.post(
    `${homeserverUrl}/_matrix/client/v3/user/${encodeURIComponent(matrixUserId)}/openid/request_token`,
    {
      headers: {
        Authorization: `Bearer ${matrixAccessToken}`,
      },
      data: {},
    },
  );

  const body = await response.text();
  expect(response.ok(), `Matrix OpenID token request failed: ${response.status()} ${body}`).toBe(true);
  const openIdToken = JSON.parse(body) as MatrixOpenIdToken;
  expect(openIdToken.access_token).toBeTruthy();
  expect(openIdToken.matrix_server_name).toBe(`matrix.${domain}`);
  return openIdToken;
}

async function requestLiveKitJwt(
  request: APIRequestContext,
  label: string,
  roomId: string,
  matrixUserId: string,
  openIdToken: MatrixOpenIdToken,
): Promise<MatrixRtcJwtResponse> {
  const response = await request.post(`${matrixRtcJwtUrl}/get_token`, {
    data: {
      room_id: roomId,
      slot_id: 'm.call#ROOM',
      openid_token: openIdToken,
      member: {
        id: `${label}-${Date.now()}`,
        claimed_user_id: matrixUserId,
        claimed_device_id: `playwright-matrixrtc-${label}`,
      },
    },
  });

  const body = await response.text();
  expect(response.ok(), `MatrixRTC JWT exchange failed for ${label}: ${response.status()} ${body}`).toBe(true);
  const token = JSON.parse(body) as MatrixRtcJwtResponse;
  expect(token.jwt).toBeTruthy();
  expect(token.url).toBe(`wss://matrix-rtc.${domain}/livekit/sfu`);
  return token;
}

async function waitForParticipants(
  liveKitApi: RoomServiceClient,
  roomName: string,
  predicate: (participants: LiveKitParticipant[]) => boolean,
  label: string,
): Promise<LiveKitParticipant[]> {
  let lastParticipants: LiveKitParticipant[] = [];
  for (let attempt = 0; attempt < 40; attempt += 1) {
    lastParticipants = await liveKitApi
      .listParticipants(roomName)
      .then((participants) => participants as LiveKitParticipant[])
      .catch(() => []);

    if (predicate(lastParticipants)) {
      return lastParticipants;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }

  throw new Error(
    `${label} not observed in LiveKit room ${roomName}; last=${JSON.stringify(
      lastParticipants.map((participant) => ({
        identity: participant.identity,
        tracks: participant.tracks?.length || 0,
      })),
    )}`
  );
}

async function disconnectRoom(room: Room): Promise<void> {
  await Promise.race([
    room.disconnect(),
    new Promise<void>((resolve) => setTimeout(resolve, 3000)),
  ]);
}

async function waitForObservedEvent(
  observedEvents: string[],
  predicate: (event: string) => boolean,
  label: string,
): Promise<void> {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (observedEvents.some(predicate)) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  throw new Error(`${label} not observed; events=${observedEvents.join(', ')}`);
}

test('Element Call MatrixRTC uses internal LiveKit for two-party audio media', async ({ request }) => {
  test.setTimeout(90000);

  const matrixAccessToken = await fetchRoombotAccessToken();
  await assertMatrixTokenBelongsToRoombot(request, matrixAccessToken);
  const roomId = await resolveRoomId(request);
  const openIdToken = await requestOpenIdToken(request, roomBotUserId, matrixAccessToken);
  const [aliceToken, bobToken] = await Promise.all([
    requestLiveKitJwt(request, 'alice', roomId, roomBotUserId, openIdToken),
    requestLiveKitJwt(request, 'bob', roomId, roomBotUserId, openIdToken),
  ]);

  const alice = new Room();
  const bob = new Room();
  const observedEvents: string[] = [];

  for (const [name, room] of [
    ['alice', alice],
    ['bob', bob],
  ] as const) {
    room.on(RoomEvent.Connected, () => observedEvents.push(`${name}:connected`));
    room.on(RoomEvent.ParticipantConnected, (participant) => {
      observedEvents.push(`${name}:participant:${participant.identity}`);
    });
    room.on(RoomEvent.TrackPublished, (publication, participant) => {
      observedEvents.push(`${name}:track-published:${participant.identity}:${publication.kind}`);
    });
    room.on(RoomEvent.TrackSubscribed, (track, publication, participant) => {
      observedEvents.push(`${name}:track-subscribed:${participant.identity}:${track.kind}`);
    });
  }

  try {
    await Promise.race([alice.connect(aliceToken.url, aliceToken.jwt), timeout(20000, 'alice connect')]);
    await Promise.race([bob.connect(bobToken.url, bobToken.jwt), timeout(20000, 'bob connect')]);

    const liveKitApi = new RoomServiceClient(
      liveKitApiUrl,
      requireEnv('LIVEKIT_API_KEY'),
      requireEnv('LIVEKIT_API_SECRET'),
    );
    const liveKitRoomName = alice.name;
    expect(liveKitRoomName, 'connected LiveKit room name').toBeTruthy();
    await waitForParticipants(liveKitApi, liveKitRoomName || '', (participants) => participants.length >= 2, 'two participants');

    const audioSource = new AudioSource(48000, 1);
    const audioTrack = LocalAudioTrack.createAudioTrack('matrixrtc-playwright-audio', audioSource);
    expect(alice.localParticipant, 'connected LiveKit local participant').toBeTruthy();
    const publishOptions = {
      source: TrackSource.SOURCE_MICROPHONE,
    } as Parameters<NonNullable<typeof alice.localParticipant>['publishTrack']>[1];
    await alice.localParticipant?.publishTrack(audioTrack, publishOptions);

    const participants = await waitForParticipants(
      liveKitApi,
      liveKitRoomName || '',
      (items) => items.some((participant) => (participant.tracks || []).length > 0),
      'published audio track',
    );

    expect(
      participants.some((participant) =>
        (participant.tracks || []).some((track) => track.name === 'matrixrtc-playwright-audio')
      ),
      `LiveKit participants did not expose the published audio track; events=${observedEvents.join(', ')}`
    ).toBe(true);
    await waitForObservedEvent(
      observedEvents,
      (event) => event.includes(':track-subscribed:'),
      'LiveKit remote track subscription',
    );
  } finally {
    await Promise.allSettled([disconnectRoom(alice), disconnectRoom(bob)]);
  }
});
