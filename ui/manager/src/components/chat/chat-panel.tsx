import { useEffect, useRef, useState, useCallback } from "react";
import { useSearchParams, Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  useChatStore,
  useDeployedAgents,
  useStartConversation,
  useSendMessage,
  useEndConversation,
  useUndoConversation,
  useRedoConversation,
  useRerunConversation,
} from "@/hooks/use-chat";
import {
  uploadAttachment,
  deleteAttachment,
  isImageMime,
  formatBytes,
  AttachmentError,
  MAX_ATTACHMENTS_PER_TURN,
  type AttachmentResult,
} from "@/lib/api/attachments";
import type { SentAttachment } from "@/hooks/use-chat";
import { ChatMessage } from "./chat-message";
import { ChatActivity } from "./chat-activity";
import { ChatHistory } from "./chat-history";
import { StreamingToggle } from "./streaming-toggle";
import { DebugDrawer } from "@/components/debugger/debug-drawer";
import { useDebugStore } from "@/hooks/use-debug-events";
import { cn } from "@/lib/utils";
import {
  Bot,
  ChevronDown,
  History,
  StopCircle,
  MessageSquarePlus,
  Loader2,
  Undo2,
  Redo2,
  Brain,
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
  X,
  FileText,
  AlertTriangle,
} from "lucide-react";

/** A file the user picked, tracked through upload → ready/error. */
interface PendingAttachment {
  id: string;
  file: File;
  /** Object URL for image previews. */
  previewUrl?: string;
  status: "uploading" | "ready" | "error";
  result?: AttachmentResult;
  error?: string;
}

export function ChatPanel() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const [historyOpen, setHistoryOpen] = useState(false);
  const [agentSelectorOpen, setAgentSelectorOpen] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const agentSelectorRef = useRef<HTMLDivElement>(null);
  const autoStartedRef = useRef(false);
  const [showScrollFab, setShowScrollFab] = useState(false);
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
  const sendMessage = useSendMessage();
  const endConversation = useEndConversation();
  const undoConversation = useUndoConversation();
  const redoConversation = useRedoConversation();

  // Auto-start conversation from ?agentId= query param
  useEffect(() => {
    if (autoStartedRef.current) return;
    const agentIdParam = searchParams.get("agentId");
    if (!agentIdParam || !deployedAgents) return;

    autoStartedRef.current = true;

    // Find agent name from deployed agents list
    const agent = deployedAgents.find((b) => b.id === agentIdParam);
    const agentName = agent?.name ?? "Agent";

    // Auto-select and start conversation
    setSelectedAgent(agentIdParam, agentName);
    startConversation.mutate({ agentId: agentIdParam });

    // Remove query param so refresh doesn't re-create
    setSearchParams({}, { replace: true });
  }, [searchParams, deployedAgents, setSelectedAgent, startConversation, setSearchParams]);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

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
      startConversation.mutate({ agentId });
    },
    [setSelectedAgent, startConversation]
  );

  const handleNewConversation = useCallback(() => {
    if (!selectedAgentId) return;
    useChatStore.getState().clearMessages();
    startConversation.mutate({ agentId: selectedAgentId });
  }, [selectedAgentId, startConversation]);

  // ── Attachment upload ──
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([]);
  const isUploading = pendingAttachments.some((a) => a.status === "uploading");
  const hasReadyAttachment = pendingAttachments.some((a) => a.status === "ready");

  // Mirror the pending list in a ref so handleAttach can read a live count
  // (for the per-turn cap) without being re-created on every change.
  const pendingRef = useRef(pendingAttachments);
  useEffect(() => {
    pendingRef.current = pendingAttachments;
  }, [pendingAttachments]);

  // Reset (and free) pending attachments whenever the conversation changes, so a
  // storageRef uploaded to a previous conversation is never sent to a new one.
  useEffect(() => {
    setPendingAttachments((prev) => {
      prev.forEach((a) => a.previewUrl && URL.revokeObjectURL(a.previewUrl));
      return [];
    });
  }, [conversationId]);

  // Free any unsent preview URLs if the panel unmounts (route change / input
  // swap) — those transitions don't change conversationId.
  useEffect(
    () => () => {
      pendingRef.current.forEach((a) => a.previewUrl && URL.revokeObjectURL(a.previewUrl));
    },
    [],
  );

  // Discard a staged attachment: free its object URL and best-effort delete the
  // uploaded blob server-side. Runs OUTSIDE any state updater (StrictMode
  // double-invokes updaters, which would double-fire the revoke / DELETE).
  const discardPending = useCallback(
    (a: PendingAttachment) => {
      if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
      if (a.status === "ready" && a.result && conversationId) {
        deleteAttachment(conversationId, a.result.storageRef).catch(() => {});
      }
    },
    [conversationId],
  );

  // Secret mode and attachments are mutually exclusive — a masked turn must not
  // carry a file. Discard anything staged the moment secret mode switches on.
  useEffect(() => {
    if (!isSecretMode || pendingRef.current.length === 0) return;
    pendingRef.current.forEach(discardPending);
    pendingRef.current = [];
    setPendingAttachments([]);
  }, [isSecretMode, discardPending]);

  const handleSend = useCallback(
    (message: string, isSecret?: boolean) => {
      // Secret turns never carry attachments — a masked bubble must not leak a
      // filename or thumbnail. Discard anything staged (freeing previews and
      // best-effort deleting the blob) instead of forwarding or displaying it.
      if (isSecret) {
        if (!message.trim()) return; // nothing to send once attachments are dropped
        pendingAttachments.forEach(discardPending);
        pendingRef.current = [];
        setPendingAttachments([]);
        sendMessage.mutate({ message, isSecret: true });
        return;
      }

      // Forward only successfully-uploaded attachments as context this turn.
      const sent: SentAttachment[] = pendingAttachments
        .filter((a): a is PendingAttachment & { result: AttachmentResult } =>
          a.status === "ready" && !!a.result)
        .map((a) => ({
          storageRef: a.result.storageRef,
          fileName: a.result.fileName || a.file.name,
          mimeType: a.result.mimeType || a.file.type || "application/octet-stream",
          sizeBytes: a.result.sizeBytes ?? a.file.size,
          forwardableInline: a.result.forwardableInline,
          previewUrl: a.previewUrl,
        }));

      // Guard: nothing to send.
      if (!message.trim() && sent.length === 0) return;

      sendMessage.mutate({
        message,
        isSecret,
        attachments: sent.length ? sent : undefined,
      });
      // Ready attachments hand their preview URL to the sent message (kept alive
      // for the bubble thumbnail); free the URLs of any not-forwarded (errored)
      // chips being dropped here so they don't leak.
      const forwarded = new Set(sent.map((s) => s.previewUrl).filter(Boolean));
      pendingAttachments.forEach((a) => {
        if (a.previewUrl && !forwarded.has(a.previewUrl)) URL.revokeObjectURL(a.previewUrl);
      });
      pendingRef.current = [];
      setPendingAttachments([]);
    },
    [sendMessage, pendingAttachments, discardPending]
  );

  const handleQuickReply = useCallback(
    (reply: string) => {
      sendMessage.mutate({ message: reply });
    },
    [sendMessage]
  );

  const handleAttach = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(e.target.files ?? []);
      if (fileInputRef.current) fileInputRef.current.value = "";
      if (!files.length || !conversationId) return;

      // Enforce the per-turn cap up front against the live count (errored chips
      // don't get forwarded, so they don't consume a slot).
      const activeCount = pendingRef.current.filter((a) => a.status !== "error").length;
      const room = Math.max(0, MAX_ATTACHMENTS_PER_TURN - activeCount);
      if (files.length > room) {
        toast.error(
          t("chat.attachLimit", "You can attach up to {{max}} files per message.", {
            max: MAX_ATTACHMENTS_PER_TURN,
          })
        );
      }
      const accepted = files.slice(0, room);
      if (!accepted.length) return;

      // Build entries once — object URLs are created here, never inside a state
      // updater (StrictMode double-invokes updaters and would orphan a blob URL).
      const entries: PendingAttachment[] = accepted.map((file, i) => ({
        id: `att-${Date.now()}-${i}-${Math.random().toString(36).slice(2)}`,
        file,
        previewUrl: isImageMime(file.type) ? URL.createObjectURL(file) : undefined,
        status: "uploading",
      }));
      pendingRef.current = [...pendingRef.current, ...entries];
      setPendingAttachments((prev) => [...prev, ...entries]);

      await Promise.all(
        entries.map(async (entry) => {
          try {
            const result = await uploadAttachment(conversationId, entry.file);
            setPendingAttachments((prev) =>
              prev.map((a) => (a.id === entry.id ? { ...a, status: "ready", result } : a))
            );
            if (result.forwardableInline === false) {
              toast.warning(
                t(
                  "chat.attachTooLargeToForward",
                  "{{name}} is stored but too large to send to the model inline.",
                  { name: result.fileName || entry.file.name },
                )
              );
            }
          } catch (err) {
            const message =
              err instanceof AttachmentError
                ? err.message
                : t("chat.attachError", "Failed to upload file");
            setPendingAttachments((prev) =>
              prev.map((a) =>
                a.id === entry.id ? { ...a, status: "error", error: message } : a
              )
            );
            toast.error(message);
          }
        })
      );
    },
    [conversationId, t]
  );

  const handleRemoveAttachment = useCallback(
    (id: string) => {
      const target = pendingRef.current.find((a) => a.id === id);
      if (target) discardPending(target);
      setPendingAttachments((prev) => prev.filter((a) => a.id !== id));
    },
    [discardPending]
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
    <div className="flex h-full overflow-hidden rounded-xl border border-border bg-background shadow-sm">
      {/* History panel */}
      <ChatHistory
        open={historyOpen}
        onNewConversation={handleNewConversation}
      />

      {/* Main chat area */}
      <div className="flex flex-1 flex-col">
        {/* Top bar */}
        <div className="flex items-center gap-2 border-b border-border px-4 py-2.5">
          {/* History toggle */}
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
              <div className="absolute inset-s-0 top-full z-50 mt-1 w-full rounded-lg border border-border bg-popover p-1 shadow-lg">
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
                      key={agent.id}
                      onClick={() => handleSelectAgent(agent.id, agent.name)}
                      className={cn(
                        "flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors hover:bg-muted",
                        agent.id === selectedAgentId &&
                          "bg-primary/10 text-primary font-medium"
                      )}
                    >
                      <Bot className="h-4 w-4 shrink-0" />
                      <div className="flex-1 text-start">
                        <p className="truncate font-medium">{agent.name}</p>
                        {agent.description && (
                          <p className="truncate text-xs text-muted-foreground">
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

          {/* Streaming toggle */}
          <StreamingToggle />

          {/* Activity toggle */}
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
        {conversationId && (
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
          className="relative flex-1 overflow-y-auto"
          onScroll={(e) => {
            const el = e.currentTarget;
            const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
            setShowScrollFab(distFromBottom > 200);
          }}
        >
          {!selectedAgentId ? (
            <EmptyState />
          ) : messages.length === 0 ? (
            <div className="flex h-full items-center justify-center">
              <div className="text-center">
                <Bot className="mx-auto h-12 w-12 text-muted-foreground/30" />
                <p className="mt-3 text-sm text-muted-foreground">
                  {startConversation.isPending
                    ? t("chat.thinking")
                    : t("chat.empty")}
                </p>
              </div>
            </div>
          ) : (
            <div className="py-4">
              {messages.map((msg) => (
                <ChatMessage key={msg.id} message={msg} />
              ))}

              {/* Inline activity — live processing */}
              {showActivity && (isProcessing || isThinking) && currentTurnEvents.length > 0 && (
                <ChatActivity
                  events={currentTurnEvents}
                  isLive={true}
                />
              )}

              {/* Thinking indicator (only when activity is hidden) */}
              {!showActivity && isThinking && (
                <div className="flex items-center gap-2 px-4 py-2 text-sm text-muted-foreground animate-pulse">
                  <Brain className="h-4 w-4" />
                  <span className="italic">{t("chat.thinking")}</span>
                </div>
              )}

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

          {/* Scroll-to-bottom FAB */}
          {showScrollFab && (
            <button
              onClick={() => messagesEndRef.current?.scrollIntoView({ behavior: "smooth" })}
              className="absolute bottom-4 end-4 z-10 flex h-9 w-9 items-center justify-center rounded-full border border-border bg-card shadow-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all animate-in fade-in slide-in-from-bottom-2"
              title={t("chat.scrollToBottom", "Scroll to bottom")}
              data-testid="scroll-to-bottom"
            >
              <ArrowDown className="h-4 w-4" />
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

        {/* Undo / Redo — compact inline buttons */}
        {conversationId && (undoAvailable || redoAvailable) && (
          <div className="flex items-center gap-0.5 border-t border-border px-3 py-0.5">
            <button
              onClick={() => undoConversation.mutate()}
              disabled={!undoAvailable || isProcessing}
              className={cn(
                "flex h-6 w-6 items-center justify-center rounded transition-colors",
                undoAvailable && !isProcessing
                  ? "text-muted-foreground hover:bg-muted hover:text-foreground"
                  : "text-muted-foreground/20 cursor-not-allowed"
              )}
              title={t("chat.undo")}
              data-testid="undo-btn"
            >
              <Undo2 className="h-3 w-3" />
            </button>
            <button
              onClick={() => redoConversation.mutate()}
              disabled={!redoAvailable || isProcessing}
              className={cn(
                "flex h-6 w-6 items-center justify-center rounded transition-colors",
                redoAvailable && !isProcessing
                  ? "text-muted-foreground hover:bg-muted hover:text-foreground"
                  : "text-muted-foreground/20 cursor-not-allowed"
              )}
              title={t("chat.redo")}
              data-testid="redo-btn"
            >
              <Redo2 className="h-3 w-3" />
            </button>
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
            onFileChange={handleAttach}
            isUploading={isUploading}
            hasConversation={!!conversationId}
            pendingAttachments={pendingAttachments}
            onRemoveAttachment={handleRemoveAttachment}
            hasReadyAttachment={hasReadyAttachment}
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
  isUploading = false,
  hasConversation = false,
  pendingAttachments = [],
  onRemoveAttachment,
  hasReadyAttachment = false,
}: {
  onSend: (message: string, isSecret?: boolean) => void;
  disabled?: boolean;
  isProcessing?: boolean;
  isSecretMode: boolean;
  onToggleSecret: () => void;
  fileInputRef: React.RefObject<HTMLInputElement | null>;
  onFileChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  isUploading?: boolean;
  hasConversation?: boolean;
  pendingAttachments?: PendingAttachment[];
  onRemoveAttachment?: (id: string) => void;
  hasReadyAttachment?: boolean;
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
    <div className="border-t border-border bg-background p-4" data-tour="chat-input-area">
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
      <div className="flex items-end gap-2">
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
            placeholder={t("chat.placeholder")}
            disabled={disabled}
            rows={1}
            className={cn(
              "flex-1 resize-none rounded-xl border border-input bg-card px-4 py-3 text-sm",
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
    </div>
  );
}

/** A single pending-attachment chip: thumbnail/icon, name, size, status + remove. */
function PendingAttachmentChip({
  att,
  onRemove,
}: {
  att: PendingAttachment;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const isImage = isImageMime(att.file.type) && att.previewUrl;
  const isError = att.status === "error";

  return (
    <div
      className={cn(
        "group relative flex items-center gap-2 rounded-lg border bg-card px-2 py-1.5 pe-7 text-xs",
        isError ? "border-destructive/40" : "border-border"
      )}
      title={att.error ?? att.file.name}
      data-testid="attachment-chip"
    >
      {/* Thumbnail / icon */}
      {isImage ? (
        <img
          src={att.previewUrl}
          alt={att.file.name}
          className="h-8 w-8 shrink-0 rounded object-cover"
        />
      ) : (
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded bg-muted">
          {isError ? (
            <AlertTriangle className="h-4 w-4 text-destructive" />
          ) : (
            <FileText className="h-4 w-4 text-muted-foreground" />
          )}
        </div>
      )}

      {/* Name + size / status */}
      <div className="flex min-w-0 flex-col">
        <span className="max-w-[140px] truncate font-medium text-foreground">
          {att.file.name}
        </span>
        <span className={cn("truncate", isError ? "text-destructive" : "text-muted-foreground")}>
          {att.status === "uploading"
            ? t("chat.attachUploading", "Uploading...")
            : isError
              ? (att.error ?? t("chat.attachError", "Failed to upload file"))
              : formatBytes(att.result?.sizeBytes ?? att.file.size)}
        </span>
      </div>

      {/* Uploading spinner overlays the remove slot */}
      {att.status === "uploading" ? (
        <Loader2 className="absolute inset-e-1.5 top-1.5 h-4 w-4 animate-spin text-muted-foreground" />
      ) : (
        <button
          type="button"
          onClick={onRemove}
          className="absolute inset-e-1 top-1 flex h-5 w-5 items-center justify-center rounded-full text-muted-foreground/60 hover:bg-muted hover:text-foreground"
          title={t("common.remove", "Remove")}
          data-testid="attachment-remove"
        >
          <X className="h-3 w-3" />
        </button>
      )}
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
