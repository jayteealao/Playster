import fs from "node:fs";
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { describe, expect, it } from "vitest";
import {
  FALLBACK_VERSION,
  formatVersionLine,
  resolveGitSha,
  resolvePackageVersion,
} from "../src/version.js";

describe("resolvePackageVersion", () => {
  it("prefers SUMMARIZE_VERSION when set", () => {
    const previous = process.env.SUMMARIZE_VERSION;
    process.env.SUMMARIZE_VERSION = "9.9.9";
    try {
      expect(resolvePackageVersion()).toBe("9.9.9");
    } finally {
      if (previous === undefined) {
        delete process.env.SUMMARIZE_VERSION;
      } else {
        process.env.SUMMARIZE_VERSION = previous;
      }
    }
  });

  it("falls back when importMetaUrl is invalid", () => {
    const pkg = JSON.parse(fs.readFileSync("package.json", "utf8")) as { version: string };
    expect(resolvePackageVersion("not a url")).toBe(pkg.version);
  });

  it("keeps fallback version in sync with package.json", () => {
    const pkg = JSON.parse(fs.readFileSync("package.json", "utf8")) as { version: string };
    expect(FALLBACK_VERSION).toBe(pkg.version);
  });
});

describe("resolveGitSha", () => {
  it("does not escape an installed package root", () => {
    const root = fs.mkdtempSync(join(tmpdir(), "summarize-version-package-"));
    const appRoot = join(root, "app");
    const packageRoot = join(appRoot, "node_modules", "@steipete", "summarize");
    const modulePath = join(packageRoot, "dist", "esm", "run", "runner.js");

    try {
      mkdirSync(join(appRoot, ".git"), { recursive: true });
      mkdirSync(join(packageRoot, "dist", "esm", "run"), { recursive: true });
      writeFileSync(join(appRoot, ".git", "HEAD"), "1234567890abcdef1234567890abcdef12345678\n");
      writeFileSync(join(packageRoot, "package.json"), '{"version":"0.14.1"}\n');

      const moduleUrl = pathToFileURL(modulePath).href;
      expect(resolveGitSha(moduleUrl)).toBeNull();
      expect(formatVersionLine(moduleUrl)).toBe("0.14.1");
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  it("resolves refs from a git worktree common dir", () => {
    const root = fs.mkdtempSync(join(tmpdir(), "summarize-version-worktree-"));
    const packageRoot = join(root, "checkout");
    const gitDir = join(root, "repo", ".git", "worktrees", "checkout");
    const commonGitDir = join(root, "repo", ".git");
    const modulePath = join(packageRoot, "dist", "esm", "run", "runner.js");

    try {
      mkdirSync(join(packageRoot, "dist", "esm", "run"), { recursive: true });
      mkdirSync(join(gitDir), { recursive: true });
      mkdirSync(join(commonGitDir, "refs", "heads"), { recursive: true });
      writeFileSync(join(packageRoot, "package.json"), '{"version":"0.14.1"}\n');
      writeFileSync(join(packageRoot, ".git"), `gitdir: ${gitDir}\n`);
      writeFileSync(join(gitDir, "HEAD"), "ref: refs/heads/feature\n");
      writeFileSync(join(gitDir, "commondir"), "../..\n");
      writeFileSync(
        join(commonGitDir, "refs", "heads", "feature"),
        "abcdef1234567890abcdef1234567890abcdef12\n",
      );

      expect(resolveGitSha(pathToFileURL(modulePath).href)).toBe("abcdef12");
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});
