/* ──────────────────────────────────────────────
   @eddi/core — which conversation step a message belongs to
   Reply ratings are keyed on the server by step: step 0 is the conversation
   start (the greeting), and every turn the person sends opens the next one.
   A transcript rebuilt from a full snapshot says so on each message
   (`stepIndex`); the messages of a live turn do not, and are counted from the
   nearest message that does.

   Derived from the Chat UI's `rating-steps.ts` and the Manager's
   `lib/chat-rating-steps.ts`, which held the same rule twice.
   ────────────────────────────────────────────── */

/** The fields of a client's message these functions read. */
export interface StepMessage {
  role: string;
  content: string;
  stepIndex?: number;
  isStreaming?: boolean;
  /** Set on text the CLIENT wrote (a notice, a recorded decision). */
  kind?: string;
  /** An agent bubble that reports a failed turn. */
  isError?: boolean;
  images?: readonly unknown[];
}

/**
 * The step of every message, by position. A "system" message and anything
 * with a `kind` (a client notice, a recorded decision) belong to no step
 * (null): they are the client talking, not the agent.
 */
export function stepIndexes(messages: readonly StepMessage[]): (number | null)[] {
  let step = 0;
  return messages.map((message) => {
    if (message.role === "system" || message.kind) return null;
    if (typeof message.stepIndex === "number") {
      step = message.stepIndex;
    } else if (message.role === "user") {
      step += 1;
    }
    return step;
  });
}

/**
 * The step whose last agent message sits at each position, or null. One reply
 * rating covers a whole step, so a step that answered with several bubbles
 * gets the thumbs once, under its last one — and only once it has finished
 * streaming. A failed turn's bubble and an empty one are not rated.
 */
export function ratableSteps(messages: readonly StepMessage[]): (number | null)[] {
  const steps = stepIndexes(messages);
  return messages.map((message, i) => {
    const step = steps[i] ?? null;
    if (step === null || message.role !== "agent" || message.isStreaming || message.isError) return null;
    if (!message.content.trim() && !message.images?.length) return null;
    for (let j = i + 1; j < messages.length; j++) {
      if (steps[j] === null) continue;
      if (steps[j] !== step) break;
      if (messages[j]?.role === "agent") return null;
    }
    return step;
  });
}

/** How many of the agent's replies to the person there are (the greeting does not count). */
export function agentReplyCount(messages: readonly StepMessage[]): number {
  return ratableSteps(messages).filter((step) => step !== null && step > 0).length;
}

/** The step of the last message that belongs to one, or null. */
export function lastStepIndex(messages: readonly StepMessage[]): number | null {
  const steps = stepIndexes(messages);
  for (let i = steps.length - 1; i >= 0; i--) {
    const step = steps[i];
    if (step !== null && step !== undefined) return step;
  }
  return null;
}
