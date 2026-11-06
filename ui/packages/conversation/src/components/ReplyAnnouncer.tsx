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

import { useEffect, useState } from "react";

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

/**
 * The sentence to announce for the messages that completed since the last
 * render, or null. Pure, so the rule is testable without timers.
 */
export function nextAnnouncement(
  messages: readonly AnnouncedMessage[],
  seen: Set<string>,
  speaker: (message: AnnouncedMessage) => string,
): string | null {
  const fresh = messages.filter((m) => !m.isStreaming && !seen.has(m.id));
  for (const m of fresh) seen.add(m.id);
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
  className?: string;
}

/** A visually hidden polite status region; renders nothing visible. */
export function ReplyAnnouncer({ messages, speaker, className }: ReplyAnnouncerProps) {
  // What was on screen when the announcer mounted is history, not news.
  const [seen] = useState(() => new Set(messages.filter((m) => !m.isStreaming).map((m) => m.id)));
  const [text, setText] = useState("");
  useEffect(() => {
    const next = nextAnnouncement(messages, seen, speaker);
    if (next) setText(next);
  }, [messages, speaker, seen]);
  return (
    <div role="status" aria-live="polite" aria-atomic="true" className={className} data-testid="reply-announcer">
      {text}
    </div>
  );
}
