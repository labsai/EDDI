import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { api, isApiError } from "@/lib/api-client";
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
});
