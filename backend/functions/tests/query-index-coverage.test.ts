import * as fs from "node:fs";
import * as path from "node:path";
import { beforeAll, describe, expect, it } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";

// AC4 has two legs because the Firestore EMULATOR DOES NOT ENFORCE composite
// indexes (it executes any query regardless of firestore.indexes.json), so a
// green emulator run says nothing about missing indexes in production:
//
//   Leg 1 (static): every documented app query maps to a composite index
//   entry in firestore.indexes.json — or is single-field (automatic).
//   Leg 2 (emulator): the documented queries return results in the specified
//   order over seeded data (the real query path, minus index enforcement).

interface IndexField {
  fieldPath: string;
  order: "ASCENDING" | "DESCENDING";
}

interface CompositeIndex {
  collectionGroup: string;
  queryScope: string;
  fields: IndexField[];
}

interface DocumentedQuery {
  id: string;
  description: string;
  collection: string;
  /** Equality-filter fields (order irrelevant to index matching). */
  equality: string[];
  /** orderBy clauses in query order, with directions. */
  orderBys: IndexField[];
}

// The documented query set — the contract shared by the indexes, this test,
// and the screen slices (see docs/architecture/user-collections-and-search.md).
const DOCUMENTED_QUERIES: DocumentedQuery[] = [
  {
    id: "Q1",
    description: "Home shelf: playlists by last-opened desc",
    collection: "progress",
    equality: ["kind"],
    orderBys: [{ fieldPath: "lastOpenedAt", order: "DESCENDING" }],
  },
  {
    id: "Q2",
    description: "Continue headliner: newest video progress",
    collection: "progress",
    equality: ["kind"],
    orderBys: [{ fieldPath: "updatedAt", order: "DESCENDING" }],
  },
  {
    id: "Q3",
    description: "Player/Transcript notes for a video by anchor time",
    collection: "notes",
    equality: ["videoId"],
    orderBys: [{ fieldPath: "t", order: "ASCENDING" }],
  },
  {
    id: "Q4",
    description: "Playlist Notes tab, newest first",
    collection: "notes",
    equality: ["playlistId"],
    orderBys: [{ fieldPath: "createdAt", order: "DESCENDING" }],
  },
  {
    id: "Q5",
    description: "Transcript highlights for a video by segment start",
    collection: "highlights",
    equality: ["videoId"],
    orderBys: [{ fieldPath: "segmentStart", order: "ASCENDING" }],
  },
  {
    id: "Q6a",
    description: "Settings stats: progress updated since t0",
    collection: "progress",
    equality: [],
    orderBys: [{ fieldPath: "updatedAt", order: "ASCENDING" }],
  },
  {
    id: "Q6b",
    description: "Settings stats: highlights created since t0",
    collection: "highlights",
    equality: [],
    orderBys: [{ fieldPath: "createdAt", order: "ASCENDING" }],
  },
];

function loadIndexes(): CompositeIndex[] {
  const indexesPath = path.resolve(
    __dirname,
    "..",
    "..",
    "firestore.indexes.json",
  );
  const parsed = JSON.parse(fs.readFileSync(indexesPath, "utf8")) as {
    indexes?: CompositeIndex[];
  };
  return parsed.indexes ?? [];
}

/**
 * Firestore serves a query from a composite index when the index lists the
 * equality fields first (any internal order) followed by the orderBy fields
 * in query order with matching directions — or with ALL directions inverted
 * (an index scans both ways).
 */
function indexCovers(index: CompositeIndex, q: DocumentedQuery): boolean {
  if (index.collectionGroup !== q.collection) return false;
  if (index.queryScope !== "COLLECTION") return false;
  if (index.fields.length !== q.equality.length + q.orderBys.length) {
    return false;
  }
  const prefix = index.fields.slice(0, q.equality.length);
  const prefixPaths = new Set(prefix.map((f) => f.fieldPath));
  if (
    q.equality.length !== prefixPaths.size ||
    !q.equality.every((f) => prefixPaths.has(f))
  ) {
    return false;
  }
  const suffix = index.fields.slice(q.equality.length);
  const directMatch = suffix.every(
    (f, i) =>
      f.fieldPath === q.orderBys[i].fieldPath &&
      f.order === q.orderBys[i].order,
  );
  const invertedMatch = suffix.every(
    (f, i) =>
      f.fieldPath === q.orderBys[i].fieldPath &&
      f.order !== q.orderBys[i].order,
  );
  return directMatch || invertedMatch;
}

describe("AC4 leg 1 — static index coverage of the documented query set", () => {
  const indexes = loadIndexes();

  for (const q of DOCUMENTED_QUERIES) {
    it(`${q.id} (${q.description}) is index-covered or single-field`, () => {
      const fieldCount = q.equality.length + q.orderBys.length;
      if (fieldCount <= 1) {
        // Single-field queries ride the automatic single-field indexes.
        expect(fieldCount).toBeLessThanOrEqual(1);
        return;
      }
      const covering = indexes.filter((idx) => indexCovers(idx, q));
      expect(
        covering.length,
        `${q.id} needs a composite index (${q.collection}: ` +
          `${[...q.equality, ...q.orderBys.map((o) => o.fieldPath)].join(", ")})` +
          ` in firestore.indexes.json — the emulator will NOT catch this`,
      ).toBeGreaterThan(0);
    });
  }

  it("keeps the existing summaries index intact (release check depends on it)", () => {
    const covered = indexes.some(
      (idx) =>
        idx.collectionGroup === "summaries" &&
        idx.fields.length === 2 &&
        idx.fields[0].fieldPath === "status" &&
        idx.fields[1].fieldPath === "requestedAt",
    );
    expect(covered).toBe(true);
  });
});

describe("AC4 leg 2 — documented queries order as specified on the emulator", () => {
  const UID = "index-coverage-user";

  function ts(minutesAgo: number): Date {
    return new Date(Date.now() - minutesAgo * 60_000);
  }

  beforeAll(async () => {
    initAdminEmulator();
    await clearFirestore();
    const db = admin.firestore();
    const base = db.collection("users").doc(UID);

    // Progress: three playlists with distinct lastOpenedAt, three videos
    // with distinct updatedAt.
    await base.collection("progress").doc("pl-old").set({
      kind: "playlist",
      playlistId: "pl-old",
      lastOpenedAt: ts(300),
      updatedAt: ts(300),
    });
    await base.collection("progress").doc("pl-new").set({
      kind: "playlist",
      playlistId: "pl-new",
      lastOpenedAt: ts(5),
      updatedAt: ts(5),
    });
    await base.collection("progress").doc("pl-mid").set({
      kind: "playlist",
      playlistId: "pl-mid",
      lastOpenedAt: ts(60),
      updatedAt: ts(60),
    });
    await base.collection("progress").doc("vid-old").set({
      kind: "video",
      videoId: "vid-old",
      playlistId: "pl-old",
      positionSeconds: 10,
      durationSeconds: 100,
      updatedAt: ts(200),
    });
    await base.collection("progress").doc("vid-new").set({
      kind: "video",
      videoId: "vid-new",
      playlistId: "pl-new",
      positionSeconds: 20,
      durationSeconds: 100,
      updatedAt: ts(1),
    });

    // Notes: on one video with shuffled anchor times; across playlists with
    // distinct createdAt.
    const notes = base.collection("notes");
    await notes.doc("n1").set({
      videoId: "vid-x",
      playlistId: "pl-a",
      t: 30,
      text: "third",
      createdAt: ts(10),
      updatedAt: ts(10),
    });
    await notes.doc("n2").set({
      videoId: "vid-x",
      playlistId: "pl-a",
      t: 10,
      text: "first",
      createdAt: ts(30),
      updatedAt: ts(30),
    });
    await notes.doc("n3").set({
      videoId: "vid-x",
      playlistId: "pl-a",
      t: 20,
      text: "second",
      createdAt: ts(20),
      updatedAt: ts(20),
    });
    await notes.doc("n4").set({
      videoId: "vid-y",
      playlistId: "pl-b",
      t: 5,
      text: "other video",
      createdAt: ts(5),
      updatedAt: ts(5),
    });

    // Highlights: shuffled segment starts.
    const highlights = base.collection("highlights");
    await highlights.doc("h1").set({
      videoId: "vid-x",
      segmentStart: 50,
      text: "late",
      createdAt: ts(10),
    });
    await highlights.doc("h2").set({
      videoId: "vid-x",
      segmentStart: 5,
      text: "early",
      createdAt: ts(20),
    });
    await highlights.doc("h3").set({
      videoId: "vid-x",
      segmentStart: 25,
      text: "middle",
      createdAt: ts(15),
    });
  });

  it("Q1: playlists order by lastOpenedAt descending", async () => {
    const db = admin.firestore();
    const snap = await db
      .collection(`users/${UID}/progress`)
      .where("kind", "==", "playlist")
      .orderBy("lastOpenedAt", "desc")
      .get();
    expect(snap.docs.map((d) => d.id)).toEqual(["pl-new", "pl-mid", "pl-old"]);
  });

  it("Q2: newest video progress first, limit 1", async () => {
    const db = admin.firestore();
    const snap = await db
      .collection(`users/${UID}/progress`)
      .where("kind", "==", "video")
      .orderBy("updatedAt", "desc")
      .limit(1)
      .get();
    expect(snap.docs.map((d) => d.id)).toEqual(["vid-new"]);
  });

  it("Q3: notes for a video order by anchor time ascending", async () => {
    const db = admin.firestore();
    const snap = await db
      .collection(`users/${UID}/notes`)
      .where("videoId", "==", "vid-x")
      .orderBy("t", "asc")
      .get();
    expect(snap.docs.map((d) => d.id)).toEqual(["n2", "n3", "n1"]);
  });

  it("Q4: playlist notes order by createdAt descending", async () => {
    const db = admin.firestore();
    const snap = await db
      .collection(`users/${UID}/notes`)
      .where("playlistId", "==", "pl-a")
      .orderBy("createdAt", "desc")
      .get();
    expect(snap.docs.map((d) => d.id)).toEqual(["n1", "n3", "n2"]);
  });

  it("Q5: highlights for a video order by segmentStart ascending", async () => {
    const db = admin.firestore();
    const snap = await db
      .collection(`users/${UID}/highlights`)
      .where("videoId", "==", "vid-x")
      .orderBy("segmentStart", "asc")
      .get();
    expect(snap.docs.map((d) => d.id)).toEqual(["h2", "h3", "h1"]);
  });

  it("Q6: single-field range scans return the since-t0 subset", async () => {
    const db = admin.firestore();
    const t0 = new Date(Date.now() - 100 * 60_000);
    const progress = await db
      .collection(`users/${UID}/progress`)
      .where("updatedAt", ">=", t0)
      .get();
    expect(new Set(progress.docs.map((d) => d.id))).toEqual(
      new Set(["pl-new", "pl-mid", "vid-new"]),
    );
    const highlights = await db
      .collection(`users/${UID}/highlights`)
      .where("createdAt", ">=", t0)
      .get();
    expect(highlights.size).toBe(3);
  });
});
