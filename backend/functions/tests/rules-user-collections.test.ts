import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
} from "firebase/firestore";
import {
  ALLOWLISTED_UID,
  STRANGER_UID,
  getTestEnv,
  teardownTestEnv,
} from "./setup";

// AC: user A (owner, allowlisted) succeeds on every documented op for
// progress/notes/highlights; user B (stranger) is denied on every path;
// an allowlisted caller on someone ELSE's path is denied (owner gate holds
// independently of the allowlist); unauthenticated is denied; malformed
// writes are rejected by validation.

const OWNER = ALLOWLISTED_UID;

function progressVideoDoc() {
  return {
    kind: "video",
    videoId: "vid-1",
    playlistId: "pl-1",
    positionSeconds: 767,
    durationSeconds: 1394,
    updatedAt: serverTimestamp(),
  };
}

function progressPlaylistDoc() {
  return {
    kind: "playlist",
    playlistId: "pl-1",
    lastOpenedAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

function noteDoc() {
  return {
    videoId: "vid-1",
    playlistId: "pl-1",
    t: 222,
    text: "The 5-line contract idea.",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

function highlightDoc() {
  return {
    videoId: "vid-1",
    segmentStart: 43,
    text: "I think the problem is that we built a library.",
    createdAt: serverTimestamp(),
  };
}

describe("firestore.rules — users/{uid} progress/notes/highlights", () => {
  beforeAll(async () => {
    await getTestEnv();
  });

  afterAll(async () => {
    await teardownTestEnv();
  });

  beforeEach(async () => {
    const env = await getTestEnv();
    await env.clearFirestore();
    // Seed one doc per collection for both the owner and the stranger via
    // the rules-bypassing admin context, so read/update/delete paths have
    // targets regardless of which side the test exercises.
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      for (const uid of [OWNER, STRANGER_UID]) {
        await setDoc(doc(db, `users/${uid}/progress/vid-1`), {
          kind: "video",
          videoId: "vid-1",
          playlistId: "pl-1",
          positionSeconds: 10,
          durationSeconds: 100,
          updatedAt: new Date(),
        });
        await setDoc(doc(db, `users/${uid}/notes/note-1`), {
          videoId: "vid-1",
          playlistId: "pl-1",
          t: 5,
          text: "seed note",
          createdAt: new Date(),
          updatedAt: new Date(),
        });
        await setDoc(doc(db, `users/${uid}/highlights/hl-1`), {
          videoId: "vid-1",
          segmentStart: 5,
          text: "seed highlight",
          createdAt: new Date(),
        });
      }
    });
  });

  describe("owner (allowlisted, own path) succeeds on every documented op", () => {
    it("progress: get, list, create, update, delete", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      await assertSucceeds(getDoc(doc(db, `users/${OWNER}/progress/vid-1`)));
      await assertSucceeds(
        getDocs(
          query(
            collection(db, `users/${OWNER}/progress`),
            where("kind", "==", "video"),
          ),
        ),
      );
      await assertSucceeds(
        setDoc(doc(db, `users/${OWNER}/progress/vid-2`), progressVideoDoc()),
      );
      await assertSucceeds(
        setDoc(doc(db, `users/${OWNER}/progress/pl-1`), progressPlaylistDoc()),
      );
      await assertSucceeds(
        updateDoc(doc(db, `users/${OWNER}/progress/vid-1`), {
          positionSeconds: 999,
          updatedAt: serverTimestamp(),
        }),
      );
      await assertSucceeds(deleteDoc(doc(db, `users/${OWNER}/progress/vid-1`)));
    });

    it("notes: get, list ordered by t, create, update, delete", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      await assertSucceeds(getDoc(doc(db, `users/${OWNER}/notes/note-1`)));
      await assertSucceeds(
        getDocs(
          query(collection(db, `users/${OWNER}/notes`), orderBy("t", "asc")),
        ),
      );
      await assertSucceeds(
        setDoc(doc(db, `users/${OWNER}/notes/note-2`), noteDoc()),
      );
      await assertSucceeds(
        updateDoc(doc(db, `users/${OWNER}/notes/note-1`), {
          text: "edited",
          updatedAt: serverTimestamp(),
        }),
      );
      await assertSucceeds(deleteDoc(doc(db, `users/${OWNER}/notes/note-1`)));
    });

    it("highlights: get, list, create, delete", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      await assertSucceeds(getDoc(doc(db, `users/${OWNER}/highlights/hl-1`)));
      await assertSucceeds(
        getDocs(
          query(
            collection(db, `users/${OWNER}/highlights`),
            where("videoId", "==", "vid-1"),
          ),
        ),
      );
      await assertSucceeds(
        setDoc(doc(db, `users/${OWNER}/highlights/hl-2`), highlightDoc()),
      );
      await assertSucceeds(
        deleteDoc(doc(db, `users/${OWNER}/highlights/hl-1`)),
      );
    });
  });

  describe("stranger (not allowlisted) is denied on every path", () => {
    it("denies reads and writes on the stranger's OWN path (allowlist gate)", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(STRANGER_UID).firestore();
      // Owner-of-path but not allowlisted: proves the allowlist gate holds
      // independently of the owner gate.
      await assertFails(
        getDoc(doc(db, `users/${STRANGER_UID}/progress/vid-1`)),
      );
      await assertFails(
        setDoc(
          doc(db, `users/${STRANGER_UID}/progress/vid-2`),
          progressVideoDoc(),
        ),
      );
      await assertFails(getDoc(doc(db, `users/${STRANGER_UID}/notes/note-1`)));
      await assertFails(
        setDoc(doc(db, `users/${STRANGER_UID}/notes/note-2`), noteDoc()),
      );
      await assertFails(
        getDoc(doc(db, `users/${STRANGER_UID}/highlights/hl-1`)),
      );
      await assertFails(
        setDoc(
          doc(db, `users/${STRANGER_UID}/highlights/hl-2`),
          highlightDoc(),
        ),
      );
      await assertFails(
        deleteDoc(doc(db, `users/${STRANGER_UID}/notes/note-1`)),
      );
    });

    it("denies reads and writes on the owner's path", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(STRANGER_UID).firestore();
      await assertFails(getDoc(doc(db, `users/${OWNER}/progress/vid-1`)));
      await assertFails(getDocs(collection(db, `users/${OWNER}/progress`)));
      await assertFails(
        setDoc(doc(db, `users/${OWNER}/progress/vid-2`), progressVideoDoc()),
      );
      await assertFails(getDoc(doc(db, `users/${OWNER}/notes/note-1`)));
      await assertFails(
        updateDoc(doc(db, `users/${OWNER}/notes/note-1`), { text: "hijack" }),
      );
      await assertFails(getDoc(doc(db, `users/${OWNER}/highlights/hl-1`)));
      await assertFails(deleteDoc(doc(db, `users/${OWNER}/highlights/hl-1`)));
    });
  });

  it("denies the ALLOWLISTED user on someone else's path (owner gate holds independently)", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(OWNER).firestore();
    await assertFails(getDoc(doc(db, `users/${STRANGER_UID}/progress/vid-1`)));
    await assertFails(
      setDoc(
        doc(db, `users/${STRANGER_UID}/progress/vid-2`),
        progressVideoDoc(),
      ),
    );
    await assertFails(getDoc(doc(db, `users/${STRANGER_UID}/notes/note-1`)));
    await assertFails(
      setDoc(doc(db, `users/${STRANGER_UID}/notes/note-2`), noteDoc()),
    );
    await assertFails(getDoc(doc(db, `users/${STRANGER_UID}/highlights/hl-1`)));
    await assertFails(
      deleteDoc(doc(db, `users/${STRANGER_UID}/highlights/hl-1`)),
    );
  });

  it("denies unauthenticated access on every path", async () => {
    const env = await getTestEnv();
    const db = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, `users/${OWNER}/progress/vid-1`)));
    await assertFails(
      setDoc(doc(db, `users/${OWNER}/progress/vid-2`), progressVideoDoc()),
    );
    await assertFails(getDoc(doc(db, `users/${OWNER}/notes/note-1`)));
    await assertFails(
      setDoc(doc(db, `users/${OWNER}/notes/note-2`), noteDoc()),
    );
    await assertFails(getDoc(doc(db, `users/${OWNER}/highlights/hl-1`)));
    await assertFails(
      setDoc(doc(db, `users/${OWNER}/highlights/hl-2`), highlightDoc()),
    );
  });

  describe("write validation", () => {
    it("rejects progress docs with a bad kind or missing/mistyped fields", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      const target = doc(db, `users/${OWNER}/progress/bad`);
      // Unknown kind.
      await assertFails(
        setDoc(target, { ...progressVideoDoc(), kind: "channel" }),
      );
      // Missing updatedAt.
      const { updatedAt: _u, ...noUpdatedAt } = progressVideoDoc();
      await assertFails(setDoc(target, noUpdatedAt));
      // Wrong-typed timestamp.
      await assertFails(
        setDoc(target, { ...progressVideoDoc(), updatedAt: "yesterday" }),
      );
      // Negative position.
      await assertFails(
        setDoc(target, { ...progressVideoDoc(), positionSeconds: -1 }),
      );
      // Video kind missing videoId.
      const { videoId: _v, ...noVideoId } = progressVideoDoc();
      await assertFails(setDoc(target, noVideoId));
      // Playlist kind missing lastOpenedAt.
      const { lastOpenedAt: _l, ...noLastOpened } = progressPlaylistDoc();
      await assertFails(setDoc(target, noLastOpened));
    });

    it("rejects notes with missing fields, oversized text, or bad types", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      const target = doc(db, `users/${OWNER}/notes/bad`);
      // Missing videoId.
      const { videoId: _v, ...noVideoId } = noteDoc();
      await assertFails(setDoc(target, noVideoId));
      // Oversized text (5001 chars > 5000 cap).
      await assertFails(
        setDoc(target, { ...noteDoc(), text: "x".repeat(5001) }),
      );
      // At-cap text is accepted (boundary).
      await assertSucceeds(
        setDoc(doc(db, `users/${OWNER}/notes/at-cap`), {
          ...noteDoc(),
          text: "x".repeat(5000),
        }),
      );
      // Negative anchor time.
      await assertFails(setDoc(target, { ...noteDoc(), t: -3 }));
      // Wrong-typed t.
      await assertFails(setDoc(target, { ...noteDoc(), t: "01:02" }));
      // Wrong-typed createdAt.
      await assertFails(
        setDoc(target, { ...noteDoc(), createdAt: 1234567890 }),
      );
    });

    it("rejects highlights with missing fields or bad types", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      const target = doc(db, `users/${OWNER}/highlights/bad`);
      const { segmentStart: _s, ...noSegmentStart } = highlightDoc();
      await assertFails(setDoc(target, noSegmentStart));
      await assertFails(
        setDoc(target, { ...highlightDoc(), segmentStart: -0.5 }),
      );
      await assertFails(
        setDoc(target, { ...highlightDoc(), text: "x".repeat(5001) }),
      );
      const { createdAt: _c, ...noCreatedAt } = highlightDoc();
      await assertFails(setDoc(target, noCreatedAt));
    });
  });

  // TST-2: the tests above only prove the rules ACCEPT/REJECT a hand-built
  // fixture — they never write-then-read, and never exercise the write
  // MODE (merge vs. full overwrite vs. add()) each Android repository
  // actually uses. A field rename or a merge->full-overwrite regression in
  // either the rules or the Kotlin repo would pass every test above while
  // silently breaking the real app. This block closes that gap: each case
  // performs the client's exact operation (same field map, same
  // setDoc/addDoc call shape, same merge option) against the emulator, then
  // reads the document BACK and asserts on the persisted values — a true
  // round trip, not just a permission check. Source of truth for each
  // shape (re-check these line numbers if the Kotlin repos change):
  //   ProgressRepository.kt:141-149 upsertVideoProgress's `data` map,
  //     written via `collection.document(videoId).set(data, SetOptions.merge())`
  //   ProgressRepository.kt:185-191 upsertPlaylistOpened's `data` map,
  //     written via `collection.document(playlistId).set(data, SetOptions.merge())`
  //   NotesRepository.kt:73-81 createNote's `data` map,
  //     written via `.collection("notes").add(data)` (auto-id, no merge)
  //   HighlightsRepository.kt:95-101 addHighlight's `data` map,
  //     written via `.document(highlightId(videoId, segmentStart)).set(data)`
  //     (deterministic id, full overwrite, no merge)
  describe("write-shape round trips (literal client payloads)", () => {
    it("upsertVideoProgress: merge-write round-trips and a second tick's merge-write wins", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      const ref = doc(db, `users/${OWNER}/progress/vid-rt`);

      // First tick — mirrors ProgressRepository's exact
      // `.set(data, SetOptions.merge())` call.
      await assertSucceeds(setDoc(ref, progressVideoDoc(), { merge: true }));
      const first = await getDoc(ref);
      expect(first.exists()).toBe(true);
      expect(first.data()?.kind).toBe("video");
      expect(first.data()?.videoId).toBe("vid-1");
      expect(first.data()?.playlistId).toBe("pl-1");
      expect(first.data()?.positionSeconds).toBe(767);
      expect(first.data()?.durationSeconds).toBe(1394);
      expect(first.data()?.updatedAt).toBeInstanceOf(Timestamp);

      // A later playback tick for the same video — the idempotent-upsert
      // path a real throttle cadence takes. The new position must win and
      // no sibling field must be dropped by the merge.
      await assertSucceeds(
        setDoc(
          ref,
          { ...progressVideoDoc(), positionSeconds: 900 },
          { merge: true },
        ),
      );
      const second = await getDoc(ref);
      expect(second.data()?.positionSeconds).toBe(900);
      expect(second.data()?.playlistId).toBe("pl-1");
    });

    it("upsertPlaylistOpened: merge-write round-trips the kind=playlist shape", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      // Deterministic doc id = playlistId, exactly as
      // `collection.document(playlistId)` in ProgressRepository.
      const ref = doc(db, `users/${OWNER}/progress/pl-rt`);

      await assertSucceeds(setDoc(ref, progressPlaylistDoc(), { merge: true }));
      const snap = await getDoc(ref);
      expect(snap.exists()).toBe(true);
      expect(snap.data()?.kind).toBe("playlist");
      expect(snap.data()?.playlistId).toBe("pl-1");
      expect(snap.data()?.lastOpenedAt).toBeInstanceOf(Timestamp);
      expect(snap.data()?.updatedAt).toBeInstanceOf(Timestamp);

      // Re-opening the same playlist later re-stamps lastOpenedAt — the
      // shelf-order query (Q1) depends on this actually advancing.
      await assertSucceeds(setDoc(ref, progressPlaylistDoc(), { merge: true }));
      const reopened = await getDoc(ref);
      expect(reopened.data()?.kind).toBe("playlist");
    });

    it("createNote: add()-shape round-trips as an auto-id document", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      // addDoc mirrors NotesRepository's `.collection("notes").add(data)` —
      // auto-generated id, full document, no merge option in play.
      const written = await assertSucceeds(
        addDoc(collection(db, `users/${OWNER}/notes`), noteDoc()),
      );
      const snap = await getDoc(written);
      expect(snap.exists()).toBe(true);
      expect(snap.data()?.videoId).toBe("vid-1");
      expect(snap.data()?.playlistId).toBe("pl-1");
      expect(snap.data()?.t).toBe(222);
      expect(snap.data()?.text).toBe("The 5-line contract idea.");
      expect(snap.data()?.createdAt).toBeInstanceOf(Timestamp);
      expect(snap.data()?.updatedAt).toBeInstanceOf(Timestamp);
    });

    it("addHighlight: deterministic-id set()-shape round-trips (no merge, full overwrite)", async () => {
      const env = await getTestEnv();
      const db = env.authenticatedContext(OWNER).firestore();
      // Mirrors HighlightsRepository.highlightId(videoId, segmentStart):
      // "${videoId}_${(segmentStart * 1000).toLong()}".
      const ref = doc(db, `users/${OWNER}/highlights/vid-1_43000`);

      await assertSucceeds(setDoc(ref, highlightDoc()));
      const snap = await getDoc(ref);
      expect(snap.exists()).toBe(true);
      expect(snap.data()?.videoId).toBe("vid-1");
      expect(snap.data()?.segmentStart).toBe(43);
      expect(snap.data()?.text).toBe(
        "I think the problem is that we built a library.",
      );
      expect(snap.data()?.createdAt).toBeInstanceOf(Timestamp);

      // The toggle-off path (removeHighlight): same deterministic id,
      // plain delete — proves the id scheme really is idempotent both ways.
      await assertSucceeds(deleteDoc(ref));
      const afterDelete = await getDoc(ref);
      expect(afterDelete.exists()).toBe(false);
    });

    // NOT covered here, deliberately: ProgressRepository's
    // `lastWriteCaptureMillis` monotonic guard (ProgressRepository.kt:62,
    // 135-139) is an in-process, session-lifetime `ConcurrentHashMap` check
    // that runs BEFORE the Firestore call — a late-arriving, older-captured
    // write is dropped client-side and never reaches the network. Nothing
    // about `capturedAtMillis` is part of the written document (only
    // `updatedAt`'s server timestamp is), so there is no Firestore-visible
    // artifact for a rules/emulator test to assert on — the guard is
    // structurally invisible to this suite. Verifying it requires a JVM-
    // level test that calls `upsertVideoProgress` twice with out-of-order
    // `capturedAtMillis` and asserts the second call is a no-op; per this
    // redesign's D2 test-scope decision (00-index.md, TST-1/TST-19) that
    // repository is not unit-tested directly (final class, live-Firebase
    // init, no mockk/coroutines-test on the classpath) — a real gap, left
    // open rather than papered over.
  });
});
