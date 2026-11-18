/* ──────────────────────────────────────────────
   The tool calls each turn ran, from the stream's `tool_result` events (B6)

   A plain store, like the controllers: `subscribe` and `getSnapshot`, read in
   React through `useToolActivity`. A host feeds it from AgentConversation's
   hooks — `beginTurn` from `onTurnStarting`, `record` from `onStreamEvent`
   through @eddi/core's `toolResultListener` — and reads it back per turn to draw
   the tool cards and to tell the user, before Edit or Regenerate, which tools a
   turn ran.

   Live only. The events are not stored with the conversation in a form a
   client may read: the detailed snapshot keeps the raw tool trace, which the
   server redacts only on its way out through the stream (`ToolTraceRedaction`).
   So a reload, or a conversation opened from the sidebar, shows no cards for
   its earlier turns — the turns this page watched keep theirs while it is open.
   ────────────────────────────────────────────── */

import { stepIndexes, type StepMessage } from "@eddi/core";
import type { ToolResultEvent } from "@eddi/core/tool-events";

/** The tool calls of one turn, in the order they finished. */
export interface ToolTurnActivity {
  readonly results: readonly ToolResultEvent[];
  /** Calls past {@link ToolActivityLogOptions.maxPerTurn}: counted, not kept. */
  readonly dropped: number;
}

export interface ToolActivityLogOptions {
  /** Results kept per turn; later ones are only counted. A model can call a tool a hundred times. */
  maxPerTurn?: number;
  /** Conversations remembered; the least recently changed one is forgotten first. */
  maxConversations?: number;
}

/** The turns of one conversation, by step index. */
export type ToolTurns = ReadonlyMap<number, ToolTurnActivity>;

const EMPTY: ToolTurns = new Map();

/** What a conversation without an id (not started yet) is filed under. */
const NO_CONVERSATION = "";

export const DEFAULT_MAX_TOOL_RESULTS_PER_TURN = 50;
export const DEFAULT_MAX_TOOL_CONVERSATIONS = 20;

/**
 * The tool calls of the turns a page watched, per conversation and step. A
 * step's entry is replaced, never merged, when a new turn takes that step (a
 * send after an undo, a rerun): what a tool did in a turn that was taken back is
 * not what the turn now on screen did.
 */
export class ToolActivityLog {
  private readonly maxPerTurn: number;
  private readonly maxConversations: number;
  /** Insertion order is recency: a changed conversation moves to the end. */
  private readonly conversations = new Map<string, ToolTurns>();
  private readonly listeners = new Set<() => void>();
  private version = 0;

  constructor(options: ToolActivityLogOptions = {}) {
    this.maxPerTurn = Math.max(1, options.maxPerTurn ?? DEFAULT_MAX_TOOL_RESULTS_PER_TURN);
    this.maxConversations = Math.max(1, options.maxConversations ?? DEFAULT_MAX_TOOL_CONVERSATIONS);
  }

  /**
   * A turn is starting on `step` (its question is already in the transcript):
   * forget what that step and every later one recorded. The later ones belong
   * to turns an undo took back, which a new turn makes unreachable for redo.
   */
  beginTurn(conversationId: string | null, step: number | null): void {
    if (step === null) return;
    this.update(conversationId, (turns) => {
      let changed = false;
      const next = new Map(turns);
      for (const key of turns.keys()) {
        if (key >= step) {
          next.delete(key);
          changed = true;
        }
      }
      return changed ? next : turns;
    });
  }

  /** One finished tool call of the turn on `step`. */
  record(conversationId: string | null, step: number | null, result: ToolResultEvent): void {
    if (step === null) return;
    this.update(conversationId, (turns) => {
      const current = turns.get(step) ?? { results: [], dropped: 0 };
      const entry: ToolTurnActivity =
        current.results.length < this.maxPerTurn
          ? { results: [...current.results, result], dropped: current.dropped }
          : { results: current.results, dropped: current.dropped + 1 };
      return new Map(turns).set(step, entry);
    });
  }

  /**
   * Forget one step — after a turn ran again without a stream (a rerun through
   * REST), whose tools this log never heard about.
   */
  clearStep(conversationId: string | null, step: number | null): void {
    if (step === null) return;
    this.update(conversationId, (turns) => {
      if (!turns.has(step)) return turns;
      const next = new Map(turns);
      next.delete(step);
      return next;
    });
  }

  /** Forget everything (a sign-out, a test). */
  clear(): void {
    if (this.conversations.size === 0) return;
    this.conversations.clear();
    this.version += 1;
    for (const listener of [...this.listeners]) listener();
  }

  /** The turns recorded for a conversation. The same object until it changes. */
  turns(conversationId: string | null): ToolTurns {
    return this.conversations.get(conversationId ?? NO_CONVERSATION) ?? EMPTY;
  }

  /** The tool calls of one turn, or undefined when it ran none this page saw. */
  forStep(conversationId: string | null, step: number | null): ToolTurnActivity | undefined {
    return step === null ? undefined : this.turns(conversationId).get(step);
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  /** Changes with every change — for `useSyncExternalStore`. */
  getSnapshot = (): number => this.version;

  private update(conversationId: string | null, change: (turns: ToolTurns) => ToolTurns): void {
    const key = conversationId ?? NO_CONVERSATION;
    const before = this.conversations.get(key) ?? EMPTY;
    const after = change(before);
    if (after === before) return;
    this.conversations.delete(key);
    if (after.size > 0) this.conversations.set(key, after);
    while (this.conversations.size > this.maxConversations) {
      const oldest = this.conversations.keys().next().value as string;
      this.conversations.delete(oldest);
    }
    this.version += 1;
    for (const listener of [...this.listeners]) listener();
  }
}

/** The distinct tool names of a turn, in the order they first ran. */
export function toolNames(activity: ToolTurnActivity | undefined): string[] {
  return activity ? [...new Set(activity.results.map((r) => r.tool))] : [];
}

/**
 * Where each turn's tool cards go in a transcript: position → step. Under the
 * turn's question, so they read in the order things happened — the question,
 * the tools it set off, the reply. A step with no question on screen (the
 * greeting) gets them after its last message. A step the transcript does not
 * show (undone) gets none.
 */
export function toolCardPositions(messages: readonly StepMessage[], turns: ToolTurns): Map<number, number> {
  const at = new Map<number, number>();
  if (turns.size === 0) return at;
  const steps = stepIndexes(messages);
  const questionAt = new Map<number, number>();
  const lastAt = new Map<number, number>();
  steps.forEach((step, i) => {
    if (step === null) return;
    lastAt.set(step, i);
    if (messages[i]?.role === "user" && !questionAt.has(step)) questionAt.set(step, i);
  });
  for (const step of turns.keys()) {
    const position = questionAt.get(step) ?? lastAt.get(step);
    if (position !== undefined) at.set(position, step);
  }
  return at;
}
