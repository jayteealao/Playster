import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

const ALLOWLISTED_UID = "ALLOWLISTED_UID_FOR_TESTS";
// Must be set before importing src/ — defineString() resolves at module load.
process.env.ALLOWED_UID = ALLOWLISTED_UID;

// Stub the inner sync engine so the callable handler runs without touching
// any real Google APIs. Mocks must be hoisted before importing the function
// under test.
vi.mock("../src/youtube/index.js", () => ({
  syncAll: vi.fn(async () => ({
    regular: { playlistCount: 0, videoCount: 0 },
    watchLater: {
      videoCount: 0,
      pagesThisRun: 0,
      totalItemsKnown: 0,
      complete: true,
      rebuilds: 0,
    },
  })),
  syncPlaylistById: vi.fn(async () => ({ videoCount: 0 })),
  syncWatchLater: vi.fn(async () => ({
    videoCount: 0,
    pagesThisRun: 0,
    totalItemsKnown: 0,
    complete: true,
    rebuilds: 0,
  })),
}));

// Stub the auth secrets so defineSecret().value() doesn't blow up off-network.
vi.mock("../src/auth/oauth.js", () => ({
  oauthSecrets: [],
  getAuthUrl: vi.fn(),
  handleCallback: vi.fn(),
  getAuthenticatedClient: vi.fn(),
}));

import functionsTestFactory from "firebase-functions-test";

let functionsTest: ReturnType<typeof functionsTestFactory>;
let mod: typeof import("../src/index.js");

beforeAll(async () => {
  functionsTest = functionsTestFactory();
  mod = await import("../src/index.js");
});

afterAll(() => {
  functionsTest?.cleanup();
});

describe("syncAllPlaylists callable — auth gating", () => {
  it("rejects unauthenticated calls with unauthenticated", async () => {
    const wrapped = functionsTest.wrap(mod.syncAllPlaylists);
    await expect(
      wrapped({ data: {}, auth: undefined } as never),
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  it("rejects stranger uid with permission-denied", async () => {
    const wrapped = functionsTest.wrap(mod.syncAllPlaylists);
    await expect(
      wrapped({ data: {}, auth: { uid: "stranger", token: {} } } as never),
    ).rejects.toMatchObject({ code: "permission-denied" });
  });

  it("passes through for the allowlisted uid", async () => {
    const wrapped = functionsTest.wrap(mod.syncAllPlaylists);
    const result = await wrapped({
      data: {},
      auth: { uid: ALLOWLISTED_UID, token: {} },
    } as never);
    expect(result).toEqual({
      regular: { playlistCount: 0, videoCount: 0 },
      watchLater: {
        videoCount: 0,
        pagesThisRun: 0,
        totalItemsKnown: 0,
        complete: true,
        rebuilds: 0,
      },
    });
  });
});

describe("syncPlaylist callable — input validation under allowlist", () => {
  it("throws invalid-argument when playlistId is missing", async () => {
    const wrapped = functionsTest.wrap(mod.syncPlaylist);
    await expect(
      wrapped({
        data: {},
        auth: { uid: ALLOWLISTED_UID, token: {} },
      } as never),
    ).rejects.toMatchObject({ code: "invalid-argument" });
  });
});
