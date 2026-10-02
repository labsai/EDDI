import { describe, it, expect, vi, beforeEach, afterEach, type MockInstance } from "vitest";
import { api } from "@/lib/api-client";
import { createLogEventSource } from "../logs";
import { createCoordinatorEventSource } from "../coordinator";

/**
 * Review 2026-10-02 (Manager, SSE): both streams were built with
 * `api.getAuthHeader()` evaluated ONCE, so after Keycloak refreshed the access
 * token every reconnect still carried the old one. The backend answered 401
 * until the retry budget ran out and the stream stopped for good.
 *
 * These go through the real factories and the real BearerEventSource; only the
 * network is faked.
 */
describe.each([
  ["live log stream", () => createLogEventSource({ level: "ERROR" })],
  ["coordinator stream", () => createCoordinatorEventSource()],
])("%s after a token refresh", (_name, open) => {
  let fetchSpy: MockInstance<typeof fetch>;

  beforeEach(() => {
    vi.useFakeTimers();
    fetchSpy = vi.spyOn(globalThis, "fetch");
  });

  afterEach(() => {
    api.clearAuthToken();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("reconnects with the refreshed token, not the one it was opened with", async () => {
    api.setAuthToken("token-1");
    // First attempt: the token has just expired → 401. Second: whatever is sent.
    fetchSpy.mockResolvedValueOnce(new Response(null, { status: 401 }));
    fetchSpy.mockResolvedValueOnce(new Response(null, { status: 401 }));

    const source = open();
    await vi.advanceTimersByTimeAsync(0);

    // keycloak-js refreshed the token (auth-provider → api.setAuthToken).
    api.setAuthToken("token-2");
    await vi.advanceTimersByTimeAsync(10_000);

    const sent = fetchSpy.mock.calls.map(
      ([, init]) => (init?.headers as Record<string, string>).Authorization,
    );
    expect(sent).toEqual(["Bearer token-1", "Bearer token-2"]);
    source.close();
  });
});
