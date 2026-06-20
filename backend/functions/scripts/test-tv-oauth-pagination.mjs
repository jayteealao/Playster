// Pagination spike on the winning client (TVHTML5_SIMPLY_EMBEDDED_PLAYER).
// Confirms we can fetch the full Watch Later list, not just the first page.

import { Innertube, UniversalCache } from "youtubei.js";
import * as path from "node:path";
import * as fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CACHE_DIR = path.resolve(__dirname, "..", ".tmp", "innertube-oauth");

const originalFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = typeof input === "string" ? input : input.url;
  const res = await originalFetch(input, init);
  if (!res.ok && url.includes("/youtubei/v1/")) {
    const body = await res.clone().text();
    console.log("  [fetch " + res.status + "] " + url.split("?")[0]);
    console.log("  body: " + body.slice(0, 500).replace(/\s+/g, " "));
  }
  return res;
};

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

  // Patch context to send the wire-correct client_name (v13.4.0 sends the
  // literal enum tag otherwise — YouTube returns 400 INVALID_ARGUMENT).
  innertube.session.context.client.clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER";
  innertube.session.context.client.clientVersion = "2.0";

  console.log("signed in. client=" + innertube.session.context.client.clientName);
  console.log("\nFetching first page of WL...");

  let current = await innertube.getPlaylist("WL");
  const allItems = [];
  const pushItems = (page) => {
    const items = page.videos ?? page.items ?? [];
    allItems.push(...items);
  };
  pushItems(current);
  console.log("  page 1: " + (current.videos?.length ?? 0) +
    " items, has_continuation=" + Boolean(current.has_continuation));

  let pageNum = 1;
  while (current.has_continuation) {
    pageNum++;
    try {
      current = await current.getContinuation();
    } catch (err) {
      console.log("  page " + pageNum + " continuation FAILED: " +
        (err instanceof Error ? err.message : String(err)));
      break;
    }
    pushItems(current);
    console.log("  page " + pageNum + ": " + (current.videos?.length ?? current.items?.length ?? 0) +
      " items, has_continuation=" + Boolean(current.has_continuation));
    if (pageNum > 100) {
      console.log("  safety break at 100 pages");
      break;
    }
  }

  console.log("\nTotal items collected: " + allItems.length);
  console.log("First 3:");
  allItems.slice(0, 3).forEach((v, i) => {
    const title = typeof v.title?.toString === "function" ? v.title.toString() : v.title;
    console.log("  " + (i + 1) + ". " + (v.id ?? "?") + "  " + (title ?? ""));
  });
  console.log("Last 3:");
  allItems.slice(-3).forEach((v, i) => {
    const title = typeof v.title?.toString === "function" ? v.title.toString() : v.title;
    console.log("  " + (allItems.length - 2 + i) + ". " + (v.id ?? "?") + "  " + (title ?? ""));
  });
}

main().catch((err) => {
  console.error("Fatal:", err?.message ?? err);
  process.exit(1);
});
