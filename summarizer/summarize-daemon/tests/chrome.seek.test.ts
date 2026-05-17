import { describe, expect, it, vi } from "vitest";
import { seekToSecondsInDocument } from "../apps/chrome-extension/src/lib/seek";

describe("seekToSecondsInDocument", () => {
  it("seeks and plays HTML media elements", () => {
    let currentTime = 0;
    const play = vi.fn().mockResolvedValue(undefined);
    const video = {
      get currentTime() {
        return currentTime;
      },
      set currentTime(value: number) {
        currentTime = value;
      },
      play,
    } as unknown as HTMLVideoElement;
    const doc = {
      querySelector: () => video,
      getElementById: () => null,
    } as unknown as Document;

    const result = seekToSecondsInDocument(doc, 42);

    expect(result).toEqual({ ok: true });
    expect(currentTime).toBe(42);
    expect(play).toHaveBeenCalled();
  });

  it("seeks and plays YouTube player when present", () => {
    const player = {} as HTMLElement & {
      seekTo?: (time: number, allowSeekAhead?: boolean) => void;
      playVideo?: () => void;
    };
    const seekTo = vi.fn();
    const playVideo = vi.fn();
    player.seekTo = seekTo;
    player.playVideo = playVideo;
    const doc = {
      querySelector: () => null,
      getElementById: () => player,
    } as unknown as Document;

    const result = seekToSecondsInDocument(doc, 12);

    expect(result).toEqual({ ok: true });
    expect(seekTo).toHaveBeenCalledWith(12, true);
    expect(playVideo).toHaveBeenCalled();
  });
});
