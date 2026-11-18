/* ──────────────────────────────────────────────
   How a turn that did not end with a reply is classified

   One classification for the three apps (the Manager, Workforce, the Chat
   widget). The controller decides it, from what it saw — an HTTP status, a stream
   `error` code, how much of the reply arrived — and every app renders from it
   instead of re-deriving it from the status:

   - `notAccepted`    the server never took the turn (413, 404, 403, 401, 503, 429,
                      a refusing stream code, a 409 that is not a pause). The draft
                      is handed back and the message leaves the transcript.
   - `unknownOutcome` the turn may have been accepted and may still finish: a 408,
                      a gateway timeout, a connection that dropped after the send,
                      a stream that ended with nothing. The message stays; the user
                      is told it may still be working; a retry carries the same
                      Idempotency-Key, so the turn runs once.
   - `ended`          the conversation is over (410, `conversation_ended`).
   - `paused`         the conversation waits for a human decision. Only the real
                      pause signal: `awaiting_approval`, or a 409 after which the
                      conversation reads AWAITING_HUMAN — not every 409.
   - `failed`         the turn ran and failed (a 5xx, a stream error mid-turn). A
                      visible notice, and a retry that retries this turn.
   - `notStored`      the turn ran but its result could not be saved
                      (`turn_not_stored`: 507 conversation too large, 422 data
                      rejected, 409 conflict, 503 store failure). The message
                      stays; sending again runs the turn again — or, when the
                      conversation is too large, a new conversation is the way on.
   - `empty`          the turn finished without an answer.

   New backend codes (a "turn was not stored" error event, a redacted
   `task_failed` with an error class and a `retryable` flag, a `cancelled`
   terminal event) are mapped HERE, in `STREAM_CODE_CLASSES` — an unknown code is
   `failed` (or `notAccepted` while nothing of the reply arrived and the code is
   one of the backend's refusals), never a crash and never silent.
   ────────────────────────────────────────────── */

import { UNCONSUMED_STREAM_ERROR_CODES } from "@eddi/core";

export type TurnFailureClass =
  | "notAccepted"
  | "unknownOutcome"
  | "ended"
  | "paused"
  | "failed"
  | "notStored"
  | "empty";

/**
 * Stream error codes with a class of their own. Everything else falls to the
 * rules in {@link classifyTurnFailure}. Add the backend's new codes here.
 */
export const STREAM_CODE_CLASSES: Readonly<Record<string, TurnFailureClass>> = {
  conversation_ended: "ended",
  awaiting_approval: "paused",
  // The REST body spells it TURN_NOT_STORED; codes are compared lower-cased.
  turn_not_stored: "notStored",
  // Refused at admission BEFORE the pipeline ran (507 CONVERSATION_TOO_LARGE): nothing ran.
  conversation_too_large: "notAccepted",
};

/**
 * What `turn_not_stored` means by its `reason`: the turn RAN and its result was
 * not saved, except where the reason says otherwise.
 *
 * - `conversation_too_large` (507) and `data_rejected` (422): not saved, and sending
 *   again cannot help.
 * - `conflict` and `fenced` (409): the turn ran on a stale view or after losing its
 *   lease; reload the conversation, then sending again is fine.
 * - `conversation_ended` (410): the conversation is over.
 * - `store_failure` and `outcome_unknown` (503): it may or may not have been
 *   stored — the unknown-outcome class: the message stays and the user decides.
 * - no reason: the generic "not saved". An unknown reason is the unknown-outcome
 *   class, never a guess in either direction.
 */
export function classifyNotStored(reason: string | null | undefined): TurnFailureClass {
  if (!reason) return "notStored";
  switch (reason.toLowerCase()) {
    case "conversation_too_large":
    case "data_rejected":
    case "conflict":
    case "fenced":
      return "notStored";
    case "conversation_ended":
      return "ended";
    default: // store_failure, outcome_unknown, and any reason this client does not know
      return "unknownOutcome";
  }
}

/** The class a (stream or REST) code maps to in {@link STREAM_CODE_CLASSES}, or null. */
export function mappedCode(code: string | null | undefined): string | null {
  return code && STREAM_CODE_CLASSES[code.toLowerCase()] ? code : null;
}

/** What an HTTP error's JSON body says, when it says it. */
export interface ErrorBody {
  code: string | null;
  retryable: boolean | null;
  reason: string | null;
}

/**
 * The `code`, `retryable` and `reason` of an HTTP error. Reads both client
 * shapes (@eddi/core's `ApiError`, whose `body` is the raw text, and the
 * Manager's `ApiClientError`, whose `body` is parsed) and both envelopes
 * (`{"error": {"code": …}}` and `{"error": "…", "code": …}`).
 */
export function errorBodyOf(err: unknown): ErrorBody {
  const none: ErrorBody = { code: null, retryable: null, reason: null };
  if (!err || typeof err !== "object") return none;
  let body: unknown = (err as { body?: unknown }).body;
  if (typeof body === "string") {
    try {
      body = JSON.parse(body);
    } catch {
      body = null;
    }
  }
  const root = body && typeof body === "object" ? (body as Record<string, unknown>) : null;
  const inner = root && root.error && typeof root.error === "object" ? (root.error as Record<string, unknown>) : null;
  const pick = (key: string): unknown => inner?.[key] ?? root?.[key];
  const code = pick("code");
  const retryable = pick("retryable");
  const reason = pick("reason");
  const clientCode = (err as { code?: unknown }).code;
  return {
    code: typeof code === "string" ? code : typeof clientCode === "string" ? clientCode : null,
    retryable: typeof retryable === "boolean" ? retryable : null,
    reason: typeof reason === "string" ? reason : null,
  };
}

/**
 * Whether an error is a genuine network failure — fetch's `TypeError`, a stream
 * read that broke, the client's own status-0 error. Anything else without a
 * status (a plain `Error` thrown by client code) is a bug, not "the agent may
 * still be working".
 */
export function isNetworkError(err: unknown): boolean {
  if (err instanceof TypeError) return true;
  if (typeof DOMException !== "undefined" && err instanceof DOMException) return err.name === "NetworkError";
  return (err as { status?: unknown } | null)?.status === 0;
}

/** Whether the request can have reached the server before it failed. */
export type Reach = "notReached" | "maybeReached" | "bug";

/** What the controller saw of a failed turn. */
export interface FailureSignal {
  /** The HTTP status; null or 0 for a request that never got an answer. */
  status: number | null;
  /** The stream `error` frame's code. */
  code: string | null;
  /** Whether any of the reply had arrived. */
  partial: boolean;
  /** For a failure without a status: could the request have reached the server? Default: maybe. */
  reach?: Reach;
  /** The body's or frame's `reason` (for `turn_not_stored`). */
  reason?: string | null;
}

/** Statuses after which the server may still have run (or may still be running) the turn. */
const UNKNOWN_OUTCOME_STATUSES: ReadonlySet<number> = new Set([408, 502, 504]);

/** The class of a turn that did not end with a reply. */
export function classifyTurnFailure({ status, code, partial, reach, reason }: FailureSignal): TurnFailureClass {
  if (code !== null) {
    const mapped = STREAM_CODE_CLASSES[code.toLowerCase()];
    if (mapped === "notStored") return classifyNotStored(reason);
    if (mapped) return mapped;
    // A refusal the backend sends before it runs the turn — but not once part of
    // the reply is on screen, then the turn did run.
    if (!partial && UNCONSUMED_STREAM_ERROR_CODES.has(code)) return "notAccepted";
    return "failed";
  }
  if (status === null || status === 0) {
    // Nothing can have reached the server (offline, a refused cross-origin
    // preflight): not sent. A bug in client code is not "still working" either.
    if (reach === "notReached") return "notAccepted";
    if (reach === "bug") return "failed";
    return "unknownOutcome";
  }
  if (status === 410) return "ended";
  if (UNKNOWN_OUTCOME_STATUSES.has(status)) return "unknownOutcome";
  if (status >= 400 && status < 500) return "notAccepted";
  if (status === 503) return "notAccepted";
  return "failed";
}

/** Whether the server turned the turn down before running it (the draft is handed back). */
export function isRefusal(cls: TurnFailureClass): boolean {
  return cls === "notAccepted" || cls === "ended" || cls === "paused";
}
