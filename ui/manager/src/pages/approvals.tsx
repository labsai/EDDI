import { useMemo, useState, type ReactNode } from "react";
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
  Wrench,
  ChevronDown,
  UserCheck,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { findSelfTargetedCalls } from "@/lib/operator/self-guard";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import { RequestPreview } from "@/components/operator/request-preview";
import {
  usePendingApprovals,
  useAllGroupPendingApprovals,
  useResumeConversation,
  useCancelConversation,
  useApprovalStatus,
} from "@/hooks/use-hitl";
import { timeoutPolicyLabel } from "@/lib/hitl-labels";
import { useHasRole } from "@/hooks/use-auth";
import type { PendingApprovalSummary, HitlVerdict, ToolCallDecision, PendingToolCallView } from "@/lib/api/hitl";

/**
 * The redacted-preview render prop shared by every `ApprovalBanner` consumer.
 *
 * Deliberately simpler than the operator screen's version: that one falls back
 * to a client-side `operationId` reconstruction (via a fetched OpenAPI spec) for
 * a call the backend could not preview. Fetching and indexing that spec just for
 * the rare unpreviewable case is not worth the weight here — an approver in the
 * inbox sees the redacted arguments only for those, the same baseline every
 * surface had before request-preview existed.
 */
function renderCallExtra(call: PendingToolCallView): ReactNode {
  if (!call.requestPreview) return null;
  return <RequestPreview preview={call.requestPreview} pinned={call.requestPinned} callId={call.callId} />;
}

/** A pending confirmation for an irreversible queue action. */
type PendingConfirm = { item: PendingApprovalSummary; action: HitlVerdict | "CANCEL" };

interface ApprovalQueueRowProps {
  item: PendingApprovalSummary;
  onRequestConfirm: (item: PendingApprovalSummary, action: HitlVerdict | "CANCEL") => void;
  onToolDecide: (
    item: PendingApprovalSummary,
    verdict: HitlVerdict,
    note?: string,
    toolDecisions?: Record<string, ToolCallDecision>,
  ) => void;
  onToolCancel: (item: PendingApprovalSummary) => void;
  resumeMutation: ReturnType<typeof useResumeConversation>;
  cancelMutation: ReturnType<typeof useCancelConversation>;
}

/**
 * One inbox row. A TOOL_CALL pause expands in place into the same
 * `ApprovalBanner` the operator screen and conversation-detail use, rather
 * than only linking out — decided here is decided, no navigation required.
 *
 * A dedicated component, not inline JSX in the parent's `.map`, because the
 * expand/collapse state and the `pauseDetails` fetch it drives are legitimately
 * per-row: hooks cannot be called conditionally inside a loop, and each row's
 * `useApprovalStatus` call must be independent so expanding one does not fetch
 * — or show loading state — for every other row.
 */
function ApprovalQueueRow({
  item,
  onRequestConfirm,
  onToolDecide,
  onToolCancel,
  resumeMutation,
  cancelMutation,
}: ApprovalQueueRowProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const isToolCall = !item.groupId && item.pauseType === "TOOL_CALL";
  /** I6: a member's turn, not a decision anyone takes from this queue. */
  const isHumanTurn = item.pauseType === "HUMAN_TURN";
  // Fetched only while expanded: pauseDetails (the per-call redacted arguments
  // and request preview) is not on the list summary, deliberately — a payload
  // that size has no place in an endpoint that lists every pending approval at
  // once. `enabled: expanded` means collapsing and re-expanding re-fetches
  // rather than trusting a stale cache, matching every other pause surface.
  const approvalStatus = useApprovalStatus(item.conversationId, expanded);

  const isSubmitting =
    (resumeMutation.isPending && resumeMutation.variables?.conversationId === item.conversationId) ||
    (cancelMutation.isPending && cancelMutation.variables === item.conversationId);

  // The same refusal the operator screen applies, enforced here too: this inbox
  // is precisely where an admin decides a pause WITHOUT the surrounding context
  // of the conversation that raised it, so it is the likelier place for a
  // self-repointing write to be waved through.
  //
  // Keyed on the pause's OWN agentId, not a separately-fetched operator id.
  // Reading the operator config needs `GET /globalvariables/…`, which is
  // eddi-admin/eddi-editor only — so for a dedicated eddi-approver (a
  // first-class user of this page) that fetch 403s, the id is undefined, and
  // the guard silently evaluates to "nothing blocked" while the UI still looks
  // guarded. The acting agent id rides on the pause itself, so every role that
  // can see the pause can evaluate the guard. See `self-guard.ts`.
  const blockedCalls = useMemo(() => {
    const details = approvalStatus.data?.pauseDetails;
    // Narrowed on the discriminator: a RULE pause carries no per-call requests.
    const pending = details?.type === "TOOL_CALL" ? details.calls : undefined;
    return findSelfTargetedCalls(pending, item.agentId).map((hit) => ({
      callId: hit.callId,
      reason: t(
        "operator.approval.blockedSelfTarget",
        "An agent may not modify its own definition, and this request targets the operator's own agent ({{agentId}}). Approving is unavailable for the whole batch while it is present — reject, and make this change from that agent's own page.",
        { agentId: hit.agentId },
      ),
    }));
  }, [approvalStatus.data, item.agentId, t]);

  return (
    <>
      <tr className="hover:bg-muted/20 transition-colors">
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
          {item.pauseType === "TOOL_CALL" && (
            <span
              className="me-1.5 inline-flex items-center gap-1 rounded-full bg-amber-500/10 px-2 py-0.5 text-[10px] font-medium text-amber-600"
              data-testid={`tool-badge-${item.conversationId}`}
            >
              <Wrench className="h-3 w-3" /> {t("hitl.tool", "Tool")}
            </span>
          )}
          {/* A HUMAN member's turn (I6) rides the SAME pending-approvals
              endpoints as real approvals — there is no separate "my turns"
              feed — so it lands in this queue discriminated only by pauseType.
              Without a badge it reads as one more thing to approve or reject,
              which is the one thing it is not: nobody is deciding here, a
              member simply owes the discussion their contribution. */}
          {isHumanTurn && (
            <span
              className="me-1.5 inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-medium text-primary"
              data-testid={`human-turn-badge-${item.conversationId}`}
            >
              <UserCheck className="h-3 w-3" />{" "}
              {t("hitl.humanTurn", "Member's turn")}
            </span>
          )}
          {item.pauseType === "TOOL_CALL" && item.toolNames && item.toolNames.length > 0
            ? item.toolNames.join(", ")
            : isHumanTurn
              ? t("hitl.humanTurnReason", "Waiting on {{member}} to speak", {
                  member: item.pendingMemberId || t("hitl.humanTurnMemberFallback", "a member"),
                })
              : item.pauseReason || "—"}
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
            {isToolCall && (
              <>
                <button
                  onClick={() => setExpanded((v) => !v)}
                  aria-expanded={expanded}
                  className="inline-flex items-center gap-1 rounded-md bg-amber-500/10 px-2.5 py-1 text-xs font-medium text-amber-600 hover:bg-amber-500/20 transition-colors"
                  data-testid={`review-${item.conversationId}`}
                >
                  {expanded ? t("common.close", "Close") : t("hitl.review", "Review")}
                  <ChevronDown
                    className={cn("h-3 w-3 transition-transform", expanded && "rotate-180")}
                    aria-hidden="true"
                  />
                </button>
                <button
                  onClick={() => onRequestConfirm(item, "CANCEL")}
                  disabled={cancelMutation.isPending && cancelMutation.variables === item.conversationId}
                  className="rounded-md border border-border px-2.5 py-1 text-xs text-muted-foreground hover:bg-muted transition-colors disabled:opacity-50"
                  data-testid={`cancel-${item.conversationId}`}
                >
                  {t("hitl.cancel", "Cancel")}
                </button>
              </>
            )}
            {!item.groupId && item.pauseType !== "TOOL_CALL" && !isHumanTurn && (
              <>
                <button
                  onClick={() => onRequestConfirm(item, "APPROVED")}
                  disabled={resumeMutation.isPending && resumeMutation.variables?.conversationId === item.conversationId}
                  className="rounded-md bg-emerald-600 px-2.5 py-1 text-xs font-medium text-white hover:bg-emerald-500 transition-colors disabled:opacity-50"
                  data-testid={`approve-${item.conversationId}`}
                >
                  {t("hitl.approve", "Approve")}
                </button>
                <button
                  onClick={() => onRequestConfirm(item, "REJECTED")}
                  disabled={resumeMutation.isPending && resumeMutation.variables?.conversationId === item.conversationId}
                  className="rounded-md bg-destructive px-2.5 py-1 text-xs font-medium text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50"
                  data-testid={`reject-${item.conversationId}`}
                >
                  {t("hitl.reject", "Reject")}
                </button>
                <button
                  onClick={() => onRequestConfirm(item, "CANCEL")}
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
      {isToolCall && expanded && (
        <tr data-testid={`tool-decision-row-${item.conversationId}`}>
          <td colSpan={6} className="bg-muted/10 px-4 py-4">
            <ApprovalBanner
              surface="regular"
              pauseReason={item.pauseReason ?? undefined}
              pausedAt={item.pausedAt}
              timeoutPolicy={item.timeoutPolicy ?? undefined}
              approvalTimeout={item.approvalTimeout ?? undefined}
              pauseDetails={approvalStatus.data?.pauseDetails ?? null}
              // The query's own flags, not `!data`: that conflated a failed
              // read with a successful one carrying no pauseDetails, and
              // resolved the latter to "not pending" — enabling Approve on
              // the surface where an approver has the LEAST context, while
              // the operator chat blocked it for the same pause. All three
              // approval surfaces now derive this identically.
              pauseDetailsPending={approvalStatus.isLoading}
              pauseDetailsError={approvalStatus.isError}
              onRetryPauseDetails={() => void approvalStatus.refetch()}
              isSubmitting={isSubmitting}
              requireExplicitPerCall
              blockedCalls={blockedCalls}
              renderCallExtra={renderCallExtra}
              onDecide={(verdict, note, _taskApprovals, toolDecisions) => onToolDecide(item, verdict, note, toolDecisions)}
              onCancel={() => onToolCancel(item)}
            />
          </td>
        </tr>
      )}
    </>
  );
}

export function ApprovalsPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  // Approve/Reject/Cancel from the queue are irreversible (resume executes the
  // gated tools / rejection can't be undone / cancel aborts the run), so each
  // routes through this confirmation before the mutation fires.
  const [confirm, setConfirm] = useState<PendingConfirm | null>(null);
  // The backend scopes the inbox by role: admins/approvers see every pending
  // approval, everyone else sees only their own conversations. Communicate
  // which scope the list reflects so an owner-scoped empty queue isn't mistaken
  // for "nothing pending anywhere".
  const isAdmin = useHasRole("eddi-admin");
  const isApproverRole = useHasRole("eddi-approver");
  const isApprover = isAdmin || isApproverRole;
  const queryClient = useQueryClient();
  const { data: regular, isLoading, isError, refetch } = usePendingApprovals();
  const { data: groupPendings, isLoading: groupsLoading, isError: groupsError, truncated: groupsTruncated } = useAllGroupPendingApprovals();
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

  const doQuickAction = (item: PendingApprovalSummary, verdict: HitlVerdict) => {
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

  /**
   * Decide a TOOL_CALL pause from the inline panel — per-call verdicts included.
   *
   * `useResumeConversation.onSuccess` already invalidates `["approval-status",
   * conversationId]`, but a REMOVE (not just invalidate) is still needed here:
   * a turn may pause again immediately on a fresh batch (`maxPausesPerTurn`,
   * default 3), and an invalidated-but-not-yet-refetched cache entry can render
   * for one frame before the refetch lands — showing the FIRST pause's calls
   * under what is now the second pause. Same reasoning as the operator screen's
   * `handleDecide`.
   */
  const decideToolCall = (
    item: PendingApprovalSummary,
    verdict: HitlVerdict,
    note?: string,
    toolDecisions?: Record<string, ToolCallDecision>,
  ) => {
    resumeMutation.mutate(
      { conversationId: item.conversationId, decision: { verdict, note, toolDecisions } },
      {
        onSuccess: () => {
          toast.success(verdict === "APPROVED" ? t("hitl.approved", "Approved") : t("hitl.rejected", "Rejected"));
          queryClient.removeQueries({ queryKey: ["approval-status", item.conversationId] });
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      },
    );
  };

  const doCancel = (item: PendingApprovalSummary) => {
    if (!item.groupId) {
      cancelMutation.mutate(item.conversationId, {
        onSuccess: () => toast.success(t("hitl.cancelled", "Cancelled")),
        onError: (err) => toast.error(getErrorMessage(err)),
      });
    }
  };

  // Only fired after the reviewer confirms in the AlertDialog.
  const runConfirmedAction = () => {
    if (!confirm) return;
    const { item, action } = confirm;
    setConfirm(null);
    if (action === "CANCEL") doCancel(item);
    else doQuickAction(item, action);
  };

  const confirmDialog = (() => {
    if (!confirm) return null;
    switch (confirm.action) {
      case "APPROVED":
        return {
          title: t("hitl.confirmApproveTitle", "Approve request?"),
          description: t("hitl.confirmApproveDescription", "Approve and resume this conversation?"),
          confirmLabel: t("hitl.approve", "Approve"),
          variant: "warning" as const,
          isPending: resumeMutation.isPending,
        };
      case "REJECTED":
        return {
          title: t("hitl.confirmRejectTitle", "Reject request?"),
          description: t("hitl.confirmRejectDescription", "Reject this request? The conversation will not proceed."),
          confirmLabel: t("hitl.reject", "Reject"),
          variant: "destructive" as const,
          isPending: resumeMutation.isPending,
        };
      case "CANCEL":
        return {
          title: t("hitl.confirmCancelTitle", "Cancel conversation?"),
          description: t("hitl.confirmCancelDescription", "Cancel this conversation? Any in-progress work is aborted."),
          confirmLabel: t("hitl.confirmCancelButton", "Cancel conversation"),
          variant: "destructive" as const,
          isPending: cancelMutation.isPending,
        };
    }
  })();

  // Loading state — wait for BOTH the regular and cross-group queries so an
  // empty regular list doesn't flash "No pending approvals" before group items load.
  if (isLoading || groupsLoading) {
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
        <span className="text-xs text-muted-foreground" data-testid="approvals-scope">
          {isApprover
            ? t("hitl.scopeAll", "Showing all pending approvals across the system.")
            : t("hitl.scopeOwn", "Showing pending approvals for your conversations.")}
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
                <ApprovalQueueRow
                  key={item.conversationId}
                  item={item}
                  onRequestConfirm={(row, action) => setConfirm({ item: row, action })}
                  onToolDecide={decideToolCall}
                  onToolCancel={doCancel}
                  resumeMutation={resumeMutation}
                  cancelMutation={cancelMutation}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Confirmation gate — no queue action fires on a single click. */}
      {confirmDialog && (
        <AlertDialog
          open={confirm !== null}
          onOpenChange={(open) => {
            if (!open) setConfirm(null);
          }}
          title={confirmDialog.title}
          description={confirmDialog.description}
          confirmLabel={confirmDialog.confirmLabel}
          cancelLabel={t("hitl.confirmDismiss", "Go back")}
          variant={confirmDialog.variant}
          isPending={confirmDialog.isPending}
          onConfirm={runConfirmedAction}
        />
      )}
    </div>
  );
}
