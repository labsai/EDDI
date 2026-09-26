import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createTokenRefresher,
  FORCED_REFRESH_COOLDOWN_MS,
  realmRoles,
  REFRESH_MIN_VALIDITY_SECONDS,
  sessionLost,
  type KeycloakLike,
} from "../keycloak-session";

function fakeKeycloak(overrides: Partial<KeycloakLike> = {}): KeycloakLike {
  return {
    token: "t",
    refreshToken: "r",
    tokenParsed: { realm_access: { roles: ["eddi-admin"] } },
    updateToken: vi.fn(async () => true),
    // Default: inside the refresh window but not yet expired.
    isTokenExpired: vi.fn((min?: number) => (min ?? 0) > 0),
    ...overrides,
  };
}

describe("keycloak session", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("refreshes AHEAD of expiry, not after it", async () => {
    const kc = fakeKeycloak();
    const refresher = createTokenRefresher(kc, vi.fn(), vi.fn());
    await refresher.ensureFresh();
    expect(kc.updateToken).toHaveBeenCalledWith(REFRESH_MIN_VALIDITY_SECONDS);
    expect(REFRESH_MIN_VALIDITY_SECONDS).toBeGreaterThan(0);
  });

  it("does not make a request wait on Keycloak while the token is still valid", async () => {
    // A hung token endpoint must not freeze the Manager for the last minute of
    // every token's life.
    const kc = fakeKeycloak({ updateToken: vi.fn(() => new Promise<boolean>(() => {})) });
    const refresher = createTokenRefresher(kc, vi.fn(), vi.fn());
    const outcome = await Promise.race([
      refresher.ensureFresh().then(() => "returned"),
      new Promise((r) => setTimeout(() => r("blocked"), 50)),
    ]);
    expect(outcome).toBe("returned");
    expect(kc.updateToken).toHaveBeenCalledTimes(1); // started in the background
  });

  it("waits for the refresh when the token has already expired", async () => {
    let finish: (v: boolean) => void = () => {};
    const kc = fakeKeycloak({
      isTokenExpired: vi.fn(() => true),
      updateToken: vi.fn(() => new Promise<boolean>((r) => (finish = r))),
    });
    const refresher = createTokenRefresher(kc, vi.fn(), vi.fn());
    let done = false;
    const pending = refresher.ensureFresh().then(() => (done = true));
    await new Promise((r) => setTimeout(r, 10));
    expect(done).toBe(false);
    finish(true);
    await pending;
    expect(done).toBe(true);
  });

  it("does nothing while the token is comfortably valid", async () => {
    const kc = fakeKeycloak({ isTokenExpired: vi.fn(() => false) });
    await createTokenRefresher(kc, vi.fn(), vi.fn()).ensureFresh();
    expect(kc.updateToken).not.toHaveBeenCalled();
  });

  it("rate-limits forced refreshes, so a persistent 401 does not hit Keycloak every time", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-26T10:00:00Z"));
    const kc = fakeKeycloak();
    const refresher = createTokenRefresher(kc, vi.fn(), vi.fn());

    expect(await refresher.forceRefresh()).toBe(true);
    expect(await refresher.forceRefresh()).toBe(false);
    expect(kc.updateToken).toHaveBeenCalledTimes(1);

    vi.setSystemTime(Date.now() + FORCED_REFRESH_COOLDOWN_MS);
    expect(await refresher.forceRefresh()).toBe(true);
    expect(kc.updateToken).toHaveBeenCalledTimes(2);
  });

  it("publishes the new token after a refresh", async () => {
    const onRefreshed = vi.fn();
    const refresher = createTokenRefresher(fakeKeycloak(), onRefreshed, vi.fn());
    await refresher.ensureFresh();
    expect(onRefreshed).toHaveBeenCalledTimes(1);
  });

  it("does not publish when the token was still valid", async () => {
    const onRefreshed = vi.fn();
    const kc = fakeKeycloak({ updateToken: vi.fn(async () => false) });
    await createTokenRefresher(kc, onRefreshed, vi.fn()).ensureFresh();
    expect(onRefreshed).not.toHaveBeenCalled();
  });

  it("forceRefresh asks for an unconditional refresh", async () => {
    const kc = fakeKeycloak();
    const ok = await createTokenRefresher(kc, vi.fn(), vi.fn()).forceRefresh();
    expect(ok).toBe(true);
    expect(kc.updateToken).toHaveBeenCalledWith(-1);
  });

  it("a TRANSIENT refresh failure keeps the session (no sign-out)", async () => {
    // Network error / Keycloak 5xx: keycloak-js keeps its tokens.
    const onSessionLost = vi.fn();
    const kc = fakeKeycloak({
      updateToken: vi.fn(async () => {
        throw new Error("Failed to fetch");
      }),
    });
    const refresher = createTokenRefresher(kc, vi.fn(), onSessionLost);
    await expect(refresher.ensureFresh()).resolves.toBeUndefined();
    await expect(refresher.forceRefresh()).resolves.toBe(false);
    expect(onSessionLost).not.toHaveBeenCalled();
  });

  it("a rejected refresh token ends the session", async () => {
    // HTTP 400 from the token endpoint: keycloak-js clears its tokens.
    const onSessionLost = vi.fn();
    const kc: KeycloakLike = fakeKeycloak();
    kc.updateToken = vi.fn(async () => {
      kc.refreshToken = undefined;
      kc.token = undefined;
      throw new Error("Server responded with an invalid status.");
    });
    await createTokenRefresher(kc, vi.fn(), onSessionLost).ensureFresh();
    expect(onSessionLost).toHaveBeenCalledTimes(1);
    expect(sessionLost(kc)).toBe(true);
  });

  it("does nothing without a refresh token", async () => {
    const kc = fakeKeycloak({ refreshToken: undefined });
    const refresher = createTokenRefresher(kc, vi.fn(), vi.fn());
    await refresher.ensureFresh();
    expect(await refresher.forceRefresh()).toBe(false);
    expect(kc.updateToken).not.toHaveBeenCalled();
  });

  it("reads realm roles from the current token", () => {
    expect(realmRoles(fakeKeycloak())).toEqual(["eddi-admin"]);
    expect(realmRoles(fakeKeycloak({ tokenParsed: {} }))).toEqual([]);
  });
});
