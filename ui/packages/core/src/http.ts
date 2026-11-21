/* ──────────────────────────────────────────────
   @eddi/core — HTTP
   Status-aware fetch. Every non-2xx becomes an error carrying the status and
   the server's body, so callers can tell 409 (conversation paused, input not
   consumed) from 403 (not the owner) from 404 (unknown conversation).

   Derived from the Chat UI's `api/http.ts`. An `HttpClient` holds the base
   URL, how to authenticate, and how to turn a refused response into an error;
   each app configures one. The module-level functions at the bottom drive a
   default client, which is how the Chat UI has always used this layer.
   ────────────────────────────────────────────── */

/**
 * Where the bearer token comes from.
 *
 * Conversation ownership is enforced server-side: an unauthenticated client
 * can be locked out of the very conversation it started, so a deployment with
 * OIDC enabled must supply a token.
 */
export interface AuthProvider {
  /** The bearer token to send with the next request, or null to send none. */
  getToken(): string | null | Promise<string | null>;
  /**
   * Called once when a request is answered 401, with the token that request
   * carried. Resolve true when a different, fresh token is now available and
   * the request should be repeated; false to let the 401 surface. A 401 is
   * safe to repeat: the request was refused before anything ran.
   */
  onUnauthorized?(rejectedToken: string | null): Promise<boolean>;
}

/** Builds the error thrown for a non-2xx response. Consumes the body. */
export type ErrorFactory = (response: Response, context: string) => Promise<Error>;

/** The fetch an `HttpClient` sends through. Resolved at call time by default. */
export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>;

export interface HttpClientOptions {
  /** Prepended to every path. Default: "" (same origin). */
  baseUrl?: string;
  /** Default: none — no Authorization header is sent. */
  auth?: AuthProvider | null;
  /**
   * The transport. Default: the global `fetch`, looked up on every request so
   * a test that replaces it (or MSW, which patches it) is honoured.
   */
  fetch?: FetchLike;
  /** Default: an {@link ApiError} carrying the status and the raw body. */
  createError?: ErrorFactory;
}

/**
 * A non-2xx response. Carries the status code and the raw server body so
 * callers can branch on it — the backend returns meaningful plain-text bodies
 * on 409 (naming the resume endpoint) that were previously discarded.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body: string;

  constructor(status: number, body: string, context: string) {
    super(`${context}: ${status}${body ? ` — ${body}` : ""}`);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

/** The default {@link ErrorFactory}: read the body, keep it on an ApiError. */
export async function apiErrorOf(response: Response, context: string): Promise<ApiError> {
  let body = "";
  try {
    body = await response.text();
  } catch {
    // body already consumed or unreadable — status alone still informs
  }
  return new ApiError(response.status, body, context);
}

/**
 * Pull the `{error, code}` envelope EDDI returns on a failure.
 *
 * Branching on the code alone is not enough to explain a failure: the backend
 * collapses every AttachmentStoreException into ATTACHMENT_REJECTED — MIME
 * rejection, the per-conversation file and byte quotas, and empty files all
 * arrive under that one code, and only the `error` string says which.
 *
 * Returns nulls for a non-ApiError, an empty body, or a non-JSON body (a
 * container-level 413 never reaches the attachment layer, so it has none).
 */
export function errorPayload(err: unknown): {
  code: string | null;
  message: string | null;
} {
  if (!(err instanceof ApiError) || !err.body.trim()) {
    return { code: null, message: null };
  }
  try {
    const parsed = JSON.parse(err.body) as { error?: unknown; code?: unknown };
    return {
      code: typeof parsed.code === "string" ? parsed.code : null,
      message: typeof parsed.error === "string" ? parsed.error : null,
    };
  } catch {
    return { code: null, message: null };
  }
}

/** Encode a single path segment so /, ?, # in data don't break the URL. */
export function encodeSegment(value: string): string {
  return encodeURIComponent(value);
}

/** Add the bearer token without disturbing caller-supplied headers. */
function withAuth(init: RequestInit | undefined, token: string | null): RequestInit | undefined {
  if (!token) return init;
  const headers = new Headers(init?.headers);
  headers.set("Authorization", `Bearer ${token}`);
  return { ...init, headers };
}

function normalizeToken(token: string | null | undefined): string | null {
  return token && token.trim() ? token.trim() : null;
}

export class HttpClient {
  private baseUrl: string;
  private auth: AuthProvider | null;
  private readonly fetchImpl: FetchLike | undefined;
  private readonly createError: ErrorFactory;

  constructor(options: HttpClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? "").replace(/\/$/, "");
    this.auth = options.auth ?? null;
    this.fetchImpl = options.fetch;
    this.createError = options.createError ?? apiErrorOf;
  }

  /** Set the API base URL (e.g. from the widget's config). */
  setBaseUrl(url: string): void {
    this.baseUrl = url.replace(/\/$/, "");
  }

  getBaseUrl(): string {
    return this.baseUrl;
  }

  /** Install (or with null remove) the auth provider. */
  setAuthProvider(auth: AuthProvider | null): void {
    this.auth = auth;
  }

  getAuthProvider(): AuthProvider | null {
    return this.auth;
  }

  buildUrl(path: string): string {
    return `${this.baseUrl}${path}`;
  }

  private send(path: string, init: RequestInit | undefined, token: string | null): Promise<Response> {
    const transport = this.fetchImpl ?? ((url: string, i?: RequestInit) => globalThis.fetch(url, i));
    return transport(this.buildUrl(path), withAuth(init, token));
  }

  /**
   * The token to send. Synchronous when the provider is, so a request goes out
   * in the same tick it was made, exactly as a bare `fetch` would.
   */
  private token(): string | null | Promise<string | null> {
    if (!this.auth) return null;
    const raw = this.auth.getToken();
    return raw instanceof Promise ? raw.then(normalizeToken) : normalizeToken(raw);
  }

  /**
   * Fetch that throws on a non-2xx status — an {@link ApiError} unless the
   * client was given its own `createError` — reading the body first so the
   * error carries it.
   */
  async request(path: string, init: RequestInit | undefined, context: string): Promise<Response> {
    const pending = this.token();
    const sentToken = pending instanceof Promise ? await pending : pending;
    let res = await this.send(path, init, sentToken);
    if (res.status === 401 && this.auth?.onUnauthorized) {
      // One retry at most: a second 401 with a fresh token is a real refusal.
      if (await this.auth.onUnauthorized(sentToken)) {
        res = await this.send(path, init, await this.token());
      }
    }
    if (!res.ok) throw await this.createError(res, context);
    return res;
  }

  /**
   * As `request`, but parses a JSON body. Returns null for an empty body — the
   * backend answers undo/redo/resume with 200 and no content, and calling
   * res.json() on that throws.
   */
  async requestJson<T>(path: string, init: RequestInit | undefined, context: string): Promise<T | null> {
    const res = await this.request(path, init, context);
    const text = await res.text();
    if (!text.trim()) return null;
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new ApiError(res.status, text, `${context}: malformed JSON response`);
    }
  }
}

/* ─── The default client ───────────────────────── */

/*
 * The default client authenticates with a static token and an optional 401
 * handler, set through the functions below. The Chat UI's embedding handshake
 * (`embed-auth.ts`) installs the handler: a host page hands the widget a
 * short-lived OIDC access token, and an expired or not-yet-delivered token is
 * answered by asking the host for a new one instead of failing the turn.
 */
let staticToken: string | null = null;
let staticUnauthorized: ((rejectedToken: string | null) => Promise<boolean>) | null = null;

const staticTokenAuth: AuthProvider = {
  getToken: () => staticToken,
  onUnauthorized: (rejected) => (staticUnauthorized ? staticUnauthorized(rejected) : Promise.resolve(false)),
};

/** The client the module-level functions of this package use. */
export const defaultHttp = new HttpClient({ auth: staticTokenAuth });

/** Set the default client's API base URL. Call once at startup. */
export function setBaseUrl(url: string): void {
  defaultHttp.setBaseUrl(url);
}

/** Set the bearer token the default client sends, or null to send none. */
export function setAuthToken(token: string | null): void {
  staticToken = normalizeToken(token);
}

/** The bearer token the default client currently sends, or null. */
export function getAuthToken(): string | null {
  return staticToken;
}

/** Install (or with null remove) the default client's 401 handler. */
export function setUnauthorizedHandler(handler: ((rejectedToken: string | null) => Promise<boolean>) | null): void {
  staticUnauthorized = handler;
}

/** `defaultHttp.buildUrl`. */
export function buildUrl(path: string): string {
  return defaultHttp.buildUrl(path);
}

/** `defaultHttp.request`. */
export function request(path: string, init: RequestInit | undefined, context: string): Promise<Response> {
  return defaultHttp.request(path, init, context);
}

/** `defaultHttp.requestJson`. */
export function requestJson<T>(path: string, init: RequestInit | undefined, context: string): Promise<T | null> {
  return defaultHttp.requestJson<T>(path, init, context);
}
