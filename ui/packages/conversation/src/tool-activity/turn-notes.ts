/* ──────────────────────────────────────────────
   Turn notes (T2.7): why an answer came the way it did, in plain words

   What the stream says about a turn beyond its text and its tools:

   - `cascade_escalation {fromStep, toStep, confidence, threshold, reason,
     durationMs}` — a model cascade gave up on a step and asked the next model
     (docs/model-cascade.md). `reason` is one of `low_confidence`, `timeout`,
     `error`, `retryable_error`, `invalid_output`.
   - `llm_retry {reason, attempt}` — the same model was asked again because its
     reply was unusable (`empty`, `truncated`, `content_filter`,
     `invalid_json`, `schema_mismatch`, `context_too_long`).
   - `guardrail_triggered {type, phase, action}` — already kept on the reply
     itself by the conversation controller (`ConversationMessage.guardrails`);
     {@link guardrailNoteKey} words it.

   Only what those events carry is explained. Nothing is inferred: a cost
   ceiling that stopped a cascade early, a model's name or price, and a
   non-streamed turn send no event, so they get no note. Live only, like the
   tool cards: nothing here is stored with the conversation in a form a client
   may read, so a reload shows no notes for earlier turns.
   ────────────────────────────────────────────── */

import type { SSEEvent } from "@eddi/core";

/** One thing the stream said about how a turn's answer was reached. */
export type TurnNote =
  | { readonly kind: "escalation"; readonly reason: string }
  | { readonly kind: "retry"; readonly reason: string };

/** The notes of one conversation, by step index. */
export type TurnNoteTurns = ReadonlyMap<number, readonly TurnNote[]>;

const EMPTY: TurnNoteTurns = new Map();
const NO_CONVERSATION = "";

/** Notes kept per turn; a cascade has a handful of steps, a retry policy at most three re-asks. */
export const MAX_TURN_NOTES_PER_TURN = 10;
export const MAX_TURN_NOTE_CONVERSATIONS = 20;

function str(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

/**
 * The note a stream event carries, or null. Unknown events, malformed JSON and
 * a payload without a reason give null: a note says only what the server said.
 */
export function parseTurnNote(event: SSEEvent): TurnNote | null {
  if (event.type !== "cascade_escalation" && event.type !== "llm_retry") return null;
  let payload: unknown;
  try {
    payload = JSON.parse(event.data);
  } catch {
    return null;
  }
  if (!payload || typeof payload !== "object") return null;
  const reason = str((payload as { reason?: unknown }).reason);
  if (!reason) return null;
  return event.type === "cascade_escalation" ? { kind: "escalation", reason } : { kind: "retry", reason };
}

/** An `onStreamEvent` hook that hands each note to `record`. */
export function turnNoteListener(record: (note: TurnNote) => void): (event: SSEEvent) => void {
  return (event) => {
    const note = parseTurnNote(event);
    if (note) record(note);
  };
}

/**
 * The notes of the turns a page watched, per conversation and step — the same
 * bookkeeping as `ToolActivityLog`: `beginTurn` empties the turn's step and
 * every later (undone) one, `clearStep` forgets a step that ran again without a
 * stream, and the least recently changed conversation is forgotten first.
 */
export class TurnNotesLog {
  private readonly conversations = new Map<string, TurnNoteTurns>();
  private readonly listeners = new Set<() => void>();

  beginTurn(conversationId: string | null, step: number | null): void {
    if (step === null) return;
    this.update(conversationId, (turns) => {
      const next = new Map(turns);
      for (const key of turns.keys()) if (key >= step) next.delete(key);
      return next.size === turns.size ? turns : next;
    });
  }

  /** One note for the turn on `step`. The same note twice in a turn is kept once. */
  record(conversationId: string | null, step: number | null, note: TurnNote): void {
    if (step === null) return;
    this.update(conversationId, (turns) => {
      const current = turns.get(step) ?? [];
      if (current.length >= MAX_TURN_NOTES_PER_TURN) return turns;
      if (current.some((n) => n.kind === note.kind && n.reason === note.reason)) return turns;
      return new Map(turns).set(step, [...current, note]);
    });
  }

  clearStep(conversationId: string | null, step: number | null): void {
    if (step === null) return;
    this.update(conversationId, (turns) => {
      if (!turns.has(step)) return turns;
      const next = new Map(turns);
      next.delete(step);
      return next;
    });
  }

  clear(): void {
    if (this.conversations.size === 0) return;
    this.conversations.clear();
    this.emit();
  }

  /** The same object until the conversation's notes change (for `useSyncExternalStore`). */
  turns(conversationId: string | null): TurnNoteTurns {
    return this.conversations.get(conversationId ?? NO_CONVERSATION) ?? EMPTY;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  private update(conversationId: string | null, change: (turns: TurnNoteTurns) => TurnNoteTurns): void {
    const key = conversationId ?? NO_CONVERSATION;
    const before = this.conversations.get(key) ?? EMPTY;
    const after = change(before);
    if (after === before) return;
    this.conversations.delete(key);
    if (after.size > 0) this.conversations.set(key, after);
    while (this.conversations.size > MAX_TURN_NOTE_CONVERSATIONS) {
      this.conversations.delete(this.conversations.keys().next().value as string);
    }
    this.emit();
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }
}

/** The i18n key (under `turnNotes.`) and English fallback of a note. */
export function turnNoteKey(note: TurnNote): [key: string, fallback: string] {
  if (note.kind === "escalation") {
    switch (note.reason) {
      case "low_confidence":
        return ["turnNotes.escalation.lowConfidence", "Answered by a larger model because the first answer wasn't confident enough."];
      case "timeout":
        return ["turnNotes.escalation.timeout", "Answered by another model because the first one took too long."];
      case "error":
      case "retryable_error":
        return ["turnNotes.escalation.error", "Answered by another model because the first one failed."];
      case "invalid_output":
        return ["turnNotes.escalation.invalidOutput", "Answered by another model because the first answer couldn't be used."];
      default:
        return ["turnNotes.escalation.other", "Answered by another model than the first one tried."];
    }
  }
  switch (note.reason) {
    case "empty":
      return ["turnNotes.retry.empty", "The model was asked again because its first reply was empty."];
    case "truncated":
      return ["turnNotes.retry.truncated", "The model was asked again because its first reply was cut off."];
    case "content_filter":
      return ["turnNotes.retry.contentFilter", "The model was asked again because its provider's content filter stopped the first reply."];
    case "invalid_json":
    case "schema_mismatch":
      return ["turnNotes.retry.format", "The model was asked again because its first reply wasn't in the expected format."];
    case "context_too_long":
      return ["turnNotes.retry.contextTooLong", "The model was asked again because the conversation was too long for it."];
    default:
      return ["turnNotes.retry.other", "The model was asked again because its first reply couldn't be used."];
  }
}

/** A guardrail notice as the controller keeps it on a reply (`GuardrailNotice`). */
export interface GuardrailNoteInput {
  type: string;
  phase: string;
  action: string;
}

const GUARDRAIL_KEYS: Record<string, [string, string]> = {
  "input:block": ["turnNotes.guardrail.inputBlock", "A safety check stopped your message ({{type}})."],
  "input:redact": ["turnNotes.guardrail.inputRedact", "A safety check removed part of your message before the agent saw it ({{type}})."],
  "input:warn": ["turnNotes.guardrail.inputWarn", "A safety check flagged your message ({{type}})."],
  "output:block": ["turnNotes.guardrail.outputBlock", "A safety check withheld the answer ({{type}})."],
  "output:redact": ["turnNotes.guardrail.outputRedact", "A safety check removed part of the answer ({{type}})."],
  "output:reprompt": ["turnNotes.guardrail.outputReprompt", "A safety check sent the answer back to be rewritten ({{type}})."],
  "output:warn": ["turnNotes.guardrail.outputWarn", "A safety check flagged the answer ({{type}})."],
};

/**
 * The i18n key and English fallback of a guardrail notice. A phase and action
 * this build has no sentence for still get one, naming the raw words — the
 * notice is never dropped.
 */
export function guardrailNoteKey(notice: GuardrailNoteInput): [key: string, fallback: string] {
  return (
    GUARDRAIL_KEYS[`${notice.phase}:${notice.action}`] ?? [
      "turnNotes.guardrail.other",
      "A safety check acted on this turn ({{type}}: {{phase}}, {{action}}).",
    ]
  );
}
