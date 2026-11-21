/* ──────────────────────────────────────────────
   The Idempotency-Key a turn carries
   ────────────────────────────────────────────── */

import { afterEach, describe, expect, it } from "vitest";
import { createConversationApi, newIdempotencyKey, sendManagedAgentMessage, sendMessage, sendMessageStreaming } from "./conversation";
import { HttpClient, isSameOrigin, setBaseUrl } from "./http";
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

describe("the key goes only to the page's own origin unless opted in (M1b)", () => {
  const sees = async (http: HttpClient) => {
    const seen: Array<string | null> = [];
    http.setBaseUrl(http.getBaseUrl());
    globalThis.fetch = (async (_url: string, init?: RequestInit) => {
      seen.push(new Headers(init?.headers).get("Idempotency-Key"));
      return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
    }) as typeof fetch;
    await createConversationApi(http).sendMessage("c", "hi", { idempotencyKey: "k" });
    return seen[0];
  };

  it("classifies the base: empty, relative and the page's origin are same-origin", () => {
    expect(isSameOrigin("")).toBe(true);
    expect(isSameOrigin("/api")).toBe(true);
    expect(isSameOrigin(window.location.origin)).toBe(true);
    expect(isSameOrigin("https://other.example.test")).toBe(false);
    expect(isSameOrigin("//other.example.test")).toBe(false);
  });

  it("same origin: sent", async () => {
    expect(await sees(new HttpClient({ baseUrl: "" }))).toBe("k");
    expect(await sees(new HttpClient({ baseUrl: window.location.origin }))).toBe("k");
  });

  it("another origin: not sent, so a CORS list without the header keeps working as before", async () => {
    expect(await sees(new HttpClient({ baseUrl: "https://other.example.test" }))).toBeNull();
  });

  it("another origin with the explicit opt-in: sent", async () => {
    expect(await sees(new HttpClient({ baseUrl: "https://other.example.test", idempotencyKeys: true }))).toBe("k");
    const http = new HttpClient({ baseUrl: "https://other.example.test" });
    http.setIdempotencyKeys(true);
    expect(await sees(http)).toBe("k");
  });
});
