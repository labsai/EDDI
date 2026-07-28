import { useState, useRef, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Send, Square, RotateCcw, AlertTriangle, Bot, User } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ChatActivity } from "@/components/chat/chat-activity";
import { OPERATOR_STARTER_PROMPTS } from "@/lib/operator/system-prompt";
import type { ChatMessage } from "@/lib/api/chat";
import type { PipelineEvent } from "@/hooks/use-debug-events";
import { cn } from "@/lib/utils";

interface OperatorChatProps {
  messages: ChatMessage[];
  events: PipelineEvent[];
  /** Completed turns' traces, keyed by the agent message they belong to. */
  tracesByMessageId: Record<string, PipelineEvent[]>;
  isStreaming: boolean;
  error: string | null;
  onSend: (input: string) => void;
  onStop: () => void;
  onReset: () => void;
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
}: OperatorChatProps) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, events]);

  function submit(text: string) {
    const value = text.trim();
    if (!value || isStreaming) return;
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
        aria-label={t("operator.chat.transcript")}
      >
        {messages.length === 0 && (
          <div className="space-y-3 py-8 text-center">
            <Bot className="mx-auto h-10 w-10 text-muted-foreground/40" />
            <p className="text-sm text-muted-foreground">{t("operator.chat.empty")}</p>
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
          placeholder={t("operator.chat.placeholder")}
          aria-label={t("operator.chat.placeholder")}
          className="h-10 flex-1 rounded-md border border-input bg-background px-3 text-sm"
          data-testid="operator-input"
        />
        {isStreaming ? (
          <Button variant="outline" size="icon" onClick={onStop} title={t("operator.chat.stop")} aria-label={t("operator.chat.stop")}>
            <Square className="h-4 w-4" />
          </Button>
        ) : (
          <Button
            size="icon"
            onClick={() => submit(input)}
            disabled={!input.trim()}
            title={t("operator.chat.send")}
            aria-label={t("operator.chat.send")}
            data-testid="operator-send"
          >
            <Send className="h-4 w-4" />
          </Button>
        )}
        <Button
          variant="ghost"
          size="icon"
          onClick={onReset}
          title={t("operator.chat.newConversation")}
          aria-label={t("operator.chat.newConversation")}
        >
          <RotateCcw className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}
