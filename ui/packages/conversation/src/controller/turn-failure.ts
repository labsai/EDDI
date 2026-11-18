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
   - `empty`          the turn finished without an answer.

   New backend codes (a "turn was not stored" error event, a redacted
   `task_failed` with an error class and a `retryable` flag, a `cancelled`
   terminal event) are mapped HERE, in `STREAM_CODE_CLASSES` — an unknown code is
   `failed` (or `notAccepted` while nothing of the reply arrived and the code is
   one of the backend's refusals), never a crash and never silent.
   ────────────────────────────────────────────── */

import { UNCONSUMED_STREAM_ERROR_CODES } from "@eddi/core";

export type TurnFailureClass = "notAccepted" | "unknownOutcome" | "ended" | "paused" | "failed" | "empty";

/**
 * Stream error codes with a class of their own. Everything else falls to the
 * rules in {@link classifyTurnFailure}. Add the backend's new codes here.
 */
export const STREAM_CODE_CLASSES: Readonly<Record<string, TurnFailureClass>> = {
  conversation_ended: "ended",
  awaiting_approval: "paused",
};

/** What the controller saw of a failed turn. */
export interface FailureSignal {
  /** The HTTP status; null or 0 for a request that never got an answer. */
  status: number | null;
  /** The stream `error` frame's code. */
  code: string | null;
  /** Whether any of the reply had arrived. */
  partial: boolean;
}

/** Statuses after which the server may still have run (or may still be running) the turn. */
const UNKNOWN_OUTCOME_STATUSES: ReadonlySet<number> = new Set([408, 502, 504]);

/** The class of a turn that did not end with a reply. */
export function classifyTurnFailure({ status, code, partial }: FailureSignal): TurnFailureClass {
  if (code !== null) {
    const mapped = STREAM_CODE_CLASSES[code];
    if (mapped) return mapped;
    // A refusal the backend sends before it runs the turn — but not once part of
    // the reply is on screen, then the turn did run.
    if (!partial && UNCONSUMED_STREAM_ERROR_CODES.has(code)) return "notAccepted";
    return "failed";
  }
  if (status === null || status === 0) return "unknownOutcome";
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
