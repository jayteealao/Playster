import { describe, it, expect, afterAll, beforeAll, vi } from "vitest";
import { buildApp, TEST_API_KEY, type TestContext } from "./setup.js";

// Mock dispatchJob so it doesn't run async work
vi.mock("../src/runners/index.js", () => ({
  dispatchJob: vi.fn(),
}));

describe("Upload job endpoints", () => {
  let ctx: TestContext;

  beforeAll(async () => {
    ctx = await buildApp();
  });

  afterAll(async () => {
    await ctx.cleanup();
  });

  it("POST /v1/jobs with multipart file returns 201", async () => {
    const boundary = "----TestBoundary123";
    const fileContent = "This is a test file content for summarization.";
    const body = [
      `--${boundary}`,
      'Content-Disposition: form-data; name="file"; filename="test.txt"',
      "Content-Type: text/plain",
      "",
      fileContent,
      `--${boundary}--`,
    ].join("\r\n");

    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    expect(res.statusCode).toBe(201);
    const json = res.json();
    expect(json.ok).toBe(true);
    expect(json.id).toBeDefined();
  });

  it("POST /v1/jobs multipart without file returns 400", async () => {
    const boundary = "----TestBoundary456";
    // Multipart body with no file field
    const body = [
      `--${boundary}`,
      'Content-Disposition: form-data; name="options"',
      "",
      '{"format":"markdown"}',
      `--${boundary}--`,
    ].join("\r\n");

    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    expect(res.statusCode).toBe(400);
  });
});
