import { describe, it, expect, vi, beforeEach } from "vitest";
import type Database from "better-sqlite3";

vi.mock("../src/security/ssrf.js", () => ({
  validateUrl: vi.fn(),
}));

vi.mock("rss-parser", () => {
  const Parser = vi.fn();
  return { default: Parser };
});

vi.mock("../src/db/jobs.js", () => ({
  updateJobStatus: vi.fn(),
}));

vi.mock("../src/runners/url-runner.js", () => ({
  runUrlJob: vi.fn(),
}));

import { runRssJob } from "../src/runners/rss-runner.js";
import { validateUrl } from "../src/security/ssrf.js";
import { updateJobStatus } from "../src/db/jobs.js";
import { runUrlJob } from "../src/runners/url-runner.js";
import Parser from "rss-parser";
import { EventStore } from "../src/events/event-store.js";
import type { Job } from "../src/db/jobs.js";
import type { Config } from "../src/config.js";

function makeJob(overrides: Partial<Job> = {}): Job {
  const now = new Date().toISOString();
  return {
    id: "test-job-id",
    type: "rss",
    status: "queued",
    source: "https://example.com/feed.xml",
    options: null,
    result: null,
    error: null,
    daemon_job_id: null,
    client_id: null,
    metadata: null,
    created_at: now,
    updated_at: now,
    completed_at: null,
    ...overrides,
  };
}

function mockParserItems(items: Array<{ link?: string; title?: string; guid?: string }>) {
  const parserInstance = {
    parseURL: vi.fn().mockResolvedValue({ items }),
  };
  vi.mocked(Parser).mockImplementation(() => parserInstance as unknown as Parser);
}

describe("rss-runner SSRF on item URL", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("blocks delegation when the resolved item URL points to a private IP", async () => {
    vi.mocked(validateUrl)
      .mockResolvedValueOnce({ safe: true })
      .mockResolvedValueOnce({
        safe: false,
        error: "Blocked private/reserved IP: 127.0.0.1",
      });

    mockParserItems([
      { link: "http://127.0.0.1/blocked", title: "Bad item", guid: "g1" },
    ]);

    const eventStore = new EventStore();
    const job = makeJob();

    await runRssJob(job, {} as Config, eventStore, {} as Database.Database);

    expect(vi.mocked(validateUrl)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(validateUrl).mock.calls[0][0]).toBe("https://example.com/feed.xml");
    expect(vi.mocked(validateUrl).mock.calls[1][0]).toBe("http://127.0.0.1/blocked");

    expect(vi.mocked(updateJobStatus)).toHaveBeenCalledWith(
      job.id,
      "failed",
      expect.objectContaining({
        error: expect.objectContaining({
          message: expect.stringContaining("Blocked RSS item URL"),
        }),
      }),
    );

    expect(vi.mocked(runUrlJob)).not.toHaveBeenCalled();
  });

  it("delegates to url-runner when item URL is safe", async () => {
    vi.mocked(validateUrl).mockResolvedValue({ safe: true });
    vi.mocked(runUrlJob).mockResolvedValue(undefined);

    mockParserItems([
      {
        link: "https://safe.example.com/article",
        title: "Good item",
        guid: "g2",
      },
    ]);

    const eventStore = new EventStore();
    const job = makeJob({ id: "j2" });

    await runRssJob(job, {} as Config, eventStore, {} as Database.Database);

    expect(vi.mocked(validateUrl)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(runUrlJob)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(runUrlJob)).toHaveBeenCalledWith(
      expect.objectContaining({
        source: "https://safe.example.com/article",
        type: "url",
      }),
      expect.anything(),
      expect.anything(),
      expect.anything(),
    );
  });
});
