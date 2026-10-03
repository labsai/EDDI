/* ──────────────────────────────────────────────
   EDDI Chat — embedding token hand-off
   A host page that frames the widget passes it an OIDC access token by
   postMessage, and passes a new one whenever the widget asks. The protocol:

     widget → host  { type: "eddi-chat-ready",         protocol: 1 }
     widget → host  { type: "eddi-chat-token-request", protocol: 1 }
     host → widget  { type: "eddi-chat-token",         token: "<access token>" }

   "ready" is sent once the widget listens, so the host never has to guess
   when its message will be heard. "token-request" is sent shortly before the
   current token expires and whenever the backend answers 401. The host
   answers both by posting a token.

   Every message the widget sends goes to an origin from the allow-list
   (`?tokenOrigin=`), never to "*": postMessage drops a message whose target
   origin does not match the receiving window, so only the real parent at an
   allow-listed origin ever reads it. Every message the widget accepts must
   come from that same parent (event.source) at an allow-listed origin
   (event.origin). Which origins may frame /chat at all is decided by the
   operator in `eddi.chat.frame-ancestors`, which the browser enforces before
   the widget runs.

   The token is kept in memory only — never in the URL, storage or a cookie.
   ────────────────────────────────────────────── */

import { getAuthToken, setAuthToken, setUnauthorizedHandler } from "./http";

export const EMBED_PROTOCOL_VERSION = 1;
export const MSG_READY = "eddi-chat-ready";
export const MSG_TOKEN_REQUEST = "eddi-chat-token-request";
export const MSG_TOKEN = "eddi-chat-token";

/** How long a 401 waits for the host to answer a token request. */
export const TOKEN_WAIT_MS = 10_000;
/** Ask for a new token this long before the current one expires. */
export const REFRESH_LEAD_MS = 60_000;
/** Never schedule a refresh sooner than this after receiving a token. */
const MIN_REFRESH_DELAY_MS = 5_000;
/** An access token is a few KB; anything far larger is not one. */
const MAX_TOKEN_LENGTH = 16_384;

/**
 * Origins allowed to hand the widget a bearer token, read from `?tokenOrigin=`
 * (comma-separated, exact `scheme://host[:port]` each). An explicit opt-in:
 * with no value no token is accepted and nothing is ever posted to the host.
 * A malformed entry, a wildcard, a path or the opaque origin "null" is dropped
 * rather than widening the allow-list.
 */
export function parseAllowedTokenOrigins(params: URLSearchParams): string[] {
  const raw = params.get("tokenOrigin");
  if (!raw) return [];
  return raw
    .split(",")
    .map((o) => o.trim())
    .filter((o) => {
      if (!o || o.includes("*")) return false;
      try {
        const url = new URL(o);
        return (url.protocol === "https:" || url.protocol === "http:") && url.origin === o;
      } catch {
        return false;
      }
    });
}

/** A plausible bearer token: printable ASCII, no whitespace, bounded. */
export function isPlausibleToken(token: unknown): token is string {
  return (
    typeof token === "string" &&
    token.length > 0 &&
    token.length <= MAX_TOKEN_LENGTH &&
    /^[\x21-\x7e]+$/.test(token)
  );
}

/**
 * The `exp` claim of a JWT in milliseconds, or null when the token is not a
 * JWT or carries none. Read only to schedule a refresh — the token is never
 * trusted on this basis; the backend verifies it.
 */
export function tokenExpiryMs(token: string): number | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
    const payload = JSON.parse(atob(padded)) as { exp?: unknown };
    return typeof payload.exp === "number" && Number.isFinite(payload.exp)
      ? payload.exp * 1000
      : null;
  } catch {
    return null;
  }
}

export interface EmbedAuth {
  /** Remove the listener, the 401 handler and any pending refresh. */
  stop(): void;
}

interface EmbedAuthOptions {
  allowedOrigins: string[];
  /** Injected for tests; defaults to the real window. */
  win?: Window;
  /** Called with each accepted token, after it is installed. */
  onToken?: (token: string) => void;
  now?: () => number;
}

/**
 * Start the hand-off. Inert (returns a no-op) unless there is an allow-list
 * AND the widget is actually framed: a top-level window has no host to ask.
 */
export function startEmbedAuth({
  allowedOrigins,
  win = window,
  onToken,
  now = Date.now,
}: EmbedAuthOptions): EmbedAuth {
  const host = win.parent;
  if (allowedOrigins.length === 0 || !host || host === win) {
    return { stop() {} };
  }

  let stopped = false;
  let refreshTimer: ReturnType<typeof setTimeout> | null = null;
  /** Waiters for the next token, resolved true on arrival or false on timeout. */
  let waiters: Array<(ok: boolean) => void> = [];

  const post = (type: string) => {
    for (const origin of allowedOrigins) {
      try {
        // An exact target origin: if the parent is not at `origin`, the
        // browser discards the message. Never "*".
        host.postMessage({ type, protocol: EMBED_PROTOCOL_VERSION }, origin);
      } catch {
        // A detached or cross-agent parent; nothing to tell.
      }
    }
  };

  const scheduleRefresh = (token: string) => {
    if (refreshTimer) clearTimeout(refreshTimer);
    refreshTimer = null;
    const exp = tokenExpiryMs(token);
    if (exp === null) return;
    const remaining = exp - now();
    // Ask a minute ahead; a token shorter-lived than that is renewed at
    // roughly 80 % of its life, and never sooner than a few seconds.
    const lead = Math.min(REFRESH_LEAD_MS, remaining * 0.2);
    const delay = Math.max(MIN_REFRESH_DELAY_MS, remaining - lead);
    refreshTimer = setTimeout(() => {
      refreshTimer = null;
      if (!stopped) post(MSG_TOKEN_REQUEST);
    }, delay);
  };

  const onMessage = (event: MessageEvent) => {
    if (stopped) return;
    if (event.source !== host) return;
    if (!allowedOrigins.includes(event.origin)) return;
    const data = event.data as { type?: unknown; token?: unknown } | null;
    if (!data || typeof data !== "object" || data.type !== MSG_TOKEN) return;
    const token = typeof data.token === "string" ? data.token.trim() : data.token;
    if (!isPlausibleToken(token)) return;
    setAuthToken(token);
    scheduleRefresh(token);
    const pending = waiters;
    waiters = [];
    pending.forEach((resolve) => resolve(true));
    onToken?.(token);
  };

  /**
   * A 401: repeat at once if a newer token arrived while the request was in
   * flight; otherwise ask the host and wait for its answer. Concurrent 401s
   * share the one request.
   */
  const onUnauthorized = (rejected: string | null): Promise<boolean> => {
    if (stopped) return Promise.resolve(false);
    const current = getAuthToken();
    if (current && current !== rejected) return Promise.resolve(true);
    return new Promise<boolean>((resolve) => {
      const first = waiters.length === 0;
      const timer = setTimeout(() => {
        waiters = waiters.filter((w) => w !== settle);
        resolve(false);
      }, TOKEN_WAIT_MS);
      const settle = (ok: boolean) => {
        clearTimeout(timer);
        resolve(ok);
      };
      waiters.push(settle);
      if (first) post(MSG_TOKEN_REQUEST);
    });
  };

  win.addEventListener("message", onMessage);
  setUnauthorizedHandler(onUnauthorized);
  post(MSG_READY);

  return {
    stop() {
      stopped = true;
      win.removeEventListener("message", onMessage);
      setUnauthorizedHandler(null);
      if (refreshTimer) clearTimeout(refreshTimer);
      const pending = waiters;
      waiters = [];
      pending.forEach((resolve) => resolve(false));
    },
  };
}
