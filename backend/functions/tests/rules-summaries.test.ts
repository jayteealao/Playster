import {
  afterAll,
  beforeAll,
  beforeEach,
  describe,
  it,
} from "vitest";
import {
  assertFails,
  assertSucceeds,
} from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import {
  ALLOWLISTED_UID,
  STRANGER_UID,
  getTestEnv,
  teardownTestEnv,
} from "./setup";

/**
 * Slice 3 extension of slice 1's rules suite. Confirms summaries/ and quota/
 * are readable by the allowlisted operator, denied to strangers, and write-
 * locked for all clients (Admin SDK only).
 */
describe("firestore.rules — summaries + quota (slice 3)", () => {
  beforeAll(async () => {
    await getTestEnv();
  });

  afterAll(async () => {
    await teardownTestEnv();
  });

  beforeEach(async () => {
    const env = await getTestEnv();
    await env.clearFirestore();
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, "summaries/seed"), { status: "queued" });
      await setDoc(doc(db, "quota/openrouter"), {
        date: "2026-05-19",
        requestCount: 1,
      });
    });
  });

  it("allowlisted uid can read summaries/ and quota/", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(ALLOWLISTED_UID).firestore();
    await assertSucceeds(getDoc(doc(db, "summaries/seed")));
    await assertSucceeds(getDoc(doc(db, "quota/openrouter")));
  });

  it("stranger uid is denied on summaries/ and quota/", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(STRANGER_UID).firestore();
    await assertFails(getDoc(doc(db, "summaries/seed")));
    await assertFails(getDoc(doc(db, "quota/openrouter")));
  });

  it("unauthenticated reads are denied on summaries/ and quota/", async () => {
    const env = await getTestEnv();
    const db = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, "summaries/seed")));
    await assertFails(getDoc(doc(db, "quota/openrouter")));
  });

  it("allowlisted uid cannot write summaries/ or quota/", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(ALLOWLISTED_UID).firestore();
    await assertFails(setDoc(doc(db, "summaries/seed"), { status: "x" }));
    await assertFails(setDoc(doc(db, "quota/openrouter"), { x: 1 }));
  });
});
