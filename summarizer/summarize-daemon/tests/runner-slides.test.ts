import { describe, expect, it } from "vitest";
import { resolveRunnerSlidesSettings } from "../src/run/runner-slides.js";

describe("resolveRunnerSlidesSettings", () => {
  it("allows slides for local video files", () => {
    const settings = resolveRunnerSlidesSettings({
      normalizedArgv: ["--slides"],
      programOpts: { slides: true },
      config: null,
      inputTarget: { kind: "file", filePath: "/tmp/video.webm" },
    });

    expect(settings?.enabled).toBe(true);
  });

  it("rejects slides for stdin", () => {
    expect(() =>
      resolveRunnerSlidesSettings({
        normalizedArgv: ["--slides"],
        programOpts: { slides: true },
        config: null,
        inputTarget: { kind: "stdin" },
      }),
    ).toThrow("--slides is only supported for URLs or local video files");
  });

  it("rejects direct audio URLs", () => {
    expect(() =>
      resolveRunnerSlidesSettings({
        normalizedArgv: ["--slides"],
        programOpts: { slides: true },
        config: null,
        inputTarget: { kind: "url", url: "https://cdn.example.com/audio.mp3" },
      }),
    ).toThrow("--slides is only supported for video URLs or local video files");
  });
});
