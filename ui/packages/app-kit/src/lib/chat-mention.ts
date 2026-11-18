import type { ReactNode } from "react";
import { stepIndexes, type StepMessage } from "@eddi/core";

/**
 * @mention in the chat composer (T2.2): type `@`, pick an agent or a team, and
 * the message is handed over to it instead of being sent to the agent of this
 * chat. The host decides who may be mentioned (`candidates`, which Workforce
 * fills from the catalog: only what the caller may USE) and what a hand-off
 * does (`onHandOff`). With no `mention` prop the composer is unchanged.
 */

export interface ChatMentionCandidate {
  kind: "agent" | "team";
  id: string;
  name: string;
  description?: string | null;
}

export interface ChatMentionConfig {
  candidates: readonly ChatMentionCandidate[];
  /**
   * Hand `text` over to `target`. Return false to keep the text and the target
   * in the composer (nothing was handed over).
   */
  onHandOff: (target: ChatMentionCandidate, text: string) => boolean | void;
}

/** Options shown at once; the rest are reached by typing more. */
export const MAX_MENTION_OPTIONS = 8;

/**
 * The `@query` being typed at the caret: an `@` at the start of the text or
 * after whitespace, with no whitespace between it and the caret. Null when the
 * caret is not in one (an e-mail address is not a mention: its `@` follows a
 * letter).
 */
export function mentionQueryAt(value: string, caret: number): { start: number; query: string } | null {
  const end = Math.max(0, Math.min(caret, value.length));
  for (let i = end - 1; i >= 0; i--) {
    const ch = value[i]!;
    if (ch === "@") {
      if (i > 0 && !/\s/.test(value[i - 1]!)) return null;
      return { start: i, query: value.slice(i + 1, end) };
    }
    if (/\s/.test(ch)) return null;
  }
  return null;
}

/** Candidates whose name starts with the query first, then those containing it; case-insensitive. */
export function filterMentionCandidates(
  candidates: readonly ChatMentionCandidate[],
  query: string,
  limit = MAX_MENTION_OPTIONS,
): ChatMentionCandidate[] {
  const q = query.trim().toLocaleLowerCase();
  if (!q) return candidates.slice(0, limit);
  const starts: ChatMentionCandidate[] = [];
  const contains: ChatMentionCandidate[] = [];
  for (const c of candidates) {
    const name = c.name.toLocaleLowerCase();
    if (name.startsWith(q)) starts.push(c);
    else if (name.includes(q)) contains.push(c);
  }
  return [...starts, ...contains].slice(0, limit);
}

/** The id of one option, for `aria-activedescendant`. */
export function mentionOptionId(listId: string, index: number): string {
  return `${listId}-opt-${index}`;
}


/** A line the host puts into the transcript (see `ChatPanelProps.transcriptMarkers` in chat-panel.tsx). */
export interface ChatTranscriptMarker {
  id: string;
  afterStep: number | null;
  content: ReactNode;
}

/**
 * Where each marker goes: message position → markers after it, plus the ones
 * before the first message (`top`) and after the last (`end`).
 */
export function markerPositions(
  messages: readonly StepMessage[],
  markers: readonly ChatTranscriptMarker[],
): { top: ChatTranscriptMarker[]; after: Map<number, ChatTranscriptMarker[]>; end: ChatTranscriptMarker[] } {
  const top: ChatTranscriptMarker[] = [];
  const end: ChatTranscriptMarker[] = [];
  const after = new Map<number, ChatTranscriptMarker[]>();
  if (markers.length === 0) return { top, after, end };
  const lastAt = new Map<number, number>();
  stepIndexes(messages).forEach((step, i) => {
    if (step !== null) lastAt.set(step, i);
  });
  for (const marker of markers) {
    if (marker.afterStep === null) {
      top.push(marker);
      continue;
    }
    const position = lastAt.get(marker.afterStep);
    if (position === undefined) end.push(marker);
    else after.set(position, [...(after.get(position) ?? []), marker]);
  }
  return { top, after, end };
}
