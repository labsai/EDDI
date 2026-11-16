/* ──────────────────────────────────────────────
   Message — one message of a transcript

   The user's words as plain text, the agent's through the one markdown
   renderer, a notice (text the client wrote) as a system line. Designer
   images, attachments, guardrail notes, copy, reply ratings and a host's own
   actions (`actions`) sit under the bubble.
   ────────────────────────────────────────────── */

import { memo, useCallback, useEffect, useState, type ReactNode } from "react";
import type { RatingInput, RatingProfile, StoredRating } from "@eddi/core";
import type { ConversationMessage } from "../controller/state";
import { CopyButton, MarkdownContent } from "../markdown/Markdown";
import { useConversationText } from "../react/hooks";
import { StepRating } from "./StepRating";

export interface MessageProps {
  message: ConversationMessage;
  /**
   * Passed in rather than read from a store. The message is memoised, but a
   * store subscription re-rendered EVERY message on every token — re-parsing
   * the whole transcript's markdown per token.
   */
  enableMarkdown?: boolean;
  enableMath?: boolean;
  enableCodeHighlight?: boolean;
  enableRawHtml?: boolean;
  /** Who is speaking, for screen readers. Defaults to a generic "Assistant". */
  agentName?: string | null;
  /**
   * The step this message's thumbs rate — set only on the last agent message
   * of a step, and only when the agent asks for reply ratings.
   */
  ratingStep?: number | null;
  /** The stored rating of that step. */
  rating?: StoredRating;
  ratingProfile?: RatingProfile | null;
  /** Stable across renders, or the memoised messages re-render. */
  onRateStep?: (stepIndex: number, input: RatingInput | null) => Promise<boolean>;
  /** A host's own controls for this message (the `messageActions` slot). */
  actions?: ReactNode;
}

/** How often a streaming message re-parses its markdown, at most. */
const STREAM_RENDER_INTERVAL_MS = 50;

/**
 * The value, refreshed at most once per `ms` while `active`. A streaming reply
 * grows by a token at a time, and parsing the whole markdown document (raw
 * HTML, sanitise, highlight) per token is quadratic over the reply. When it
 * stops being active the real value is returned at once, so the final text
 * never lags.
 */
function useThrottledValue<T>(value: T, ms: number, active: boolean): T {
  const [shown, setShown] = useState(value);
  const [shownAt, setShownAt] = useState(0);
  // Leading edge, during render: the first token after a quiet spell shows in
  // the same pass instead of a frame later.
  if (active && value !== shown && Date.now() - shownAt >= ms) {
    setShown(value);
    setShownAt(Date.now());
  }
  // Trailing edge: whatever arrived inside the window is shown when it ends.
  useEffect(() => {
    if (!active || value === shown) return;
    const id = setTimeout(
      () => {
        setShown(value);
        setShownAt(Date.now());
      },
      Math.max(0, ms - (Date.now() - shownAt)),
    );
    return () => clearTimeout(id);
  }, [value, shown, shownAt, ms, active]);
  return active ? shown : value;
}

export const Message = memo(function Message({
  message,
  enableMarkdown = true,
  enableMath = true,
  enableCodeHighlight = true,
  enableRawHtml = true,
  agentName,
  ratingStep,
  rating,
  ratingProfile,
  onRateStep,
  actions,
}: MessageProps) {
  const t = useConversationText();
  const isUser = message.role === "user";
  const images = message.images ?? [];
  const hasContent = !!message.content;
  // Streaming messages re-parse markdown at a capped rate; see useThrottledValue.
  const renderedContent = useThrottledValue(message.content, STREAM_RENDER_INTERVAL_MS, !!message.isStreaming);
  const getMessageText = useCallback(() => message.content, [message.content]);

  // A notice is the client talking, not the agent: no avatar, no speaker.
  if (message.kind === "notice") {
    return (
      <div className="message message--notice" data-testid="message-notice">
        <div className="message__notice">{message.content}</div>
      </div>
    );
  }

  return (
    <div className={`message message--${message.role}`}>
      {/* Decorative initials. Who is speaking is said below, in words. */}
      <div className="message__avatar" aria-hidden="true">
        {isUser ? "U" : "E"}
      </div>
      <div className="message__bubble">
        <span className="chat-sr-only">
          {isUser
            ? t("conversation.speaker.you")
            : t("conversation.speaker.agent", {
                name: agentName?.trim() || t("conversation.speaker.agentDefault"),
              })}{" "}
        </span>
        {isUser ? (
          <p dir="auto" style={{ whiteSpace: "pre-wrap", margin: 0 }}>
            {message.content}
          </p>
        ) : enableMarkdown ? (
          <div className="markdown-body" dir="auto">
            {hasContent ? (
              <MarkdownContent enableMath={enableMath} enableCodeHighlight={enableCodeHighlight} enableRawHtml={enableRawHtml}>
                {renderedContent}
              </MarkdownContent>
            ) : images.length ? null : message.isStreaming ? (
              <span style={{ opacity: 0.5, fontStyle: "italic" }}>…</span>
            ) : (
              <p style={{ opacity: 0.5, fontStyle: "italic" }}>{t("conversation.message.noResponse")}</p>
            )}
          </div>
        ) : hasContent || !images.length ? (
          <p dir="auto" style={{ whiteSpace: "pre-wrap", margin: 0 }}>
            {message.content || (message.isStreaming ? "…" : t("conversation.message.noResponse"))}
          </p>
        ) : null}
        {images.length > 0 && (
          <div className="message__images" data-testid="message-images">
            {images.map((image, i) => (
              <img
                key={`${image.uri}-${i}`}
                className="message__image"
                src={image.uri}
                alt={image.alt ?? ""}
                loading="lazy"
                referrerPolicy="no-referrer"
              />
            ))}
          </div>
        )}
        {!isUser && hasContent && !message.isStreaming && (
          <CopyButton getText={getMessageText} label={t("conversation.copy.message")} className="message__copy" />
        )}
        {!isUser &&
          ratingStep !== undefined &&
          ratingStep !== null &&
          ratingProfile?.messageRating &&
          onRateStep && (
            <StepRating stepIndex={ratingStep} rating={rating} profile={ratingProfile} onRate={onRateStep} />
          )}
        {actions}
      </div>
    </div>
  );
});
