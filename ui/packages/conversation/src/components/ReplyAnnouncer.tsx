/* ──────────────────────────────────────────────
   ReplyAnnouncer — what a screen reader hears of a conversation

   A transcript that is itself a live region reads a streamed reply token by
   token, or not at all. This polite status region says each reply once, when
   it is complete, together with who said it — and the client's own notices
   ("Stop came too late…"). Messages that were already on screen, and a
   transcript replaced wholesale (a conversation opened from history), are not
   read out: only what arrives while the person is in the conversation.

   The transcript beside it should then be `aria-live="off"`.
   ────────────────────────────────────────────── */

import { useEffect, useRef, useState } from "react";

/** The fields of a message the announcer reads. */
export interface AnnouncedMessage {
  id: string;
  role: "user" | "agent";
  content: string;
  isStreaming?: boolean;
  kind?: string;
}

/** More new messages than this at once is a transcript being loaded, not a reply. */
const BULK = 3;
/** A long reply is read up to here; the transcript holds the rest. */
const MAX_CHARS = 600;

/** Markdown read aloud as words: no fences, emphasis marks or link targets. */
export function speakable(markdown: string): string {
  const text = markdown
    .replace(/```[\s\S]*?(```|$)/g, " ")
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/[*_~`#>|]+/g, "")
    .replace(/\s+/g, " ")
    .trim();
  return text.length > MAX_CHARS ? `${text.slice(0, MAX_CHARS).trimEnd()}…` : text;
}

/** What a message says, for telling a rebuilt transcript's old messages from new ones. */
function signature(m: AnnouncedMessage): string {
  return JSON.stringify([m.role, m.kind ?? "", m.content]);
}

/**
 * The sentence to announce for the messages that completed since the last
 * render, or null. Pure, so the rule is testable without timers.
 *
 * @param previous The completed messages of the last render. A transcript
 *   rebuilt in place (a rerun, an undo, a redo) gives every message a new id,
 *   so ids alone take the whole history for news; with `previous`, only what
 *   follows the part both lists share is new.
 */
export function nextAnnouncement(
  messages: readonly AnnouncedMessage[],
  seen: Set<string>,
  speaker: (message: AnnouncedMessage) => string,
  previous?: readonly AnnouncedMessage[],
): string | null {
  const completed = messages.filter((m) => !m.isStreaming);
  let fresh = completed.filter((m) => !seen.has(m.id));
  for (const m of fresh) seen.add(m.id);
  if (previous && fresh.length > 0) {
    // Rebuilt: what the two lists share from the start is history.
    let shared = 0;
    while (shared < previous.length && shared < completed.length && signature(previous[shared]!) === signature(completed[shared]!)) {
      shared += 1;
    }
    const tail = new Set(completed.slice(shared).map((m) => m.id));
    fresh = fresh.filter((m) => tail.has(m.id));
  }
  // A whole transcript at once: a conversation was opened, nothing was said.
  if (fresh.length > BULK) return null;
  const parts = fresh
    .filter((m) => m.role === "agent" && m.content.trim())
    .map((m) => {
      const text = speakable(m.content);
      return m.kind === "notice" ? text : `${speaker(m)} ${text}`;
    });
  return parts.length ? parts.join(" ") : null;
}

export interface ReplyAnnouncerProps {
  messages: readonly AnnouncedMessage[];
  /** "Assistant said:" — the speaker prefix for a reply. */
  speaker: (message: AnnouncedMessage) => string;
  /**
   * The conversation on screen (its id). When it changes, whatever the new
   * transcript holds is history, however short — not a reply to announce.
   */
  historyKey?: string | null;
  className?: string;
}

/** How long the region stays empty before an announcement identical to the last one is restored. */
const REPEAT_GAP_MS = 100;

/** A visually hidden polite status region; renders nothing visible. */
export function ReplyAnnouncer({ messages, speaker, historyKey, className }: ReplyAnnouncerProps) {
  // What was on screen when the announcer mounted is history, not news.
  const [seen] = useState(() => new Set(messages.filter((m) => !m.isStreaming).map((m) => m.id)));
  const [text, setText] = useState("");
  const previous = useRef<readonly AnnouncedMessage[]>(messages.filter((m) => !m.isStreaming));
  const key = useRef(historyKey);
  const textRef = useRef("");
  const repeat = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => {
    const completed = messages.filter((m) => !m.isStreaming);
    if (key.current !== historyKey) {
      // Another conversation: everything it shows is history.
      key.current = historyKey;
      for (const m of completed) seen.add(m.id);
      previous.current = completed;
      return;
    }
    const next = nextAnnouncement(messages, seen, speaker, previous.current);
    previous.current = completed;
    if (!next) return;
    clearTimeout(repeat.current);
    if (next === textRef.current) {
      // The same words again (two replies of "OK"): an unchanged region is not
      // read out, so empty it first and restore the text a moment later.
      textRef.current = "";
      setText("");
      repeat.current = setTimeout(() => {
        textRef.current = next;
        setText(next);
      }, REPEAT_GAP_MS);
      return;
    }
    textRef.current = next;
    setText(next);
  }, [messages, speaker, seen, historyKey]);
  useEffect(() => () => clearTimeout(repeat.current), []);
  return (
    <div role="status" aria-live="polite" aria-atomic="true" className={className} data-testid="reply-announcer">
      {text}
    </div>
  );
}
