import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";

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
  // F3: pin the functions-test admin app to the project id the harness clears
  // (`demo-playster`) and point it at the emulator BEFORE importing the
  // functions module. Without this, the wrapped callable writes
  // `quota/openrouter` under a different project id, and clearFirestore()
  // (which DELETEs only the demo-playster project) leaves the cap-test's
  // seeded `requestCount: 1000` to leak into the happy-path test.
  process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
  process.env.GCLOUD_PROJECT = "demo-playster";
  functionsTest = functionsTestFactory({ projectId: "demo-playster" });
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

// ---------------------------------------------------------------------------
// R-10: requestVideoSummary callable — auth gating + quota + happy path
// ---------------------------------------------------------------------------
describe("requestVideoSummary callable — auth gating", () => {
  it("rejects unauthenticated calls with unauthenticated", async () => {
    const wrapped = functionsTest.wrap(mod.requestVideoSummary);
    await expect(
      wrapped({ data: { videoId: "v1" }, auth: undefined } as never),
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  it("rejects stranger uid with permission-denied", async () => {
    const wrapped = functionsTest.wrap(mod.requestVideoSummary);
    await expect(
      wrapped({ data: { videoId: "v1" }, auth: { uid: "stranger", token: {} } } as never),
    ).rejects.toMatchObject({ code: "permission-denied" });
  });
});

describe("requestVideoSummary callable — quota + dispatch (emulator-backed)", () => {
  beforeAll(() => {
    initAdminEmulator();
    process.env.SUMMARIZER_URL = "http://summarizer.local";
    process.env.SUMMARIZER_API_KEY = "test-key";
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("allowlisted uid + quota at daily cap → resource-exhausted", async () => {
    const today = new Date().toISOString().slice(0, 10);
    await admin.firestore().doc("quota/openrouter").set({
      date: today,
      requestCount: 1000,
      dailyLimit: 1000,
      perMinuteLimit: 20,
      recentTimestamps: [],
    });
    // Seed a video so the not-found guard passes.
    await admin
      .firestore()
      .doc("playlists/p1/videos/v-quota")
      .set({ videoId: "v-quota", title: "test" });

    const wrapped = functionsTest.wrap(mod.requestVideoSummary);
    await expect(
      wrapped({
        data: { videoId: "v-quota" },
        auth: { uid: ALLOWLISTED_UID, token: {} },
      } as never),
    ).rejects.toMatchObject({ code: "resource-exhausted" });
  });

  it("allowlisted uid + happy path → returns pending shape with summaryId set", async () => {
    // Stub globalThis.fetch so no real HTTP call goes out.
    const savedFetch = globalThis.fetch;
    (globalThis as { fetch: typeof fetch }).fetch = (async () =>
      new Response(JSON.stringify({ id: "job-happy-1" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })) as unknown as typeof fetch;

    try {
      await admin
        .firestore()
        .doc("playlists/p1/videos/v-happy")
        .set({ videoId: "v-happy", title: "test" });

      const wrapped = functionsTest.wrap(mod.requestVideoSummary);
      const result = await wrapped({
        data: { videoId: "v-happy" },
        auth: { uid: ALLOWLISTED_UID, token: {} },
      } as never) as { summaryId: string };

      expect(result).toMatchObject({ summaryId: "v-happy" });
      const doc = await admin.firestore().doc("summaries/v-happy").get();
      expect(doc.data()?.summarizerJobId).toBe("job-happy-1");
    } finally {
      (globalThis as { fetch: typeof fetch }).fetch = savedFetch;
    }
  });
});
