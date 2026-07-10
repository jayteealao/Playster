import type { DirectMediaKind } from "../../direct-media.js";
import { normalizeTranscriptText } from "../normalize.js";
import type { TranscriptionConfig } from "../transcription-config.js";
import type { ProviderContext, ProviderFetchOptions, ProviderResult } from "../types.js";
import {
  buildMissingTranscriptionProviderResult,
  resolveTranscriptProviderCapabilities,
} from "./transcription-capability.js";

export async function fetchTwitterMediaTranscript({
  context,
  options,
  transcription,
  attemptedProviders,
  notes,
  mediaKindHint,
}: {
  context: ProviderContext;
  options: ProviderFetchOptions;
  transcription: TranscriptionConfig;
  attemptedProviders: ProviderResult["attemptedProviders"];
  notes: string[];
  mediaKindHint: DirectMediaKind | null;
}): Promise<ProviderResult> {
  if (!options.ytDlpPath) {
    return {
      text: null,
      source: null,
      attemptedProviders,
      metadata: { provider: "generic", kind: "twitter", reason: "missing_yt_dlp" },
      notes: "yt-dlp is not configured (set YT_DLP_PATH or ensure yt-dlp is on PATH)",
    };
  }

  const transcriptionCapabilities = await resolveTranscriptProviderCapabilities({
    transcription,
    ytDlpPath: options.ytDlpPath,
  });
  if (!transcriptionCapabilities.canTranscribe) {
    return buildMissingTranscriptionProviderResult({
      attemptedProviders,
      metadata: { provider: "generic", kind: "twitter", reason: "missing_transcription_keys" },
    });
  }

  attemptedProviders.push("yt-dlp");

  const resolved = options.resolveTwitterCookies
    ? await options.resolveTwitterCookies({ url: context.url })
    : null;
  if (resolved?.warnings?.length) notes.push(...resolved.warnings);

  const extraArgs: string[] = [];
  if (resolved?.cookiesFromBrowser) {
    extraArgs.push("--cookies-from-browser", resolved.cookiesFromBrowser);
    if (resolved.source) notes.push(`Using X cookies from ${resolved.source}`);
  }

  const mod = await import("./youtube/yt-dlp.js");
  const ytdlpResult = await mod.fetchTranscriptWithYtDlp({
    ytDlpPath: options.ytDlpPath,
    transcription,
    mediaCache: options.mediaCache ?? null,
    url: context.url,
    onProgress: options.onProgress ?? null,
    service: "generic",
    extraArgs: extraArgs.length > 0 ? extraArgs : undefined,
    mediaKind: mediaKindHint,
  });
  if (ytdlpResult.notes.length > 0) notes.push(...ytdlpResult.notes);

  if (ytdlpResult.text) {
    return {
      text: normalizeTranscriptText(ytdlpResult.text),
      source: "yt-dlp",
      attemptedProviders,
      metadata: {
        provider: "generic",
        kind: "twitter",
        transcriptionProvider: ytdlpResult.provider,
        cookieSource: resolved?.source ?? null,
      },
      notes: notes.length > 0 ? notes.join("; ") : null,
    };
  }

  if (ytdlpResult.error) {
    notes.push(`yt-dlp transcription failed: ${ytdlpResult.error.message}`);
  }

  return {
    text: null,
    source: null,
    attemptedProviders,
    metadata: {
      provider: "generic",
      kind: "twitter",
      reason: ytdlpResult.error ? "yt_dlp_failed" : "no_transcript",
      transcriptionProvider: ytdlpResult.provider,
    },
    notes: notes.length > 0 ? notes.join("; ") : null,
  };
}
