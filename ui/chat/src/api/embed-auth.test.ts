/* ──────────────────────────────────────────────
   Embedding token hand-off — origin checks, handshake, refresh, 401 retry
   ────────────────────────────────────────────── */

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  startEmbedAuth,
  parseAllowedTokenOrigins,
  tokenExpiryMs,
  isPlausibleToken,
  MSG_READY,
  MSG_TOKEN,
  MSG_TOKEN_REQUEST,
  TOKEN_WAIT_MS,
  type EmbedAuth,
} from "./embed-auth";
import { getAuthToken, request, setAuthToken, setBaseUrl, ApiError } from "./http";

const HOST = "https://portal.example";

/** A fake framed window: its parent records what the widget posts to it. */
function framedWindow() {
  const posted: Array<{ data: { type: string }; targetOrigin: string }> = [];
  const parent = {
    postMessage: (data: { type: string }, targetOrigin: string) =>
      posted.push({ data, targetOrigin }),
  } as unknown as Window;
  const target = new EventTarget();
  const win = Object.assign(target, { parent }) as unknown as Window;
  const deliver = (data: unknown, origin = HOST, source: unknown = parent) => {
    // jsdom's MessageEvent constructor only takes a real window as `source`,
    // so it is set afterwards.
    const ev = new MessageEvent("message", { data, origin });
    Object.defineProperty(ev, "source", { value: source });
    target.dispatchEvent(ev);
  };
  return { win, parent, posted, deliver };
}

/** An unsigned JWT with the given exp (seconds). Only its shape matters. */
function jwt(expSeconds: number): string {
  const b64 = (o: object) =>
    btoa(JSON.stringify(o)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
  return `${b64({ alg: "none" })}.${b64({ exp: expSeconds, sub: "u" })}.sig`;
}

let embed: EmbedAuth | null = null;
const originalFetch = globalThis.fetch;

beforeEach(() => {
  setAuthToken(null);
  setBaseUrl("");
});

afterEach(() => {
  embed?.stop();
  embed = null;
  setAuthToken(null);
  globalThis.fetch = originalFetch;
  vi.useRealTimers();
});

describe("parseAllowedTokenOrigins", () => {
  const parse = (v: string) => parseAllowedTokenOrigins(new URLSearchParams({ tokenOrigin: v }));

  it("refuses a wildcard, the opaque origin and non-http schemes", () => {
    expect(parse("*")).toEqual([]);
    expect(parse("https://*.example.org")).toEqual([]);
    expect(parse("null")).toEqual([]);
    expect(parse("javascript:alert(1)")).toEqual([]);
    expect(parse("file:///etc")).toEqual([]);
  });

  it("refuses anything that is not byte-for-byte an origin", () => {
    // The browser compares event.origin as a string, so each of these would
    // either never match or, worse, imply a looser allow-list than written.
    expect(parse("https://portal.example/")).toEqual([]);
    expect(parse("https://portal.example/path")).toEqual([]);
    expect(parse("https://portal.example:443")).toEqual([]);
    expect(parse("https://user@portal.example")).toEqual([]);
    expect(parse("HTTPS://PORTAL.EXAMPLE")).toEqual([]);
    expect(parse("https://portal.example?x=1")).toEqual([]);
  });

  it("keeps exact origins", () => {
    expect(parse(`${HOST}, http://localhost:5173`)).toEqual([HOST, "http://localhost:5173"]);
  });
});

describe("handshake", () => {
  it("announces itself to the parent at each allow-listed origin — never '*'", () => {
    const { win, posted } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST, "https://other.example"], win });

    expect(posted.map((p) => [p.data.type, p.targetOrigin])).toEqual([
      [MSG_READY, HOST],
      [MSG_READY, "https://other.example"],
    ]);
    expect(posted.every((p) => p.targetOrigin !== "*")).toBe(true);
  });

  it("is inert without an allow-list, and when not framed", () => {
    const framed = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [], win: framed.win });
    expect(framed.posted).toEqual([]);

    const top = new EventTarget() as unknown as Window & { parent: Window };
    Object.assign(top, { parent: top });
    const spy = vi.fn();
    (top as unknown as { postMessage: unknown }).postMessage = spy;
    embed = startEmbedAuth({ allowedOrigins: [HOST], win: top });
    expect(spy).not.toHaveBeenCalled();
  });
});

describe("accepting a token", () => {
  it("installs a token from the parent at an allow-listed origin", () => {
    const { win, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });

    deliver({ type: MSG_TOKEN, token: "tok-1" });

    expect(getAuthToken()).toBe("tok-1");
  });

  it("rejects a token from an origin that is not allow-listed", () => {
    const { win, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });

    deliver({ type: MSG_TOKEN, token: "evil" }, "https://evil.example");

    expect(getAuthToken()).toBe(null);
  });

  it("rejects a token from a window other than the parent, even at the right origin", () => {
    const { win, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });

    deliver({ type: MSG_TOKEN, token: "sibling" }, HOST, {} as Window);

    expect(getAuthToken()).toBe(null);
  });

  it("rejects a malformed token and other message types", () => {
    const { win, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });

    deliver({ type: MSG_TOKEN, token: "has space" });
    deliver({ type: MSG_TOKEN, token: 42 });
    deliver({ type: "something-else", token: "tok" });
    deliver("eddi-chat-token");

    expect(getAuthToken()).toBe(null);
  });

  it("stops listening once stopped", () => {
    const { win, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });
    embed.stop();

    deliver({ type: MSG_TOKEN, token: "late" });

    expect(getAuthToken()).toBe(null);
  });
});

describe("refresh before expiry", () => {
  it("asks the host for a new token a minute before the current one expires", () => {
    vi.useFakeTimers();
    const now = Date.now();
    const { win, posted, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win, now: () => now });

    deliver({ type: MSG_TOKEN, token: jwt(Math.floor(now / 1000) + 300) });
    const requests = () => posted.filter((p) => p.data.type === MSG_TOKEN_REQUEST);

    vi.advanceTimersByTime(230_000);
    expect(requests()).toHaveLength(0);
    vi.advanceTimersByTime(15_000);
    expect(requests()).toEqual([
      { data: { type: MSG_TOKEN_REQUEST, protocol: 1 }, targetOrigin: HOST },
    ]);
  });

  it("schedules no refresh for an opaque (non-JWT) token", () => {
    vi.useFakeTimers();
    const { win, posted, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });

    deliver({ type: MSG_TOKEN, token: "opaque" });
    vi.advanceTimersByTime(24 * 3600_000);

    expect(posted.filter((p) => p.data.type === MSG_TOKEN_REQUEST)).toEqual([]);
  });
});

describe("a 401 asks the host and repeats the request", () => {
  /** fetch: 401 unless the request carries `Bearer good`. */
  function backend() {
    const seen: Array<string | null> = [];
    globalThis.fetch = ((_u: string, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("Authorization");
      seen.push(auth);
      const ok = auth === "Bearer good";
      return Promise.resolve(new Response(ok ? "{}" : "", { status: ok ? 200 : 401 }));
    }) as typeof fetch;
    return seen;
  }

  it("a request sent before the first token arrives succeeds once the host answers", async () => {
    const seen = backend();
    const { win, posted, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });

    const pending = request("/agents/a/start", { method: "POST" }, "start");
    await vi.waitFor(() =>
      expect(posted.some((p) => p.data.type === MSG_TOKEN_REQUEST)).toBe(true),
    );
    deliver({ type: MSG_TOKEN, token: "good" });

    const res = await pending;
    expect(res.status).toBe(200);
    expect(seen).toEqual([null, "Bearer good"]);
  });

  it("repeats at once when a newer token arrived while the request was in flight", async () => {
    setAuthToken("stale");
    const { win, posted, deliver } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });
    const seen: Array<string | null> = [];
    globalThis.fetch = ((_u: string, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("Authorization");
      seen.push(auth);
      if (auth === "Bearer stale") {
        // The host's token lands while this request is on the wire.
        deliver({ type: MSG_TOKEN, token: "good" });
        return Promise.resolve(new Response("", { status: 401 }));
      }
      return Promise.resolve(new Response("{}", { status: 200 }));
    }) as typeof fetch;

    const res = await request("/agents/x", undefined, "read");

    expect(res.status).toBe(200);
    expect(seen).toEqual(["Bearer stale", "Bearer good"]);
    expect(posted.filter((p) => p.data.type === MSG_TOKEN_REQUEST)).toEqual([]);
  });

  it("gives up after the wait and surfaces the 401", async () => {
    vi.useFakeTimers();
    backend();
    const { win } = framedWindow();
    embed = startEmbedAuth({ allowedOrigins: [HOST], win });

    const pending = request("/agents/x", undefined, "read").catch((e) => e);
    await vi.advanceTimersByTimeAsync(TOKEN_WAIT_MS + 1);
    const err = await pending;

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
  });

  it("without the hand-off a 401 is not retried", async () => {
    const seen = backend();

    const err = await request("/agents/x", undefined, "read").catch((e) => e);

    expect((err as ApiError).status).toBe(401);
    expect(seen).toHaveLength(1);
  });
});

describe("token helpers", () => {
  it("reads exp from a JWT and nothing from anything else", () => {
    expect(tokenExpiryMs(jwt(1_900_000_000))).toBe(1_900_000_000_000);
    expect(tokenExpiryMs("opaque")).toBe(null);
    expect(tokenExpiryMs("a.%%%.c")).toBe(null);
  });

  it("bounds what counts as a token", () => {
    expect(isPlausibleToken("abc.def-ghi_jkl")).toBe(true);
    expect(isPlausibleToken("")).toBe(false);
    expect(isPlausibleToken("a b")).toBe(false);
    expect(isPlausibleToken("x".repeat(20_000))).toBe(false);
  });
});
