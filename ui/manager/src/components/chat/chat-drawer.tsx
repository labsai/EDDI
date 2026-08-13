import { useRef, useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { useChatDrawerStore, type ChatDrawerStep } from "@/hooks/use-chat-drawer";
import { useChatStore, useStartConversation, useSendMessage } from "@/hooks/use-chat";
import type { SentAttachment } from "@/hooks/use-chat";
import {
  filesFromClipboard,
  useAttachmentStaging,
  useFileDrop,
  type AttachmentStaging,
  type ReadyAttachment,
} from "@/hooks/use-attachment-staging";
import { ChatMessage } from "./chat-message";
import { ChatActivity } from "./chat-activity";
import { FileDropOverlay, PendingAttachmentChip } from "./attachment-chip";
import { StreamingToggle } from "./streaming-toggle";
import { DebugDrawer as DebugPanel } from "@/components/debugger/debug-drawer";
import { useDebugStore } from "@/hooks/use-debug-events";
import { InputHint } from "@/components/chat/input-hint";
import { useSmartAutoScroll } from "@/hooks/use-smart-auto-scroll";
import { cn } from "@/lib/utils";
import {
  ArrowDown,
  Bot,
  X,
  MessageSquarePlus,
  Loader2,
  Paperclip,
  Send,
  AlertCircle,
  RefreshCw,
  Rocket,
} from "lucide-react";

/* ─── Step progress indicator ─── */

function StepProgress({ current, error }: { current: ChatDrawerStep; error: string | null }) {
  const { t } = useTranslation();

  if (current === "error") {
    return (
      <div className="flex flex-col items-center gap-3 py-8 text-center">
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-destructive/10">
          <AlertCircle className="h-6 w-6 text-destructive" />
        </div>
        <p className="text-sm font-medium text-destructive">
          {error || t("chatDrawer.error", "Something went wrong")}
        </p>
      </div>
    );
  }

  // Single-line label for the current step
  const stepLabels: Record<string, string> = {
    saving: t("chatDrawer.saving", "Saving changes…"),
    deploying: t("chatDrawer.deploying", "Deploying agent…"),
    starting: t("chatDrawer.starting", "Starting conversation…"),
  };

  return (
    <div className="flex items-center gap-3 py-6 px-4">
      <Loader2 className="h-5 w-5 shrink-0 animate-spin text-primary" />
      <span className="text-sm font-medium text-foreground">
        {stepLabels[current] ?? t("chatDrawer.preparing", "Preparing…")}
      </span>
    </div>
  );
}

/* ─── Main ChatDrawer component ─── */
export function ChatDrawer() {
  const { t } = useTranslation();
  const isOpen = useChatDrawerStore((s) => s.isOpen);
  const agentName = useChatDrawerStore((s) => s.agentName);
  const agentId = useChatDrawerStore((s) => s.agentId);
  const step = useChatDrawerStore((s) => s.step);
  const errorMessage = useChatDrawerStore((s) => s.errorMessage);
  const close = useChatDrawerStore((s) => s.close);

  const messages = useChatStore((s) => s.messages);
  const conversationId = useChatStore((s) => s.conversationId);
  const isProcessing = useChatStore((s) => s.isProcessing);
  const isThinking = useChatStore((s) => s.isThinking);
  const currentTurnEvents = useDebugStore((s) => s.currentTurnEvents);

  const startConversation = useStartConversation();

  // Attachment staging shared between the drop zone (drawer body) and the
  // input's picker/paste — one staging area, same as the main panel.
  const staging = useAttachmentStaging(conversationId);
  const { isDragOver, dropHandlers } = useFileDrop(
    Boolean(conversationId),
    (files) => void staging.stageFiles(files),
  );

  // Smart auto-scroll: follows new content only while the user is AT the
  // bottom; scrolled up, the view stays put and a centered arrow offers the
  // way back down.
  const {
    scrollRef,
    showScrollFab,
    hasNewContent,
    scrollToBottom,
    handleScroll,
  } = useSmartAutoScroll<HTMLDivElement>({
    // isOpen: the scroll container mounts only when the drawer opens, so
    // without it an existing transcript opens scrolled to the TOP with no
    // way down but manual scrolling.
    deps: [messages, isThinking, isProcessing, isOpen],
    bottomThreshold: 80,
  });

  const handleNewConversation = useCallback(() => {
    if (!agentId) return;
    useChatStore.getState().clearMessages();
    useChatDrawerStore.getState().setStep("starting");
    startConversation.mutate(
      { agentId },
      { onSuccess: () => useChatDrawerStore.getState().setStep("ready") }
    );
  }, [agentId, startConversation]);

  const handleRetry = useCallback(() => {
    // Reset to idle — the user's "Save & Test" hook will need to be re-triggered
    useChatDrawerStore.getState().setStep("idle");
    useChatDrawerStore.getState().close();
  }, []);

  const showChat = step === "ready";
  const showProgress = step === "saving" || step === "deploying" || step === "starting";
  const showError = step === "error";

  return (
    <div
      className={cn(
        "flex shrink-0 flex-col bg-background overflow-hidden transition-[width,opacity] duration-300 ease-in-out",
        isOpen ? "w-[420px] opacity-100 border-s border-border" : "w-0 opacity-0"
      )}
      data-testid="chat-drawer"
      role="complementary"
      aria-label={t("chatDrawer.title", "Test Chat")}
    >
      {isOpen && (
        <>
          {/* Header */}
          <div className="flex items-center gap-2 border-b border-border px-4 py-2.5 shrink-0">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10">
              <Bot className="h-4 w-4 text-primary" aria-hidden="true" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-semibold text-foreground truncate">
                {t("chatDrawer.title", "Test Chat")}
              </p>
              <p className="text-xs text-muted-foreground truncate">{agentName}</p>
            </div>

            {/* Streaming toggle */}
            {showChat && <StreamingToggle />}

            {/* New conversation */}
            {showChat && (
              <button
                onClick={handleNewConversation}
                className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                title={t("chatDrawer.newConversation", "New Conversation")}
                aria-label={t("chatDrawer.newConversation", "New Conversation")}
                data-testid="drawer-new-conversation"
              >
                <MessageSquarePlus className="h-4 w-4" aria-hidden="true" />
              </button>
            )}

            {/* Close */}
            <button
              onClick={close}
              className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              title={t("common.close", "Close")}
              aria-label={t("common.close", "Close")}
              data-testid="drawer-close"
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>

          {/* Body — also the file drop zone once a conversation exists */}
          <div className="relative flex flex-1 flex-col overflow-hidden" {...dropHandlers}>
            {isDragOver && <FileDropOverlay />}
            {/* Progress steps */}
            {showProgress && (
              <div className="flex flex-1 items-center justify-center">
                <StepProgress current={step} error={null} />
              </div>
            )}

            {/* Error state */}
            {showError && (
              <div className="flex flex-1 flex-col items-center justify-center gap-4 p-6">
                <StepProgress current="error" error={errorMessage} />
                <button
                  onClick={handleRetry}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-secondary"
                  data-testid="drawer-retry"
                >
                  <RefreshCw className="h-4 w-4" />
                  {t("chatDrawer.retry", "Try again")}
                </button>
              </div>
            )}

            {/* Idle — drawer opened manually (from agent-detail) without save flow */}
            {step === "idle" && !conversationId && (
              <div className="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/10">
                  <Rocket className="h-8 w-8 text-primary" />
                </div>
                <p className="text-sm text-muted-foreground">
                  {t("chatDrawer.ready", "Ready — type a message to test")}
                </p>
              </div>
            )}

            {/* Chat messages */}
            {(showChat || (step === "idle" && conversationId)) && (
              <>
                <div className="relative flex-1 min-h-0">
                <div ref={scrollRef} onScroll={handleScroll} className="h-full overflow-y-auto" aria-live="polite" aria-relevant="additions">
                  {messages.length === 0 ? (
                    <div className="flex h-full items-center justify-center">
                      <div className="text-center">
                        <Bot className="mx-auto h-10 w-10 text-muted-foreground/30" />
                        <p className="mt-2 text-sm text-muted-foreground">
                          {t("chatDrawer.ready", "Ready — type a message to test")}
                        </p>
                      </div>
                    </div>
                  ) : (
                    <div className="py-3">
                      {messages.map((msg) => (
                        <ChatMessage key={msg.id} message={msg} />
                      ))}
                      {/* Live status — the same "Thinking…" / "Using {tool}…"
                          line the operator and main chat show; the plain
                          indicator covers the gap before the first event. */}
                      {(isProcessing || isThinking) && currentTurnEvents.length > 0 ? (
                        <ChatActivity events={currentTurnEvents} isLive showInternalSteps={false} />
                      ) : isThinking ? (
                        <div className="flex items-center gap-2 px-4 py-2 text-sm text-muted-foreground animate-pulse">
                          <Loader2 className="h-4 w-4 animate-spin" />
                          <span className="italic">{t("chat.thinking")}</span>
                        </div>
                      ) : null}
                    </div>
                  )}
                </div>

                {/* Centered scroll-to-bottom arrow — only while scrolled up */}
                {showScrollFab && (
                  <button
                    type="button"
                    onClick={() => scrollToBottom("smooth")}
                    className="absolute bottom-3 inset-x-0 mx-auto z-10 flex h-9 w-9 items-center justify-center rounded-full border border-border bg-card shadow-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all animate-in fade-in slide-in-from-bottom-2"
                    title={t("chat.scrollToBottom", "Scroll to bottom")}
                    aria-label={t("chat.scrollToBottom", "Scroll to bottom")}
                    data-testid="drawer-scroll-to-bottom"
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

                {/* Quick replies */}
                <QuickRepliesBar />

                {/* Debug drawer — same as main chat */}
                {conversationId && (
                  <DebugPanel
                    conversationId={conversationId}
                    agentId={agentId}
                  />
                )}

                {/* Input */}
                <DrawerChatInput
                  disabled={!conversationId}
                  isProcessing={isProcessing}
                  staging={staging}
                />
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}

/* ─── Quick replies bar for the drawer ─── */
function QuickRepliesBar() {
  const quickReplies = useChatStore((s) => s.quickReplies);
  const isProcessing = useChatStore((s) => s.isProcessing);
  const sendMessage = useSendMessage();

  if (quickReplies.length === 0 || isProcessing) return null;

  return (
    <div className="flex flex-wrap gap-1.5 border-t border-border px-3 py-2 shrink-0">
      {quickReplies.map((reply, i) => (
        <button
          type="button"
          key={`${reply}-${i}`}
          onClick={() => sendMessage.mutate({ message: reply })}
          className="rounded-full border border-primary/30 bg-primary/5 px-2.5 py-1 text-xs font-medium text-primary transition-colors hover:bg-primary/15"
          data-testid="drawer-quick-reply"
        >
          {reply}
        </button>
      ))}
    </div>
  );
}

/* ─── Simplified chat input for the drawer ─── */
function DrawerChatInput({
  disabled = false,
  isProcessing = false,
  staging,
}: {
  disabled?: boolean;
  isProcessing?: boolean;
  staging: AttachmentStaging;
}) {
  const { t } = useTranslation();
  const [value, setValue] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const sendMessage = useSendMessage();
  const { pendingAttachments, isUploading, hasReadyAttachment } = staging;

  const handleSend = useCallback(() => {
    const trimmed = value.trim();
    // Attachment-only turns are allowed, matching the main panel; the guard
    // runs BEFORE draining so a no-op send never clears staged chips.
    if ((!trimmed && !hasReadyAttachment) || disabled || isProcessing || isUploading) return;
    const sent: SentAttachment[] = staging.takeForSend().map((a: ReadyAttachment) => ({
      storageRef: a.result.storageRef,
      fileName: a.result.fileName || a.file.name,
      mimeType: a.result.mimeType || a.file.type || "application/octet-stream",
      sizeBytes: a.result.sizeBytes ?? a.file.size,
      forwardableInline: a.result.forwardableInline,
      previewUrl: a.previewUrl,
    }));
    sendMessage.mutate({ message: trimmed, attachments: sent.length ? sent : undefined });
    setValue("");
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [value, disabled, isProcessing, isUploading, hasReadyAttachment, staging, sendMessage]);

  const handleInput = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`;
  }, []);

  const canSend =
    (value.trim().length > 0 || hasReadyAttachment) && !disabled && !isProcessing && !isUploading;

  return (
    <div className="border-t border-border bg-background p-3 shrink-0">
      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={staging.handleFileInput}
        data-testid="drawer-file-input"
      />
      {pendingAttachments.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-2" data-testid="drawer-pending-attachments">
          {pendingAttachments.map((att) => (
            <PendingAttachmentChip key={att.id} att={att} onRemove={() => staging.removeAttachment(att.id)} />
          ))}
        </div>
      )}
      <div className="flex items-end gap-2">
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={disabled || isUploading}
          className={cn(
            "flex h-10 w-10 shrink-0 items-center justify-center rounded-xl transition-colors",
            isUploading
              ? "bg-primary/10 text-primary animate-pulse"
              : "text-muted-foreground hover:bg-muted hover:text-foreground",
            disabled && "cursor-not-allowed opacity-40",
          )}
          title={t("chat.attach", "Attach file")}
          aria-label={t("chat.attach", "Attach file")}
          data-testid="drawer-attach-btn"
        >
          {isUploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Paperclip className="h-4 w-4" />}
        </button>
        <textarea
          ref={textareaRef}
          data-testid="drawer-chat-input"
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
            // Screenshots / copied files paste as attachments; text pastes
            // fall through untouched.
            const files = filesFromClipboard(e);
            if (!files.length || disabled) return;
            e.preventDefault();
            void staging.stageFiles(files);
          }}
          placeholder={t("chat.placeholder")}
          disabled={disabled}
          rows={1}
          className={cn(
            "flex-1 resize-none rounded-xl border border-input bg-card px-3 py-2.5 text-sm",
            "placeholder:text-muted-foreground",
            "focus:outline-none focus:ring-2 focus:ring-ring",
            "disabled:cursor-not-allowed disabled:opacity-50",
            "max-h-[120px] min-h-[40px]"
          )}
        />
        <button
          onClick={handleSend}
          disabled={!canSend}
          className={cn(
            "flex h-10 w-10 shrink-0 items-center justify-center rounded-xl transition-colors",
            canSend
              ? "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
              : "bg-muted text-muted-foreground cursor-not-allowed"
          )}
          aria-label={t("chat.send")}
          data-testid="drawer-chat-send"
        >
          {isProcessing ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Send className="h-4 w-4" />
          )}
        </button>
      </div>
      <InputHint />
    </div>
  );
}
