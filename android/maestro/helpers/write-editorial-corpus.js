#!/usr/bin/env node
// Seed the Firestore emulator with the editorial verification corpus:
// mock-shaped playlists/videos, a per-user progress spread, notes and
// highlights on the featured video, transcripts (including a
// 2,000-paragraph stressor), a chapters-bearing summary, a
// description-chapters video, and a no-transcript video. Fixture ids are
// recorded in android/maestro/fixtures/editorial-fixtures.json.
//
// PROJECT ID: the Android Firestore SDK addresses documents under the app's
// OWN project id (from google-services.json) even when pointed at the
// emulator, so seeds must land under that project — NOT the emulator
// suite's default. Override with FIRESTORE_PROJECT_ID if the app config
// ever changes.
//
// Requires a running Firestore emulator (default 127.0.0.1:8080; override
// with FIRESTORE_EMULATOR_HOST or FIRESTORE_EMULATOR).
const fs = require("node:fs");
const path = require("node:path");
const admin = require(
  path.join(
    __dirname,
    "..",
    "..",
    "..",
    "backend",
    "functions",
    "node_modules",
    "firebase-admin",
  ),
);

const PROJECT_ID = process.env.FIRESTORE_PROJECT_ID || "playster-406121";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  process.env.FIRESTORE_EMULATOR_HOST =
    process.env.FIRESTORE_EMULATOR || "127.0.0.1:8080";
}

/** Same allowlist-literal read as create-fixture-user.js — never hardcoded. */
function allowlistUid() {
  const rulesPath = path.join(
    __dirname,
    "..",
    "..",
    "..",
    "backend",
    "firestore.rules",
  );
  const rules = fs.readFileSync(rulesPath, "utf8");
  const match = rules.match(/request\.auth\.uid == "([^"]+)"/);
  if (!match) {
    throw new Error(`no allowlist uid literal found in ${rulesPath}`);
  }
  return match[1];
}

admin.initializeApp({ projectId: PROJECT_ID });
const db = admin.firestore();

const NOW = Date.now();
const minutesAgo = (m) => new Date(NOW - m * 60_000);
const daysAgo = (d) => minutesAgo(d * 24 * 60);

// --- content (mirrors the design mock's data shapes; synthetic ids) ---

const PLAYLISTS = [
  {
    id: "ed-p1",
    title: "Designing Better Interfaces",
    channelTitle: "Joey Banks",
    description:
      "A season on contracts, runtime systems, and editorial maintenance.",
    videoCount: 14,
    lastOpened: minutesAgo(5),
  },
  {
    id: "ed-p2",
    title: "Modern Type Practice",
    channelTitle: "Ellen Lupton",
    description: "Where type meets the screen.",
    videoCount: 12,
    lastOpened: daysAgo(1),
  },
  {
    id: "ed-p3",
    title: "Motion, Honestly",
    channelTitle: "Val Head",
    description: "Easing curves people can actually feel.",
    videoCount: 9,
    lastOpened: daysAgo(3),
  },
  {
    id: "ed-p4",
    title: "Product Notes",
    channelTitle: "Lenny's Archive",
    description: "Talks worth a second listen.",
    videoCount: 7,
    lastOpened: daysAgo(7),
  },
  // ed-p5 is deliberately untouched: no progress doc, exercising the
  // "never opened" shelf state.
  {
    id: "ed-p5",
    title: "Inbox",
    channelTitle: "Saved for later",
    description:
      "Things you tossed here in the last seven days. Read them, or let them go.",
    videoCount: 3,
    lastOpened: null,
  },
];

// Episodes for "Designing Better Interfaces" (mock's VIDEO_LIST, synthetic ids).
const P1_VIDEOS = [
  { id: "ed-v01", title: "Why design systems plateau", dur: "PT12M8S", sec: 728 },
  { id: "ed-v02", title: "Tokens, but actually useful", dur: "PT18M33S", sec: 1113 },
  { id: "ed-v03", title: "The component contract", dur: "PT21M9S", sec: 1269 },
  { id: "ed-v04", title: "Theming without tears", dur: "PT14M55S", sec: 895 },
  { id: "ed-v05", title: "A11y is the system", dur: "PT19M46S", sec: 1186 },
  { id: "ed-v06", title: "Versioning without crying", dur: "PT17M21S", sec: 1041 },
  { id: "ed-v07", title: "Docs as a product", dur: "PT13M2S", sec: 782 },
  { id: "ed-v08", title: "Adoption, measured honestly", dur: "PT24M18S", sec: 1458 },
  { id: "ed-v09", title: "The Future of Design Systems", dur: "PT23M14S", sec: 1394 },
  { id: "ed-v10", title: "When to fork the system", dur: "PT15M47S", sec: 947 },
  { id: "ed-v11", title: "AI in the component layer", dur: "PT20M11S", sec: 1211 },
  { id: "ed-v12", title: "Picking a north-star token", dur: "PT11M54S", sec: 714 },
];

const FEATURED_ID = "ed-v09";

// Mock transcript paragraphs (t in seconds, speaker folded into text).
const FEATURED_TRANSCRIPT = [
  [0, "Alright, let's just get into it. I've been thinking a lot about why design systems plateau."],
  [8, "You ship the first wave, everyone's happy, the demo GIF goes viral."],
  [14, "And then year two hits, and the system kind of just sits there."],
  [22, "Components get added but nobody's really using them. People fork. Tokens drift."],
  [31, "And the team starts blaming adoption, blaming the orgs, blaming the tools."],
  [39, "But I don't think that's the problem."],
  [43, "I think the problem is that we built a library."],
  [46, "And what people actually needed was a contract."],
  [53, "A contract is a promise. If you call this thing a Button, here's what it will do."],
  [62, "Notice I didn't say how it looks. The contract is about behavior, not pixels."],
  [71, "And once you accept that, your whole job changes."],
  [78, "Tokens stop being a paint bucket and start being the API your product reads from at runtime."],
  [88, "Components stop being a destination and start being a place to negotiate — with density, with theme, with locale, with accessibility."],
  [99, "And maintenance stops being a backlog and starts being editorial work."],
  [108, "So here's what I think the next five years actually look like."],
  [116, "You'll stop hearing the phrase design system and start hearing type contract, motion contract, surface contract — small bundles of guarantees the host can rely on."],
];

const FEATURED_SUMMARY_CONTENT = [
  "Design systems plateau when they're treated as a library instead of a " +
    "contract. The next decade belongs to runtime systems — tokens that " +
    "resolve at render time, components that negotiate with their host, and " +
    "adoption measured in deleted code, not added components.",
  "",
  "- Component libraries are a means, not the end — the real product is the contract.",
  "- Tokens should resolve at runtime so theming, density and accessibility are first-class.",
  "- Adoption is measured by lines deleted, not stories shipped.",
  "- AI assistants make weird APIs expensive — boring, predictable surfaces win.",
  "- The maintainer's job is now editorial: pruning, deprecating, narrating.",
].join("\n");

// Mock chapters (canonical seconds; display strings are client-side).
const FEATURED_CHAPTERS = [
  { t: 0, label: "Cold open: the plateau", dur: 134 },
  { t: 134, label: "Why libraries stop scaling", dur: 248 },
  { t: 382, label: "Tokens as a runtime contract", dur: 331 },
  { t: 713, label: "The component negotiates", dur: 294 },
  { t: 1007, label: "Adoption metrics that lie", dur: 202 },
  { t: 1209, label: "Editorial maintenance", dur: 185 },
];

async function main() {
  const uid = allowlistUid();
  const batchWrites = [];

  // Playlists + videos.
  for (const pl of PLAYLISTS) {
    batchWrites.push(
      db.doc(`playlists/${pl.id}`).set({
        id: pl.id,
        playlistId: pl.id,
        title: pl.title,
        description: pl.description,
        thumbnailUrl: "",
        videoCount: pl.videoCount,
        publishedAt: "2025-11-02T09:00:00Z",
        channelTitle: pl.channelTitle,
        privacyStatus: "public",
        source: "api",
        lastSyncedAt: minutesAgo(30),
      }),
    );
  }
  P1_VIDEOS.forEach((video, i) => {
    const doc = {
      videoId: video.id,
      playlistId: "ed-p1",
      title: video.title,
      channelTitle: "Joey Banks",
      channelId: "UC_ED_FIXTURE",
      duration: video.dur,
      thumbnailUrl: "",
      publishedAt: `2025-10-${String(i + 1).padStart(2, "0")}T12:00:00Z`,
      viewCount: 1840 * (i + 1),
      position: i,
      addedAt: `2025-12-${String(i + 1).padStart(2, "0")}T08:00:00Z`,
    };
    // ed-v11: the description-chapters fixture — chapter lines embedded in
    // the video description (the summarizer-independent chapters source).
    if (video.id === "ed-v11") {
      doc.description = [
        "A talk about what model assistance does to component APIs.",
        "",
        "00:00 Intro",
        "03:12 The autocomplete trap",
        "09:40 Boring APIs win",
        "15:05 What to instrument",
      ].join("\n");
    }
    batchWrites.push(
      db.doc(`playlists/ed-p1/videos/${video.id}`).set(doc),
    );
  });

  // Progress spread: fresh / mid / nearly-done; untouched videos have no doc.
  const featured = P1_VIDEOS.find((v) => v.id === FEATURED_ID);
  batchWrites.push(
    db.doc(`users/${uid}/progress/${FEATURED_ID}`).set({
      kind: "video",
      videoId: FEATURED_ID,
      playlistId: "ed-p1",
      positionSeconds: 767, // 12:47 into 23:14 — the mock's 0.55.
      durationSeconds: featured.sec,
      updatedAt: minutesAgo(5),
    }),
    db.doc(`users/${uid}/progress/ed-v03`).set({
      kind: "video",
      videoId: "ed-v03",
      playlistId: "ed-p1",
      positionSeconds: 1210, // ~95% — the "almost done" state.
      durationSeconds: 1269,
      updatedAt: daysAgo(2),
    }),
    db.doc(`users/${uid}/progress/ed-v01`).set({
      kind: "video",
      videoId: "ed-v01",
      playlistId: "ed-p1",
      positionSeconds: 31, // barely started.
      durationSeconds: 728,
      updatedAt: daysAgo(6),
    }),
  );
  for (const pl of PLAYLISTS) {
    if (!pl.lastOpened) continue; // ed-p5: never opened.
    batchWrites.push(
      db.doc(`users/${uid}/progress/${pl.id}`).set({
        kind: "playlist",
        playlistId: pl.id,
        lastOpenedAt: pl.lastOpened,
        updatedAt: pl.lastOpened,
      }),
    );
  }

  // Notes on the featured video (mock NOTES, seconds canonical).
  const notes = [
    [222, '"400 components, nobody used them" — ask for source on the number.'],
    [767, "The 5-line contract idea. Try to draft this for the Button at work."],
    [1110, "The internal Slack screenshot — send to Marta."],
  ];
  notes.forEach(([t, text], i) => {
    batchWrites.push(
      db.doc(`users/${uid}/notes/ed-note-${i + 1}`).set({
        videoId: FEATURED_ID,
        playlistId: "ed-p1",
        t,
        text,
        createdAt: daysAgo(3 - i),
        updatedAt: daysAgo(3 - i),
      }),
    );
  });

  // Highlights on the featured video (mock's highlighted transcript lines).
  const highlights = [
    [43, "I think the problem is that we built a library."],
    [46, "And what people actually needed was a contract."],
  ];
  highlights.forEach(([segmentStart, text], i) => {
    batchWrites.push(
      db.doc(`users/${uid}/highlights/ed-hl-${i + 1}`).set({
        videoId: FEATURED_ID,
        segmentStart,
        text,
        createdAt: daysAgo(2),
      }),
    );
  });

  // Transcripts: featured (mock paragraphs) + 2,000-paragraph stressor.
  // ed-v10 deliberately gets NO transcript doc (the no-transcript state).
  batchWrites.push(
    db.doc(`transcripts/${FEATURED_ID}`).set({
      videoId: FEATURED_ID,
      status: "available",
      source: "youtubei",
      language: "en",
      segments: FEATURED_TRANSCRIPT.map(([start, text]) => ({ start, text })),
      createdAt: daysAgo(10),
      updatedAt: daysAgo(10),
    }),
  );
  const longSegments = Array.from({ length: 2000 }, (_, i) => ({
    start: i * 8,
    text:
      `Paragraph ${i + 1}: tokens, naming, and the slow work of keeping a ` +
      "design language honest across platforms and years of churn.",
  }));
  batchWrites.push(
    db.doc("transcripts/ed-v02").set({
      videoId: "ed-v02",
      status: "available",
      source: "youtubei",
      language: "en",
      segments: longSegments,
      createdAt: daysAgo(10),
      updatedAt: daysAgo(10),
    }),
  );

  // Summary with structured chapters (the summarizer-chapters fixture).
  batchWrites.push(
    db.doc(`summaries/${FEATURED_ID}`).set({
      videoId: FEATURED_ID,
      status: "completed",
      model: "free",
      content: FEATURED_SUMMARY_CONTENT,
      chapters: FEATURED_CHAPTERS,
      requestedAt: daysAgo(9),
      completedAt: daysAgo(9),
    }),
  );

  await Promise.all(batchWrites);
  console.log(
    `write-editorial-corpus OK: project=${PROJECT_ID} user=${uid} ` +
      `playlists=${PLAYLISTS.length} videos=${P1_VIDEOS.length} ` +
      `progress=${3 + PLAYLISTS.filter((p) => p.lastOpened).length} ` +
      `notes=${notes.length} highlights=${highlights.length} transcripts=2 summaries=1`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
