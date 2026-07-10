// One-shot local spike: does TV OAuth let us read Watch Later?
//
// Usage (from backend/functions/):
//   node scripts/test-tv-oauth.mjs
//
// First run: prints a verification URL + user code; visit it, enter the code,
// approve. The script polls until you finish, then attempts to read WL.
// Subsequent runs reuse cached credentials and skip the device-code prompt.
//
// Note: youtubei.js v13.4.0 sends `client_type` enum tags ("TV", "TV_SIMPLY",
// "TV_EMBEDDED") directly as context.client.client_name, which YouTube rejects
// with 400 INVALID_ARGUMENT. We patch the context post-create to send the real
// wire names ("TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER").

import { Innertube, UniversalCache } from "youtubei.js";
import * as path from "node:path";
import * as fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CACHE_DIR = path.resolve(__dirname, "..", ".tmp", "innertube-oauth");

// Real client_name values YouTube's protobuf enum accepts (from
// zerodytrash/YouTube-Internal-Clients).
const TV_CLIENTS = [
  { libName: "TV", wireName: "TVHTML5", version: "7.20250812.07.00" },
  {
    libName: "TV_SIMPLY",
    wireName: "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
    version: "2.0",
  },
  {
    libName: "TV_EMBEDDED",
    wireName: "TVHTML5_FOR_KIDS",
    version: "7.20250812.07.00",
  },
];

function dumpError(err) {
  const lines = [];
  lines.push("    message: " + (err?.message ?? String(err)));
  if (err?.info) {
    const info =
      typeof err.info === "string" ? err.info : JSON.stringify(err.info);
    lines.push("    info: " + info.slice(0, 600));
  }
  return lines.join("\n");
}

const originalFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = typeof input === "string" ? input : input.url;
  const res = await originalFetch(input, init);
  if (!res.ok && url.includes("/youtubei/v1/")) {
    const clone = res.clone();
    const body = await clone.text();
    console.log("    [fetch " + res.status + "] " + url.split("?")[0]);
    console.log("    body: " + body.slice(0, 600).replace(/\s+/g, " "));
  }
  return res;
};

async function tryClient({ libName, wireName, version }) {
  console.log(
    "\n========== " +
      libName +
      " -> wire " +
      wireName +
      " v" +
      version +
      " ==========",
  );

  const innertube = await Innertube.create({
    cache: new UniversalCache(true, CACHE_DIR),
    client_type: libName,
  });

  innertube.session.on("auth-pending", (data) => {
    console.log("\n  Open: " + data.verification_url);
    console.log("  Code: " + data.user_code + "\n");
  });
  innertube.session.on("update-credentials", async () => {
    await innertube.session.oauth.cacheCredentials();
  });

  await innertube.session.signIn();

  // Patch the broken enum mapping in v13.4.0
  innertube.session.context.client.clientName = wireName;
  innertube.session.context.client.clientVersion = version;

  console.log(
    "patched context: client_name=" +
      innertube.session.context.client.clientName +
      " client_version=" +
      innertube.session.context.client.clientVersion +
      " logged_in=" +
      innertube.session.logged_in,
  );

  console.log("\n[baseline] getInfo('dQw4w9WgXcQ')");
  try {
    const info = await innertube.getInfo("dQw4w9WgXcQ");
    console.log("    ok. title=" + info.basic_info?.title);
  } catch (err) {
    console.log("    FAILED");
    console.log(dumpError(err));
  }

  console.log("\n[1] getPlaylist('WL')");
  try {
    const wl = await innertube.getPlaylist("WL");
    const items = wl.videos ?? wl.items ?? [];
    console.log(
      "    ok. items=" + items.length + " title=" + (wl.info?.title ?? "?"),
    );
    items.slice(0, 3).forEach((v, i) => {
      const title =
        typeof v.title?.toString === "function" ? v.title.toString() : v.title;
      console.log(
        "      " + (i + 1) + ". " + (v.id ?? "?") + "  " + (title ?? ""),
      );
    });
  } catch (err) {
    console.log("    FAILED");
    console.log(dumpError(err));
  }

  console.log("\n[2] getLibrary()");
  try {
    const library = await innertube.getLibrary();
    console.log("    ok. keys=" + Object.keys(library ?? {}).join(","));
    const wl = library.watch_later;
    if (wl) {
      const items = wl.contents ?? wl.videos ?? wl.items ?? [];
      console.log("    watch_later items=" + items.length);
    } else {
      console.log("    library.watch_later is " + typeof wl);
    }
  } catch (err) {
    console.log("    FAILED");
    console.log(dumpError(err));
  }
}

async function main() {
  await fs.mkdir(CACHE_DIR, { recursive: true });
  for (const cfg of TV_CLIENTS) {
    try {
      await tryClient(cfg);
    } catch (err) {
      console.log(
        cfg.libName +
          " failed entirely: " +
          (err instanceof Error ? err.message : String(err)),
      );
    }
  }
  console.log("\nDone. Cached credentials at " + CACHE_DIR);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
