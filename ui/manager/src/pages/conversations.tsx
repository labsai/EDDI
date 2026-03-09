import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
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
} from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import {
  useConversationDescriptors,
  useDeleteConversation,
} from "@/hooks/use-conversations";
import { parseConversationUri, type ConversationState } from "@/lib/api/conversations";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";

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
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);

  const { data: conversations, isLoading, isError, refetch } =
    useConversationDescriptors(
      50,
      0,
      search,
      "",
      stateFilter === "ALL" ? undefined : stateFilter
    );
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
          onError: () => toast.error(t("common.error")),
        }
      );
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
            placeholder={t("conversations.searchPlaceholder")}
            className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="conversation-search"
          />
        </div>

        {/* State filter */}
        <div className="flex items-center gap-1.5">
          <Filter className="h-4 w-4 text-muted-foreground" />
          {stateFilters.map((sf) => (
            <Button
              key={sf.value}
              variant={stateFilter === sf.value ? "primary" : "secondary"}
              size="sm"
              className="rounded-full"
              onClick={() => setStateFilter(sf.value)}
            >
              {sf.label}
            </Button>
          ))}
        </div>
      </div>

      {/* Content */}
      {isLoading && (
        <div className="overflow-hidden rounded-xl border bg-card shadow-sm">
          <div className="space-y-0">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex items-center gap-4 border-b border-border px-5 py-4">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-4 w-20" />
                <Skeleton className="h-5 w-16 rounded-full" />
                <Skeleton className="h-4 w-32" />
                <Skeleton className="ml-auto h-6 w-6" />
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
        />
      )}

      {!isLoading && !isError && conversations && conversations.length > 0 && (
        <>
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("conversations.count", { count: conversations.length })}
          </p>

          {/* Conversation table */}
          <div className="overflow-hidden rounded-xl border bg-card shadow-sm">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border bg-secondary/50">
                  <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("conversations.id")}
                  </th>
                  <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("conversations.bot")}
                  </th>
                  <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("conversations.state")}
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
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* Delete confirmation */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("conversations.confirmDelete")}
        description={t("conversations.confirmDelete")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
