import { beforeEach, describe, expect, it, vi } from "vitest";
import { handlePanelSlidesContextRequest } from "../apps/chrome-extension/src/entrypoints/background/panel-slides-context.js";

describe("chrome panel slides context", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  it("returns an error when there is no active tab url", async () => {
    const send = vi.fn();

    await handlePanelSlidesContextRequest({
      session: { windowId: 1 } as never,
      requestId: "slides-1",
      requestedUrl: null,
      loadSettings: vi.fn(async () => ({ token: "", extendedLogging: false })) as never,
      getActiveTab: vi.fn(async () => null) as never,
      canSummarizeUrl: () => false,
      panelSessionStore: {
        getCachedExtract: () => null,
        setCachedExtract: vi.fn(),
      },
      urlsMatch: () => false,
      send,
      fetchImpl: vi.fn() as never,
      resolveLogLevel: () => "verbose",
    });

    expect(send).toHaveBeenCalledWith({
      type: "slides:context",
      requestId: "slides-1",
      ok: false,
      error: "No active tab for slides.",
    });
  });

  it("fetches timed transcript text and stores it in the tab cache", async () => {
    const send = vi.fn();
    const setCachedExtract = vi.fn();
    const fetchImpl = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({
        ok: true,
        extracted: { transcriptTimedText: "0:01 intro" },
      }),
    })) as never;

    await handlePanelSlidesContextRequest({
      session: { windowId: 7 } as never,
      requestId: "slides-2",
      requestedUrl: "https://example.com/video",
      loadSettings: vi.fn(async () => ({ token: "secret", extendedLogging: false })) as never,
      getActiveTab: vi.fn(async () => ({
        id: 4,
        url: "https://example.com/video",
        title: "Video",
      })) as never,
      canSummarizeUrl: () => true,
      panelSessionStore: {
        getCachedExtract: () => null,
        setCachedExtract,
      },
      urlsMatch: () => true,
      send,
      fetchImpl,
      resolveLogLevel: () => "verbose",
    });

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(setCachedExtract).toHaveBeenCalledWith(
      4,
      expect.objectContaining({
        url: "https://example.com/video",
        transcriptTimedText: "0:01 intro",
      }),
    );
    expect(send).toHaveBeenCalledWith({
      type: "slides:context",
      requestId: "slides-2",
      ok: true,
      transcriptTimedText: "0:01 intro",
    });
  });
});
