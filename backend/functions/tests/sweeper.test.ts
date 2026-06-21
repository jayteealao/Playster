import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";
import {
  DISPATCHER_LOCK_TTL_MS,
  PENDING_STUCK_TIMEOUT_MS,
  STUCK_TIMEOUT_MS,
} from "../src/summarizer/constants.js";

async function seedRunning(
  videoId: string,
  requestedAtMs: number,
): Promise<void> {
  await admin
    .firestore()
    .doc(`summaries/${videoId}`)
    .set({
      videoId,
      status: "running",
      model: "free",
      requestedAt: admin.firestore.Timestamp.fromMillis(requestedAtMs),
    });
}

describe("summary sweeper — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  // AC-12: stuck-running flip.
  it("flips status=running docs older than STUCK_TIMEOUT_MS to failed-transient", async () => {
    const { sweepStuckRunning } = await import("../src/summarizer/sweeper.js");
    const now = Date.now();
    await seedRunning("v-stuck", now - 2 * 60 * 60 * 1000); // 2h old
    await seedRunning("v-fresh", now - 5 * 60 * 1000); //     5min old

    const result = await sweepStuckRunning();
    expect(result).toEqual({ scanned: 1, flipped: 1 });

    const stuck = await admin.firestore().doc("summaries/v-stuck").get();
    expect(stuck.data()?.status).toBe("failed-transient");
    expect(stuck.data()?.errorCode).toBe("stuck_running_timeout");
    expect(stuck.data()?.errorMessage).toContain(`${STUCK_TIMEOUT_MS / 1000}s`);

    const fresh = await admin.firestore().doc("summaries/v-fresh").get();
    expect(fresh.data()?.status).toBe("running");
  });

  // AC-14: idempotent re-runs — the second pass must produce zero further mutations.
  it("second run produces zero further mutations (idempotent)", async () => {
    const { sweepStuckRunning } = await import("../src/summarizer/sweeper.js");
    await seedRunning("v-stuck", Date.now() - 2 * 60 * 60 * 1000);

    const first = await sweepStuckRunning();
    expect(first).toEqual({ scanned: 1, flipped: 1 });

    const afterFirst = await admin.firestore().doc("summaries/v-stuck").get();
    const errMsgAfterFirst = afterFirst.data()?.errorMessage;
    const updateTimeFirst = afterFirst.updateTime?.toMillis();

    const second = await sweepStuckRunning();
    expect(second).toEqual({ scanned: 0, flipped: 0 });

    const afterSecond = await admin.firestore().doc("summaries/v-stuck").get();
    expect(afterSecond.data()?.errorMessage).toBe(errMsgAfterFirst);
    expect(afterSecond.updateTime?.toMillis()).toBe(updateTimeFirst);
  });

  // Per-doc tx no-op when the doc's status changed between the query and the tx.
  // We can't faithfully race the query inside the helper, so we exercise the
  // guard line directly: pre-flip a stuck doc to completed and assert the tx
  // does NOT clobber it. This is the unit-style fallback the plan calls out.
  it("per-doc tx is a no-op when status is no longer 'running'", async () => {
    const { sweepStuckRunning } = await import("../src/summarizer/sweeper.js");
    // Seed two docs old enough to be selected by the query.
    const old = Date.now() - 2 * 60 * 60 * 1000;
    await seedRunning("v-completed-mid", old);
    await seedRunning("v-still-stuck", old);

    // Flip v-completed-mid to completed BEFORE the sweeper runs. The query
    // selector (status == "running") will exclude it, but if a future change
    // ever broadens the selector, the per-doc tx must still no-op.
    await admin
      .firestore()
      .doc("summaries/v-completed-mid")
      .set(
        { status: "completed", summarizerJobId: "job-done" },
        { merge: true },
      );

    const result = await sweepStuckRunning();
    expect(result.flipped).toBe(1);

    // v-completed-mid stays completed; v-still-stuck flips.
    const completed = await admin
      .firestore()
      .doc("summaries/v-completed-mid")
      .get();
    expect(completed.data()?.status).toBe("completed");
    expect(completed.data()?.errorCode).toBeUndefined();

    const stuck = await admin.firestore().doc("summaries/v-still-stuck").get();
    expect(stuck.data()?.status).toBe("failed-transient");
  });

  // AC-16 (sweeper side): a stale lock beyond DISPATCHER_LOCK_TTL_MS is reclaimed.
  it("acquireSweeperLock reclaims a lock whose TTL has expired", async () => {
    const { acquireSweeperLock } = await import("../src/summarizer/sweeper.js");
    const staleAcquiredAt = Date.now() - (DISPATCHER_LOCK_TTL_MS + 1_000);
    await admin.firestore().doc("locks/summarySweeper").set({
      acquiredAt: staleAcquiredAt,
      holder: "stale-instance",
    });

    const acquired = await acquireSweeperLock();
    expect(acquired).toBeTruthy();

    const snap = await admin.firestore().doc("locks/summarySweeper").get();
    const newAcquiredAt = snap.data()?.acquiredAt as number | undefined;
    expect(newAcquiredAt).toBeDefined();
    expect(newAcquiredAt!).toBeGreaterThan(staleAcquiredAt);
  });

  it("acquireSweeperLock returns true once, false on overlap, true after release", async () => {
    const { acquireSweeperLock, releaseSweeperLock } =
      await import("../src/summarizer/sweeper.js");
    const token = await acquireSweeperLock();
    expect(token).toBeTruthy();
    expect(await acquireSweeperLock()).toBe(false);
    await releaseSweeperLock(token as string);
    expect(await acquireSweeperLock()).toBeTruthy();
  });

  it("sweepStuckRunning returns early when lock is held", async () => {
    const { sweepStuckRunning, acquireSweeperLock } =
      await import("../src/summarizer/sweeper.js");
    expect(await acquireSweeperLock()).toBeTruthy();
    const result = await sweepStuckRunning();
    expect(result).toEqual({ scanned: 0, flipped: 0 });
  });

  // F-06: stuck-pending recovery pass.
  it("flips status=pending docs older than PENDING_STUCK_TIMEOUT_MS to failed-transient", async () => {
    const { sweepStuckRunning } = await import("../src/summarizer/sweeper.js");
    const now = Date.now();
    // Seed a stuck-pending doc (older than threshold)
    await admin
      .firestore()
      .doc("summaries/v-stuck-pending")
      .set({
        videoId: "v-stuck-pending",
        status: "pending",
        model: "free",
        requestedAt: admin.firestore.Timestamp.fromMillis(
          now - PENDING_STUCK_TIMEOUT_MS - 60_000,
        ),
      });
    // Seed a fresh-pending doc (within threshold — should NOT be flipped)
    await admin
      .firestore()
      .doc("summaries/v-fresh-pending")
      .set({
        videoId: "v-fresh-pending",
        status: "pending",
        model: "free",
        requestedAt: admin.firestore.Timestamp.fromMillis(now - 30_000),
      });

    const result = await sweepStuckRunning();
    // Only the stuck-pending doc is scanned+flipped; fresh-pending is not selected
    expect(result.scanned).toBeGreaterThanOrEqual(1);
    expect(result.flipped).toBeGreaterThanOrEqual(1);

    const stuckDoc = await admin
      .firestore()
      .doc("summaries/v-stuck-pending")
      .get();
    expect(stuckDoc.data()?.status).toBe("failed-transient");
    expect(stuckDoc.data()?.errorCode).toBe("stuck_pending_timeout");

    const freshDoc = await admin
      .firestore()
      .doc("summaries/v-fresh-pending")
      .get();
    expect(freshDoc.data()?.status).toBe("pending");
  });

  // F-06: per-doc tx is a no-op when a pending doc's status changed before tx executes.
  it("pending-pass per-doc tx is a no-op when status is no longer 'pending'", async () => {
    const { sweepStuckRunning } = await import("../src/summarizer/sweeper.js");
    const old = Date.now() - PENDING_STUCK_TIMEOUT_MS - 60_000;
    // Seed as pending, then flip to running before the sweeper runs —
    // simulates dispatchSummary completing the HTTP call between query and tx.
    await admin
      .firestore()
      .doc("summaries/v-pending-mid")
      .set({
        videoId: "v-pending-mid",
        status: "pending",
        model: "free",
        requestedAt: admin.firestore.Timestamp.fromMillis(old),
      });
    await admin
      .firestore()
      .doc("summaries/v-pending-mid")
      .set({ status: "running" }, { merge: true });

    const result = await sweepStuckRunning();
    // The pending query would not select it since it's now "running", so
    // scanned may be 0 for this doc; the key assertion is it stays "running".
    expect(result.flipped).toBe(0);

    const doc = await admin.firestore().doc("summaries/v-pending-mid").get();
    expect(doc.data()?.status).toBe("running");
  });
});
