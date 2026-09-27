import { afterEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { api, isApiError, type TokenRefresher } from "../api-client";
import { createTokenRefresher, type KeycloakLike } from "../keycloak-session";

const PATH = "/probe-auth";
const URL = `${window.location.origin}${PATH}`;

/**
 * Auth: the token used to be renewed only after Keycloak reported it expired,
 * and a 401 was never retried — so every request in the seconds around expiry
 * failed. The client now asks the session to refresh ahead of time, and retries
 * once after a forced refresh.
 */
describe("ApiClient token refresh", () => {
  afterEach(() => {
    api.setTokenRefresher(null);
    api.clearAuthToken();
  });

  function refresherIssuing(token: string, refreshed = true): TokenRefresher & {
    ensureFresh: ReturnType<typeof vi.fn>;
    forceRefresh: ReturnType<typeof vi.fn>;
  } {
    return {
      ensureFresh: vi.fn(async () => {}),
      forceRefresh: vi.fn(async () => {
        if (refreshed) api.setAuthToken(token);
        return refreshed;
      }),
    };
  }

  it("checks freshness before sending", async () => {
    server.use(http.get(URL, () => HttpResponse.json({ ok: true })));
    const refresher = refresherIssuing("new");
    api.setTokenRefresher(refresher);

    await api.get(PATH);
    expect(refresher.ensureFresh).toHaveBeenCalledTimes(1);
    expect(refresher.forceRefresh).not.toHaveBeenCalled();
  });

  it("retries a 401 once, with the refreshed token", async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(URL, ({ request }) => {
        const auth = request.headers.get("Authorization");
        seen.push(auth);
        return auth === "Bearer new"
          ? HttpResponse.json({ ok: true })
          : new HttpResponse(null, { status: 401 });
      }),
    );
    api.setAuthToken("old");
    const refresher = refresherIssuing("new");
    api.setTokenRefresher(refresher);

    await expect(api.get(PATH)).resolves.toEqual({ ok: true });
    expect(seen).toEqual(["Bearer old", "Bearer new"]);
    expect(refresher.forceRefresh).toHaveBeenCalledTimes(1);
  });

  it("does not retry when the refresh fails, and surfaces the 401", async () => {
    let calls = 0;
    server.use(
      http.get(URL, () => {
        calls++;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    api.setTokenRefresher(refresherIssuing("new", false));

    const error = await api.get(PATH).catch((e: unknown) => e);
    expect(isApiError(error) && error.status).toBe(401);
    expect(calls).toBe(1);
  });

  it("retries at most once — a second 401 is real", async () => {
    let calls = 0;
    server.use(
      http.get(URL, () => {
        calls++;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    api.setTokenRefresher(refresherIssuing("new"));

    await expect(api.get(PATH)).rejects.toMatchObject({ status: 401 });
    expect(calls).toBe(2);
  });

  it.each(["post", "put", "delete"] as const)(
    "does not replay a 401'd %s — it renews the token instead",
    async (method) => {
      // Not every backend 401 means "the handler never ran" (managed-agent
      // conversations are created before the handler's own 401), so a write
      // is surfaced, not repeated. The token is still renewed for the next try.
      let calls = 0;
      server.use(
        http[method](URL, () => {
          calls++;
          return new HttpResponse(null, { status: 401 });
        }),
      );
      api.setAuthToken("old");
      const refresher = refresherIssuing("new");
      api.setTokenRefresher(refresher);

      const send =
        method === "delete" ? api.delete(PATH) : api[method](PATH, { name: "x" });
      const error = await send.catch((e: unknown) => e);
      expect(isApiError(error) && error.status).toBe(401);
      expect(calls).toBe(1);
      expect(refresher.forceRefresh).toHaveBeenCalledTimes(1);
      expect(api.getAuthHeader()).toEqual({ Authorization: "Bearer new" });
    },
  );

  it("does not force a refresh for a 401'd write when a background refresh already swapped the token", async () => {
    server.use(
      http.post(URL, () => {
        api.setAuthToken("new"); // the background refresh lands mid-flight
        return new HttpResponse(null, { status: 401 });
      }),
    );
    api.setAuthToken("old");
    const refresher = refresherIssuing("newer");
    api.setTokenRefresher(refresher);

    await expect(api.post(PATH, { name: "x" })).rejects.toMatchObject({ status: 401 });
    expect(refresher.forceRefresh).not.toHaveBeenCalled();
  });

  it("without a refresher (auth disabled) a 401 is not retried", async () => {
    let calls = 0;
    server.use(
      http.get(URL, () => {
        calls++;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    await expect(api.get(PATH)).rejects.toMatchObject({ status: 401 });
    expect(calls).toBe(1);
  });

  it("retries with a token a background refresh swapped in, without forcing another", async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(URL, ({ request }) => {
        const auth = request.headers.get("Authorization");
        seen.push(auth);
        if (auth === "Bearer old") {
          // The background refresh lands while this request is in flight.
          api.setAuthToken("new");
          return new HttpResponse(null, { status: 401 });
        }
        return HttpResponse.json({ ok: true });
      }),
    );
    api.setAuthToken("old");
    const refresher = refresherIssuing("newer");
    api.setTokenRefresher(refresher);

    await expect(api.get(PATH)).resolves.toEqual({ ok: true });
    expect(seen).toEqual(["Bearer old", "Bearer new"]);
    expect(refresher.forceRefresh).not.toHaveBeenCalled();
  });

  it("retries EVERY request that 401'd together, not just the first (real refresher)", async () => {
    // With the real Keycloak refresher: two requests on a revoked token both
    // come back 401 before the forced refresh has answered. The second used to
    // hit the forced-refresh cooldown and surface its 401.
    let finish: (v: boolean) => void = () => {};
    const keycloak: KeycloakLike = {
      token: "old",
      refreshToken: "r",
      isTokenExpired: () => false,
      updateToken: vi.fn(
        () =>
          new Promise<boolean>((resolve) => {
            finish = (v) => {
              keycloak.token = "new";
              resolve(v);
            };
          }),
      ),
    };
    let unauthorized = 0;
    server.use(
      http.get(URL, ({ request }) => {
        if (request.headers.get("Authorization") === "Bearer new") {
          return HttpResponse.json({ ok: true });
        }
        // Answer the refresh once both requests have been refused.
        if (++unauthorized === 2) setTimeout(() => finish(true), 0);
        return new HttpResponse(null, { status: 401 });
      }),
    );
    api.setAuthToken("old");
    api.setTokenRefresher(
      createTokenRefresher(keycloak, () => api.setAuthToken(keycloak.token!), vi.fn()),
    );

    await expect(Promise.all([api.get(PATH), api.get(PATH)])).resolves.toEqual([
      { ok: true },
      { ok: true },
    ]);
    expect(keycloak.updateToken).toHaveBeenCalledTimes(1);
  });

  it("does not replay a request under a different session's token", async () => {
    // The session that sent the request ends and another one's token is
    // installed before the 401 arrives: the "changed" token is not a refresh
    // of the sender's session, so the request must not be replayed under it.
    const seen: (string | null)[] = [];
    server.use(
      http.get(URL, ({ request }) => {
        const auth = request.headers.get("Authorization");
        seen.push(auth);
        if (auth === "Bearer alice") {
          api.clearAuthToken(); // alice's session ends
          api.setAuthToken("bob"); // another session's token arrives
          return new HttpResponse(null, { status: 401 });
        }
        return HttpResponse.json({ ok: true });
      }),
    );
    api.setAuthToken("alice");
    const refresher = refresherIssuing("alice-2");
    api.setTokenRefresher(refresher);

    await expect(api.get(PATH)).rejects.toMatchObject({ status: 401 });
    expect(seen).toEqual(["Bearer alice"]);
    expect(refresher.forceRefresh).not.toHaveBeenCalled();
  });

  it("does not retry when the session ended while the forced refresh was pending", async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(URL, ({ request }) => {
        seen.push(request.headers.get("Authorization"));
        return request.headers.get("Authorization") === "Bearer bob"
          ? HttpResponse.json({ ok: true })
          : new HttpResponse(null, { status: 401 });
      }),
    );
    api.setAuthToken("alice");
    api.setTokenRefresher({
      ensureFresh: vi.fn(async () => {}),
      forceRefresh: vi.fn(async () => {
        api.clearAuthToken();
        api.setAuthToken("bob");
        return true;
      }),
    });

    await expect(api.get(PATH)).rejects.toMatchObject({ status: 401 });
    expect(seen).toEqual(["Bearer alice"]);
  });

  it("does not send at all when the session ended while waiting for the refresh", async () => {
    // An expired token makes the request wait on the refresh; the token
    // endpoint rejects it, the session is lost and the token cleared. The
    // request must not then go out with no token.
    let calls = 0;
    server.use(
      http.post(URL, () => {
        calls++;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    api.setAuthToken("alice");
    api.setTokenRefresher({
      ensureFresh: vi.fn(async () => {
        api.clearAuthToken(); // onSessionLost
      }),
      forceRefresh: vi.fn(async () => false),
    });

    const error = await api.post(PATH, { write: 1 }).catch((e: unknown) => e);
    expect(isApiError(error) && error.status).toBe(401);
    expect(calls).toBe(0);
  });

  it("a write retried while its renewal is pending waits for the new token (real refresher)", async () => {
    // POST 401s → a forced refresh starts, not awaited. The user retries at
    // once, with the refused token still far from expiry: the retry must wait
    // for the refresh and carry the new token.
    let finish: () => void = () => {};
    const keycloak: KeycloakLike = {
      token: "old",
      refreshToken: "r",
      isTokenExpired: () => false,
      updateToken: vi.fn(
        () =>
          new Promise<boolean>((resolve) => {
            finish = () => {
              keycloak.token = "new";
              resolve(true);
            };
          }),
      ),
    };
    const seen: (string | null)[] = [];
    server.use(
      http.post(URL, ({ request }) => {
        const auth = request.headers.get("Authorization");
        seen.push(auth);
        return auth === "Bearer new"
          ? HttpResponse.json({ ok: true })
          : new HttpResponse(null, { status: 401 });
      }),
    );
    api.setAuthToken("old");
    api.setTokenRefresher(
      createTokenRefresher(keycloak, () => api.setAuthToken(keycloak.token!), vi.fn()),
    );

    await expect(api.post(PATH, { n: 1 })).rejects.toMatchObject({ status: 401 });
    const retry = api.post(PATH, { n: 1 });
    await new Promise((r) => setTimeout(r, 10));
    finish();

    await expect(retry).resolves.toEqual({ ok: true });
    expect(seen).toEqual(["Bearer old", "Bearer new"]);
    expect(keycloak.updateToken).toHaveBeenCalledTimes(1);
  });
});
