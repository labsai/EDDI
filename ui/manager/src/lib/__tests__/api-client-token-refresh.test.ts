import { afterEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { api, isApiError, type TokenRefresher } from "../api-client";

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

  it("resends the same body on retry", async () => {
    const bodies: string[] = [];
    let first = true;
    server.use(
      http.post(URL, async ({ request }) => {
        bodies.push(await request.text());
        if (first) {
          first = false;
          return new HttpResponse(null, { status: 401 });
        }
        return HttpResponse.json({ ok: true });
      }),
    );
    api.setTokenRefresher(refresherIssuing("new"));

    await api.post(PATH, { name: "x" });
    expect(bodies).toEqual(['{"name":"x"}', '{"name":"x"}']);
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
});
