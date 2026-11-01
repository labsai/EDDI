import type { ChatMessage } from "@/lib/api/chat";

/**
 * Which conversation step each chat message belongs to — reply ratings are
 * keyed by step on the server. Step 0 is the conversation start (the greeting);
 * each message the person sends opens the next step. A transcript rebuilt from
 * a snapshot carries the step on every message (`stepIndex`); live messages are
 * counted from the nearest one that does. Decisions and notices belong to none.
 *
 * The Chat UI has the same rule in `ui/chat/src/rating-steps.ts`; the two apps
 * do not import from each other.
 */
export function stepIndexes(messages: readonly ChatMessage[]): (number | null)[] {
  let step = 0;
  return messages.map((message) => {
    if (message.role === "system" || message.kind) return null;
    if (typeof message.stepIndex === "number") step = message.stepIndex;
    else if (message.role === "user") step += 1;
    return step;
  });
}

/** The step whose last finished agent message sits at each position, or null. */
export function ratableSteps(messages: readonly ChatMessage[]): (number | null)[] {
  const steps = stepIndexes(messages);
  return messages.map((message, i) => {
    const step = steps[i] ?? null;
    if (step === null || message.role !== "agent" || message.isStreaming || message.isError || !message.content.trim()) return null;
    for (let j = i + 1; j < messages.length; j++) {
      if (steps[j] === null) continue;
      if (steps[j] !== step) break;
      if (messages[j]!.role === "agent") return null;
    }
    return step;
  });
}

/** The agent's replies to the person so far (the greeting does not count). */
export function agentReplyCount(messages: readonly ChatMessage[]): number {
  return ratableSteps(messages).filter((step) => step !== null && step > 0).length;
}

/** The step of the last message that belongs to one, or null. */
export function lastStepIndex(messages: readonly ChatMessage[]): number | null {
  const steps = stepIndexes(messages);
  for (let i = steps.length - 1; i >= 0; i--) {
    if (steps[i] !== null) return steps[i]!;
  }
  return null;
}
