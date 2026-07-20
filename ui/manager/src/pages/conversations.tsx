import { useState, useEffect, useMemo } from "react";
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
  HandMetal,
  ChevronLeft,
  ChevronRight,
  Activity,
} from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { cn } from "@/lib/utils";
import {
  useConversationDescriptors,
  useDeleteConversation,
  useConversationStepCount,
} from "@/hooks/use-conversations";
import { useAgentDescriptors, groupAgentsByName, useAgentVersions } from "@/hooks/use-agents";
import { parseConversationUri, MAX_CONVERSATION_LIMIT, type ConversationState } from "@/lib/api/conversations";
import { AgentPicker } from "@/components/shared/agent-picker";
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
  AWAITING_HUMAN: { icon: HandMetal, color: "text-orange-500", bg: "bg-orange-500/10" },
};

const STATE_FILTER_VALUES: (ConversationState | "ALL")[] = [
  "ALL", "READY", "IN_PROGRESS", "ENDED", "EXECUTION_INTERRUPTED", "ERROR", "AWAITING_HUMAN",
];

/** Page-size options offered to the user (backend clamps `limit` to 100). */
const PAGE_SIZE_OPTIONS = [25, 50, MAX_CONVERSATION_LIMIT];

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
    AWAITING_HUMAN: t("hitl.awaitingHuman", "Awaiting Human"),
  };
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [deletePermanent, setDeletePermanent] = useState(false);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("conversations"));

  // Filters + pagination. `page` maps directly to the backend `index` (a page
  // index, not a row offset); `pageSize` maps to `limit`.
  const [agentFilter, setAgentFilter] = useState("");
  const [versionFilter, setVersionFilter] = useState<number | undefined>(undefined);
  const [pageSize, setPageSize] = useState(50);
  const [page, setPage] = useState(0);

  // Any change to the query criteria must reset to the first page, otherwise a
  // stale high page index yields an empty result on the new criteria.
  useEffect(() => {
    setPage(0);
  }, [search, stateFilter, agentFilter, versionFilter, pageSize]);

  // Reset the version sub-filter whenever the agent changes (versions belong to
  // a specific agent).
  useEffect(() => {
    setVersionFilter(undefined);
  }, [agentFilter]);

  const { data: versions } = useAgentVersions(agentFilter);
  // Dedupe by version number — one <option> per distinct version.
  const versionOptions = useMemo(
    () => (versions ? [...new Map(versions.map((v) => [v.version, v])).values()] : []),
    [versions]
  );

  const { data: conversations, isLoading, isFetching, isError, refetch } =
    useConversationDescriptors(
      pageSize,
      page,
      search,
      agentFilter,
      stateFilter === "ALL" ? undefined : stateFilter,
      versionFilter
    );
  const { data: agents = [] } = useAgentDescriptors(50);
  const deleteMutation = useDeleteConversation();

  const hasActiveFilters =
    !!search || stateFilter !== "ALL" || !!agentFilter || versionFilter != null;

  const pageCount = conversations?.length ?? 0;
  const rangeStart = pageCount > 0 ? page * pageSize + 1 : 0;
  const rangeEnd = page * pageSize + pageCount;
  // Offset pagination without a total count: a full page implies more may
  // follow; a short page is the last one.
  const hasNextPage = pageCount === pageSize;
  const hasPrevPage = page > 0;

  function confirmDelete() {
    if (deleteTarget) {
      deleteMutation.mutate(
        { id: deleteTarget, permanent: deletePermanent },
        {
          onSuccess: () => {
            toast.success(
              deletePermanent
                ? t("conversations.permanentDeleteSuccess", "Permanently deleted")
                : t("conversations.softDeleteSuccess", "Conversation deleted")
            );
            setDeleteTarget(null);
            setDeletePermanent(false);
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
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <MessageSquare className="h-8 w-8 text-primary" />
            {t("pages.conversations.title")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("pages.conversations.subtitle")}
          </p>
        </div>
        <Link
          to="/manage/conversations/monitoring"
          className="inline-flex items-center gap-2 rounded-lg border border-input bg-background px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-secondary/50"
          data-testid="monitor-active-link"
        >
          <Activity className="h-4 w-4 text-primary" />
          {t("conversations.monitorActive", "Monitor active")}
        </Link>
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

      {/* Agent + version filter row */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center" data-testid="agent-filter">
        <div className="flex items-center gap-1.5 sm:w-80">
          <Bot className="h-4 w-4 shrink-0 text-muted-foreground" />
          <AgentPicker
            value={agentFilter}
            onChange={setAgentFilter}
            placeholder={t("conversations.filterByAgent", "Filter by agent")}
          />
        </div>
        {agentFilter && versionOptions.length > 0 && (
          <select
            value={versionFilter ?? ""}
            onChange={(e) =>
              setVersionFilter(e.target.value ? Number(e.target.value) : undefined)
            }
            aria-label={t("conversations.filterByVersion", "Filter by agent version")}
            data-testid="version-filter"
            className="h-9 rounded-md border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">{t("conversations.allVersions", "All versions")}</option>
            {versionOptions.map((v) => (
              <option key={v.version} value={v.version}>
                {t("conversations.version", "v{{version}}", { version: v.version })}
              </option>
            ))}
          </select>
        )}
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
            hasActiveFilters
              ? t("common.noResults")
              : t("conversations.empty")
          }
          description={
            !hasActiveFilters
              ? t("conversations.emptyDescription", "Deploy an agent and start a conversation from the Chat page.")
              : undefined
          }
          actionLabel={!hasActiveFilters ? t("nav.chat") : undefined}
          onAction={!hasActiveFilters ? () => navigate("/manage/chat") : undefined}
        />
      )}

      {!isLoading && !isError && conversations && conversations.length > 0 && (
        <>
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground" data-testid="pagination-range">
            {t("conversations.showingRange", "Showing {{from}}–{{to}}", {
              from: rangeStart,
              to: rangeEnd,
            })}
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

      {/* Pagination controls */}
      {!isLoading && !isError && (pageCount > 0 || page > 0) && (
        <div
          className="mt-4 flex flex-wrap items-center justify-between gap-3"
          data-testid="conversation-pagination"
        >
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <label htmlFor="page-size-select">
              {t("conversations.perPage", "Per page")}
            </label>
            <select
              id="page-size-select"
              value={pageSize}
              onChange={(e) => setPageSize(Number(e.target.value))}
              data-testid="page-size-select"
              className="h-8 rounded-md border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {PAGE_SIZE_OPTIONS.map((size) => (
                <option key={size} value={size}>
                  {size}
                </option>
              ))}
            </select>
          </div>

          <div className="flex items-center gap-3">
            <span className="text-sm text-muted-foreground" data-testid="page-indicator">
              {t("conversations.pageNumber", "Page {{page}}", { page: page + 1 })}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={!hasPrevPage || isFetching}
              data-testid="pagination-prev"
              aria-label={t("common.previous", "Previous")}
            >
              <ChevronLeft className="h-4 w-4" />
              {t("common.previous", "Previous")}
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => p + 1)}
              disabled={!hasNextPage || isFetching}
              data-testid="pagination-next"
              aria-label={t("common.next", "Next")}
            >
              {t("common.next", "Next")}
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        </div>
      )}

      </div>

      {/* Delete confirmation */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDeleteTarget(null);
            setDeletePermanent(false);
          }
        }}
        title={t("conversations.confirmDelete")}
        description={t(
          "conversations.confirmDeleteSoft",
          "By default this is a soft delete — the conversation is hidden from listings but its stored data is kept on the server."
        )}
        confirmLabel={
          deletePermanent
            ? t("conversations.deletePermanentAction", "Delete permanently")
            : t("common.delete")
        }
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      >
        <label className="flex cursor-pointer items-start gap-2 rounded-lg border border-border/60 bg-muted/30 p-3 text-sm">
          <input
            type="checkbox"
            className="mt-0.5 h-4 w-4 accent-destructive"
            checked={deletePermanent}
            onChange={(e) => setDeletePermanent(e.target.checked)}
            data-testid="delete-permanent-checkbox"
          />
          <span className="text-muted-foreground">
            {t(
              "conversations.deletePermanentLabel",
              "Permanently delete — also erases attachments, the memory snapshot and approval history. This cannot be undone."
            )}
          </span>
        </label>
      </AlertDialog>
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
