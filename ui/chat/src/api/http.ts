/* ──────────────────────────────────────────────
   EDDI Chat — HTTP core
   Status-aware fetch. Every non-2xx becomes an ApiError carrying the status
   and the server's body, so callers can distinguish 409 (conversation paused)
   from 403 (not the owner) from 404 (unknown conversation).
   ────────────────────────────────────────────── */

let _baseUrl = "";

/** Set the API base URL (e.g. from ChatConfig). Call once at startup. */
export function setBaseUrl(url: string): void {
  _baseUrl = url.replace(/\/$/, "");
}

export function buildUrl(path: string): string {
  return `${_baseUrl}${path}`;
}

/** Encode a single path segment so /, ?, # in data don't break the URL. */
export function encodeSegment(value: string): string {
  return encodeURIComponent(value);
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

/**
 * Fetch that throws ApiError on a non-2xx status, reading the body first so
 * the error carries it.
 */
export async function request(
  path: string,
  init: RequestInit | undefined,
  context: string,
): Promise<Response> {
  const res = await fetch(buildUrl(path), init);
  if (!res.ok) {
    let body = "";
    try {
      body = await res.text();
    } catch {
      // body already consumed or unreadable — status alone still informs
    }
    throw new ApiError(res.status, body, context);
  }
  return res;
}

/**
 * As `request`, but parses a JSON body. Returns null for an empty body — the
 * backend answers undo/redo/resume with 200 and no content, and calling
 * res.json() on that throws.
 */
export async function requestJson<T>(
  path: string,
  init: RequestInit | undefined,
  context: string,
): Promise<T | null> {
  const res = await request(path, init, context);
  const text = await res.text();
  if (!text.trim()) return null;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(res.status, text, `${context}: malformed JSON response`);
  }
}
