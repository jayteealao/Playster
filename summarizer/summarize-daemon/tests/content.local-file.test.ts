import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { describe, expect, it } from "vitest";
import {
  isLocalFileUrl,
  resolveLocalDirectMediaSource,
  resolveLocalFileMtime,
  resolveLocalFileReference,
} from "../packages/core/src/content/local-file.js";

describe("content/local-file", () => {
  it("resolves local file references from paths and file urls", async () => {
    const root = await mkdtemp(join(tmpdir(), "summarize-local-file-"));
    const filePath = join(root, "deck.webm");
    await writeFile(filePath, new Uint8Array([1, 2, 3]));

    try {
      const fromPath = resolveLocalFileReference(filePath);
      const fromUrl = resolveLocalFileReference(pathToFileURL(filePath).href);

      expect(fromPath?.filePath).toBe(filePath);
      expect(fromPath?.fileUrl).toBe(pathToFileURL(filePath).href);
      expect(fromPath?.mtimeMs).toBeGreaterThan(0);
      expect(fromUrl).toEqual(fromPath);
      expect(isLocalFileUrl(pathToFileURL(filePath).href)).toBe(true);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it("resolves local direct media with media kind/type hints", async () => {
    const root = await mkdtemp(join(tmpdir(), "summarize-local-media-"));
    const filePath = join(root, "audio-only.webm");
    await writeFile(filePath, new Uint8Array([1, 2, 3]));

    try {
      const inferred = resolveLocalDirectMediaSource(filePath);
      const hinted = resolveLocalDirectMediaSource(pathToFileURL(filePath).href, "audio");

      expect(inferred?.mediaKind).toBe("video");
      expect(inferred?.mediaType).toBe("video/webm");
      expect(hinted?.mediaKind).toBe("audio");
      expect(hinted?.mediaType).toBe("audio/webm");
      expect(resolveLocalFileMtime(pathToFileURL(filePath).href)).toBeGreaterThan(0);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it("returns null for non-media files", async () => {
    const root = await mkdtemp(join(tmpdir(), "summarize-local-text-"));
    const filePath = join(root, "notes.txt");
    await writeFile(filePath, "hello");

    try {
      expect(resolveLocalDirectMediaSource(filePath)).toBeNull();
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
});
