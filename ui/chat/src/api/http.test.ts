/* ──────────────────────────────────────────────
   HTTP core — auth and error surfacing
   ────────────────────────────────────────────── */

import { describe, it, expect, afterEach, beforeEach } from "vitest";
import {
  request,
  setAuthToken,
  setBaseUrl,
  ApiError,
  errorPayload,
  setUnauthorizedHandler,
} from "./http";
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

describe("401 handler", () => {
  afterEach(() => setUnauthorizedHandler(null));

  /** fetch answering 401 to `staleToken` (or no token) and 200 otherwise. */
  function fetchRejecting(staleToken: string | null) {
    const seen: Array<string | null> = [];
    globalThis.fetch = ((_url: string, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("Authorization");
      seen.push(auth);
      const ok = auth !== null && auth !== `Bearer ${staleToken}`;
      return Promise.resolve(new Response(ok ? "{}" : "", { status: ok ? 200 : 401 }));
    }) as typeof fetch;
    return seen;
  }

  it("repeats a 401 once with the token the handler installed", async () => {
    setAuthToken("expired");
    const seen = fetchRejecting("expired");
    const rejected: Array<string | null> = [];
    setUnauthorizedHandler(async (token) => {
      rejected.push(token);
      setAuthToken("fresh");
      return true;
    });

    const res = await request("/agents/x", { method: "POST", body: "{}" }, "ctx");

    expect(res.status).toBe(200);
    expect(rejected).toEqual(["expired"]);
    expect(seen).toEqual(["Bearer expired", "Bearer fresh"]);
  });

  it("surfaces the 401 when the handler has no fresh token", async () => {
    const seen = fetchRejecting(null);
    setUnauthorizedHandler(async () => false);

    const err = await request("/agents/x", undefined, "ctx").catch((e) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect(seen).toHaveLength(1);
  });

  it("retries at most once", async () => {
    globalThis.fetch = (() => Promise.resolve(new Response("", { status: 401 }))) as typeof fetch;
    let calls = 0;
    setUnauthorizedHandler(async () => {
      calls += 1;
      return true;
    });

    const err = await request("/agents/x", undefined, "ctx").catch((e) => e);

    expect((err as ApiError).status).toBe(401);
    expect(calls).toBe(1);
  });

  it("does not consult the handler for a 403", async () => {
    mockFetchResponse(403);
    let called = false;
    setUnauthorizedHandler(async () => (called = true));

    await request("/agents/x", undefined, "ctx").catch(() => undefined);

    expect(called).toBe(false);
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

describe("errorPayload", () => {
  const apiError = (body: string) => new ApiError(400, body, "ctx");

  it("splits the backend's {error, code} envelope", () => {
    const { code, message } = errorPayload(
      apiError(
        JSON.stringify({ error: "File too large: 24 bytes", code: "ATTACHMENT_TOO_LARGE" }),
      ),
    );

    expect(code).toBe("ATTACHMENT_TOO_LARGE");
    expect(message).toBe("File too large: 24 bytes");
  });

  it("returns nulls for a non-JSON body", () => {
    // Quarkus caps the request body before the attachment layer runs, so an
    // oversize upload arrives as a bare 413 with an HTML or empty body.
    expect(errorPayload(apiError("<html>413</html>"))).toEqual({
      code: null,
      message: null,
    });
  });

  it("returns nulls for an empty body and for a non-ApiError", () => {
    expect(errorPayload(apiError(""))).toEqual({ code: null, message: null });
    expect(errorPayload(new Error("boom"))).toEqual({
      code: null,
      message: null,
    });
  });

  it("ignores non-string error and code fields rather than coercing them", () => {
    expect(errorPayload(apiError(JSON.stringify({ error: 42, code: [] })))).toEqual(
      { code: null, message: null },
    );
  });
});
