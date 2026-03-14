import { useEffect, useRef, useState, useCallback } from "react";
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  useChatStore,
  useDeployedBots,
  useStartConversation,
  useSendMessage,
  useEndConversation,
  useUndoConversation,
  useRedoConversation,
} from "@/hooks/use-chat";
import { ChatMessage } from "./chat-message";
import { ChatInput } from "./chat-input";
import { ChatHistory } from "./chat-history";
import { StreamingToggle } from "./streaming-toggle";
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
} from "lucide-react";

export function ChatPanel() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [historyOpen, setHistoryOpen] = useState(false);
  const [botSelectorOpen, setBotSelectorOpen] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const botSelectorRef = useRef<HTMLDivElement>(null);
  const autoStartedRef = useRef(false);

  // Store state
  const messages = useChatStore((s) => s.messages);
  const selectedBotId = useChatStore((s) => s.selectedBotId);
  const selectedBotName = useChatStore((s) => s.selectedBotName);
  const conversationId = useChatStore((s) => s.conversationId);
  const isProcessing = useChatStore((s) => s.isProcessing);
  const isThinking = useChatStore((s) => s.isThinking);
  const undoAvailable = useChatStore((s) => s.undoAvailable);
  const redoAvailable = useChatStore((s) => s.redoAvailable);
  const quickReplies = useChatStore((s) => s.quickReplies);
  const setSelectedBot = useChatStore((s) => s.setSelectedBot);

  // Queries & mutations
  const { data: deployedBots, isLoading: botsLoading } = useDeployedBots();
  const startConversation = useStartConversation();
  const sendMessage = useSendMessage();
  const endConversation = useEndConversation();
  const undoConversation = useUndoConversation();
  const redoConversation = useRedoConversation();

  // Auto-start conversation from ?botId= query param
  useEffect(() => {
    if (autoStartedRef.current) return;
    const botIdParam = searchParams.get("botId");
    if (!botIdParam || !deployedBots) return;

    autoStartedRef.current = true;

    // Find bot name from deployed bots list
    const bot = deployedBots.find((b) => b.id === botIdParam);
    const botName = bot?.name ?? "Bot";

    // Auto-select and start conversation
    setSelectedBot(botIdParam, botName);
    startConversation.mutate({ botId: botIdParam });

    // Remove query param so refresh doesn't re-create
    setSearchParams({}, { replace: true });
  }, [searchParams, deployedBots, setSelectedBot, startConversation, setSearchParams]);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  // Close bot selector on outside click
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (
        botSelectorRef.current &&
        !botSelectorRef.current.contains(e.target as Node)
      ) {
        setBotSelectorOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  const handleSelectBot = useCallback(
    (botId: string, botName: string) => {
      setSelectedBot(botId, botName);
      setBotSelectorOpen(false);
      startConversation.mutate({ botId });
    },
    [setSelectedBot, startConversation]
  );

  const handleNewConversation = useCallback(() => {
    if (!selectedBotId) return;
    useChatStore.getState().clearMessages();
    startConversation.mutate({ botId: selectedBotId });
  }, [selectedBotId, startConversation]);

  const handleSend = useCallback(
    (message: string) => {
      sendMessage.mutate({ message });
    },
    [sendMessage]
  );

  const handleQuickReply = useCallback(
    (reply: string) => {
      sendMessage.mutate({ message: reply });
    },
    [sendMessage]
  );

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

          {/* Bot selector */}
          <div ref={botSelectorRef} className="relative flex-1">
            <button
              onClick={() => setBotSelectorOpen((p) => !p)}
              className="flex w-full items-center gap-2 rounded-lg border border-input bg-card px-3 py-2 text-sm transition-colors hover:bg-muted"
              data-testid="bot-selector"
            >
              <Bot className="h-4 w-4 text-muted-foreground" />
              <span
                className={cn(
                  "flex-1 text-start truncate",
                  !selectedBotName && "text-muted-foreground"
                )}
              >
                {selectedBotName ?? t("chat.selectBot")}
              </span>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-muted-foreground transition-transform",
                  botSelectorOpen && "rotate-180"
                )}
              />
            </button>

            {/* Dropdown */}
            {botSelectorOpen && (
              <div className="absolute inset-s-0 top-full z-50 mt-1 w-full rounded-lg border border-border bg-popover p-1 shadow-lg">
                {botsLoading ? (
                  <div className="flex items-center justify-center py-3">
                    <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                  </div>
                ) : !deployedBots?.length ? (
                  <p className="px-3 py-2 text-xs text-muted-foreground">
                    {t("chat.noBots")}
                  </p>
                ) : (
                  deployedBots.map((bot) => (
                    <button
                      key={bot.id}
                      onClick={() => handleSelectBot(bot.id, bot.name)}
                      className={cn(
                        "flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors hover:bg-muted",
                        bot.id === selectedBotId &&
                          "bg-primary/10 text-primary font-medium"
                      )}
                    >
                      <Bot className="h-4 w-4 shrink-0" />
                      <div className="flex-1 text-start">
                        <p className="truncate font-medium">{bot.name}</p>
                        {bot.description && (
                          <p className="truncate text-xs text-muted-foreground">
                            {bot.description}
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

        {/* Messages */}
        <div className="flex-1 overflow-y-auto">
          {!selectedBotId ? (
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

              {/* Thinking indicator */}
              {isThinking && (
                <div className="flex items-center gap-2 px-4 py-2 text-sm text-muted-foreground animate-pulse">
                  <Brain className="h-4 w-4" />
                  <span className="italic">{t("chat.thinking")}</span>
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>
          )}
        </div>

        {/* Quick replies */}
        {quickReplies.length > 0 && !isProcessing && (
          <div className="flex flex-wrap gap-2 border-t border-border px-4 py-2">
            {quickReplies.map((reply, i) => (
              <button
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

        {/* Undo / Redo action bar — near the input */}
        {conversationId && (
          <div className="flex items-center gap-1 border-t border-border px-4 py-1">
            <button
              onClick={() => undoConversation.mutate()}
              disabled={!undoAvailable || isProcessing}
              className={cn(
                "flex h-8 w-8 items-center justify-center rounded-lg transition-colors",
                undoAvailable && !isProcessing
                  ? "text-muted-foreground hover:bg-muted hover:text-foreground"
                  : "text-muted-foreground/30 cursor-not-allowed"
              )}
              title={t("chat.undo")}
              data-testid="undo-btn"
            >
              <Undo2 className="h-3.5 w-3.5" />
            </button>
            <button
              onClick={() => redoConversation.mutate()}
              disabled={!redoAvailable || isProcessing}
              className={cn(
                "flex h-8 w-8 items-center justify-center rounded-lg transition-colors",
                redoAvailable && !isProcessing
                  ? "text-muted-foreground hover:bg-muted hover:text-foreground"
                  : "text-muted-foreground/30 cursor-not-allowed"
              )}
              title={t("chat.redo")}
              data-testid="redo-btn"
            >
              <Redo2 className="h-3.5 w-3.5" />
            </button>
          </div>
        )}

        {/* Input */}
        <ChatInput
          onSend={handleSend}
          disabled={!conversationId}
          isProcessing={isProcessing}
        />
      </div>
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
