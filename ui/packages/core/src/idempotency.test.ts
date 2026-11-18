/* ──────────────────────────────────────────────
   The Idempotency-Key a turn carries
   ────────────────────────────────────────────── */

import { afterEach, describe, expect, it } from "vitest";
import { newIdempotencyKey, sendManagedAgentMessage, sendMessage, sendMessageStreaming } from "./conversation";
import { setBaseUrl } from "./http";
import { captureFetch } from "./test-utils/sse";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
  setBaseUrl("");
});

const header = (init: RequestInit | undefined) => new Headers(init?.headers).get("Idempotency-Key");

describe("Idempotency-Key", () => {
  it("is sent on a say, and only when one is given", async () => {
    const { calls } = captureFetch(200, "{}", { "Content-Type": "application/json" });
    await sendMessage("c", "hi", { idempotencyKey: "k-1" });
    await sendMessage("c", "hi");
    expect(header(calls[0]?.init)).toBe("k-1");
    expect(header(calls[1]?.init)).toBeNull();
  });

  it("is sent on a streamed say and on a managed say", async () => {
    const { calls } = captureFetch(200, "{}", { "Content-Type": "application/json" });
    const gen = sendMessageStreaming("c", "hi", { idempotencyKey: "k-2" });
    await gen.next().catch(() => undefined);
    await sendManagedAgentMessage("i", "u", "hi", undefined, undefined, "k-3");
    expect(header(calls[0]?.init)).toBe("k-2");
    expect(header(calls[1]?.init)).toBe("k-3");
  });

  it("is generated as 1 to 128 printable ASCII characters, never repeating", () => {
    const a = newIdempotencyKey();
    expect(a).not.toBe(newIdempotencyKey());
    expect(a).toMatch(/^[\x21-\x7e]{1,128}$/);
  });
});
