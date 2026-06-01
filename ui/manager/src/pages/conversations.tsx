import { useState, useEffect } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router-dom";
import {
  MessageSquare,
  Search,
  ExternalLink,
  Trash2,
  Circle,
  CheckCircle2,
  Clock,
  AlertTriangle,
  Filter,
  Bot,
} from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { cn } from "@/lib/utils";
import {
  useConversationDescriptors,
  useDeleteConversation,
  useConversationStepCount,
} from "@/hooks/use-conversations";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import { parseConversationUri, type ConversationState } from "@/lib/api/conversations";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import {
  ViewToggle,
  type ViewMode,
} from "@/components/shared/view-toggle";
import { getStoredViewMode, setStoredViewMode } from "@/components/shared/view-mode";

// Status config labels resolved via i18n inside component
const stateIcons: Record<
  ConversationState,
  { icon: typeof Circle; color: string; bg: string }
> = {
  READY: { icon: Circle, color: "text-emerald-500", bg: "bg-emerald-500/10" },
  IN_PROGRESS: { icon: Clock, color: "text-amber-500", bg: "bg-amber-500/10" },
  ERROR: { icon: AlertTriangle, color: "text-destructive", bg: "bg-destructive/10" },
  ENDED: { icon: CheckCircle2, color: "text-muted-foreground", bg: "bg-muted" },
  EXECUTION_INTERRUPTED: { icon: AlertTriangle, color: "text-amber-500", bg: "bg-amber-500/10" },
};

const STATE_FILTER_VALUES: (ConversationState | "ALL")[] = [
  "ALL", "READY", "IN_PROGRESS", "ENDED", "ERROR",
];

export function ConversationsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("conversations"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);
  const [stateFilter, setStateFilter] = useState<ConversationState | "ALL">("ALL");

  // i18n labels for conversation states
  const stateLabels: Record<ConversationState | "ALL", string> = {
    ALL: t("conversations.filterAll", "All"),
    READY: t("conversations.stateActive", "Active"),
    IN_PROGRESS: t("conversations.stateInProgress", "In Progress"),
    ERROR: t("status.error", "Error"),
    ENDED: t("conversations.stateEnded", "Ended"),
    EXECUTION_INTERRUPTED: t("conversations.stateInterrupted", "Interrupted"),
  };
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("conversations"));

  const { data: conversations, isLoading, isError, refetch } =
    useConversationDescriptors(
      50,
      0,
      search,
      "",
      stateFilter === "ALL" ? undefined : stateFilter
    );
  const { data: agents = [] } = useAgentDescriptors(50);
  const deleteMutation = useDeleteConversation();

  function confirmDelete() {
    if (deleteTarget) {
      deleteMutation.mutate(
        { id: deleteTarget },
        {
          onSuccess: () => {
            toast.success(t("common.delete") + " ✓");
            setDeleteTarget(null);
          },
          onError: (err) => toast.error(getErrorMessage(err)),
        }
      );
    }
  }

  function handleViewChange(mode: ViewMode) {
    setView(mode);
    setStoredViewMode("conversations", mode);
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
            placeholder={t("conversations.searchPlaceholder")}
            aria-label={t("conversations.searchPlaceholder")}
            className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="conversation-search"
          />
        </div>

        {/* State filter + view toggle */}
        <div className="flex items-center gap-3" data-tour="conversations-filters">
          <div className="flex items-center gap-1.5">
            <Filter className="h-4 w-4 text-muted-foreground" />
            {STATE_FILTER_VALUES.map((sf) => (
              <Button
                key={sf}
                variant={stateFilter === sf ? "primary" : "secondary"}
                size="sm"
                className="rounded-full"
                onClick={() => setStateFilter(sf)}
              >
                {stateLabels[sf]}
              </Button>
            ))}
          </div>
          <ViewToggle view={view} onChange={handleViewChange} />
        </div>
      </div>

      {/* Content */}
      <div data-tour="conversations-content">
      {isLoading && (
        <div className="overflow-hidden rounded-xl border bg-card shadow-sm">
          <div className="space-y-0">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex items-center gap-4 border-b border-border px-5 py-4">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-4 w-20" />
                <Skeleton className="h-5 w-16 rounded-full" />
                <Skeleton className="h-4 w-32" />
                <Skeleton className="ms-auto h-6 w-6" />
              </div>
            ))}
          </div>
        </div>
      )}

      {isError && (
        <ErrorState
          message={t("common.error")}
          onRetry={() => refetch()}
          retryLabel={t("common.retry")}
        />
      )}

      {!isLoading && !isError && (!conversations || conversations.length === 0) && (
        <EmptyState
          icon={MessageSquare}
          title={
            search || stateFilter !== "ALL"
              ? t("common.noResults")
              : t("conversations.empty")
          }
          description={
            !search && stateFilter === "ALL"
              ? t("conversations.emptyDescription", "Deploy an agent and start a conversation from the Chat page.")
              : undefined
          }
          actionLabel={!search && stateFilter === "ALL" ? t("nav.chat") : undefined}
          onAction={!search && stateFilter === "ALL" ? () => navigate("/manage/chat") : undefined}
        />
      )}

      {!isLoading && !isError && conversations && conversations.length > 0 && (
        <>
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("conversations.count", { count: conversations.length })}
          </p>

          {view === "card" ? (
            /* Card grid */
            <div
              className="cq-card-grid"
              data-testid="conversation-grid"
            >
              {conversations.map((conv) => {
                const convId = parseConversationUri(conv.resource);
                const state = conv.conversationState || "READY";
                const config = stateIcons[state];
                const stateLabel = stateLabels[state];
                const StateIcon = config.icon;
                const agentName = agents ? groupAgentsByName(agents).find(a => a.id === conv.agentId)?.name : null;

                return (
                  <Link
                    key={conv.resource}
                    to={`/manage/conversationview/${convId}`}
                    className={cn(
                      "group flex flex-col rounded-xl border bg-card p-5 shadow-sm transition-all duration-200",
                      "hover:shadow-md hover:border-primary/30"
                    )}
                    data-testid={`conversation-card-${convId}`}
                  >
                    {/* State badge */}
                    <div className="flex items-start justify-between">
                      <span
                        className={cn(
                          "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium",
                          config.bg,
                          config.color
                        )}
                      >
                        <StateIcon className="h-3.5 w-3.5" />
                        {stateLabel}
                      </span>
                      <button
                        onClick={(e) => {
                          e.preventDefault();
                          e.stopPropagation();
                          setDeleteTarget(convId);
                        }}
                        className="rounded-md p-1 text-muted-foreground opacity-0 transition-opacity hover:text-destructive hover:bg-destructive/10 group-hover:opacity-100 focus:opacity-100"
                        aria-label={t("conversations.deleteConversation", "Delete conversation")}
                      >
                        <Trash2 className="h-4 w-4" aria-hidden="true" />
                      </button>
                    </div>

                    {/* ID */}
                    <div className="mt-3">
                      <p className="font-mono text-sm font-medium text-foreground truncate" title={convId}>
                        {convId}
                      </p>
                    </div>

                    {/* Agent info + Step count */}
                    {conv.agentId && (
                      <div className="mt-2 flex items-center gap-1.5">
                        <Bot className="h-3.5 w-3.5 text-muted-foreground" />
                        <span className="text-xs text-muted-foreground truncate" title={agentName || conv.agentId}>
                          {agentName || conv.agentId}
                          {conv.agentVersion ? ` v${conv.agentVersion}` : ""}
                        </span>
                      </div>
                    )}

                    {/* Step count badge */}
                    <div className="mt-2">
                      <StepCountBadge conversationId={convId} />
                    </div>

                    {/* Footer */}
                    <div className="mt-auto pt-3 border-t border-border">
                      <span className="text-xs text-muted-foreground">
                        {conv.lastModifiedOn
                          ? new Date(conv.lastModifiedOn).toLocaleString()
                          : "—"}
                      </span>
                    </div>
                  </Link>
                );
              })}
            </div>
          ) : (
            /* List table */
            <div
              className="overflow-hidden rounded-xl border bg-card shadow-sm"
              data-testid="conversation-list"
            >
              <table className="w-full">
                <thead>
                  <tr className="border-b border-border bg-secondary/50">
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.id")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.agent")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.state")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.steps", "Steps")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.lastActivity")}
                    </th>
                    <th className="px-5 py-3 text-end text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.actions")}
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {conversations.map((conv) => {
                    const convId = parseConversationUri(conv.resource);
                    const state = conv.conversationState || "READY";
                    const config = stateIcons[state];
                    const StateIcon = config.icon;
                    const agentName = agents ? groupAgentsByName(agents).find(a => a.id === conv.agentId)?.name || conv.agentId : conv.agentId;

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
                            <span className="font-mono" title={convId}>
                              {convId.length > 20 ? `${convId.slice(0, 20)}…` : convId}
                            </span>
                            <ExternalLink className="h-3 w-3 opacity-40" />
                          </Link>
                        </td>
                        <td className="px-5 py-3">
                          <span className="text-sm text-muted-foreground">
                            {conv.agentId
                              ? `${agentName}${conv.agentVersion ? ` v${conv.agentVersion}` : ""}`
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
                            {stateLabels[state]}
                          </span>
                        </td>
                        <td className="px-5 py-3">
                          <StepCountBadge conversationId={convId} />
                        </td>
                        <td className="px-5 py-3">
                          <span className="text-sm text-muted-foreground">
                            {conv.lastModifiedOn
                              ? new Date(conv.lastModifiedOn).toLocaleString()
                              : "—"}
                          </span>
                        </td>
                        <td className="px-5 py-3 text-end">
                          <Button
                            variant="ghost"
                            size="icon"
                            className="text-muted-foreground hover:text-destructive hover:bg-destructive/10"
                            onClick={() => setDeleteTarget(convId)}
                            disabled={deleteMutation.isPending}
                            aria-label={t("conversations.deleteConversation", "Delete conversation")}
                          >
                            <Trash2 className="h-4 w-4" aria-hidden="true" />
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      </div>

      {/* Delete confirmation */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("conversations.confirmDelete")}
        description={t("conversations.confirmDeleteDescription", "This conversation and its history will be permanently removed.")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}

/** Lazily loads and displays the step count for a conversation. */
function StepCountBadge({ conversationId }: { conversationId: string }) {
  const { t } = useTranslation();
  const { data: count, isLoading } = useConversationStepCount(conversationId);

  if (isLoading) {
    return <span className="inline-block h-4 w-8 animate-pulse rounded bg-secondary" />;
  }

  if (count === undefined || count === null) return <span className="text-xs text-muted-foreground">—</span>;

  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
      {count} {count === 1 ? t("conversations.step", "step") : t("conversations.steps", "steps")}
    </span>
  );
}
