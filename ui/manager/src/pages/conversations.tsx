import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import {
  MessageSquare,
  Search,
  RefreshCw,
  AlertCircle,
  ExternalLink,
  Trash2,
  Circle,
  CheckCircle2,
  Clock,
  AlertTriangle,
  Filter,
} from "lucide-react";
import { cn } from "@/lib/utils";
import {
  useConversationDescriptors,
  useDeleteConversation,
} from "@/hooks/use-conversations";
import { parseConversationUri, type ConversationState } from "@/lib/api/conversations";

const stateConfig: Record<
  ConversationState,
  { icon: typeof Circle; label: string; color: string; bg: string }
> = {
  READY: {
    icon: Circle,
    label: "Active",
    color: "text-emerald-500",
    bg: "bg-emerald-500/10",
  },
  IN_PROGRESS: {
    icon: Clock,
    label: "In Progress",
    color: "text-amber-500",
    bg: "bg-amber-500/10",
  },
  ERROR: {
    icon: AlertTriangle,
    label: "Error",
    color: "text-destructive",
    bg: "bg-destructive/10",
  },
  ENDED: {
    icon: CheckCircle2,
    label: "Ended",
    color: "text-muted-foreground",
    bg: "bg-muted",
  },
};

const stateFilters: { value: ConversationState | "ALL"; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "READY", label: "Active" },
  { value: "IN_PROGRESS", label: "In Progress" },
  { value: "ENDED", label: "Ended" },
  { value: "ERROR", label: "Error" },
];

export function ConversationsPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [stateFilter, setStateFilter] = useState<ConversationState | "ALL">(
    "ALL"
  );

  const { data: conversations, isLoading, isError, refetch } =
    useConversationDescriptors(
      50,
      0,
      search,
      "",
      stateFilter === "ALL" ? undefined : stateFilter
    );
  const deleteMutation = useDeleteConversation();

  function handleDelete(id: string) {
    if (
      window.confirm(
        t(
          "conversations.confirmDelete",
          "Are you sure you want to delete this conversation?"
        )
      )
    ) {
      deleteMutation.mutate({ id });
    }
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <MessageSquare className="h-8 w-8 text-primary" />
          {t("pages.conversations.title")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("pages.conversations.subtitle")}
        </p>
      </div>

      {/* Search + Filter bar */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search className="absolute inset-s-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t(
              "conversations.searchPlaceholder",
              "Search by conversation ID..."
            )}
            className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="conversation-search"
          />
        </div>

        {/* State filter */}
        <div className="flex items-center gap-1.5">
          <Filter className="h-4 w-4 text-muted-foreground" />
          {stateFilters.map((sf) => (
            <button
              key={sf.value}
              onClick={() => setStateFilter(sf.value)}
              className={cn(
                "rounded-full px-3 py-1 text-xs font-medium transition-colors",
                stateFilter === sf.value
                  ? "bg-primary text-primary-foreground"
                  : "bg-secondary text-secondary-foreground hover:bg-secondary/80"
              )}
            >
              {sf.label}
            </button>
          ))}
        </div>
      </div>

      {/* Content */}
      {isLoading && (
        <div className="flex items-center justify-center py-20">
          <RefreshCw className="h-8 w-8 animate-spin text-primary" />
        </div>
      )}

      {isError && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-16">
          <AlertCircle className="h-12 w-12 text-destructive" />
          <p className="mt-4 text-lg font-medium text-destructive">
            {t("common.error")}
          </p>
          <button
            onClick={() => refetch()}
            className="mt-4 rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
          >
            {t("common.retry")}
          </button>
        </div>
      )}

      {!isLoading && !isError && (!conversations || conversations.length === 0) && (
        <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border py-16">
          <MessageSquare className="h-12 w-12 text-muted-foreground/50" />
          <p className="mt-4 text-lg font-medium text-muted-foreground">
            {search || stateFilter !== "ALL"
              ? t("common.noResults")
              : t(
                  "conversations.empty",
                  "No conversations yet. Deploy a bot and start chatting!"
                )}
          </p>
        </div>
      )}

      {!isLoading && !isError && conversations && conversations.length > 0 && (
        <>
          <p className="text-sm text-muted-foreground">
            {t("conversations.count", {
              count: conversations.length,
              defaultValue: "{{count}} conversation(s)",
            })}
          </p>

          {/* Conversation table */}
          <div className="overflow-hidden rounded-xl border bg-card shadow-sm">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border bg-secondary/50">
                  <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("conversations.id", "Conversation")}
                  </th>
                  <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("conversations.bot", "Bot")}
                  </th>
                  <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("conversations.state", "State")}
                  </th>
                  <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("conversations.lastActivity", "Last Activity")}
                  </th>
                  <th className="px-5 py-3 text-end text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("conversations.actions", "Actions")}
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {conversations.map((conv) => {
                  const convId = parseConversationUri(conv.resource);
                  const state = conv.conversationState || "READY";
                  const config = stateConfig[state];
                  const StateIcon = config.icon;

                  return (
                    <tr
                      key={conv.resource}
                      className="hover:bg-secondary/30 transition-colors"
                    >
                      <td className="px-5 py-3">
                        <Link
                          to={`/manage/conversationview/${convId}`}
                          className="inline-flex items-center gap-1 text-sm font-medium text-foreground hover:text-primary transition-colors"
                        >
                          <span className="font-mono">
                            {convId.slice(0, 12)}…
                          </span>
                          <ExternalLink className="h-3 w-3 opacity-40" />
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground">
                          {conv.botId
                            ? `${conv.botId} v${conv.botVersion}`
                            : "—"}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span
                          className={cn(
                            "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium",
                            config.bg,
                            config.color
                          )}
                        >
                          <StateIcon className="h-3 w-3" />
                          {config.label}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground">
                          {conv.lastModifiedOn
                            ? new Date(
                                conv.lastModifiedOn
                              ).toLocaleString()
                            : "—"}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-end">
                        <button
                          onClick={() => handleDelete(convId)}
                          disabled={deleteMutation.isPending}
                          className="rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors disabled:opacity-50"
                          title={t("common.delete")}
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
