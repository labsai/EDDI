/* ──────────────────────────────────────────────
   EDDI Chat — which conversation step a message belongs to
   Reply ratings are keyed on the server by step: step 0 is the conversation
   start (the greeting), and every turn the person sends opens the next one.
   A transcript rebuilt from a full snapshot says so on each message
   (`stepIndex`); the messages of a live turn do not, and are counted from the
   nearest message that does.
   ────────────────────────────────────────────── */

import type { ChatMessage } from "@/types";

/**
 * The step of every message, by position. Widget notices belong to no step
 * (null): they are the widget talking, not the agent.
 */
export function stepIndexes(messages: readonly ChatMessage[]): (number | null)[] {
  let step = 0;
  return messages.map((message) => {
    if (message.kind === "notice") return null;
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
 * streaming.
 */
export function ratableSteps(messages: readonly ChatMessage[]): (number | null)[] {
  const steps = stepIndexes(messages);
  return messages.map((message, i) => {
    const step = steps[i];
    if (step === null || message.role !== "agent" || message.isStreaming) return null;
    if (!message.content.trim() && !message.images?.length) return null;
    for (let j = i + 1; j < messages.length; j++) {
      if (steps[j] === null) continue;
      if (steps[j] !== step) break;
      if (messages[j].role === "agent") return null;
    }
    return step;
  });
}

/** How many of the agent's replies to the person there are (the greeting does not count). */
export function agentReplyCount(messages: readonly ChatMessage[]): number {
  const steps = ratableSteps(messages);
  return steps.filter((step) => step !== null && step > 0).length;
}

/** The step of the last message that belongs to one, or null. */
export function lastStepIndex(messages: readonly ChatMessage[]): number | null {
  const steps = stepIndexes(messages);
  for (let i = steps.length - 1; i >= 0; i--) {
    if (steps[i] !== null) return steps[i];
  }
  return null;
}
