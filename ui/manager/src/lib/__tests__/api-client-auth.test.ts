import { afterEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { api } from "../api-client";

const PATH = "/agentstore/agents";
const URL = `${window.location.origin}${PATH}`;

describe("api client session handling", () => {
  afterEach(() => {
    api.setTokenRefresher(null);
    api.setUnauthorizedHandler(null);
    api.clearAuthToken();
  });

  it("renews the token before a request, so one that expired in a throttled tab is not sent", async () => {
    const seen: string[] = [];
    server.use(
      http.get(URL, ({ request }) => {
        seen.push(request.headers.get("Authorization") ?? "");
        return HttpResponse.json({});
      }),
    );
    api.setAuthToken("stale");
    api.setTokenRefresher(async (force) => {
      expect(force).toBe(false);
      api.setAuthToken("fresh");
      return true;
    });

    await api.get(PATH);

    expect(seen).toEqual(["Bearer fresh"]);
  });

  it("on a 401 forces one refresh and retries once with the new token", async () => {
    let calls = 0;
    server.use(
      http.get(URL, ({ request }) => {
        calls++;
        return request.headers.get("Authorization") === "Bearer new"
          ? HttpResponse.json({ ok: true })
          : new HttpResponse(null, { status: 401 });
      }),
    );
    const handler = vi.fn();
    api.setAuthToken("old");
    api.setUnauthorizedHandler(handler);
    api.setTokenRefresher(async (force) => {
      if (force) api.setAuthToken("new");
      return true;
    });

    await expect(api.get(PATH)).resolves.toEqual({ ok: true });

    expect(calls).toBe(2);
    expect(handler).not.toHaveBeenCalled();
  });

  it("reports an unrecoverable 401 instead of logging out, and still throws", async () => {
    server.use(http.get(URL, () => new HttpResponse(null, { status: 401 })));
    const handler = vi.fn();
    api.setUnauthorizedHandler(handler);
    api.setTokenRefresher(async () => {
      throw new Error("refresh token expired");
    });

    await expect(api.get(PATH)).rejects.toMatchObject({ status: 401 });

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it("does nothing special for a 401 when auth is not configured", async () => {
    server.use(http.get(URL, () => new HttpResponse(null, { status: 401 })));
    const handler = vi.fn();
    api.setUnauthorizedHandler(handler);

    await expect(api.get(PATH)).rejects.toMatchObject({ status: 401 });

    expect(handler).not.toHaveBeenCalled();
  });
});
