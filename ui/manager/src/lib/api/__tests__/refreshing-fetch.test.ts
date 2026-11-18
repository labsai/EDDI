import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@eddi/app-kit/lib/api-client";
import { streamGroupDiscussion } from "@eddi/app-kit/lib/api/groups";
import { listAttachments } from "@eddi/app-kit/lib/api/attachments";

/**
 * After an idle tab the access token is stale. Group streams, attachments,
 * secrets, ratings and branding used to send a bare `fetch` with the stored
 * header, so the first call failed with "401 Unauthorized" and the user's action
 * (an approval, a discussion start) was lost. They go through
 * `api.authorizedFetch`: renew, retry once with the fresh token, and if the session
 * is really gone raise the session-expired state.
 */

const json = (status: number, body: unknown = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("calls that used to be a bare fetch", () => {
  let tokens: string[];
  let fetchMock: ReturnType<typeof vi.fn>;
  let refresher: ReturnType<typeof vi.fn>;
  let unauthorized: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    tokens = [];
    api.setAuthToken("stale");
    refresher = vi.fn(async (force: boolean) => {
      if (force) api.setAuthToken("fresh");
      return true;
    });
    unauthorized = vi.fn();
    api.setTokenRefresher(refresher as never);
    api.setUnauthorizedHandler(unauthorized as never);
    fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("Authorization") ?? "";
      tokens.push(auth);
      return auth === "Bearer fresh" ? json(200, []) : json(401, { error: "expired" });
    });
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => {
    api.setTokenRefresher(null);
    api.setUnauthorizedHandler(null);
    api.clearAuthToken();
    vi.unstubAllGlobals();
  });

  it("an attachment call: 401 -> forced refresh -> one retry with the fresh token, no error, no banner", async () => {
    await expect(listAttachments("conv-1")).resolves.toEqual([]);
    expect(tokens).toEqual(["Bearer stale", "Bearer fresh"]);
    expect(refresher).toHaveBeenCalledWith(true);
    expect(unauthorized).not.toHaveBeenCalled();
  });

  it("a group stream start: the 401 is retried BEFORE the body is read, so the decision is not lost", async () => {
    fetchMock.mockImplementation(async (_url: string, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("Authorization") ?? "";
      tokens.push(auth);
      return auth === "Bearer fresh"
        ? new Response("event: done\ndata: {}\n\n", { status: 200, headers: { "Content-Type": "text/event-stream" } })
        : json(401);
    });
    const events: unknown[] = [];
    for await (const e of streamGroupDiscussion("g1", "question")) events.push(e);
    expect(tokens).toEqual(["Bearer stale", "Bearer fresh"]);
    expect(events.length).toBeGreaterThan(0);
    expect(unauthorized).not.toHaveBeenCalled();
  });

  it("when the session is really gone: one retry only, then the session-expired state, not a loop", async () => {
    refresher.mockImplementation(async () => true); // refreshes, but the server still says 401
    fetchMock.mockImplementation(async () => json(401));
    await expect(listAttachments("conv-1")).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(unauthorized).toHaveBeenCalledTimes(1);
  });

  it("the token is renewed before the request, not only after a 401", async () => {
    fetchMock.mockImplementation(async () => json(200, []));
    await listAttachments("conv-1");
    expect(refresher).toHaveBeenCalledWith(false);
  });
});
