import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Writable } from "node:stream";
import { describe, expect, it, vi } from "vitest";
import { runCli } from "../src/run.js";

function collectStream() {
  let text = "";
  const stream = new Writable({
    write(chunk, _encoding, callback) {
      text += chunk.toString();
      callback();
    },
  });
  return { stream, getText: () => text };
}

const mocks = vi.hoisted(() => ({
  generateTextWithModelId: vi.fn(async () => ({
    text: "fallback summary",
    canonicalModelId: "openai/gpt-5.2",
    provider: "openai",
    usage: { promptTokens: 1, completionTokens: 2, totalTokens: 3 },
  })),
  streamTextWithModelId: vi.fn(async () => ({
    textStream: {
      async *[Symbol.asyncIterator]() {
        throw new Error("LLM request timed out");
      },
    },
    canonicalModelId: "openai/gpt-5.2",
    provider: "openai",
    usage: Promise.resolve({ promptTokens: 1, completionTokens: 0, totalTokens: 1 }),
    lastError: () => null,
  })),
}));

vi.mock("../src/llm/generate-text.js", () => mocks);

describe("cli stream fallback", () => {
  it("falls back when the stream iterator times out before yielding text", async () => {
    const root = mkdtempSync(join(tmpdir(), "summarize-stream-fallback-"));
    const filePath = join(root, "input.txt");
    writeFileSync(filePath, "hello world", "utf8");
    const stdout = collectStream();
    const stderr = collectStream();

    await runCli(["--model", "openai/gpt-5.2", "--stream", "on", "--plain", filePath], {
      env: { HOME: root, OPENAI_API_KEY: "test" },
      fetch: async () => {
        throw new Error("unexpected fetch");
      },
      stdout: stdout.stream,
      stderr: stderr.stream,
    });

    expect(stdout.getText()).toContain("fallback summary");
    expect(mocks.streamTextWithModelId).toHaveBeenCalledTimes(1);
    expect(mocks.generateTextWithModelId).toHaveBeenCalledTimes(1);
  });
});
