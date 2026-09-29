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

import { extractOutputTexts } from "./sse-events";
import type { ChatMessage, ConversationStep } from "@/types";

const INPUT_INITIAL = "input:initial";
const OUTPUT_PREFIX = "output";

/** Same mask the composer shows when a secret turn is sent. */
export const SECRET_MASK = "●●●●●●●●";

/**
 * Placeholder the backend now persists in `input:initial` for a secret-flagged
 * turn (Conversation.scrubSecretUserInput). Seeing it means the raw text was
 * scrubbed server-side, so masking survives a reload without the session ref.
 */
const SECRET_INPUT_PLACEHOLDER = "<secret input>";

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
): ChatMessage[] {
  if (!Array.isArray(steps)) return [];

  const messages: ChatMessage[] = [];
  for (const step of steps) {
    const data = step?.conversationStep;
    if (!Array.isArray(data)) continue;

    for (const entry of data) {
      const key = entry?.key;
      if (typeof key !== "string") continue;

      if (key === INPUT_INITIAL) {
        const text = typeof entry.value === "string" ? entry.value.trim() : "";
        if (!text) continue;
        // The backend now scrubs `input:initial` to the placeholder for a
        // secret-flagged turn (Conversation.scrubSecretUserInput), so masking
        // survives a reload. We still consult the session set as a fallback for
        // turns sent to an older backend, and normalise both to the same mask so
        // the transcript never prints either the raw secret or the raw
        // placeholder token.
        const isSecret =
          text === SECRET_INPUT_PLACEHOLDER || secretTexts.has(text);
        messages.push(makeMessage("user", isSecret ? SECRET_MASK : text));
        continue;
      }

      if (key.startsWith(OUTPUT_PREFIX)) {
        // `value` is normally a list of output items, but HITL writes bare
        // strings, and a single string is possible too.
        const items = Array.isArray(entry.value) ? entry.value : [entry.value];
        for (const text of extractOutputTexts(items)) {
          messages.push(makeMessage("agent", text));
        }
      }
      // actions / quickReplies are control data, not transcript content.
    }
  }
  return messages;
}
