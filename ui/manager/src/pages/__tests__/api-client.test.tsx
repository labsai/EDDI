import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { api, isApiError, ApiClientError, getErrorMessage } from "@/lib/api-client";
import { server } from "@/test/mocks/server";

// Pause MSW so our manual fetch mocks take priority
beforeEach(() => {
  server.close();
});
afterEach(() => {
  vi.restoreAllMocks();
  server.listen({ onUnhandledRequest: "error" });
});

describe("ApiClient", () => {
  it("labels a network failure as one, and reports status 0 rather than an HTTP code", async () => {
    // A dead network, DNS failure or CORS rejection is not an HTTP error and
    // must not read like one. Nothing asserted either half, so the `Network
    // error:` prefix could be dropped and all 645 tests across the API layer
    // stayed green — leaving "Failed to fetch" to reach a toast on its own,
    // indistinguishable from a message the backend sent.
    vi.spyOn(global, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));

    const error = await api.get("/agentstore/agents/descriptors").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiClientError);
    expect((error as Error).message).toBe("Network error: Failed to fetch");
    // 0, not 500: there was no response, so there is no status to report, and
    // anything in the HTTP range would claim the server answered.
    expect((error as ApiClientError).status).toBe(0);
  });

  it("throws a real Error, so `err instanceof Error ? err.message : String(err)` reads the backend text", async () => {
    // Dozens of call sites unwrap failures exactly that way. When the client
    // threw a plain object literal, the instanceof branch never matched and
    // every backend rejection surfaced as "[object Object]" — the message that
    // sent people to the server log to find out what the UI already had.
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ error: "System prompt is required" }), {
        status: 400,
        statusText: "Bad Request",
        headers: { "Content-Type": "application/json" },
      }),
    );

    const error = await api.post("/administration/agents/setup", {}).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(Error);
    expect(error).toBeInstanceOf(ApiClientError);
    expect(isApiError(error)).toBe(true);
    const unwrapped = error instanceof Error ? error.message : String(error);
    expect(unwrapped).toBe("System prompt is required");
    expect(getErrorMessage(error)).toBe("System prompt is required (HTTP 400)");
    expect((error as ApiClientError).status).toBe(400);
    expect((error as ApiClientError).url).toContain("/administration/agents/setup");
    expect((error as Error).name).toBe("ApiClientError");
  });

  it("throws on non-JSON 2xx response", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response("<html>not json</html>", {
        status: 200,
        statusText: "OK",
        headers: { "Content-Type": "text/html" },
      }),
    );

    try {
      await api.get("/test");
      expect.fail("Expected api.get to throw");
    } catch (error: unknown) {
      expect(isApiError(error)).toBe(true);
      if (isApiError(error)) {
        expect(error.message).toContain("Unexpected non-JSON response");
        expect(error.status).toBe(200);
      }
    }
  });

  it("parses valid JSON response correctly", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        statusText: "OK",
        headers: { "Content-Type": "application/json" },
      }),
    );

    const result = await api.get<{ ok: boolean }>("/test");
    expect(result).toEqual({ ok: true });
  });

  it("returns undefined for 204 No Content without throwing", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response(null, {
        status: 204,
        statusText: "No Content",
      }),
    );

    const result = await api.get("/test");
    expect(result).toBeUndefined();
  });

  it("returns undefined for 200 with Content-Length: 0", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response("", {
        status: 200,
        statusText: "OK",
        headers: { "Content-Length": "0" },
      }),
    );

    const result = await api.get("/test");
    expect(result).toBeUndefined();
  });

  // EDDI's group endpoints (RestGroupTemplates, RestGroupWorkspace) return
  // 400/409 bodies shaped {"error": "..."} — a key the client never checked.
  it("extracts the message from a JSON error body keyed 'error'", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ error: "Missing role assignment(s): researcher2" }), {
        status: 400,
        statusText: "Bad Request",
        headers: { "Content-Type": "application/json" },
      }),
    );

    try {
      await api.get("/test");
      expect.fail("Expected api.get to throw");
    } catch (error: unknown) {
      expect(isApiError(error)).toBe(true);
      if (isApiError(error)) {
        expect(error.message).toBe("Missing role assignment(s): researcher2");
      }
    }
  });

  // EDDI's global IllegalArgumentExceptionMapper (save-time config validators
  // — vote phases, human members, facilitator, HITL config) and
  // ResourceModifiedExceptionMapper (version-conflict 409s) both return the
  // message as a PLAIN TEXT body, not JSON. Losing this collapsed every one of
  // those errors to a bare "Bad Request"/"Conflict" with no detail at all.
  it("falls back to the raw text body when the error body is not JSON", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response("Group must not mix HUMAN members with task-force phases", {
        status: 400,
        statusText: "Bad Request",
        headers: { "Content-Type": "text/plain" },
      }),
    );

    try {
      await api.post("/test", {});
      expect.fail("Expected api.post to throw");
    } catch (error: unknown) {
      expect(isApiError(error)).toBe(true);
      if (isApiError(error)) {
        expect(error.message).toBe(
          "Group must not mix HUMAN members with task-force phases",
        );
      }
    }
  });

  // A reverse proxy's 502/504 page is markup, not a message — dumping the whole
  // document into a toast is worse than showing the status phrase.
  it("ignores an HTML error page and keeps statusText", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response("<html><head><title>502 Bad Gateway</title></head><body><h1>502 Bad Gateway</h1></body></html>", {
        status: 502,
        statusText: "Bad Gateway",
        headers: { "Content-Type": "text/html" },
      }),
    );

    try {
      await api.get("/test");
      expect.fail("Expected api.get to throw");
    } catch (error: unknown) {
      expect(isApiError(error)).toBe(true);
      if (isApiError(error)) {
        expect(error.message).toBe("Bad Gateway");
        expect(error.message).not.toContain("<html");
      }
    }
  });

  it("truncates an oversized plain-text error body", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response("x".repeat(5000), {
        status: 500,
        statusText: "Internal Server Error",
        headers: { "Content-Type": "text/plain" },
      }),
    );

    try {
      await api.get("/test");
      expect.fail("Expected api.get to throw");
    } catch (error: unknown) {
      expect(isApiError(error)).toBe(true);
      if (isApiError(error)) {
        expect(error.message.length).toBeLessThanOrEqual(401); // 400 + the ellipsis
        expect(error.message.endsWith("…")).toBe(true);
      }
    }
  });

  // JSON.parse("null") succeeds and yields null — reading `.message` off it
  // throws, which previously fell through to using the literal text "null".
  it("keeps statusText for a JSON body with no recognizable message field", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response("null", {
        status: 400,
        statusText: "Bad Request",
        headers: { "Content-Type": "application/json" },
      }),
    );

    try {
      await api.get("/test");
      expect.fail("Expected api.get to throw");
    } catch (error: unknown) {
      expect(isApiError(error)).toBe(true);
      if (isApiError(error)) expect(error.message).toBe("Bad Request");
    }
  });

  it("keeps statusText when the error body is empty", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response("", {
        status: 500,
        statusText: "Internal Server Error",
      }),
    );

    try {
      await api.get("/test");
      expect.fail("Expected api.get to throw");
    } catch (error: unknown) {
      expect(isApiError(error)).toBe(true);
      if (isApiError(error)) {
        expect(error.message).toBe("Internal Server Error");
      }
    }
  });
});
