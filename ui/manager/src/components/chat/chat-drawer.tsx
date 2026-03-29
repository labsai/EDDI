import { useEffect, useRef, useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { useChatDrawerStore, type ChatDrawerStep } from "@/hooks/use-chat-drawer";
import { useChatStore, useStartConversation, useSendMessage } from "@/hooks/use-chat";
import { ChatMessage } from "./chat-message";
import { StreamingToggle } from "./streaming-toggle";
import { DebugDrawer as DebugPanel } from "@/components/debugger/debug-drawer";
import { cn } from "@/lib/utils";
import {
  Bot,
  X,
  MessageSquarePlus,
  Loader2,
  Send,
  CheckCircle2,
  AlertCircle,
  RefreshCw,
  Rocket,
} from "lucide-react";

/* ─── Step progress indicator ─── */
const STEP_ORDER: ChatDrawerStep[] = ["saving", "deploying", "starting", "ready"];

function StepProgress({ current, error }: { current: ChatDrawerStep; error: string | null }) {
  const { t } = useTranslation();

  const steps = [
    { key: "saving", label: t("chatDrawer.saving", "Saving changes…") },
    { key: "deploying", label: t("chatDrawer.deploying", "Deploying agent…") },
    { key: "starting", label: t("chatDrawer.starting", "Starting conversation…") },
  ];

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

  const currentIdx = STEP_ORDER.indexOf(current);

  return (
    <div className="space-y-3 py-6">
      {steps.map((step, idx) => {
        const isDone = currentIdx > idx;
        const isActive = currentIdx === idx;
        return (
          <div key={step.key} className="flex items-center gap-3 px-4">
            {isDone ? (
              <CheckCircle2 className="h-5 w-5 shrink-0 text-emerald-500" />
            ) : isActive ? (
              <Loader2 className="h-5 w-5 shrink-0 animate-spin text-primary" />
            ) : (
              <div className="h-5 w-5 shrink-0 rounded-full border-2 border-muted-foreground/30" />
            )}
            <span
              className={cn(
                "text-sm",
                isDone && "text-emerald-600 dark:text-emerald-400 line-through opacity-70",
                isActive && "font-medium text-foreground",
                !isDone && !isActive && "text-muted-foreground"
              )}
            >
              {step.label}
            </span>
          </div>
        );
      })}
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

  const startConversation = useStartConversation();
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

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

          {/* Body */}
          <div className="flex flex-1 flex-col overflow-hidden">
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
                <div className="flex-1 overflow-y-auto" aria-live="polite" aria-relevant="additions">
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
                      {isThinking && (
                        <div className="flex items-center gap-2 px-4 py-2 text-sm text-muted-foreground animate-pulse">
                          <Loader2 className="h-4 w-4 animate-spin" />
                          <span className="italic">{t("chat.thinking")}</span>
                        </div>
                      )}
                      <div ref={messagesEndRef} />
                    </div>
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
}: {
  disabled?: boolean;
  isProcessing?: boolean;
}) {
  const { t } = useTranslation();
  const [value, setValue] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const sendMessage = useSendMessage();

  const handleSend = useCallback(() => {
    const trimmed = value.trim();
    if (!trimmed || disabled || isProcessing) return;
    sendMessage.mutate({ message: trimmed });
    setValue("");
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [value, disabled, isProcessing, sendMessage]);

  const handleInput = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`;
  }, []);

  const canSend = value.trim().length > 0 && !disabled && !isProcessing;

  return (
    <div className="border-t border-border bg-background p-3 shrink-0">
      <div className="flex items-end gap-2">
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
    </div>
  );
}
