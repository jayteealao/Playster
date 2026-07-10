import { chmod, mkdir, realpath, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "..");

const distDir = path.join(repoRoot, "dist");

// ESM binary wrapper.
// Avoid bundling: CJS deps (e.g. commander) can trigger esbuild's dynamic-require shim in ESM output.
export const CLI_WRAPPER = `#!/usr/bin/env node
await import('./esm/cli.js')
`;

export async function writeCliWrapper(targetDistDir = distDir) {
  await mkdir(targetDistDir, { recursive: true });
  const wrapperPath = path.join(targetDistDir, "cli.js");
  await writeFile(wrapperPath, CLI_WRAPPER, "utf8");
  await chmod(wrapperPath, 0o755);
  return wrapperPath;
}

const currentFile = await realpath(__filename).catch(() => __filename);
const entryFile = process.argv[1]
  ? await realpath(process.argv[1]).catch(() => path.resolve(process.argv[1]))
  : null;

if (entryFile === currentFile) {
  await writeCliWrapper();
}
