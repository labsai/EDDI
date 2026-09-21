/* ──────────────────────────────────────────────
   EDDI Chat — SSE payload interpretation
   Pure functions over the wire format, so the stream semantics are testable
   without mounting the widget.
   ────────────────────────────────────────────── */

import type { ConversationState, ConversationOutput } from "@/types";

/** The trimmed snapshot the `done` event carries — NOT the full snapshot. */
export interface DoneSnapshot {
  conversationState?: ConversationState;
  conversationOutputs?: ConversationOutput[];
}

/**
 * States on which ConversationService drops a queued turn without consuming
 * it (`processConversationStep` → `notifySkipped`).
 */
const SKIP_STATES: ReadonlyArray<ConversationState> = [
  "AWAITING_HUMAN",
  "IN_PROGRESS",
  "ENDED",
];

/**
 * Extract a human-readable message from an `error` event payload.
 * The backend emits `{"message":"…"}`; older/edge paths may send bare text.
 */
export function parseErrorMessage(data: string): string {
  if (!data.trim()) return "The agent reported an error.";
  try {
    const parsed = JSON.parse(data);
    if (parsed && typeof parsed.message === "string" && parsed.message.trim()) {
      return parsed.message;
    }
  } catch {
    // not JSON — fall through to the raw payload
  }
  return data;
}

/** Parse the `done` payload, tolerating an empty or malformed body. */
export function parseDoneSnapshot(data: string): DoneSnapshot | null {
  if (!data.trim()) return null;
  try {
    const parsed = JSON.parse(data);
    return parsed && typeof parsed === "object" ? (parsed as DoneSnapshot) : null;
  } catch {
    return null;
  }
}

/**
 * True when this `done` represents a turn the server dropped rather than
 * answered.
 *
 * `onSkipped` defaults to `onComplete`, so a skipped turn is indistinguishable
 * from a real one at the transport level. Two signals are needed:
 *
 *  1. No token was streamed, and
 *  2. the conversation was ALREADY in a skip state when we sent.
 *
 * The second condition is essential. `processConversationStep` skips based on
 * the state it finds on arrival — so a turn is only dropped if the pause (or
 * the in-flight turn, or the end) predates it. A turn that is accepted and then
 * pauses ALSO ends with zero tokens and AWAITING_HUMAN; treating that as
 * skipped told the user "your message was not sent" when it had been sent and
 * had caused the pause.
 */
export function isSkippedTurn(
  snapshot: DoneSnapshot | null,
  tokenCount: number,
  stateBeforeSend: ConversationState | null,
): boolean {
  if (!snapshot || tokenCount > 0) return false;
  const state = snapshot.conversationState;
  if (!state) return false;

  // IN_PROGRESS in a turn's OWN `done` can only mean the turn was dropped: an
  // accepted turn never reports itself as still running. The skip is a race
  // against the PERSISTED state, which the client cannot have observed, so the
  // prior-state guard must not be applied here.
  if (state === "IN_PROGRESS") return true;

  // AWAITING_HUMAN is the genuinely ambiguous case — an accepted turn that then
  // paused looks identical to one dropped into an already-paused conversation.
  // Only the state we held before sending separates them.
  if (state === "AWAITING_HUMAN") {
    return !!stateBeforeSend && SKIP_STATES.includes(stateBeforeSend);
  }

  // ENDED reached BY this turn (CONVERSATION_END) is a legitimate outcome; only
  // a send into an already-ended conversation is a drop.
  if (state === "ENDED") {
    return stateBeforeSend === "ENDED";
  }

  return false;
}

/**
 * True when THIS turn was accepted and then paused for human approval — as
 * opposed to being dropped because the conversation was already paused.
 */
export function isTurnPaused(
  snapshot: DoneSnapshot | null,
  stateBeforeSend: ConversationState | null,
): boolean {
  if (snapshot?.conversationState !== "AWAITING_HUMAN") return false;
  return !stateBeforeSend || !SKIP_STATES.includes(stateBeforeSend);
}

/** True when the conversation is waiting on a human decision. */
export function isPausedState(state: ConversationState | null): boolean {
  return state === "AWAITING_HUMAN";
}

/**
 * Pull the renderable text out of a conversation output's `output` array.
 *
 * Entries are NOT uniformly objects: HITL writes its pending-approval
 * placeholder and reviewer-rejection message as bare Java Strings, which
 * serialize to raw JSON strings. Reading only `.text` dropped them silently,
 * so a paused or rejected turn rendered as nothing at all.
 *
 * `inputField` items are excluded — they configure the composer rather than
 * appearing in the transcript.
 */
export function extractOutputTexts(output: unknown): string[] {
  if (!Array.isArray(output)) return [];

  const texts: string[] = [];
  for (const item of output) {
    if (typeof item === "string") {
      if (item.trim()) texts.push(item);
      continue;
    }
    if (item && typeof item === "object") {
      const record = item as { type?: string; text?: string };
      if (record.type === "inputField") continue;
      if (typeof record.text === "string" && record.text.trim()) {
        texts.push(record.text);
      }
    }
  }
  return texts;
}

/** User-facing copy explaining why a turn was dropped. */
export function skippedTurnMessage(state: ConversationState | undefined): string {
  switch (state) {
    case "AWAITING_HUMAN":
      return "This conversation is waiting for a reviewer to approve the previous step. Your message was not sent.";
    case "IN_PROGRESS":
      return "The agent is still working on your previous message. Your message was not sent — please try again in a moment.";
    case "ENDED":
      return "This conversation has ended. Your message was not sent.";
    default:
      return "Your message was not processed.";
  }
}
