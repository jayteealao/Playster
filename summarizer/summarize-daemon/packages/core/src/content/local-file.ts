import { statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  inferDirectMediaKind,
  resolveDirectMediaExtension,
  resolveDirectMediaType,
  type DirectMediaKind,
} from "./direct-media.js";

export type LocalFileReference = {
  filePath: string;
  fileUrl: string;
  filename: string;
  mtimeMs: number;
};

export type LocalDirectMediaSource = LocalFileReference & {
  extension: string | null;
  mediaKind: DirectMediaKind;
  mediaType: string;
};

export function isLocalFileUrl(value: string): boolean {
  try {
    return new URL(value).protocol === "file:";
  } catch {
    return false;
  }
}

export function resolveLocalFileReference(value: string): LocalFileReference | null {
  try {
    const filePath = isLocalFileUrl(value)
      ? fileURLToPath(stripFileUrlSearchAndHash(new URL(value)))
      : path.resolve(value);
    const stat = statSync(filePath);
    if (!stat.isFile()) return null;
    return {
      filePath,
      fileUrl: pathToFileURL(filePath).href,
      filename: path.basename(filePath),
      mtimeMs: stat.mtimeMs ?? 0,
    };
  } catch {
    return null;
  }
}

export function resolveLocalFileMtime(value: string): number | null {
  return resolveLocalFileReference(value)?.mtimeMs ?? null;
}

export function resolveLocalDirectMediaSource(
  value: string,
  kindHint: DirectMediaKind | null = null,
): LocalDirectMediaSource | null {
  const file = resolveLocalFileReference(value);
  if (!file) return null;
  const mediaKind =
    kindHint ?? inferDirectMediaKind(file.filePath) ?? inferDirectMediaKind(file.fileUrl);
  if (!mediaKind) return null;
  const mediaType =
    resolveDirectMediaType(file.filePath, mediaKind) ??
    resolveDirectMediaType(file.fileUrl, mediaKind);
  if (!mediaType) return null;
  return {
    ...file,
    extension: resolveDirectMediaExtension(file.filePath),
    mediaKind,
    mediaType,
  };
}

function stripFileUrlSearchAndHash(url: URL): URL {
  url.search = "";
  url.hash = "";
  return url;
}
