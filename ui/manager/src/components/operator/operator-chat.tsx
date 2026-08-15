import { useState, useRef, useLayoutEffect, type ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { formatMarkdownText } from "@/components/groups/group-utils";
import { Send, Square, RotateCcw, AlertTriangle, ArrowDown, Bot, User, PauseCircle, Loader2, Paperclip, CheckCircle2, XCircle, Wrench } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ChatActivity } from "@/components/chat/chat-activity";
import { InputHint } from "@/components/chat/input-hint";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import { FileDropOverlay, PendingAttachmentChip } from "@/components/chat/attachment-chip";
import { MessageAttachments } from "@/components/chat/chat-message";
import {
  filesFromClipboard,
  useAttachmentStaging,
  useFileDrop,
  type ReadyAttachment,
} from "@/hooks/use-attachment-staging";
import { useSmartAutoScroll } from "@/hooks/use-smart-auto-scroll";
import { OPERATOR_STARTER_PROMPTS } from "@/lib/operator/system-prompt";
import type { ChatMessage } from "@/lib/api/chat";
import type { SentAttachment } from "@/hooks/use-chat";
import type { PipelineEvent } from "@/hooks/use-debug-events";
import type { HitlVerdict, PauseDetails, ToolCallDecision, PendingToolCallView } from "@/lib/api/hitl";
import { cn } from "@/lib/utils";

export interface OperatorChatProps {
  messages: ChatMessage[];
  events: PipelineEvent[];
  /** Live tool_call names for the turn in flight — drives "Using {tool}…". */
  liveToolCalls?: string[];
  /** True once the model resumed writing after its last tool call. */
  liveToolsSettled?: boolean;
  /** Completed turns' traces, keyed by the agent message they belong to. */
  tracesByMessageId: Record<string, PipelineEvent[]>;
  /**
   * False while another tab is showing this pane. The pane stays MOUNTED (a
   * streaming turn and the composer draft must survive the switch), but
   * `display:none` still destroys the scroll container's position, so becoming
   * visible again has to restore it.
   */
  isVisible?: boolean;
  /**
   * True while a conversation picked from History is being read back. Without
   * it an empty `messages` renders the starter-prompt empty state, which is
   * indistinguishable from a brand-new chat for the whole duration of the read.
   */
  isRestoring?: boolean;
  /**
   * True when the shown conversation cannot take another turn (ENDED, ERROR,
   * EXECUTION_INTERRUPTED, or a turn still IN_PROGRESS). The transcript stays
   * readable; the composer closes, because sending would fail at the backend
   * and an enabled composer over a dead conversation is a trap.
   */
  isReadOnly?: boolean;
  isStreaming: boolean;
  error: string | null;
  onSend: (input: string, attachments?: SentAttachment[]) => void;
  onStop: () => void;
  onReset: () => void;
  /**
   * Attachment support (optional — surfaces that omit both render no attach
   * affordance). `conversationId` addresses uploads; `onEnsureConversation`
   * lazily creates the conversation when a file is attached before the first
   * message, mirroring how send() itself starts one.
   */
  conversationId?: string | null;
  onEnsureConversation?: () => Promise<string>;
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
  liveToolCalls,
  liveToolsSettled,
  tracesByMessageId,
  isVisible = true,
  isRestoring = false,
  isReadOnly = false,
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
  conversationId,
  onEnsureConversation,
}: OperatorChatProps) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Smart auto-scroll: follows new content only while the user is AT the
  // bottom. Scrolled up (reading something mid-stream), the view stays put and
  // a centered arrow offers the way back down.
  const {
    scrollRef,
    showScrollFab,
    hasNewContent,
    scrollToBottom,
    handleScroll,
  } = useSmartAutoScroll<HTMLDivElement>({
    deps: [messages, events.length, isStreaming],
    bottomThreshold: 80,
  });

  // Coming back from another tab. `display:none` resets scrollTop to 0, and the
  // auto-scroll effect above only fires when its deps change — none of which do
  // on a tab flip — so without this the admin lands on message 1 of a long
  // transcript with no new content to bring them back down.
  //
  // The OFFSET is restored, not just "jump to the bottom": someone who switched
  // away while reading an older part of the transcript wants that place back,
  // and always bottoming out would lose it just as surely as the reset did. The
  // one case that does bottom out is having been at the bottom already, where
  // following new content is the point.
  const wasVisibleRef = useRef(isVisible);
  const hiddenScrollTopRef = useRef<number | null>(null);
  const hiddenAtBottomRef = useRef(true);
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!isVisible && wasVisibleRef.current && el) {
      // Going away: remember where they were, before display:none erases it.
      hiddenScrollTopRef.current = el.scrollTop;
      hiddenAtBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight <= 80;
    } else if (isVisible && !wasVisibleRef.current) {
      // Coming back. At the bottom before, or nothing recorded (first reveal) —
      // follow the transcript; otherwise put them back where they were.
      if (hiddenAtBottomRef.current || hiddenScrollTopRef.current === null) {
        scrollToBottom("auto");
      } else if (el) {
        el.scrollTop = hiddenScrollTopRef.current;
      }
    }
    wasVisibleRef.current = isVisible;
  }, [isVisible, scrollToBottom, scrollRef]);

  // Same staging as the main chat panel — picker, paste, chips, per-turn cap.
  const {
    pendingAttachments,
    isUploading,
    hasReadyAttachment,
    stageFiles,
    handleFileInput,
    removeAttachment,
    takeForSend,
  } = useAttachmentStaging(conversationId ?? null, onEnsureConversation);
  const attachEnabled = Boolean(onEnsureConversation || conversationId);
  // One predicate for "the composer is closed", so the textarea, the send
  // button, the attach button and the drop target cannot drift apart.
  const composerClosed = isPaused || isReadOnly;

  const { isDragOver, dropHandlers } = useFileDrop(attachEnabled && !composerClosed, (files) => {
    void stageFiles(files);
  });

  /** Grow with content up to the same 120px ceiling as chat-drawer. */
  function autoResizeInput() {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`;
    el.style.overflowY = el.scrollHeight > 120 ? "auto" : "hidden";
  }

  function submit(text: string) {
    const value = text.trim();
    // Attachment-only turns are allowed once an upload is ready, matching the
    // main chat panel; nothing sends while an upload is still in flight.
    if ((!value && !hasReadyAttachment) || isStreaming || composerClosed || isUploading) return;
    const sent: SentAttachment[] = takeForSend().map((a: ReadyAttachment) => ({
      storageRef: a.result.storageRef,
      fileName: a.result.fileName || a.file.name,
      mimeType: a.result.mimeType || a.file.type || "application/octet-stream",
      sizeBytes: a.result.sizeBytes ?? a.file.size,
      forwardableInline: a.result.forwardableInline,
      previewUrl: a.previewUrl,
    }));
    onSend(value, sent.length ? sent : undefined);
    setInput("");
    // The height was sized to the multi-line draft just sent — snap it back.
    requestAnimationFrame(autoResizeInput);
  }

  // min-w-0 on the root: as a grid item this would otherwise grow to the
  // widest unbreakable line inside it (an approval card's one-line JSON args)
  // and push the whole page into horizontal scroll.
  return (
    <div className="relative flex h-full min-h-0 min-w-0 flex-col rounded-xl border border-border" {...dropHandlers}>
      {isDragOver && <FileDropOverlay />}
      <div className="relative flex-1 min-h-0">
      <div
        ref={scrollRef}
        onScroll={handleScroll}
        className="h-full space-y-4 overflow-y-auto p-4"
        data-testid="operator-messages"
        role="log"
        aria-live="polite"
        aria-relevant="additions text"
        aria-label={t("operator.chat.transcript", "Operator conversation")}
      >
        {messages.length === 0 && isRestoring && (
          <div className="space-y-3 py-8 text-center" role="status" data-testid="operator-restoring">
            <Loader2 className="mx-auto h-6 w-6 animate-spin text-muted-foreground/60" />
            <p className="text-sm text-muted-foreground">
              {t("operator.chat.restoring", "Opening that conversation…")}
            </p>
          </div>
        )}
        {messages.length === 0 && !isRestoring && (
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
          message.kind ? (
            // A recorded decision or an outcome notice — a transcript fact, not
            // an agent reply, so it renders as a centred rule rather than a
            // bubble. Its presence is the point: every decision leaves a trace,
            // even when the resumed turn came back with nothing to show.
            <div
              key={message.id}
              className="flex items-center gap-2 px-4 py-1 text-[11px] text-muted-foreground"
              data-testid={`operator-decision-${message.code}`}
            >
              <span className="h-px flex-1 bg-border/60" />
              {message.kind === "decision" ? (
                message.code === "rejected" ? (
                  <XCircle className="h-3.5 w-3.5 shrink-0 text-destructive" aria-hidden="true" />
                ) : (
                  // "partial" is still an approval — amber because part of the
                  // batch deliberately did not go through.
                  <CheckCircle2
                    className={cn(
                      "h-3.5 w-3.5 shrink-0",
                      message.code === "partial" ? "text-amber-500" : "text-emerald-500",
                    )}
                    aria-hidden="true"
                  />
                )
              ) : message.code === "executed" ? (
                // The receipt of what an approval actually ran — a tool fact,
                // not a pause, so it gets its own icon.
                <Wrench className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
              ) : (
                <PauseCircle className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />
              )}
              <span className="shrink-0">
                {/* `count` goes ONLY to "partial", the one pluralized key. Handing
                    i18next a count on the others sends it looking for
                    `approved_other` and it lands on the base key by fallback
                    rather than by design. `detail` likewise goes only to
                    "executed": the ran-calls list is dynamic, so its key is a
                    template ("Ran {{calls}}") rather than a static sentence. */}
                {message.code === "partial"
                  ? t("operator.decisionLog.partial", message.content, { count: message.count ?? 0 })
                  : message.code === "executed"
                    ? t("operator.decisionLog.executed", message.content, { calls: message.detail ?? "" })
                    : t(`operator.decisionLog.${message.code}`, message.content)}
              </span>
              <span className="h-px flex-1 bg-border/60" />
            </div>
          ) : (
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
                "max-w-[80%] rounded-lg px-3 py-2 text-sm",
                message.role === "user"
                  ? "bg-primary text-primary-foreground whitespace-pre-wrap"
                  : "bg-muted",
              )}
            >
              {message.role === "agent" && message.content ? (
                /* Same rendering contract as chat-message.tsx: markdown via
                   remark-gfm, through formatMarkdownText to repair the glued
                   headings/bold LLMs emit, and deliberately NO rehypeRaw —
                   operator output is LLM output built from tool results, i.e.
                   untrusted, so raw HTML stays escaped. Previously plain text,
                   which showed status reports as literal ## and ** markers. */
                <div className="prose prose-sm dark:prose-invert max-w-none overflow-hidden break-words [&_pre]:rounded-lg [&_pre]:bg-background/60 [&_pre]:p-3 [&_pre]:overflow-x-auto [&_code]:rounded [&_code]:bg-background/60 [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                    {formatMarkdownText(message.content)}
                  </ReactMarkdown>
                </div>
              ) : (
                message.content
              )}
              {message.isStreaming && !message.content && (
                <span className="text-muted-foreground">…</span>
              )}
              {message.role === "user" && message.attachments?.length ? (
                <div className={cn(message.content && "mt-2")}>
                  <MessageAttachments attachments={message.attachments} />
                </div>
              ) : null}
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
              liveToolCalls={message.isStreaming ? liveToolCalls : undefined}
              liveToolsSettled={liveToolsSettled}
            />
          ) : null}
          </div>
          )
        ))}

        {/* The approved step is running, and it is NOT streamed — resolveApproval
            polls until the resumed turn settles, which is routinely 30-60s for a
            turn that creates an agent. Until this row existed the only feedback
            was a spinner inside the Approve button, so the transcript sat
            unchanged and approving read as "nothing happened". Reported exactly
            that way, twice. */}
        {isResolvingPause && (
          <div
            className="flex items-center gap-2 px-4 py-1 text-[11px] text-muted-foreground"
            data-testid="operator-chat-resolving"
            role="status"
          >
            <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" aria-hidden="true" />
            <span>
              {t("operator.chat.resolving", "Running the approved step — this can take a moment…")}
            </span>
          </div>
        )}

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

      </div>

      {/* Centered scroll-to-bottom arrow — shown only while scrolled up */}
      {showScrollFab && (
        <button
          type="button"
          onClick={() => scrollToBottom("smooth")}
          className="absolute bottom-3 inset-x-0 mx-auto z-10 flex h-9 w-9 items-center justify-center rounded-full border border-border bg-card shadow-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all animate-in fade-in slide-in-from-bottom-2"
          title={t("chat.scrollToBottom", "Scroll to bottom")}
          aria-label={t("chat.scrollToBottom", "Scroll to bottom")}
          data-testid="operator-scroll-to-bottom"
        >
          <ArrowDown className="h-4 w-4" />
          {hasNewContent && (
            <span className="absolute -top-1 -end-1 flex h-3 w-3">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-primary opacity-75" />
              <span className="relative inline-flex rounded-full h-3 w-3 bg-primary" />
            </span>
          )}
        </button>
      )}
      </div>

      {/* Pending attachment chips (staged, uploading, errored) */}
      {pendingAttachments.length > 0 && (
        <div className="flex flex-wrap gap-2 border-t border-border px-3 pt-2" data-testid="operator-pending-attachments">
          {pendingAttachments.map((att) => (
            <PendingAttachmentChip key={att.id} att={att} onRemove={() => removeAttachment(att.id)} />
          ))}
        </div>
      )}
      <div className={cn("flex items-end gap-2 p-3", pendingAttachments.length === 0 && "border-t border-border")}>
        {attachEnabled && (
          <>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              className="hidden"
              onChange={handleFileInput}
              data-testid="operator-file-input"
            />
            <Button
              variant="ghost"
              size="icon"
              onClick={() => fileInputRef.current?.click()}
              disabled={composerClosed || isUploading}
              title={t("chat.attach", "Attach file")}
              aria-label={t("chat.attach", "Attach file")}
              data-testid="operator-attach-btn"
            >
              {isUploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Paperclip className="h-4 w-4" />}
            </Button>
          </>
        )}
        {/* A textarea, not an input: the old input's own keydown handler already
            special-cased !e.shiftKey, but an <input> cannot hold a second line,
            so Shift+Enter silently did nothing. Same Enter-sends /
            Shift+Enter-newline contract and auto-resize as chat-drawer. */}
        <textarea
          ref={inputRef}
          value={input}
          onChange={(e) => {
            setInput(e.target.value);
            autoResizeInput();
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit(input);
            }
          }}
          onPaste={(e) => {
            // Screenshots / copied files paste as attachments; text pastes
            // (no files on the clipboard) fall through untouched.
            const files = filesFromClipboard(e);
            if (!files.length || !attachEnabled || composerClosed) return;
            e.preventDefault();
            void stageFiles(files);
          }}
          disabled={composerClosed}
          rows={1}
          placeholder={
            isPaused
              ? t("operator.chat.pausedPlaceholder", "Awaiting a decision above before the operator can continue…")
              : isReadOnly
                ? t("operator.chat.readOnlyPlaceholder", "This conversation is finished — start a new one to continue.")
                : t("operator.chat.placeholder", "Ask about agents, conversations, deployments, logs…")
          }
          aria-label={t("operator.chat.placeholder", "Ask about agents, conversations, deployments, logs…")}
          className="max-h-[120px] min-h-[40px] flex-1 resize-none rounded-md border border-input bg-background px-3 py-2.5 text-sm disabled:opacity-50"
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
            disabled={(!input.trim() && !hasReadyAttachment) || composerClosed || isUploading}
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
      <InputHint className="px-3 pb-2 -mt-1" />
    </div>
  );
}
