import { spawnTracked } from "../processes.js";
import { runWithConcurrency } from "./process.js";
import type { SlideImage } from "./types.js";

const TESSERACT_TIMEOUT_MS = 120_000;

export function cleanOcrText(text: string): string {
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length >= 2)
    .filter((line) => !(line.length > 20 && !line.includes(" ")))
    .filter((line) => /[a-z0-9]/i.test(line));
  return lines.join("\n");
}

export function estimateOcrConfidence(text: string): number {
  if (!text) return 0;
  const total = text.length;
  if (total === 0) return 0;
  const alnum = Array.from(text).filter((char) => /[a-z0-9]/i.test(char)).length;
  return Math.min(1, alnum / total);
}

export async function runTesseract(tesseractPath: string, imagePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const { proc, handle } = spawnTracked(
      tesseractPath,
      [imagePath, "stdout", "--oem", "3", "--psm", "6"],
      {
        stdio: ["ignore", "pipe", "pipe"],
        label: "tesseract",
        kind: "tesseract",
        captureOutput: false,
      },
    );
    let stdout = "";
    let stderr = "";
    let stderrBuffer = "";

    const timeout = setTimeout(() => {
      proc.kill("SIGKILL");
      reject(new Error("tesseract timed out"));
    }, TESSERACT_TIMEOUT_MS);

    if (proc.stdout) {
      proc.stdout.setEncoding("utf8");
      proc.stdout.on("data", (chunk: string) => {
        stdout += chunk;
      });
    }

    if (proc.stderr) {
      proc.stderr.setEncoding("utf8");
      proc.stderr.on("data", (chunk: string) => {
        if (stderr.length < 8192) stderr += chunk;
        stderrBuffer += chunk;
        const lines = stderrBuffer.split(/\r?\n/);
        stderrBuffer = lines.pop() ?? "";
        for (const line of lines) {
          if (line) handle?.appendOutput("stderr", line);
        }
      });
    }

    proc.on("error", (error) => {
      clearTimeout(timeout);
      reject(error);
    });

    proc.on("close", (code) => {
      clearTimeout(timeout);
      if (stderrBuffer.trim()) handle?.appendOutput("stderr", stderrBuffer.trim());
      if (code === 0) {
        resolve(stdout);
        return;
      }
      const suffix = stderr.trim() ? `: ${stderr.trim()}` : "";
      reject(new Error(`tesseract exited with code ${code}${suffix}`));
    });
  });
}

export async function runOcrOnSlides(
  slides: SlideImage[],
  tesseractPath: string,
  workers: number,
  onProgress?: ((completed: number, total: number) => void) | null,
): Promise<SlideImage[]> {
  const tasks = slides.map((slide) => async () => {
    try {
      const cleaned = cleanOcrText(await runTesseract(tesseractPath, slide.imagePath));
      return {
        ...slide,
        ocrText: cleaned,
        ocrConfidence: estimateOcrConfidence(cleaned),
      };
    } catch {
      return { ...slide, ocrText: "", ocrConfidence: 0 };
    }
  });
  const results = await runWithConcurrency(tasks, workers, onProgress ?? undefined);
  return results.sort((a, b) => a.index - b.index);
}
