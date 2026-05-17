import { describe, expect, it, vi } from "vitest";
import { createOptionsSaveRuntime } from "../apps/chrome-extension/src/entrypoints/options/persistence.js";

describe("options persistence", () => {
  it("debounces autosave and flushes one queued rerun", async () => {
    vi.useFakeTimers();
    const persist = vi.fn(async () => {});
    const setStatus = vi.fn();
    const flashStatus = vi.fn();

    const runtime = createOptionsSaveRuntime({
      isInitializing: () => false,
      setStatus,
      flashStatus,
      persist,
    });

    runtime.scheduleAutoSave(200);
    runtime.scheduleAutoSave(200);
    await vi.advanceTimersByTimeAsync(199);
    expect(persist).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(persist).toHaveBeenCalledTimes(1);

    const blockedPersist = vi.fn(() => new Promise<void>((resolve) => setTimeout(resolve, 10)));
    const queuedRuntime = createOptionsSaveRuntime({
      isInitializing: () => false,
      setStatus,
      flashStatus,
      persist: blockedPersist,
    });

    const first = queuedRuntime.saveNow();
    const second = queuedRuntime.saveNow();
    await second;
    await vi.advanceTimersByTimeAsync(20);
    await first;

    expect(blockedPersist).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });
});
