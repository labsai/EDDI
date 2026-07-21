/* ──────────────────────────────────────────────
   HTTP core — auth and error surfacing
   ────────────────────────────────────────────── */

import { describe, it, expect, afterEach, beforeEach } from "vitest";
import { request, setAuthToken, setBaseUrl, ApiError } from "./http";
import { captureFetch, mockFetchResponse } from "@/test-utils/sse";

const originalFetch = globalThis.fetch;

beforeEach(() => {
  setBaseUrl("");
  setAuthToken(null);
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  setAuthToken(null);
});

describe("auth token", () => {
  it("sends no Authorization header by default", async () => {
    const { calls } = captureFetch(200, "{}");

    await request("/agents/x", undefined, "ctx");

    const headers = new Headers(calls[0].init?.headers);
    expect(headers.has("Authorization")).toBe(false);
  });

  it("attaches a Bearer token once one is configured", async () => {
    // Conversation ownership is enforced server-side; an anonymous client can
    // be locked out of the conversation it just started.
    setAuthToken("abc123");
    const { calls } = captureFetch(200, "{}");

    await request("/agents/x", undefined, "ctx");

    const headers = new Headers(calls[0].init?.headers);
    expect(headers.get("Authorization")).toBe("Bearer abc123");
  });

  it("preserves caller-supplied headers alongside the token", async () => {
    setAuthToken("abc123");
    const { calls } = captureFetch(200, "{}");

    await request(
      "/agents/x",
      { method: "POST", headers: { "Content-Type": "text/plain" } },
      "ctx",
    );

    const headers = new Headers(calls[0].init?.headers);
    expect(headers.get("Content-Type")).toBe("text/plain");
    expect(headers.get("Authorization")).toBe("Bearer abc123");
  });

  it("stops sending the token once it is cleared", async () => {
    setAuthToken("abc123");
    setAuthToken(null);
    const { calls } = captureFetch(200, "{}");

    await request("/agents/x", undefined, "ctx");

    expect(new Headers(calls[0].init?.headers).has("Authorization")).toBe(false);
  });
});

describe("ApiError", () => {
  it("carries the status and the server's body", async () => {
    mockFetchResponse(409, "a reviewer must resolve the pending approval");

    const err = await request("/agents/x", undefined, "Send failed").catch(
      (e) => e,
    );

    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.body).toBe("a reviewer must resolve the pending approval");
  });

  it("mentions the calling context in its message", async () => {
    mockFetchResponse(404, "");

    const err = await request("/agents/x", undefined, "Send failed").catch(
      (e) => e,
    );

    expect(err.message).toContain("Send failed");
    expect(err.message).toContain("404");
  });
});
