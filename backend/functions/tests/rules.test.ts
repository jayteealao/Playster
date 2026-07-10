import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import { assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import {
  ALLOWLISTED_UID,
  STRANGER_UID,
  getTestEnv,
  teardownTestEnv,
} from "./setup";

describe("firestore.rules — allowlisted reads, no client writes", () => {
  beforeAll(async () => {
    await getTestEnv();
  });

  afterAll(async () => {
    await teardownTestEnv();
  });

  beforeEach(async () => {
    const env = await getTestEnv();
    await env.clearFirestore();
    // Seed each collection with one doc via the rules-bypassing admin context.
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, "playlists/seed"), { title: "seed" });
      await setDoc(doc(db, "videos/seed"), { videoId: "seed" });
      await setDoc(doc(db, "sync_state/watch_later"), { complete: false });
      await setDoc(doc(db, "tokens/youtube"), { refresh_token: "x" });
      await setDoc(doc(db, "summaries/seed"), { status: "queued" });
    });
  });

  it("denies unauthenticated reads on every collection", async () => {
    const env = await getTestEnv();
    const db = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, "playlists/seed")));
    await assertFails(getDoc(doc(db, "videos/seed")));
    await assertFails(getDoc(doc(db, "sync_state/watch_later")));
    await assertFails(getDoc(doc(db, "tokens/youtube")));
    await assertFails(getDoc(doc(db, "summaries/seed")));
  });

  it("denies stranger uid reads on every collection", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(STRANGER_UID).firestore();
    await assertFails(getDoc(doc(db, "playlists/seed")));
    await assertFails(getDoc(doc(db, "videos/seed")));
    await assertFails(getDoc(doc(db, "sync_state/watch_later")));
    await assertFails(getDoc(doc(db, "tokens/youtube")));
    await assertFails(getDoc(doc(db, "summaries/seed")));
  });

  it("allows allowlisted uid reads on playlists/videos/sync_state", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(ALLOWLISTED_UID).firestore();
    await assertSucceeds(getDoc(doc(db, "playlists/seed")));
    await assertSucceeds(getDoc(doc(db, "videos/seed")));
    await assertSucceeds(getDoc(doc(db, "sync_state/watch_later")));
  });

  it("denies allowlisted uid reads on tokens (Admin SDK only)", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(ALLOWLISTED_UID).firestore();
    await assertFails(getDoc(doc(db, "tokens/youtube")));
  });

  it("denies all client writes (Admin SDK only)", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(ALLOWLISTED_UID).firestore();
    await assertFails(setDoc(doc(db, "playlists/new"), { title: "x" }));
    await assertFails(setDoc(doc(db, "videos/new"), { videoId: "x" }));
    await assertFails(setDoc(doc(db, "sync_state/new"), { complete: true }));
    await assertFails(setDoc(doc(db, "tokens/new"), { refresh_token: "x" }));
  });

  it("denies any read on collections not explicitly allowed", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(ALLOWLISTED_UID).firestore();
    // Sanity: an unmodeled collection still gets the catch-all deny even for
    // the allowlisted operator. Slice 3 promoted `summaries/` and `quota/`
    // out of the catch-all; this test uses a path that remains uncovered.
    await assertFails(getDoc(doc(db, "secrets/seed")));
  });
});
