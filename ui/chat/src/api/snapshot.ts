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
        // `input:initial` is the RAW message, always — Conversation.java:337
        // stores it unmasked even for a secret turn, and the masked copy
        // (conversationOutput["input"]) is filtered off the wire entirely. So a
        // rebuild would print the user's password in clear unless we mask it
        // here from what the session knows was secret.
        messages.push(
          makeMessage("user", secretTexts.has(text) ? SECRET_MASK : text),
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
      }
      // actions / quickReplies are control data, not transcript content.
    }
  }
  return messages;
}
