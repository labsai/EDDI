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

let seq = 0;
function makeMessage(role: "user" | "agent", content: string): ChatMessage {
  seq += 1;
  return { id: `snap-${seq}-${role}`, role, content, timestamp: Date.now() };
}

/**
 * Rebuild a transcript from a snapshot's conversationSteps.
 *
 * Returns an empty list when the steps carry nothing renderable — including
 * when handed the old fictional shape. Callers MUST treat an empty result as
 * "could not rebuild" rather than "the conversation is empty": replacing a
 * populated transcript with [] is how undo/redo came to wipe the chat.
 */
export function stepsToMessages(
  steps: ConversationStep[] | undefined | null,
  secretTexts: ReadonlySet<string> = new Set(),
  outputs?: ConversationOutput[] | null,
): ChatMessage[] {
  if (!Array.isArray(steps)) return [];

  const messages: ChatMessage[] = [];
  steps.forEach((step, index) => {
    const data = step?.conversationStep;
    if (!Array.isArray(data)) return;
    // The backend sends steps and outputs as parallel lists (both whole, or
    // both just the last step), so the same index names the same turn.
    const secretTurn =
      Array.isArray(outputs) && outputs[index]?.input === SECRET_INPUT_PLACEHOLDER;

    for (const entry of data) {
      const key = entry?.key;
      if (typeof key !== "string") continue;

      if (key === INPUT_INITIAL) {
        const text = typeof entry.value === "string" ? entry.value.trim() : "";
        if (!text) continue;
        // The turn output's `input` is the masked display copy — "<secret
        // input>" for a secret turn — and is what makes a secret turn
        // recognisable after a reload. The engine now also scrubs
        // `input:initial` when a secret turn ends, but conversations stored
        // before that still carry it raw, so the mask is applied here too. The
        // session's own record covers a backend that sends neither.
        messages.push(
          makeMessage(
            "user",
            secretTurn || secretTexts.has(text) ? SECRET_MASK : text,
          ),
        );
        continue;
      }

      if (key.startsWith(OUTPUT_PREFIX)) {
        // `value` is normally a list of output items, but HITL writes bare
        // strings, and a single string is possible too.
        const items = Array.isArray(entry.value) ? entry.value : [entry.value];
        for (const text of extractOutputTexts(items)) {
          messages.push(makeMessage("agent", text));
        }
        const images = extractOutputImages(items);
        if (images.length) {
          messages.push({ ...makeMessage("agent", ""), images });
        }
      }
      // actions / quickReplies are control data, not transcript content.
    }
  });
  return messages;
}
