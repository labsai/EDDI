import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { useChatStore, useConversationHistory, useLoadConversation } from "@/hooks/use-chat";
import { parseConversationUri, type ConversationDescriptor } from "@/lib/api/conversations";
import { Plus, History, Loader2 } from "lucide-react";

interface ChatHistoryProps {
  open: boolean;
  onNewConversation: () => void;
}

export function ChatHistory({ open, onNewConversation }: ChatHistoryProps) {
  const { t } = useTranslation();
  const selectedBotId = useChatStore((s) => s.selectedBotId);
  const conversationId = useChatStore((s) => s.conversationId);
  const { data: conversations, isLoading } = useConversationHistory(selectedBotId);
  const loadConversation = useLoadConversation();

  const handleResume = (conv: ConversationDescriptor) => {
    if (!selectedBotId) return;
    const convId = parseConversationUri(conv.resource);
    loadConversation.mutate({ botId: selectedBotId, conversationId: convId });
  };

  if (!open) return null;

  return (
    <div className="flex h-full w-64 shrink-0 flex-col border-e border-border bg-card/50">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <History className="h-4 w-4" />
          {t("chat.history")}
        </div>
        <button
          onClick={onNewConversation}
          className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs font-medium text-primary transition-colors hover:bg-primary/10"
          data-testid="new-conversation-btn"
        >
          <Plus className="h-3.5 w-3.5" />
          {t("chat.newConversation")}
        </button>
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto p-2">
        {isLoading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        ) : !conversations?.length ? (
          <p className="px-2 py-4 text-center text-xs text-muted-foreground">
            {t("chat.noHistory")}
          </p>
        ) : (
          <ul className="space-y-1">
            {conversations.map((conv) => {
              const convId = parseConversationUri(conv.resource);
              const isActive = convId === conversationId;
              return (
                <li key={conv.resource}>
                  <button
                    onClick={() => handleResume(conv)}
                    disabled={loadConversation.isPending}
                    className={cn(
                      "flex w-full flex-col gap-1 rounded-lg px-3 py-2 text-start transition-colors",
                      isActive
                        ? "bg-primary/10 text-primary"
                        : "text-foreground hover:bg-muted"
                    )}
                  >
                    <span className="truncate text-xs font-medium">
                      {convId.substring(0, 12)}…
                    </span>
                    <div className="flex items-center gap-2">
                      <ConversationStateBadge state={conv.conversationState} />
                      <span className="text-[10px] text-muted-foreground">
                        {new Date(conv.lastModifiedOn).toLocaleDateString()}
                      </span>
                    </div>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

function ConversationStateBadge({
  state,
}: {
  state: string;
}) {
  const colors: Record<string, string> = {
    READY: "bg-emerald-500/15 text-emerald-600",
    IN_PROGRESS: "bg-amber-500/15 text-amber-600",
    ENDED: "bg-muted text-muted-foreground",
    ERROR: "bg-destructive/15 text-destructive",
  };
  return (
    <span
      className={cn(
        "rounded-full px-1.5 py-0.5 text-[10px] font-medium",
        colors[state] ?? colors["READY"]
      )}
    >
      {state}
    </span>
  );
}
