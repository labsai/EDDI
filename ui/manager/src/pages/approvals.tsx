import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  HandMetal,
  CheckCircle2,
  Clock,
  AlertTriangle,
  MessageSquare,
  Boxes,
  RefreshCw,
  Search,
  ExternalLink,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import {
  usePendingApprovals,
  useAllGroupPendingApprovals,
  useResumeConversation,
  useCancelConversation,
} from "@/hooks/use-hitl";
import { timeoutPolicyLabel } from "@/lib/hitl-labels";
import type { PendingApprovalSummary, HitlVerdict } from "@/lib/api/hitl";

export function ApprovalsPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const queryClient = useQueryClient();
  const { data: regular, isLoading, isError, refetch } = usePendingApprovals();
  const { data: groupPendings, isError: groupsError, truncated: groupsTruncated } = useAllGroupPendingApprovals();
  const resumeMutation = useResumeConversation();
  const cancelMutation = useCancelConversation();

  // Merge 1:1 (regular) and group-surface pendings into one queue. The regular
  // /agents/pending-approvals endpoint never carries a groupId, so group items
  // come from the backend's single cross-group GET /groups/pending-approvals.
  const approvals = useMemo(() => {
    const seen = new Set<string>();
    return [...(regular ?? []), ...(groupPendings ?? [])].filter((a) => {
      if (seen.has(a.conversationId)) return false;
      seen.add(a.conversationId);
      return true;
    });
  }, [regular, groupPendings]);

  const handleRefresh = () => {
    refetch();
    queryClient.invalidateQueries({ queryKey: ["all-group-pending-approvals"] });
  };

  const filtered = approvals.filter((a) => {
    if (!search) return true;
    const q = search.toLowerCase();
    return (
      a.conversationId?.toLowerCase().includes(q) ||
      a.agentId?.toLowerCase().includes(q) ||
      a.pauseReason?.toLowerCase().includes(q) ||
      a.userId?.toLowerCase().includes(q)
    );
  });

  const handleQuickAction = (item: PendingApprovalSummary, verdict: HitlVerdict) => {
    if (!item.groupId) {
      // Regular conversation
      resumeMutation.mutate(
        { conversationId: item.conversationId, decision: { verdict } },
        {
          onSuccess: () => toast.success(verdict === "APPROVED" ? t("hitl.approved", "Approved") : t("hitl.rejected", "Rejected")),
          onError: (err) => toast.error(getErrorMessage(err)),
        }
      );
    }
  };

  const handleCancel = (item: PendingApprovalSummary) => {
    if (!item.groupId) {
      cancelMutation.mutate(item.conversationId, {
        onSuccess: () => toast.success(t("hitl.cancelled", "Cancelled")),
        onError: (err) => toast.error(getErrorMessage(err)),
      });
    }
  };

  // Loading state
  if (isLoading) {
    return (
      <div className="space-y-6">
        <div className="space-y-2">
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <HandMetal className="h-8 w-8 text-primary" />
            {t("pages.approvals", "Pending Approvals")}
          </h1>
          <p className="text-muted-foreground">
            {t("pages.approvalsSubtitle", "Review and decide on conversations awaiting human input")}
          </p>
        </div>
        <div className="rounded-xl border bg-card p-12 text-center">
          <RefreshCw className="mx-auto h-8 w-8 animate-spin text-muted-foreground" />
          <p className="mt-3 text-muted-foreground">{t("common.loading", "Loading…")}</p>
        </div>
      </div>
    );
  }

  if (isError) {
    return (
      <div className="space-y-6">
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <HandMetal className="h-8 w-8 text-primary" />
          {t("pages.approvals", "Pending Approvals")}
        </h1>
        <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-8 text-center">
          <AlertTriangle className="mx-auto h-8 w-8 text-destructive" />
          <p className="mt-2 text-destructive">{t("common.loadError", "Failed to load data")}</p>
          <button onClick={handleRefresh} className="mt-3 text-sm text-primary hover:underline">
            {t("common.retry", "Retry")}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="space-y-2">
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <HandMetal className="h-8 w-8 text-primary" />
            {t("pages.approvals", "Pending Approvals")}
          </h1>
          <p className="text-muted-foreground">
            {t("pages.approvalsSubtitle", "Review and decide on conversations awaiting human input")}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="relative">
            <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t("common.search", "Search…")}
              className="h-10 w-64 rounded-lg border border-input bg-background ps-9 pe-4 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="approval-search"
            />
          </div>
          <button
            onClick={handleRefresh}
            aria-label={t("common.refresh", "Refresh")}
            title={t("common.refresh", "Refresh")}
            className="inline-flex items-center gap-1.5 rounded-lg border border-input bg-background px-3 py-2 text-sm font-medium text-foreground hover:bg-muted transition-colors"
            data-testid="refresh-approvals"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      </div>

      {/* Queue count badge */}
      <div className="flex flex-wrap items-center gap-2">
        <span className={cn(
          "inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-medium",
          filtered.length > 0
            ? "bg-amber-500/10 text-amber-600"
            : "bg-emerald-500/10 text-emerald-600"
        )}>
          {filtered.length > 0 ? (
            <><Clock className="h-4 w-4" /> {filtered.length} {t("hitl.pending", "pending")}</>
          ) : (
            <><CheckCircle2 className="h-4 w-4" /> {t("hitl.emptyQueue", "No pending approvals")}</>
          )}
        </span>
        {groupsTruncated && (
          <span className="inline-flex items-center gap-1 text-xs text-muted-foreground" data-testid="approvals-truncated">
            <AlertTriangle className="h-3.5 w-3.5 text-amber-500" />
            {t("hitl.groupsTruncated", "More group approvals exist than are shown here.")}
          </span>
        )}
        {/* Group inbox failed but regular list is fine — warn without blocking. */}
        {groupsError && !isError && (
          <span className="inline-flex items-center gap-1 text-xs text-destructive" data-testid="approvals-groups-error">
            <AlertTriangle className="h-3.5 w-3.5" />
            {t("hitl.groupsLoadError", "Group approvals could not be loaded.")}
          </span>
        )}
      </div>

      {/* Empty state */}
      {filtered.length === 0 ? (
        <div className="rounded-xl border bg-card p-12 text-center">
          <CheckCircle2 className="mx-auto h-12 w-12 text-emerald-500/50" />
          <h3 className="mt-4 text-lg font-semibold text-foreground">
            {t("hitl.emptyQueue", "No pending approvals")}
          </h3>
          <p className="mt-1 text-sm text-muted-foreground">
            {t("hitl.emptyQueueDescription", "All conversations are flowing — no human input needed right now.")}
          </p>
        </div>
      ) : (
        /* Approval table */
        <div className="rounded-xl border bg-card shadow-sm overflow-hidden">
          <table className="w-full text-sm" data-testid="approval-queue-table">
            <thead>
              <tr className="border-b bg-muted/30">
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.surface", "Surface")}</th>
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">ID</th>
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.pauseReason", "Reason")}</th>
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.pausedAt", "Paused")}</th>
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.timeoutPolicy", "Timeout")}</th>
                <th className="px-4 py-3 text-end font-medium text-muted-foreground">{t("common.actions", "Actions")}</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {filtered.map((item) => (
                <tr key={item.conversationId} className="hover:bg-muted/20 transition-colors">
                  <td className="px-4 py-3">
                    <span className={cn(
                      "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium",
                      item.groupId
                        ? "bg-blue-500/10 text-blue-600"
                        : "bg-purple-500/10 text-purple-600"
                    )}>
                      {item.groupId ? (
                        <><Boxes className="h-3 w-3" /> {t("hitl.group", "Group")}</>
                      ) : (
                        <><MessageSquare className="h-3 w-3" /> {t("hitl.regular", "Conversation")}</>
                      )}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <Link
                      to={item.groupId
                        ? `/manage/groups/${item.groupId}`
                        : `/manage/conversationview/${item.conversationId}`}
                      className="font-mono text-xs text-primary hover:underline"
                    >
                      {item.conversationId.slice(0, 12)}…
                      <ExternalLink className="ms-1 inline h-3 w-3" />
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground max-w-xs truncate">
                    {item.pauseReason || "—"}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground text-xs">
                    {item.pausedAt
                      ? new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "medium" }).format(new Date(item.pausedAt))
                      : "—"}
                  </td>
                  <td className="px-4 py-3">
                    <span className="text-xs text-muted-foreground">
                      {timeoutPolicyLabel(t, item.timeoutPolicy) || "—"}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center justify-end gap-1">
                      {!item.groupId && (
                        <>
                          <button
                            onClick={() => handleQuickAction(item, "APPROVED")}
                            disabled={resumeMutation.isPending && resumeMutation.variables?.conversationId === item.conversationId}
                            className="rounded-md bg-emerald-600 px-2.5 py-1 text-xs font-medium text-white hover:bg-emerald-500 transition-colors disabled:opacity-50"
                            data-testid={`approve-${item.conversationId}`}
                          >
                            {t("hitl.approve", "Approve")}
                          </button>
                          <button
                            onClick={() => handleQuickAction(item, "REJECTED")}
                            disabled={resumeMutation.isPending && resumeMutation.variables?.conversationId === item.conversationId}
                            className="rounded-md bg-destructive px-2.5 py-1 text-xs font-medium text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50"
                            data-testid={`reject-${item.conversationId}`}
                          >
                            {t("hitl.reject", "Reject")}
                          </button>
                          <button
                            onClick={() => handleCancel(item)}
                            disabled={cancelMutation.isPending && cancelMutation.variables === item.conversationId}
                            className="rounded-md border border-border px-2.5 py-1 text-xs text-muted-foreground hover:bg-muted transition-colors disabled:opacity-50"
                            data-testid={`cancel-${item.conversationId}`}
                          >
                            {t("hitl.cancel", "Cancel")}
                          </button>
                        </>
                      )}
                      {item.groupId && (
                        <Link
                          to={`/manage/groups/${item.groupId}`}
                          className="rounded-md border border-border px-2.5 py-1 text-xs text-primary hover:bg-muted transition-colors"
                        >
                          {t("common.view", "View")}
                        </Link>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
