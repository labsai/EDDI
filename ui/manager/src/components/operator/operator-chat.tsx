import { useState, useRef, useEffect, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { Send, Square, RotateCcw, AlertTriangle, Bot, User, PauseCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ChatActivity } from "@/components/chat/chat-activity";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import { OPERATOR_STARTER_PROMPTS } from "@/lib/operator/system-prompt";
import type { ChatMessage } from "@/lib/api/chat";
import type { PipelineEvent } from "@/hooks/use-debug-events";
import type { HitlVerdict, PauseDetails, ToolCallDecision, PendingToolCallView } from "@/lib/api/hitl";
import { cn } from "@/lib/utils";

export interface OperatorChatProps {
  messages: ChatMessage[];
  events: PipelineEvent[];
  /** Completed turns' traces, keyed by the agent message they belong to. */
  tracesByMessageId: Record<string, PipelineEvent[]>;
  isStreaming: boolean;
  error: string | null;
  onSend: (input: string) => void;
  onStop: () => void;
  onReset: () => void;
  /** Whether the conversation is currently AWAITING_HUMAN. */
  isPaused: boolean;
  /**
   * Pause metadata for the banner.
   *
   * The caller must source these from `approval-status`, not from a conversation
   * snapshot: `getSimpleConversationLog` returns only `hitlPausedAt` and
   * `hitlPauseType`, so a reason or timeout read from there is always undefined
   * and the banner's countdown silently never renders.
   */
  pauseReason: string | null;
  pausedAt?: string;
  timeoutPolicy?: string;
  approvalTimeout?: string;
  /** Structured RULE/TOOL_CALL detail from GET …/approval-status. `undefined`
   *  while it is still loading — distinct from `null` (nothing to show). */
  pauseDetails?: PauseDetails | null;
  /**
   * Loading/failure state of that read, taken from the caller's own query
   * rather than inferred here.
   *
   * This used to be derived locally as `pauseDetails === undefined`, which
   * cannot tell "still loading" from "the request failed" — so a failed read
   * showed a loading spinner forever with Approve disabled and no way out. It
   * also disagreed with the other two approval surfaces, which derived it
   * differently again and landed on the permissive side. See
   * `ApprovalBannerProps.pauseDetailsError`.
   */
  pauseDetailsPending?: boolean;
  pauseDetailsError?: boolean;
  onRetryPauseDetails?: () => void;
  /** Whether a submitted decision is being resumed and awaited. */
  isResolvingPause: boolean;
  /** Set only when resuming or awaiting the resumed turn's outcome failed. */
  resolveError: string | null;
  /** Required for `pauseSurface: "banner"` (the only renderer of `ApprovalBanner`,
   *  its one caller). Optional so `pauseSurface: "compact"` callers, which never
   *  reach that branch, are not forced to pass a no-op. */
  onDecide?: (verdict: HitlVerdict, note?: string, toolDecisions?: Record<string, ToolCallDecision>) => void;
  /** Calls the approver must not be able to approve here, with the reason —
   *  see `ApprovalBannerProps.blockedCalls` and `self-guard.ts`. */
  blockedCalls?: readonly { callId: string; reason: string }[];
  /** Rendered per gated call above its redacted arguments — see
   *  `ApprovalBannerProps.renderCallExtra`. */
  renderCallExtra?: (call: PendingToolCallView) => ReactNode;
  /**
   * How a pause renders. Default `"banner"` — the full `ApprovalBanner`, with
   * per-call review and redacted request previews.
   *
   * `"compact"` is for the drawer: a docked panel has no room to review a
   * gated write responsibly (a cramped preview invites rubber-stamping, and
   * `ApprovalBanner` is security-reviewed for its one full-width surface, not
   * duplicated into a second one). Compact shows the reason and a link to the
   * full page, where the real banner renders — same conversation, same pause,
   * already there.
   */
  pauseSurface?: "banner" | "compact";
}

export function OperatorChat({
  messages,
  events,
  tracesByMessageId,
  isStreaming,
  error,
  onSend,
  onStop,
  onReset,
  isPaused,
  pauseReason,
  pausedAt,
  timeoutPolicy,
  approvalTimeout,
  pauseDetails,
  pauseDetailsPending,
  pauseDetailsError,
  onRetryPauseDetails,
  isResolvingPause,
  resolveError,
  onDecide,
  blockedCalls,
  renderCallExtra,
  pauseSurface = "banner",
}: OperatorChatProps) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, events]);

  function submit(text: string) {
    const value = text.trim();
    if (!value || isStreaming || isPaused) return;
    onSend(value);
    setInput("");
  }

  return (
    <div className="flex h-full min-h-0 flex-col rounded-xl border border-border">
      <div
        className="flex-1 space-y-4 overflow-y-auto p-4"
        data-testid="operator-messages"
        role="log"
        aria-live="polite"
        aria-relevant="additions text"
        aria-label={t("operator.chat.transcript", "Operator conversation")}
      >
        {messages.length === 0 && (
          <div className="space-y-3 py-8 text-center">
            <Bot className="mx-auto h-10 w-10 text-muted-foreground/40" />
            <p className="text-sm text-muted-foreground">{t("operator.chat.empty", "Ask about your deployment. The operator looks things up and shows you every call it makes.")}</p>
            <div className="mx-auto flex max-w-xl flex-wrap justify-center gap-2">
              {OPERATOR_STARTER_PROMPTS.map((key) => (
                <button
                  key={key}
                  onClick={() => submit(t(key))}
                  className="rounded-full border border-border px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:border-primary hover:text-foreground"
                  data-testid="operator-starter"
                >
                  {t(key)}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((message) => (
          <div key={message.id} className="space-y-2">
          <div
            className={cn(
              "flex gap-3",
              message.role === "user" ? "justify-end" : "justify-start",
            )}
          >
            {message.role === "agent" && (
              <Bot className="mt-1 h-5 w-5 shrink-0 text-primary" />
            )}
            <div
              className={cn(
                "max-w-[80%] rounded-lg px-3 py-2 text-sm whitespace-pre-wrap",
                message.role === "user"
                  ? "bg-primary text-primary-foreground"
                  : "bg-muted",
              )}
            >
              {message.content}
              {message.isStreaming && !message.content && (
                <span className="text-muted-foreground">…</span>
              )}
            </div>
            {message.role === "user" && (
              <User className="mt-1 h-5 w-5 shrink-0 text-muted-foreground" />
            )}
          </div>

          {/* An answer is only as trustworthy as the reads behind it, so each
              turn keeps its own trace instead of the newest one replacing it. */}
          {message.role === "agent" && (message.isStreaming ? events : tracesByMessageId[message.id])?.length ? (
            <ChatActivity
              events={message.isStreaming ? events : tracesByMessageId[message.id]!}
              isLive={Boolean(message.isStreaming) && isStreaming}
              showInternalSteps={false}
            />
          ) : null}
          </div>
        ))}

        {error && (
          <div
            className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
            data-testid="operator-chat-error"
            role="alert"
          >
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {/* Inline in the transcript, same placement as a group discussion's
            pause (discussion-transcript.tsx) — the decision belongs where the
            conversation that is waiting on it is, not on a separate page. */}
        {isPaused && pauseSurface === "compact" && (
          <div
            className="flex flex-col gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm text-amber-700 dark:text-amber-400"
            role="alert"
            data-testid="operator-chat-compact-pause"
          >
            <div className="flex items-start gap-2">
              <PauseCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span className="flex-1">
                {pauseReason ||
                  t(
                    "operator.chat.pauseCompactFallback",
                    "The operator needs your approval before continuing.",
                  )}
              </span>
            </div>
            <Link
              to="/manage/operator"
              className="self-start text-xs font-medium underline hover:no-underline"
              data-testid="operator-chat-compact-pause-link"
            >
              {t("operator.chat.pauseCompactReview", "Review to approve →")}
            </Link>
          </div>
        )}

        {isPaused && pauseSurface === "banner" && (
          <ApprovalBanner
            surface="regular"
            pauseReason={pauseReason ?? undefined}
            pausedAt={pausedAt}
            timeoutPolicy={timeoutPolicy}
            approvalTimeout={approvalTimeout}
            pauseDetails={pauseDetails ?? null}
            // Falls back to the old local derivation only when the caller
            // supplies neither flag, so a caller that has not been updated
            // still blocks Approve rather than silently enabling it. The
            // earlier `pauseDetailsError ? false : …` branch here did the
            // opposite — it resolved the error case to "not pending", which
            // was only safe if the banner separately blocked on the error
            // flag, and it did not.
            pauseDetailsPending={pauseDetailsPending ?? pauseDetails === undefined}
            pauseDetailsError={pauseDetailsError}
            onRetryPauseDetails={onRetryPauseDetails}
            isSubmitting={isResolvingPause}
            requireExplicitPerCall
            blockedCalls={blockedCalls}
            renderCallExtra={renderCallExtra}
            onDecide={(verdict, note, _taskApprovals, toolDecisions) => onDecide?.(verdict, note, toolDecisions)}
          />
        )}

        {resolveError && (
          <div
            className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
            data-testid="operator-resolve-error"
            role="alert"
          >
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{resolveError}</span>
          </div>
        )}

        <div ref={endRef} />
      </div>

      <div className="flex items-center gap-2 border-t border-border p-3">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit(input);
            }
          }}
          disabled={isPaused}
          placeholder={
            isPaused
              ? t("operator.chat.pausedPlaceholder", "Awaiting a decision above before the operator can continue…")
              : t("operator.chat.placeholder", "Ask about agents, conversations, deployments, logs…")
          }
          aria-label={t("operator.chat.placeholder", "Ask about agents, conversations, deployments, logs…")}
          className="h-10 flex-1 rounded-md border border-input bg-background px-3 text-sm disabled:opacity-50"
          data-testid="operator-input"
        />
        {isStreaming ? (
          <Button variant="outline" size="icon" onClick={onStop} title={t("operator.chat.stop", "Stop")} aria-label={t("operator.chat.stop", "Stop")}>
            <Square className="h-4 w-4" />
          </Button>
        ) : (
          <Button
            size="icon"
            onClick={() => submit(input)}
            disabled={!input.trim() || isPaused}
            title={t("operator.chat.send", "Send")}
            aria-label={t("operator.chat.send", "Send")}
            data-testid="operator-send"
          >
            <Send className="h-4 w-4" />
          </Button>
        )}
        <Button
          variant="ghost"
          size="icon"
          onClick={onReset}
          title={t("operator.chat.newConversation", "Start a new conversation")}
          aria-label={t("operator.chat.newConversation", "Start a new conversation")}
        >
          <RotateCcw className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}
