import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import { assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
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
      await assertSucceeds(
        deleteDoc(doc(db, `users/${OWNER}/progress/vid-1`)),
      );
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
      await assertSucceeds(
        getDoc(doc(db, `users/${OWNER}/highlights/hl-1`)),
      );
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
      await assertFails(
        deleteDoc(doc(db, `users/${OWNER}/highlights/hl-1`)),
      );
    });
  });

  it("denies the ALLOWLISTED user on someone else's path (owner gate holds independently)", async () => {
    const env = await getTestEnv();
    const db = env.authenticatedContext(OWNER).firestore();
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
});
