// Raw /browse spike: bypass v13's playlist parser entirely. We just want to
// know — is the continuation token in YouTube's TV response, and can we walk
// it to get the full Watch Later list?

import { Innertube, UniversalCache } from "youtubei.js";
import * as path from "node:path";
import * as fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CACHE_DIR = path.resolve(__dirname, "..", ".tmp", "innertube-oauth");

async function main() {
  await fs.mkdir(CACHE_DIR, { recursive: true });

  const innertube = await Innertube.create({
    cache: new UniversalCache(true, CACHE_DIR),
    client_type: "TV_SIMPLY",
  });
  innertube.session.on("update-credentials", async () => {
    await innertube.session.oauth.cacheCredentials();
  });
  await innertube.session.signIn();

  // Try the full TVHTML5 surface — returns Tile/HorizontalList grid with many
  // more items per page (the smart-TV YouTube app uses this).
  innertube.session.context.client.clientName = "TVHTML5";
  innertube.session.context.client.clientVersion = "7.20250812.07.00";

  // Walk an arbitrary nested object collecting any (videoId, title) pairs and
  // any continuation tokens we see along the way. The TV response shape is
  // not stable enough across YouTube revisions to address by path; structural
  // search is more robust.
  function collect(node, acc = { items: [], continuations: [] }) {
    if (node == null) return acc;
    if (Array.isArray(node)) {
      for (const v of node) collect(v, acc);
      return acc;
    }
    if (typeof node !== "object") return acc;

    if (node.videoId && typeof node.videoId === "string") {
      const title =
        node.title?.simpleText ??
        node.title?.runs?.[0]?.text ??
        (typeof node.title === "string" ? node.title : undefined) ??
        node.metadata?.title?.simpleText ??
        node.metadata?.title?.runs?.[0]?.text ??
        node.headline?.simpleText ??
        node.headline?.runs?.[0]?.text;
      acc.items.push({ id: node.videoId, title: title ?? "(no title)" });
    }
    if (node.continuationCommand?.token) {
      acc.continuations.push(node.continuationCommand.token);
    } else if (
      node.token &&
      node.request === "CONTINUATION_REQUEST_TYPE_BROWSE"
    ) {
      acc.continuations.push(node.token);
    } else if (typeof node.continuation === "string") {
      acc.continuations.push(node.continuation);
    }

    for (const k of Object.keys(node)) collect(node[k], acc);
    return acc;
  }

  console.log("\n[page 1] /browse browseId=VLWL");
  const first = await innertube.actions.execute("/browse", {
    browseId: "VLWL",
  });
  const firstData = first.data ?? first;

  // Optional raw-page capture for the regression fixture. Set DUMP_PATH to
  // write the raw /browse VLWL response to disk, then redact + trim it by hand
  // before committing as tests/fixtures/innertube-browse-wl-lockup.json.
  // See docs/operations/backfill-watch-later.md.
  if (process.env.DUMP_PATH) {
    await fs.writeFile(process.env.DUMP_PATH, JSON.stringify(firstData, null, 2));
    console.log("  raw page 1 written to " + process.env.DUMP_PATH);
  }

  const result1 = collect(firstData);

  // Dedupe — structural walk can revisit shared nodes.
  const seen = new Set();
  const dedupedItems = [];
  for (const item of result1.items) {
    if (!seen.has(item.id)) {
      seen.add(item.id);
      dedupedItems.push(item);
    }
  }
  const uniqueCont = [...new Set(result1.continuations)];

  console.log("  items on page 1: " + dedupedItems.length);
  console.log("  continuation tokens found: " + uniqueCont.length);
  if (uniqueCont.length) {
    console.log(
      "  first token (truncated): " + uniqueCont[0].slice(0, 60) + "...",
    );
  }

  let pageNum = 1;
  let nextToken = uniqueCont[0];
  while (nextToken) {
    pageNum++;
    if (pageNum > 100) {
      console.log("  safety break at 100 pages");
      break;
    }
    let next;
    try {
      next = await innertube.actions.execute("/browse", {
        continuation: nextToken,
      });
    } catch (err) {
      console.log(
        "  page " +
          pageNum +
          " /browse FAILED: " +
          (err instanceof Error ? err.message : String(err)),
      );
      break;
    }
    const result = collect(next.data ?? next);
    let added = 0;
    for (const item of result.items) {
      if (!seen.has(item.id)) {
        seen.add(item.id);
        dedupedItems.push(item);
        added++;
      }
    }
    const tokens = [...new Set(result.continuations)].filter(
      (t) => t !== nextToken,
    );
    console.log(
      "  page " +
        pageNum +
        ": +" +
        added +
        " new, " +
        tokens.length +
        " token(s), total=" +
        dedupedItems.length,
    );
    nextToken = tokens[0];
  }

  console.log("\nTotal unique items: " + dedupedItems.length);
  if (dedupedItems.length) {
    console.log("First 3:");
    dedupedItems
      .slice(0, 3)
      .forEach((v, i) =>
        console.log("  " + (i + 1) + ". " + v.id + "  " + v.title),
      );
    console.log("Last 3:");
    dedupedItems
      .slice(-3)
      .forEach((v, i) =>
        console.log(
          "  " + (dedupedItems.length - 2 + i) + ". " + v.id + "  " + v.title,
        ),
      );
  }
}

main().catch((err) => {
  console.error("Fatal:", err?.message ?? err);
  process.exit(1);
});
