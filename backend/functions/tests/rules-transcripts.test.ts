import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import { assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import {
  ALLOWLISTED_UID,
  STRANGER_UID,
  getTestEnv,
  teardownTestEnv,
} from "./setup";

/**
 * Confirms transcripts/ is readable by the allowlisted operator,
 * denied to strangers and unauthenticated callers, and write-locked
 * for all clients (Admin SDK only).
 */
describe("firestore.rules — transcripts (transcript-infra)", () => {
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
      await setDoc(doc(db, "transcripts/seed"), {
        videoId: "seed-video-id",
        status: "pending",
        createdAt: new Date(),
        updatedAt: new Date(),
      });
    });
  });

  it("allowlisted uid can read transcripts/", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(ALLOWLISTED_UID).firestore();
    await assertSucceeds(getDoc(doc(db, "transcripts/seed")));
  });

  it("stranger uid is denied on transcripts/", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(STRANGER_UID).firestore();
    await assertFails(getDoc(doc(db, "transcripts/seed")));
  });

  it("unauthenticated reads are denied on transcripts/", async () => {
    const env = await getTestEnv();
    const db = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, "transcripts/seed")));
  });

  it("allowlisted uid cannot write transcripts/", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(ALLOWLISTED_UID).firestore();
    await assertFails(
      setDoc(doc(db, "transcripts/seed"), {
        videoId: "seed-video-id",
        status: "available",
        createdAt: new Date(),
        updatedAt: new Date(),
      })
    );
  });
});
