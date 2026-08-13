import { useEffect, useRef, useState, useCallback, useMemo } from "react";
import { useSearchParams, Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import {
  useChatStore,
  useDeployedAgents,
  useStartConversation,
  useResumeOrStartConversation,
  useSendMessage,
  useEndConversation,
  useUndoConversation,
  useRedoConversation,
  useRerunConversation,
} from "@/hooks/use-chat";
import type { SentAttachment } from "@/hooks/use-chat";
import {
  filesFromClipboard,
  useAttachmentStaging,
  useFileDrop,
  type PendingAttachment,
  type ReadyAttachment,
} from "@/hooks/use-attachment-staging";
import { FileDropOverlay, PendingAttachmentChip } from "./attachment-chip";
import { ChatMessage } from "./chat-message";
import { ChatActivity } from "./chat-activity";
import { ChatHistory } from "./chat-history";
import { StreamingToggle } from "./streaming-toggle";
import { DebugDrawer } from "@/components/debugger/debug-drawer";
import { useDebugStore, type PipelineEvent } from "@/hooks/use-debug-events";
import { useSmartAutoScroll } from "@/hooks/use-smart-auto-scroll";
import { cn } from "@/lib/utils";
import { InputHint } from "@/components/chat/input-hint";
import {
  Bot,
  ChevronDown,
  History,
  StopCircle,
  MessageSquarePlus,
  Loader2,
  Undo2,
  Redo2,
  Lock,
  Unlock,
  Eye,
  EyeOff,
  Send,
  Paperclip,
  RefreshCw,
  Activity,
  ArrowDown,
  Info,
  ChevronUp,
  Hash,
  Clock,
  Layers,
  HandMetal,
  Wrench,
} from "lucide-react";

export function ChatPanel({ embedded = false }: { embedded?: boolean } = {}) {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const [historyOpen, setHistoryOpen] = useState(false);
  const [agentSelectorOpen, setAgentSelectorOpen] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const agentSelectorRef = useRef<HTMLDivElement>(null);

  const [contextOpen, setContextOpen] = useState(false);

  // Store state
  const messages = useChatStore((s) => s.messages);
  const selectedAgentId = useChatStore((s) => s.selectedAgentId);
  const selectedAgentName = useChatStore((s) => s.selectedAgentName);
  const conversationId = useChatStore((s) => s.conversationId);
  const isProcessing = useChatStore((s) => s.isProcessing);
  const isThinking = useChatStore((s) => s.isThinking);
  const isPaused = useChatStore((s) => s.isPaused);
  const pauseReason = useChatStore((s) => s.pauseReason);
  const undoAvailable = useChatStore((s) => s.undoAvailable);
  const redoAvailable = useChatStore((s) => s.redoAvailable);
  const quickReplies = useChatStore((s) => s.quickReplies);
  const setSelectedAgent = useChatStore((s) => s.setSelectedAgent);
  const activeInputField = useChatStore((s) => s.activeInputField);
  const isSecretMode = useChatStore((s) => s.isSecretMode);
  const toggleSecretMode = useChatStore((s) => s.toggleSecretMode);
  const clearInputField = useChatStore((s) => s.clearInputField);

  // Activity display
  const showActivity = useDebugStore((s) => s.showActivity);
  const toggleShowActivity = useDebugStore((s) => s.toggleShowActivity);
  const currentTurnEvents = useDebugStore((s) => s.currentTurnEvents);

  // Queries & mutations
  const { data: deployedAgents, isLoading: agentsLoading } = useDeployedAgents();
  const startConversation = useStartConversation();
  // Opening a chat reopens the last conversation; only "New Conversation"
  // deliberately starts a fresh one.
  const openConversation = useResumeOrStartConversation();
  const sendMessage = useSendMessage();
  const endConversation = useEndConversation();
  const undoConversation = useUndoConversation();
  const redoConversation = useRedoConversation();

  // Open the chat for the ?agentId= query param
  useEffect(() => {
    const agentIdParam = searchParams.get("agentId");
    if (!agentIdParam) return;

    // Skip if this agent is already selected (prevents duplicate opens)
    if (agentIdParam === selectedAgentId) {
      // Still clean the URL params
      setSearchParams({}, { replace: true });
      return;
    }

    // Resolve agent name: URL param > deployed agents lookup > fallback to ID
    const agentNameParam = searchParams.get("agentName");
    const agentName =
      agentNameParam ||
      deployedAgents?.find((b) => b.id === agentIdParam)?.name ||
      agentIdParam;

    // Auto-select and reopen the agent's last conversation (or start one)
    setSelectedAgent(agentIdParam, agentName);
    openConversation.mutate(
      { agentId: agentIdParam },
      { onError: (err) => toast.error(getErrorMessage(err)) },
    );

    // Remove query params so refresh doesn't re-open
    setSearchParams({}, { replace: true });
  }, [searchParams, deployedAgents, selectedAgentId, setSelectedAgent, openConversation, setSearchParams]);

  // Smart auto-scroll: auto scrolls when at bottom, pauses when user scrolls up
  const {
    scrollRef: scrollContainerRef,
    showScrollFab,
    hasNewContent,
    scrollToBottom,
    handleScroll,
  } = useSmartAutoScroll<HTMLDivElement>({
    deps: [messages, isProcessing, isThinking, currentTurnEvents.length],
    bottomThreshold: 80,
  });

  // Close agent selector on outside click
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (
        agentSelectorRef.current &&
        !agentSelectorRef.current.contains(e.target as Node)
      ) {
        setAgentSelectorOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  const handleSelectAgent = useCallback(
    (agentId: string, agentName: string) => {
      setSelectedAgent(agentId, agentName);
      setAgentSelectorOpen(false);
      openConversation.mutate(
        { agentId },
        { onError: (err) => toast.error(getErrorMessage(err)) },
      );
      // Focus the chat input after the dropdown closes and UI settles
      setTimeout(() => {
        const input = document.querySelector<HTMLTextAreaElement>('[data-testid="chat-input"]');
        input?.focus();
      }, 150);
    },
    [setSelectedAgent, openConversation]
  );

  /** Explicit fresh start — the one path that always creates a conversation. */
  const handleNewConversation = useCallback(() => {
    if (!selectedAgentId) return;
    useChatStore.getState().clearMessages();
    startConversation.mutate(
      { agentId: selectedAgentId },
      { onError: (err) => toast.error(getErrorMessage(err)) },
    );
  }, [selectedAgentId, startConversation]);

  // ── Attachment upload (shared staging hook — also used by the operator) ──
  const fileInputRef = useRef<HTMLInputElement>(null);
  const {
    pendingAttachments,
    isUploading,
    hasReadyAttachment,
    stageFiles,
    handleFileInput,
    removeAttachment,
    discardAll,
    takeForSend,
  } = useAttachmentStaging(conversationId);

  // Secret mode and attachments are mutually exclusive — a masked turn must not
  // carry a file. Discard anything staged the moment secret mode switches on.
  useEffect(() => {
    if (isSecretMode) discardAll();
  }, [isSecretMode, discardAll]);

  const handleSend = useCallback(
    (message: string, isSecret?: boolean) => {
      // Secret turns never carry attachments — a masked bubble must not leak a
      // filename or thumbnail. Discard anything staged (freeing previews and
      // best-effort deleting the blob) instead of forwarding or displaying it.
      if (isSecret) {
        if (!message.trim()) return; // nothing to send once attachments are dropped
        discardAll();
        sendMessage.mutate({ message, isSecret: true });
        return;
      }

      // Guard BEFORE draining the staging area: a no-op send must not clear
      // the user's staged chips.
      if (!message.trim() && !hasReadyAttachment) return;

      // Forward only successfully-uploaded attachments as context this turn.
      const sent: SentAttachment[] = takeForSend().map((a: ReadyAttachment) => ({
        storageRef: a.result.storageRef,
        fileName: a.result.fileName || a.file.name,
        mimeType: a.result.mimeType || a.file.type || "application/octet-stream",
        sizeBytes: a.result.sizeBytes ?? a.file.size,
        forwardableInline: a.result.forwardableInline,
        previewUrl: a.previewUrl,
      }));

      sendMessage.mutate({
        message,
        isSecret,
        attachments: sent.length ? sent : undefined,
      });
    },
    [sendMessage, takeForSend, discardAll, hasReadyAttachment]
  );

  const handleQuickReply = useCallback(
    (reply: string) => {
      sendMessage.mutate({ message: reply });
    },
    [sendMessage]
  );

  // Pasted files (screenshots via Ctrl/Cmd+V) go through the same staging as
  // the picker. No-conversation and secret-mode cases are handled at the input.
  const handlePasteFiles = useCallback(
    (files: File[]) => {
      if (!conversationId) return;
      void stageFiles(files);
    },
    [conversationId, stageFiles],
  );

  // Dropping files anywhere on the chat area stages them, same as the picker
  // and paste. Secret mode blocks it — a masked turn must not carry a file.
  const { isDragOver, dropHandlers } = useFileDrop(
    Boolean(conversationId) && !isSecretMode,
    handlePasteFiles,
  );

  // ── Rerun last step ──
  const rerunConversation = useRerunConversation();
  const lastMessage = messages[messages.length - 1];
  const showRerun = lastMessage?.role === "agent" && (lastMessage.content ?? "").includes("⚠️ Error");

  const handleRerun = useCallback(() => {
    rerunConversation.mutate(undefined, {
      onSuccess: () => toast.success(t("chat.retrySuccess", "Step re-executed")),
      onError: () => toast.error(t("chat.retryError", "Retry failed")),
    });
  }, [rerunConversation, t]);

  return (
    <div className={cn(
      "flex h-full min-w-0 overflow-hidden bg-background",
      !embedded && "rounded-xl border border-border shadow-sm"
    )}>
      {/* History panel — hidden in embedded mode (no room for nested side panels) */}
      {!embedded && (
        <ChatHistory
          open={historyOpen}
          onNewConversation={handleNewConversation}
        />
      )}

      {/* Main chat area — also the file drop zone */}
      <div className="relative flex flex-1 flex-col min-w-0 min-h-0" {...dropHandlers}>
        {isDragOver && <FileDropOverlay />}
        {/* Top bar */}
        <div className={cn(
          "flex items-center border-b border-border",
          embedded ? "gap-1 px-2 py-1.5" : "gap-2 px-4 py-2.5"
        )}>
          {/* History toggle — hidden in embedded mode */}
          {!embedded && (
            <button
              onClick={() => setHistoryOpen((p) => !p)}
              className={cn(
                "flex h-9 w-9 items-center justify-center rounded-lg transition-colors",
                historyOpen
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-muted hover:text-foreground"
              )}
              title={t("chat.history")}
              data-testid="history-toggle"
            >
              <History className="h-4 w-4" />
            </button>
          )}

          {/* Agent selector */}
          <div ref={agentSelectorRef} className="relative flex-1">
            <button
              onClick={() => setAgentSelectorOpen((p) => !p)}
              className="flex w-full items-center gap-2 rounded-lg border border-input bg-card px-3 py-2 text-sm transition-colors hover:bg-muted"
              data-testid="agent-selector"
            >
              <Bot className="h-4 w-4 text-muted-foreground" />
              <span
                className={cn(
                  "flex-1 text-start truncate",
                  !selectedAgentName && "text-muted-foreground"
                )}
              >
                {selectedAgentName ?? t("chat.selectAgent")}
              </span>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-muted-foreground transition-transform",
                  agentSelectorOpen && "rotate-180"
                )}
              />
            </button>

            {/* Dropdown */}
            {agentSelectorOpen && (
              <div className="absolute inset-s-0 top-full z-50 mt-1 w-full max-h-80 overflow-y-auto rounded-lg border border-border bg-popover p-1 shadow-lg">
                {agentsLoading ? (
                  <div className="flex items-center justify-center py-3">
                    <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                  </div>
                ) : !deployedAgents?.length ? (
                  <p className="px-3 py-2 text-xs text-muted-foreground">
                    {t("chat.noAgents")}
                  </p>
                ) : (
                  deployedAgents.map((agent) => (
                    <button
                      key={agent.resource}
                      onClick={() => handleSelectAgent(agent.id, agent.name || agent.id)}
                      className={cn(
                        "flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors hover:bg-muted min-w-0",
                        agent.id === selectedAgentId &&
                          "bg-primary/10 text-primary font-medium"
                      )}
                    >
                      <Bot className="h-4 w-4 shrink-0" />
                      <div className="flex-1 min-w-0 text-start">
                        <p className="truncate font-medium">{agent.name}</p>
                        {agent.description && (
                          <p className="line-clamp-2 text-xs text-muted-foreground/80 leading-snug">
                            {agent.description}
                          </p>
                        )}
                      </div>
                    </button>
                  ))
                )}
              </div>
            )}
          </div>

          {/* Streaming toggle — hidden in embedded mode to save space */}
          {!embedded && <StreamingToggle />}

          {/* Activity toggle — hidden in embedded mode */}
          {!embedded && (
            <button
              onClick={toggleShowActivity}
              className={cn(
                "flex h-9 w-9 items-center justify-center rounded-lg transition-colors",
                showActivity
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-muted hover:text-foreground",
              )}
              title={showActivity ? t("chat.hideActivity", "Hide Activity") : t("chat.showActivity", "Show Activity")}
              data-testid="activity-toggle"
            >
              <Activity className="h-4 w-4" />
            </button>
          )}

          {/* Top bar actions */}
          {conversationId && (
            <>
              <button
                onClick={handleNewConversation}
                className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                title={t("chat.newConversation")}
                data-testid="new-conversation"
              >
                <MessageSquarePlus className="h-4 w-4" />
              </button>
              <button
                onClick={() => endConversation.mutate()}
                className="flex h-9 w-9 items-center justify-center rounded-lg text-destructive/70 transition-colors hover:bg-destructive/10 hover:text-destructive"
                title={t("chat.endConversation")}
                data-testid="end-conversation"
              >
                <StopCircle className="h-4 w-4" />
              </button>
            </>
          )}
        </div>

        {/* Conversation context header */}
        {conversationId && contextOpen && (
          <div className="flex items-center gap-4 border-b border-border/50 bg-muted/30 px-4 py-1.5 text-[11px] text-muted-foreground">
            <div className="flex items-center gap-1" title="Conversation ID">
              <Hash className="h-3 w-3" />
              <button
                onClick={() => {
                  navigator.clipboard.writeText(conversationId);
                  toast.success(t("common.copied", "Copied!"));
                }}
                className="font-mono hover:text-foreground transition-colors truncate max-w-[120px]"
                title={conversationId}
              >
                {conversationId.slice(0, 12)}…
              </button>
            </div>
            <div className="flex items-center gap-1" title="Steps">
              <Layers className="h-3 w-3" />
              <span>{messages.filter((m) => m.role === "user").length} {t("chat.context.stepCount", "turns")}</span>
            </div>
            <div className="flex items-center gap-1" title="Started">
              <Clock className="h-3 w-3" />
              <span>
                {messages[0]
                  ? new Date(messages[0].timestamp).toLocaleTimeString(undefined, {
                      hour: "2-digit",
                      minute: "2-digit",
                    })
                  : "—"}
              </span>
            </div>
          </div>
        )}
        {conversationId && !embedded && (
          <button
            onClick={() => setContextOpen((p) => !p)}
            className={cn(
              "flex w-full items-center justify-center py-0.5 text-muted-foreground/40 hover:text-muted-foreground hover:bg-muted/30 transition-colors",
              contextOpen && "border-b border-border/30",
            )}
            title={contextOpen ? "Hide conversation info" : "Show conversation info"}
            data-testid="context-toggle"
          >
            {contextOpen ? (
              <ChevronUp className="h-3 w-3" />
            ) : (
              <Info className="h-3 w-3" />
            )}
          </button>
        )}

        {/* Messages */}
        <div
          ref={scrollContainerRef}
          className="relative flex-1 overflow-y-auto min-h-0"
          onScroll={handleScroll}
        >
          {!selectedAgentId ? (
            <EmptyState />
          ) : messages.length === 0 ? (
            <div className="flex h-full items-center justify-center">
              <div className="text-center">
                <Bot className="mx-auto h-12 w-12 text-muted-foreground/30" />
                <p className="mt-3 text-sm text-muted-foreground">
                  {startConversation.isPending || openConversation.isPending
                    ? t("chat.thinking")
                    : conversationId
                      ? // An opened conversation that turned out to hold no
                        // turns — say so, so it doesn't read as a failed load.
                        t("chat.emptyConversation", "This conversation has no messages yet.")
                      : t("chat.empty")}
                </p>
              </div>
            </div>
          ) : (
            <div className={embedded ? "py-2" : "py-4"}>
              {messages.map((msg) => (
                <ChatMessage key={msg.id} message={msg} />
              ))}

              {/* Live status — the SAME line the operator chat shows:
                  "Thinking…" / "Using {tool}…" + tool-call count, from the
                  turn's live events. The dots indicator covers only the gap
                  before the first event arrives. */}
              {(isProcessing || isThinking) &&
                (currentTurnEvents.length > 0 ? (
                  <ChatActivity events={currentTurnEvents} isLive showInternalSteps={false} />
                ) : (
                  <InlineThinkingIndicator events={currentTurnEvents} />
                ))}

              {/* Rerun button — shown when last message is an error */}
              {showRerun && !isProcessing && (
                <div className="flex justify-center py-2">
                  <button
                    onClick={handleRerun}
                    disabled={rerunConversation.isPending}
                    className="inline-flex items-center gap-1.5 rounded-full border border-amber-500/30 bg-amber-500/5 px-4 py-1.5 text-xs font-medium text-amber-600 transition-colors hover:bg-amber-500/15 disabled:opacity-50 dark:text-amber-400"
                    data-testid="rerun-btn"
                  >
                    <RefreshCw className={cn("h-3.5 w-3.5", rerunConversation.isPending && "animate-spin")} />
                    {t("chat.retry", "Retry Last Step")}
                  </button>
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>
          )}

          {/* Scroll-to-bottom FAB with new content pulse */}
          {showScrollFab && (
            <button
              onClick={() => scrollToBottom("smooth")}
              className="absolute bottom-4 inset-x-0 mx-auto z-10 flex h-9 w-9 items-center justify-center rounded-full border border-border bg-card shadow-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all animate-in fade-in slide-in-from-bottom-2"
              title={t("chat.scrollToBottom", "Scroll to bottom")}
              data-testid="scroll-to-bottom"
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

        {/* Debug drawer */}
        {conversationId && (
          <DebugDrawer
            conversationId={conversationId}
            agentId={selectedAgentId}
          />
        )}

        {/* Quick replies — hidden while paused so a pill can't fire a send
            against an AWAITING_HUMAN conversation (the input/send are also guarded). */}
        {quickReplies.length > 0 && !isProcessing && !isPaused && (
          <div className="flex flex-wrap gap-2 border-t border-border px-4 py-2">
            {quickReplies.map((reply, i) => (
              <button
                type="button"
                key={`${reply}-${i}`}
                onClick={() => handleQuickReply(reply)}
                className="rounded-full border border-primary/30 bg-primary/5 px-3 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/15"
                data-testid="quick-reply-btn"
              >
                {reply}
              </button>
            ))}
          </div>
        )}


        {/* Awaiting-approval notice — input is disabled while a turn is paused
            for human approval; the decision is made on the conversation's
            review page (which renders the full approval banner). */}
        {isPaused && conversationId && (
          <div
            className="flex flex-wrap items-center gap-2 border-t border-amber-500/30 bg-amber-500/5 px-4 py-2.5 text-xs"
            data-testid="chat-pause-banner"
          >
            <HandMetal className="h-4 w-4 shrink-0 text-amber-500" aria-hidden="true" />
            <span className="text-amber-600 dark:text-amber-400">
              {pauseReason || t("hitl.chatPaused", "This conversation is awaiting human approval.")}
            </span>
            {!location.pathname.startsWith("/workforce") && (
              <Link
                to={`/manage/conversationview/${conversationId}`}
                className="ms-auto rounded-md bg-amber-500/10 px-2.5 py-1 font-medium text-amber-600 hover:bg-amber-500/20 transition-colors dark:text-amber-400"
                data-testid="chat-pause-review"
              >
                {t("hitl.review", "Review")}
              </Link>
            )}
          </div>
        )}

        {/* Input — show SecretInput when backend requests it or user toggles 🔒 */}
        {activeInputField ? (
          <SecretInputField
            label={activeInputField.label}
            placeholder={activeInputField.placeholder}
            defaultValue={activeInputField.defaultValue}
            subType={activeInputField.subType}
            onSend={(val) => {
              handleSend(val, true);
              clearInputField();
            }}
            disabled={isProcessing || isPaused}
          />
        ) : (
          <ChatInputWithSecretToggle
            onSend={handleSend}
            disabled={!conversationId || isPaused}
            isProcessing={isProcessing}
            isSecretMode={isSecretMode}
            onToggleSecret={toggleSecretMode}
            fileInputRef={fileInputRef}
            onFileChange={handleFileInput}
            onPasteFiles={handlePasteFiles}
            isUploading={isUploading}
            hasConversation={!!conversationId}
            pendingAttachments={pendingAttachments}
            onRemoveAttachment={removeAttachment}
            hasReadyAttachment={hasReadyAttachment}
            onUndo={conversationId && undoAvailable && !isProcessing ? () => undoConversation.mutate() : undefined}
            onRedo={conversationId && redoAvailable && !isProcessing ? () => redoConversation.mutate() : undefined}
            embedded={embedded}
          />
        )}
      </div>
    </div>
  );
}

/* ─── Inline sub-components ──────────────────── */

/** Password input field rendered when backend requests InputFieldOutputItem */
function SecretInputField({
  label,
  placeholder,
  defaultValue = "",
  subType = "password",
  onSend,
  disabled = false,
}: {
  label?: string;
  placeholder?: string;
  defaultValue?: string;
  subType?: string;
  onSend: (value: string) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const [value, setValue] = useState(defaultValue);
  const [visible, setVisible] = useState(false);

  const handleSubmit = () => {
    const trimmed = value.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setValue("");
  };

  const inputType = visible ? "text" : (subType || "password");

  return (
    <div className="border-t border-border bg-background p-4">
      {label && (
        <div className="mb-2 flex items-center gap-1.5 text-sm font-medium text-primary" data-testid="secret-input-label">
          <Lock className="h-3.5 w-3.5" />
          {label}
        </div>
      )}
      <div className="flex items-end gap-2">
        <div className="relative flex-1">
          <input
            type={inputType}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSubmit();
              }
            }}
            placeholder={placeholder || t("chat.secretPlaceholder", "Enter secret value...")}
            disabled={disabled}
            autoFocus
            autoComplete="off"
            className={cn(
              "w-full rounded-xl border border-primary/60 bg-card px-4 py-3 pe-10 text-sm",
              "placeholder:text-muted-foreground",
              "focus:outline-none focus:ring-2 focus:ring-primary/30",
              "disabled:cursor-not-allowed disabled:opacity-50"
            )}
            data-testid="secret-input-field"
          />
          <button
            type="button"
            onClick={() => setVisible(!visible)}
            className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            title={visible ? t("chat.hide", "Hide") : t("chat.show", "Show")}
            data-testid="secret-input-eye"
          >
            {visible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
          </button>
        </div>
        <button
          onClick={handleSubmit}
          disabled={!value.trim() || disabled}
          className={cn(
            "flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition-colors",
            value.trim() && !disabled
              ? "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
              : "bg-muted text-muted-foreground cursor-not-allowed"
          )}
          data-testid="secret-input-send"
        >
          <Send className="h-5 w-5" />
        </button>
      </div>
    </div>
  );
}

/** ChatInput enhanced with 🔒/🔓 secret mode toggle */
function ChatInputWithSecretToggle({
  onSend,
  disabled = false,
  isProcessing = false,
  isSecretMode,
  onToggleSecret,
  fileInputRef,
  onFileChange,
  onPasteFiles,
  isUploading = false,
  hasConversation = false,
  pendingAttachments = [],
  onRemoveAttachment,
  hasReadyAttachment = false,
  onUndo,
  onRedo,
  embedded = false,
}: {
  onSend: (message: string, isSecret?: boolean) => void;
  disabled?: boolean;
  isProcessing?: boolean;
  isSecretMode: boolean;
  onToggleSecret: () => void;
  fileInputRef: React.RefObject<HTMLInputElement | null>;
  onFileChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  /** Files pasted into the textarea (screenshots, copied files). */
  onPasteFiles?: (files: File[]) => void;
  isUploading?: boolean;
  hasConversation?: boolean;
  pendingAttachments?: PendingAttachment[];
  onRemoveAttachment?: (id: string) => void;
  hasReadyAttachment?: boolean;
  onUndo?: () => void;
  onRedo?: () => void;
  embedded?: boolean;
}) {
  const { t } = useTranslation();
  const [value, setValue] = useState("");
  const [secretVisible, setSecretVisible] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSend = useCallback(() => {
    const trimmed = value.trim();
    // Allow an attachment-only turn (empty text) once a file is uploaded.
    if ((!trimmed && !hasReadyAttachment) || disabled || isProcessing || isUploading) return;
    onSend(trimmed, isSecretMode);
    setValue("");
    if (isSecretMode) {
      onToggleSecret();
      setSecretVisible(false);
    }
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [value, hasReadyAttachment, disabled, isProcessing, isUploading, isSecretMode, onSend, onToggleSecret]);

  const handleInput = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, []);

  const canSend =
    (value.trim().length > 0 || hasReadyAttachment) &&
    !disabled &&
    !isProcessing &&
    !isUploading;

  return (
    <div className="border-t border-border bg-background p-4 min-w-0" data-tour="chat-input-area">
      {/* Hidden file input for attachments */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={onFileChange}
        data-testid="chat-file-input"
      />
      {/* Pending attachment chips */}
      {pendingAttachments.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-2" data-testid="pending-attachments">
          {pendingAttachments.map((att) => (
            <PendingAttachmentChip
              key={att.id}
              att={att}
              onRemove={() => onRemoveAttachment?.(att.id)}
            />
          ))}
        </div>
      )}
      <div className="flex items-end gap-2 min-w-0">
        {/* 📎 Attach button */}
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={!hasConversation || isUploading || isSecretMode}
          className={cn(
            "flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition-colors",
            isUploading
              ? "bg-primary/10 text-primary animate-pulse"
              : "text-muted-foreground hover:bg-muted hover:text-foreground",
            // Attachments and secret mode are mutually exclusive (a masked turn
            // must not carry a file).
            isSecretMode && "cursor-not-allowed opacity-40"
          )}
          title={t("chat.attach", "Attach file")}
          data-testid="chat-attach-btn"
        >
          {isUploading ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Paperclip className="h-4 w-4" />
          )}
        </button>
        {/* 🔒 Secret mode toggle */}
        <button
          type="button"
          onClick={onToggleSecret}
          className={cn(
            "flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition-colors",
            isSecretMode
              ? "bg-primary/10 text-primary"
              : "text-muted-foreground hover:bg-muted hover:text-foreground"
          )}
          title={isSecretMode ? t("chat.secretModeOn", "Secret mode ON") : t("chat.secretModeOff", "Toggle secret mode")}
          data-testid="chat-secret-toggle"
        >
          {isSecretMode ? <Lock className="h-4 w-4" /> : <Unlock className="h-4 w-4" />}
        </button>

        {/* Undo / Redo — inline with input icons, only when available */}
        {(onUndo || onRedo) && (
          <>
            <div className="h-5 w-px bg-border/50 shrink-0" />
            {onUndo && (
              <button
                type="button"
                onClick={onUndo}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                title={t("chat.undo", "Undo")}
                aria-label={t("chat.undo", "Undo")}
                data-testid="undo-btn"
              >
                <Undo2 className="h-3.5 w-3.5" />
              </button>
            )}
            {onRedo && (
              <button
                type="button"
                onClick={onRedo}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                title={t("chat.redo", "Redo")}
                aria-label={t("chat.redo", "Redo")}
                data-testid="redo-btn"
              >
                <Redo2 className="h-3.5 w-3.5" />
              </button>
            )}
          </>
        )}

        {isSecretMode ? (
          /* Secret mode: password input with eye toggle */
          <div className="relative flex-1">
            <input
              type={secretVisible ? "text" : "password"}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  handleSend();
                }
              }}
              placeholder={t("chat.secretPlaceholder", "Enter secret value...")}
              disabled={disabled}
              autoComplete="off"
              className={cn(
                "w-full rounded-xl border border-primary/60 bg-card px-4 py-3 pe-10 text-sm",
                "placeholder:text-muted-foreground",
                "focus:outline-none focus:ring-2 focus:ring-primary/30",
                "disabled:cursor-not-allowed disabled:opacity-50",
                "min-h-[44px]"
              )}
              data-testid="chat-input"
            />
            <button
              type="button"
              onClick={() => setSecretVisible(!secretVisible)}
              className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              data-testid="chat-eye-toggle"
            >
              {secretVisible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
            </button>
          </div>
        ) : (
          /* Normal mode: auto-growing textarea */
          <textarea
            ref={textareaRef}
            autoFocus={!embedded}
            data-testid="chat-input"
            value={value}
            onChange={(e) => {
              setValue(e.target.value);
              handleInput();
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
            onPaste={(e) => {
              // Screenshots / copied files paste as attachments. Text pastes
              // (no files on the clipboard) fall through untouched.
              const files = filesFromClipboard(e);
              if (!files.length || !onPasteFiles || !hasConversation) return;
              e.preventDefault();
              onPasteFiles(files);
            }}
            placeholder={t("chat.placeholder")}
            disabled={disabled}
            rows={1}
            className={cn(
              "flex-1 min-w-0 resize-none rounded-xl border border-input bg-card px-4 py-3 text-sm",
              "placeholder:text-muted-foreground",
              "focus:outline-none focus:ring-2 focus:ring-ring",
              "disabled:cursor-not-allowed disabled:opacity-50",
              "max-h-40 min-h-[44px]"
            )}
          />
        )}

        <button
          onClick={handleSend}
          disabled={!canSend}
          className={cn(
            "flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition-colors",
            canSend
              ? "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
              : "bg-muted text-muted-foreground cursor-not-allowed"
          )}
          aria-label={t("chat.send")}
          data-testid="chat-send"
        >
          {isProcessing ? (
            <Loader2 className="h-5 w-5 animate-spin" />
          ) : (
            <Send className="h-5 w-5" />
          )}
        </button>
      </div>
      {/* Secret mode is a single-line password field — a newline hint there
          would promise something the input cannot do. */}
      {!isSecretMode && <InputHint />}
    </div>
  );
}


function EmptyState() {
  const { t } = useTranslation();
  return (
    <div className="flex h-full items-center justify-center">
      <div className="text-center">
        <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-2xl bg-primary/10">
          <Bot className="h-10 w-10 text-primary" />
        </div>
        <h3 className="mt-4 text-lg font-semibold text-foreground">
          {t("pages.chat.title")}
        </h3>
        <p className="mt-1 max-w-xs text-sm text-muted-foreground">
          {t("chat.empty")}
        </p>
      </div>
    </div>
  );
}

// ==================== Inline Thinking Indicator ====================

/** Internal pipeline tasks that should never surface in the UI. */
const INTERNAL_TASKS = new Set([
  "expressions", "behavior_rules", "langchain", "dictionary",
  "propertysetter", "parser", "output",
  "ai.labs.expressions", "ai.labs.behavior_rules", "ai.labs.langchain",
  "ai.labs.dictionary", "ai.labs.propertysetter", "ai.labs.parser",
  "ai.labs.output",
]);

/**
 * Sleek inline indicator shown during agent processing.
 * Shows "Thinking\u2026" with animated dots + any active tool call names.
 */
function InlineThinkingIndicator({ events }: { events: PipelineEvent[] }) {
  const { t } = useTranslation();

  // Extract active tool call names from events (only from non-internal tasks)
  const activeToolNames = useMemo(() => {
    const names: string[] = [];
    for (const ev of events) {
      if (INTERNAL_TASKS.has(ev.taskType)) continue;
      if (ev.toolTrace) {
        for (const trace of ev.toolTrace) {
          if (trace.type === "tool_call" && trace.tool && !names.includes(trace.tool)) {
            names.push(trace.tool);
          }
        }
      }
    }
    return names;
  }, [events]);

  // Check for errors in visible events
  const hasError = events.some(
    (e) => e.type === "task_failed" && !INTERNAL_TASKS.has(e.taskType),
  );

  return (
    <div
      className="flex items-center gap-2 px-4 py-2 text-xs text-muted-foreground/70"
      data-testid="thinking-indicator"
    >
      {/* Pulsing dots */}
      <span className="flex items-center gap-0.5">
        <span className="h-1 w-1 animate-bounce rounded-full bg-muted-foreground/40 [animation-delay:0ms]" />
        <span className="h-1 w-1 animate-bounce rounded-full bg-muted-foreground/40 [animation-delay:150ms]" />
        <span className="h-1 w-1 animate-bounce rounded-full bg-muted-foreground/40 [animation-delay:300ms]" />
      </span>

      {/* Status text */}
      <span className="italic">
        {hasError
          ? t("chat.activity.error", "Error occurred")
          : activeToolNames.length > 0
            ? t("chat.usingTools", "Using tools")
            : t("chat.thinking", "Thinking...")}
      </span>

      {/* Tool names — inline, comma-separated */}
      {activeToolNames.length > 0 && (
        <span className="flex items-center gap-1 text-muted-foreground/50">
          <Wrench className="h-2.5 w-2.5 shrink-0" />
          <span className="truncate max-w-[200px]">
            {activeToolNames.join(", ")}
          </span>
        </span>
      )}
    </div>
  );
}
