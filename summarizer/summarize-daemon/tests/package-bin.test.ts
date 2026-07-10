import { spawnSync } from "node:child_process";
import { statSync } from "node:fs";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import process from "node:process";
import { describe, expect, it } from "vitest";

describe("package bin wrappers", () => {
  it("writes an executable dist wrapper that runs the ESM CLI entrypoint", async () => {
    const root = await mkdtemp(join(tmpdir(), "summarize-bin-"));
    try {
      const distDir = join(root, "dist");
      await mkdir(join(distDir, "esm"), { recursive: true });
      await writeFile(
        join(distDir, "esm", "cli.js"),
        "if (process.argv.includes('--version')) process.stdout.write('0.0.0-test\\n');\n",
        "utf8",
      );

      const buildCli = (await import("../scripts/build-cli.mjs")) as {
        writeCliWrapper: (distDir: string) => Promise<string>;
      };
      const wrapperPath = await buildCli.writeCliWrapper(distDir);
      const wrapper = await readFile(wrapperPath, "utf8");
      const mode = statSync(wrapperPath).mode;

      expect(wrapper.startsWith("#!/usr/bin/env node\n")).toBe(true);
      if (process.platform !== "win32") expect(mode & 0o111).not.toBe(0);

      const result = spawnSync(
        process.platform === "win32" ? process.execPath : wrapperPath,
        process.platform === "win32" ? [wrapperPath, "--version"] : ["--version"],
        {
          encoding: "utf8",
          env: { ...process.env, NO_COLOR: "1" },
        },
      );

      expect(result.status).toBe(0);
      expect(result.stderr).toBe("");
      expect(result.stdout.trim()).toBe("0.0.0-test");
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it("keeps the generated package entry executable without a node prefix on POSIX", async () => {
    if (process.platform === "win32") return;

    const root = await mkdtemp(join(tmpdir(), "summarize-bin-direct-"));
    try {
      const distDir = join(root, "dist");
      await mkdir(join(distDir, "esm"), { recursive: true });
      await writeFile(
        join(distDir, "esm", "cli.js"),
        "if (process.argv.includes('--version')) process.stdout.write('0.0.0-direct\\n');\n",
        "utf8",
      );

      const buildCli = (await import("../scripts/build-cli.mjs")) as {
        writeCliWrapper: (distDir: string) => Promise<string>;
      };
      const wrapperPath = await buildCli.writeCliWrapper(distDir);
      const result = spawnSync(wrapperPath, ["--version"], {
        encoding: "utf8",
        env: { ...process.env, NO_COLOR: "1" },
      });

      expect(result.status).toBe(0);
      expect(result.stderr).toBe("");
      expect(result.stdout.trim()).toBe("0.0.0-direct");
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it("keeps the summarizer alias pointed at the same dist wrapper", async () => {
    const pkg = JSON.parse(await readFile("package.json", "utf8")) as {
      bin?: Record<string, string>;
    };

    expect(pkg.bin?.summarize).toBe("./dist/cli.js");
    expect(pkg.bin?.summarizer).toBe("./dist/cli.js");
  });
});
