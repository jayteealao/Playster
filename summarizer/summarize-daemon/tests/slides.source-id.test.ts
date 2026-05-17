import { createHash } from "node:crypto";
import { promises as fs } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { describe, expect, it } from "vitest";
import { resolveSlideSourceFromUrl } from "../src/slides/index.js";
import { buildDirectSourceId, buildYoutubeSourceId } from "../src/slides/source-id.js";

describe("resolveSlideSourceFromUrl", () => {
  it("prefixes YouTube ids for slide folders", () => {
    const source = resolveSlideSourceFromUrl("https://www.youtube.com/watch?v=abc123def45");
    expect(source?.sourceId).toBe("youtube-abc123def45");
  });

  it("builds direct source ids from host + basename + hash", () => {
    const url = "https://cdn.example.com/videos/Hello%20World.mp4";
    const hash = createHash("sha1").update(url).digest("hex").slice(0, 8);
    const source = resolveSlideSourceFromUrl(url);
    expect(source?.sourceId).toBe(`cdn-example-com-hello-20world-${hash}`);
  });

  it("handles invalid urls and direct helper branches", () => {
    expect(buildYoutubeSourceId("abc123")).toBe("youtube-abc123");
    expect(buildDirectSourceId("notaurl")).toMatch(/^video-[0-9a-f]{8}$/);
    expect(buildDirectSourceId("https://youtu.be/abc123")).toMatch(/^youtube-abc123-[0-9a-f]{8}$/);
    expect(resolveSlideSourceFromUrl("https://cdn.example.com/audio.mp3")).toBeNull();
  });

  it("normalizes local video files to file URLs and versions the source id by mtime", async () => {
    const filePath = path.join(tmpdir(), `summarize-slides-source-${Date.now().toString()}.webm`);
    await fs.writeFile(filePath, "video");

    try {
      const stat = await fs.stat(filePath);
      const fileUrl = pathToFileURL(filePath).href;
      const basename = path
        .basename(filePath, ".webm")
        .replace(/[^a-z0-9]+/gi, "-")
        .toLowerCase();
      const hash = createHash("sha1")
        .update(`${fileUrl}#mtime=${Math.round(stat.mtimeMs).toString()}`)
        .digest("hex")
        .slice(0, 8);
      const source = resolveSlideSourceFromUrl(filePath);

      expect(source).toEqual({
        kind: "direct",
        url: fileUrl,
        sourceId: `${basename}-${hash}`,
      });
    } finally {
      await fs.rm(filePath, { force: true });
    }
  });
});
