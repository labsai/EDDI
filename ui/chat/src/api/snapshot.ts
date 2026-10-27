/* ──────────────────────────────────────────────
   EDDI Chat — conversation snapshot → transcript

   The wire shape here is NOT `{input, output}` per step. That shape was
   invented by this client and the backend cannot produce it. What GET
   /agents/{conversationId} actually returns per step is

       { conversationStep: [{ key, value, timestamp, originWorkflowId }],
         timestamp }

   with keys drawn from MemoryKeys: "input:initial", "output*",
   "quickReplies*", "actions*" (ConversationMemoryUtilities:186-207).
   ────────────────────────────────────────────── */

import { extractOutputImages, extractOutputTexts } from "./sse-events";
import type { ChatMessage, ConversationOutput, ConversationStep } from "@/types";

const INPUT_INITIAL = "input:initial";
const OUTPUT_PREFIX = "output";

/** MemoryKeys.SECRET_INPUT_PLACEHOLDER — what the backend shows for a secret turn. */
export const SECRET_INPUT_PLACEHOLDER = "<secret input>";

/** Same mask the composer shows when a secret turn is sent. */
export const SECRET_MASK = "●●●●●●●●";

/**
 * Ids are derived from the message's position in the conversation, not from a
 * global counter, so rebuilding the transcript after undo/redo keeps the keys
 * of every unchanged message: React updates those bubbles in place instead of
 * remounting them, replaying their entrance animation and re-parsing their
 * markdown.
 */
function makeMessage(id: string, role: "user" | "agent", content: string, stepIndex?: number): ChatMessage {
  const message: ChatMessage = { id: `snap-${id}-${role}`, role, content, timestamp: Date.now() };
  if (stepIndex !== undefined) message.stepIndex = stepIndex;
  return message;
}

/**
 * Whether a full read's `conversationOutputs` positions are the server's step
 * indexes. The backend fills both lists from the same memory, one entry per
 * step, so they match — but a response whose lists disagree in length cannot
 * be trusted to, and stamping a wrong index would key a reply rating to the
 * wrong step. Then nothing is stamped and live counting takes over.
 */
export function outputsCarryStepIndexes(
  outputs: readonly unknown[] | undefined | null,
  steps: readonly unknown[] | undefined | null,
): boolean {
  if (!outputs?.length) return false;
  return !steps?.length || steps.length === outputs.length;
}

/**
 * Rebuild a transcript from a snapshot's conversationSteps.
 *
 * Returns an empty list when the steps carry nothing renderable — including
 * when handed the old fictional shape. Callers MUST treat an empty result as
 * "could not rebuild" rather than "the conversation is empty": replacing a
 * populated transcript with [] is how undo/redo came to wipe the chat.
 *
 * `fullHistory` says the steps are the WHOLE conversation (undo/redo, a
 * conversation read), so a step's position is its index on the server and is
 * stamped on its messages (`stepIndex`), which reply ratings are keyed by. A
 * `returnCurrentStepOnly` response carries one step at index 0 and must not
 * stamp it.
 */
export function stepsToMessages(
  steps: ConversationStep[] | undefined | null,
  secretTexts: ReadonlySet<string> = new Set(),
  outputs?: ConversationOutput[] | null,
  fullHistory = false,
): ChatMessage[] {
  if (!Array.isArray(steps)) return [];

  const messages: ChatMessage[] = [];
  steps.forEach((step, index) => {
    const data = step?.conversationStep;
    if (!Array.isArray(data)) return;
    // The backend sends steps and outputs as parallel lists (both whole, or
    // both just the last step), so the same index names the same turn — but
    // only when the two agree in length. A drifted document pairs an index
    // with another turn's output, which would mask an ordinary message; the
    // backend's read-time masking pairs them under the same condition.
    const paired = Array.isArray(outputs) && outputs.length === steps.length;
    const secretTurn = paired && outputs[index]?.input === SECRET_INPUT_PLACEHOLDER;
    let part = 0;
    const nextId = () => `${index}-${part++}`;
    const stepNo = fullHistory ? index : undefined;

    for (const entry of data) {
      const key = entry?.key;
      if (typeof key !== "string") continue;

      if (key === INPUT_INITIAL) {
        const text = typeof entry.value === "string" ? entry.value.trim() : "";
        if (!text) continue;
        // The turn output's `input` is the masked display copy — "<secret
        // input>" for a secret turn — and is what makes a secret turn
        // recognisable after a reload. The engine scrubs a secret turn when it
        // ends and masks older stored turns on read, so `input:initial` reads
        // the placeholder too; masking here as well covers an older backend.
        // The session's own record covers a backend that sends neither.
        messages.push(
          makeMessage(
            nextId(),
            "user",
            secretTurn || text === SECRET_INPUT_PLACEHOLDER || secretTexts.has(text)
              ? SECRET_MASK
              : text,
            stepNo,
          ),
        );
        continue;
      }

      if (key.startsWith(OUTPUT_PREFIX)) {
        // `value` is normally a list of output items, but HITL writes bare
        // strings, and a single string is possible too.
        const items = Array.isArray(entry.value) ? entry.value : [entry.value];
        for (const text of extractOutputTexts(items)) {
          messages.push(makeMessage(nextId(), "agent", text, stepNo));
        }
        const images = extractOutputImages(items);
        if (images.length) {
          messages.push({ ...makeMessage(nextId(), "agent", "", stepNo), images });
        }
      }
      // actions / quickReplies are control data, not transcript content.
    }
  });
  return messages;
}
