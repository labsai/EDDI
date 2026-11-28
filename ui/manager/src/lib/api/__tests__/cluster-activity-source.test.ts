import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "@eddi/app-kit/lib/api-client";
import { createClusterActivitySource } from "@eddi/app-kit/lib/api/cluster";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("createClusterActivitySource", () => {
  it("re-reads the bearer token on every reconnect instead of replaying a snapshot", async () => {
    // A header snapshot taken at creation goes stale at the next token refresh,
    // and the stream then reconnects with an expired token — a dead feed until a
    // reload.
    vi.useFakeTimers();
    let token = "token-1";
    vi.spyOn(api, "getAuthHeader").mockImplementation(() => ({ Authorization: `Bearer ${token}` }));
    const seen: (string | null)[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: RequestInit) => {
        seen.push(new Headers(init.headers).get("Authorization"));
        return new Response(null, { status: 401 });
      }),
    );

    const source = createClusterActivitySource();
    await vi.advanceTimersByTimeAsync(0);
    expect(seen[0]).toBe("Bearer token-1");

    token = "token-2"; // refreshed while the stream was down
    await vi.advanceTimersByTimeAsync(60_000);
    source.close();

    expect(seen.length).toBeGreaterThan(1);
    expect(seen[1]).toBe("Bearer token-2");
  });
});
