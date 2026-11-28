import { describe, it, expect, vi, afterEach } from "vitest";
import { copyToClipboard } from "@eddi/app-kit/lib/copy-to-clipboard";

afterEach(() => {
  vi.restoreAllMocks();
  Object.defineProperty(navigator, "clipboard", { value: undefined, configurable: true });
});

function setClipboard(writeText: ((t: string) => Promise<void>) | undefined) {
  Object.defineProperty(navigator, "clipboard", {
    value: writeText ? { writeText } : undefined,
    configurable: true,
  });
}

describe("copyToClipboard", () => {
  it("resolves true only after the write has completed", async () => {
    let finished = false;
    setClipboard(async () => {
      await Promise.resolve();
      finished = true;
    });
    const ok = await copyToClipboard("hello");
    expect(ok).toBe(true);
    expect(finished).toBe(true);
  });

  it("falls back to the legacy copy when the async API refuses, and reports its outcome", async () => {
    setClipboard(() => Promise.reject(new Error("NotAllowedError")));
    document.execCommand = vi.fn().mockReturnValue(true);
    expect(await copyToClipboard("x")).toBe(true);
    expect(document.execCommand).toHaveBeenCalledWith("copy");
  });

  it("reports failure honestly when neither route works", async () => {
    setClipboard(() => Promise.reject(new Error("NotAllowedError")));
    document.execCommand = vi.fn().mockReturnValue(false);
    expect(await copyToClipboard("x")).toBe(false);
  });

  it("never throws, even without a clipboard API", async () => {
    setClipboard(undefined);
    document.execCommand = vi.fn().mockImplementation(() => {
      throw new Error("blocked");
    });
    await expect(copyToClipboard("x")).resolves.toBe(false);
  });
});
