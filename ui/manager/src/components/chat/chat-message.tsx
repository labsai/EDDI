import { memo, useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import { markdownImageAsLink } from "@/lib/markdown-safe";
import remarkGfm from "remark-gfm";
import { cn } from "@/lib/utils";
import type { ChatMessage as ChatMessageType, MessageAttachment } from "@/lib/api/chat";
import type { DecisionRecord } from "@/lib/api/groups";
import {
  formatMarkdownText,
  parseVerdictJson,
  type VerdictJson,
} from "@/components/groups/group-utils";
import { DecisionRecordCard } from "@/components/groups/decision-record-card";
import { StructuredEntryBody } from "@/components/groups/structured-entry-body";
import { entryBodyToMarkdown, isStructuredBody, readMessageBody } from "@/lib/group-entry-body";
import { isImageMime, formatBytes } from "@/lib/api/attachments";
import type { OwnRating, RatingInput, RatingProfile } from "@/lib/api/ratings";
import { ChatStepRating } from "./chat-rating";
import { Bot, User, Copy, Check, FileText, AlertTriangle, ShieldAlert } from "lucide-react";

// ==================== Helpers ====================

/**
 * A judge's verdict in the shape `DecisionRecordCard` renders. The judge names a
 * tie as a side ("TIE"); the card's contract is `winner: null` for a tie.
 */
function verdictToDecision(verdict: VerdictJson): DecisionRecord {
  const winner =
    verdict.winner && verdict.winner.trim().toUpperCase() !== "TIE" ? verdict.winner : null;
  return {
    type: "VERDICT",
    outcome: null,
    winner,
    tally: verdict.scores,
    dissents: [],
    method: null,
    decidedAtPhase: null,
  };
}

function formatShortTime(ts: number): string {
  return new Date(ts).toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatFullTime(ts: number): string {
  return new Date(ts).toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

// ==================== Component ====================

interface ChatMessageProps {
  message: ChatMessageType;
  /** The step this bubble's thumbs rate — only the last agent bubble of a step, only when the agent asks for reply ratings. */
  ratingStep?: number | null;
  rating?: OwnRating;
  ratingProfile?: RatingProfile | null;
  /** Stable across renders, or every memoised bubble re-renders. */
  onRateStep?: (stepIndex: number, input: RatingInput | null) => Promise<boolean>;
}

export const ChatMessage = memo(function ChatMessage({
  message,
  ratingStep,
  rating,
  ratingProfile,
  onRateStep,
}: ChatMessageProps) {
  const isUser = message.role === "user";
  const [hovered, setHovered] = useState(false);
  const { t } = useTranslation();

  // An agent that serves as a debate judge answers with a `{winner, scores,
  // reasoning}` object, and chatting with it directly printed that object
  // verbatim — escaped newlines and all. Not parsed mid-stream: the JSON is
  // incomplete until the last token, and re-parsing on every token is waste.
  const verdict = useMemo(
    () => (isUser || message.isStreaming ? null : parseVerdictJson(message.content)),
    [isUser, message.isStreaming, message.content]
  );

  // A group member agent's own conversation holds its ballots, bid sheets,
  // bargaining moves and task plans as ordinary replies — the same contracts
  // the group transcripts render as cards, and the same reader.
  const structured = useMemo(
    () => (isUser || message.isStreaming || verdict ? null : readMessageBody(message.content)),
    [isUser, message.isStreaming, message.content, verdict]
  );

  // Copy what is on screen, not the wire format.
  const copyContent = useMemo(() => {
    if (structured) {
      const text = entryBodyToMarkdown(structured, (key, fallback, options) =>
        t(key, { ...options, defaultValue: fallback })
      );
      return text || message.content;
    }
    if (!verdict) return message.content;
    const decision = verdictToDecision(verdict);
    const outcome = decision.winner
      ? t("groups.decisionWinner", "Winner: {{winner}}", { winner: decision.winner })
      : t("groups.decisionTie", "Tie");
    const scores = Object.entries(verdict.scores ?? {})
      .map(([side, score]) => `${side}: ${score}`)
      .join(" · ");
    return [scores ? `${outcome} (${scores})` : outcome, verdict.reasoning]
      .filter(Boolean)
      .join("\n\n");
  }, [structured, verdict, message.content, t]);

  return (
    <div
      className={cn(
        "group relative flex gap-3 px-4 py-3 min-w-0",
        isUser ? "flex-row-reverse" : "flex-row"
      )}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {/* Avatar */}
      <div
        className={cn(
          "flex h-8 w-8 shrink-0 items-center justify-center rounded-full",
          isUser
            ? "bg-primary text-primary-foreground"
            : "bg-accent/20 text-accent"
        )}
      >
        {isUser ? (
          <User className="h-4 w-4" />
        ) : (
          <Bot className="h-4 w-4" />
        )}
      </div>

      {/* Content column */}
      <div className={cn("flex flex-col gap-1 max-w-[min(75%,48rem)] overflow-hidden", isUser && "items-end")}>
        {/* Bubble */}
        <div
          className={cn(
            "rounded-2xl px-4 py-2.5 text-sm leading-relaxed",
            isUser
              ? "bg-primary text-primary-foreground rounded-ee-md"
              : "bg-card border border-border text-card-foreground rounded-es-md",
            message.isStreaming && "animate-pulse"
          )}
        >
          {isUser ? (
            <div className="flex flex-col gap-2">
              {message.attachments?.length ? (
                <MessageAttachments attachments={message.attachments} />
              ) : null}
              {message.content && (
                <p className="whitespace-pre-wrap">{message.content}</p>
              )}
            </div>
          ) : (
            <div className="prose prose-sm dark:prose-invert max-w-none overflow-hidden [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs">
              {verdict ? (
                <div className="flex flex-col gap-3" data-testid="chat-verdict">
                  <DecisionRecordCard decision={verdictToDecision(verdict)} className="not-prose" />
                  {verdict.reasoning && (
                    <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownImageAsLink}>
                      {formatMarkdownText(verdict.reasoning)}
                    </ReactMarkdown>
                  )}
                </div>
              ) : structured && isStructuredBody(structured) ? (
                <div className="not-prose">
                  <StructuredEntryBody body={structured} />
                </div>
              ) : message.content ? (
                /* Deliberately NO rehypeRaw: bot/LLM output is untrusted, so
                   raw HTML stays escaped rather than being injected live. */
                <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownImageAsLink}>
                  {structured?.kind === "markdown"
                    ? structured.text
                    : formatMarkdownText(message.content)}
                </ReactMarkdown>
              ) : message.isStreaming ? (
                <TypingIndicator />
              ) : (
                <p className="text-muted-foreground italic">{t("groups.noResponse", "No response")}</p>
              )}
            </div>
          )}
        </div>

        {!isUser && message.guardrails?.length ? (
          <GuardrailNotices notices={message.guardrails} />
        ) : null}

        {/* Timestamp + hover actions row */}
        <div className={cn(
          "flex h-5 items-center gap-1.5 px-1",
          isUser ? "flex-row-reverse" : "flex-row"
        )}>
          {/* Timestamp */}
          <span
            className="text-[10px] text-muted-foreground/60 select-none"
            title={formatFullTime(message.timestamp)}
          >
            {formatShortTime(message.timestamp)}
          </span>

          {/* Hover actions — only for agent messages with content */}
          {!isUser && message.content && (
            <div className={cn("transition-opacity duration-150", hovered ? "opacity-100" : "opacity-0 focus-within:opacity-100")}>
              <CopyMessageButton content={copyContent} />
            </div>
          )}
        </div>

        {/* Reply thumbs: shown on hover/focus, always once rated, always on touch */}
        {!isUser && ratingStep !== undefined && ratingStep !== null && ratingProfile?.messageRating && onRateStep && (
          <div className={cn("px-1 transition-opacity duration-150 [@media(hover:none)]:opacity-100", hovered || rating ? "opacity-100" : "opacity-0 focus-within:opacity-100")}>
            <ChatStepRating stepIndex={ratingStep} rating={rating} profile={ratingProfile} onRate={onRateStep} />
          </div>
        )}
      </div>
    </div>
  );
});

// ==================== Guardrail notices ====================

/**
 * One quiet line per guardrail that fired on this turn: the phase it ran in, which
 * guardrail, and what it did. The outcome itself is already in the bubble (a
 * block message, a rewritten answer); this is the reason.
 */
function GuardrailNotices({ notices }: { notices: NonNullable<ChatMessageType["guardrails"]> }) {
  const { t } = useTranslation();
  return (
    <ul className="flex flex-col gap-0.5 px-1" data-testid="guardrail-notices">
      {notices.map((n, i) => {
        const phase =
          n.phase === "input"
            ? t("chat.guardrailPhaseInput", "input")
            : n.phase === "output"
              ? t("chat.guardrailPhaseOutput", "output")
              : n.phase;
        const action =
          n.action === "block"
            ? t("chat.guardrailActionBlock", "blocked")
            : n.action === "redact"
              ? t("chat.guardrailActionRedact", "redacted")
              : n.action === "reprompt"
                ? t("chat.guardrailActionReprompt", "re-asked the model")
                : n.action === "warn"
                  ? t("chat.guardrailActionWarn", "flagged")
                  : n.action;
        return (
          <li
            key={i}
            className="inline-flex items-center gap-1 text-[10px] text-muted-foreground"
            data-testid="guardrail-notice"
          >
            <ShieldAlert className="h-3 w-3 shrink-0" aria-hidden="true" />
            {t("chat.guardrailNotice", "Guardrail ({{phase}}): {{type}} {{action}}", {
              phase,
              type: n.type,
              action,
            })}
          </li>
        );
      })}
    </ul>
  );
}

// ==================== Attachments ====================

/** Render the attachments a user sent with a message (image thumbnails / file chips). */
export function MessageAttachments({ attachments }: { attachments: MessageAttachment[] }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap gap-2" data-testid="message-attachments">
      {attachments.map((att, i) => {
        const showImage = isImageMime(att.mimeType) && att.previewUrl;
        const tooLarge = att.forwardableInline === false;
        return (
          <div key={`${att.fileName}-${i}`} className="flex flex-col gap-1">
            {showImage ? (
              <img
                src={att.previewUrl}
                alt={att.fileName}
                className="max-h-40 max-w-[220px] rounded-lg object-cover"
              />
            ) : (
              <div
                className="flex items-center gap-2 rounded-lg bg-primary-foreground/10 px-2.5 py-1.5"
                title={att.fileName}
              >
                <FileText className="h-4 w-4 shrink-0 opacity-80" />
                <div className="flex min-w-0 flex-col text-xs">
                  <span className="max-w-[160px] truncate font-medium">{att.fileName}</span>
                  {att.sizeBytes != null && (
                    <span className="opacity-70">{formatBytes(att.sizeBytes)}</span>
                  )}
                </div>
              </div>
            )}
            {tooLarge && (
              <span
                className="inline-flex items-center gap-1 text-[10px] text-amber-200/90"
                data-testid="attachment-not-forwarded"
              >
                <AlertTriangle className="h-3 w-3" />
                {t("chat.attachNotForwarded", "Not sent to model")}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ==================== Copy Button ====================

function CopyMessageButton({ content }: { content: string }) {
  const [copied, setCopied] = useState(false);
  const { t } = useTranslation();

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard API unavailable (non-secure context or permission denied)
    }
  }, [content]);

  return (
    <button
      onClick={handleCopy}
      className={cn(
        "inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] transition-colors",
        copied
          ? "text-emerald-500"
          : "text-muted-foreground/50 hover:text-foreground hover:bg-muted/50"
      )}
      title={t("chat.copyMessage", "Copy message")}
      aria-label={t("chat.copyMessage", "Copy message")}
      data-testid="copy-message"
    >
      {copied ? (
        <>
          <Check className="h-3 w-3" />
          <span>{t("chat.copied", "Copied")}</span>
        </>
      ) : (
        <>
          <Copy className="h-3 w-3" />
          <span>{t("chat.copy", "Copy")}</span>
        </>
      )}
    </button>
  );
}

// ==================== Typing Indicator ====================

function TypingIndicator() {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1 py-1" aria-label={t("chat.agentTyping", "Agent is typing")}>
      <span className="h-2 w-2 animate-bounce rounded-full bg-muted-foreground/50 [animation-delay:0ms]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-muted-foreground/50 [animation-delay:150ms]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-muted-foreground/50 [animation-delay:300ms]" />
    </div>
  );
}
