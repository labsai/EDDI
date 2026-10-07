import { useCallback, useMemo, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CheckCircle2,
  Clock,
  AlertTriangle,
  MessageSquare,
  RefreshCw,
  Search,
  ExternalLink,
  Wrench,
  ChevronDown,
  UserCheck,
  Hand,
  Users,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { findBlockedCalls } from "@/lib/operator/blocked-calls";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import { RequestPreview } from "@/components/operator/request-preview";
import {
  usePendingApprovals,
  useAllGroupPendingApprovals,
  useResumeConversation,
  useCancelConversation,
  useApproveGroupPhase,
  useCancelGroupDiscussion,
  useApprovalStatus,
} from "@/hooks/use-hitl";
import { useGroup, useGroupDescriptors } from "@/hooks/use-groups";
import { useAgentDescriptors } from "@/hooks/use-agents";
import { parseResourceUri } from "@/lib/api/agents";
import { getGroupConversation, groupGroupsByName } from "@/lib/api/groups";
import { timeoutPolicyLabel } from "@/lib/hitl-labels";
import { useHasRole } from "@/hooks/use-auth";
import type {
  ApprovalStatusSummary,
  PendingApprovalSummary,
  HitlVerdict,
  ToolCallDecision,
  PendingToolCallView,
} from "@/lib/api/hitl";
import { isPauseChanged, shownPauseOf, type ShownPause } from "@/lib/hitl-pause-binding";

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
  /**
   * Where this row's group discussion lives: its id, current version and the
   * paused conversation.
   *
   * `PendingApprovalSummary` carries no version, and the group page requires
   * one — omitting it defaulted every link to version 1, so any group that had
   * ever been edited opened showing its original name and member list. The
   * version comes from the descriptor list; the conversation from the row.
   */
  groupHref: string | null;
  /** The agent's or group's display name, when the descriptor lists are loaded. */
  displayName: string | null;
  /** The group's current version — needed to resolve a HUMAN member's name. */
  groupVersion: number | null;
  onRequestConfirm: (item: PendingApprovalSummary, action: HitlVerdict | "CANCEL") => void;
  onToolDecide: (
    item: PendingApprovalSummary,
    shown: ApprovalStatusSummary | undefined,
    verdict: HitlVerdict,
    note?: string,
    toolDecisions?: Record<string, ToolCallDecision>,
  ) => void;
  onToolCancel: (item: PendingApprovalSummary) => void;
  /** Decide a group pause from the inline review panel (note included). */
  onGroupDecide: (
    item: PendingApprovalSummary,
    verdict: HitlVerdict,
    note?: string,
    taskApprovals?: Record<string, string>,
  ) => void;
  /**
   * Whether a decision or cancel for THIS row is in flight.
   *
   * Tracked by the page per conversation id, not read off the shared mutation
   * objects: a TanStack mutation remembers only its latest call, so deciding a
   * second row while the first was still running re-enabled the first row's
   * buttons — a double submit one click away.
   */
  busy: boolean;
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
  groupHref,
  displayName,
  groupVersion,
  onRequestConfirm,
  onToolDecide,
  onToolCancel,
  onGroupDecide,
  busy,
}: ApprovalQueueRowProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const isToolCall = !item.groupId && item.pauseType === "TOOL_CALL";
  /** I6: a member's turn, not a decision anyone takes from this queue. */
  const isHumanTurn = item.pauseType === "HUMAN_TURN";
  /** A group phase pause: decided here, and reviewable in place like a tool call. */
  const isGroupDecision = !!item.groupId && !isHumanTurn;
  // A HUMAN turn names its member by id; the group's roster has the name a
  // person would recognise. Only fetched for those rows.
  const { data: turnGroup } = useGroup(
    isHumanTurn && item.groupId ? item.groupId : "",
    groupVersion ?? undefined,
  );
  const turnMemberName =
    turnGroup?.members?.find((m) => m.agentId === item.pendingMemberId)?.displayName || null;
  // The paused discussion itself, fetched only while the review is open: what
  // phase it stopped in and what was just said is what a verdict is about.
  const groupConversation = useQuery({
    queryKey: ["groupConversations", item.groupId, item.conversationId],
    queryFn: () => getGroupConversation(item.groupId!, item.conversationId),
    enabled: expanded && isGroupDecision,
  });
  // Fetched only while expanded: pauseDetails (the per-call redacted arguments
  // and request preview) is not on the list summary, deliberately — a payload
  // that size has no place in an endpoint that lists every pending approval at
  // once. `enabled: expanded` means collapsing and re-expanding re-fetches
  // rather than trusting a stale cache, matching every other pause surface.
  const approvalStatus = useApprovalStatus(item.conversationId, expanded);

  const isSubmitting = busy;
  /** This row's own group decision, so one busy row does not disable the queue. */
  const groupDecisionPending = busy;

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
  // can see the pause can evaluate the guard. See `blocked-calls.ts`.
  const blockedCalls = useMemo(() => {
    const details = approvalStatus.data?.pauseDetails;
    // Narrowed on the discriminator: a RULE pause carries no per-call requests.
    const pending = details?.type === "TOOL_CALL" ? details.calls : undefined;
    return findBlockedCalls(pending, item.agentId, item.conversationId, t);
  }, [approvalStatus.data, item.agentId, item.conversationId, t]);

  return (
    <>
      <tr className="hover:bg-muted/20 transition-colors max-md:block max-md:px-4 max-md:py-3">
        <td className="px-4 py-3 max-md:block max-md:px-0 max-md:py-0.5">
          <span className={cn(
            "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium",
            item.groupId
              ? "bg-blue-500/10 text-blue-600"
              : "bg-purple-500/10 text-purple-600"
          )}>
            {item.groupId ? (
              <><Users className="h-3 w-3" /> {t("hitl.group", "Group")}</>
            ) : (
              <><MessageSquare className="h-3 w-3" /> {t("hitl.regular", "Conversation")}</>
            )}
          </span>
        </td>
        <td className="px-4 py-3 max-md:block max-md:px-0 max-md:py-0.5 max-md:before:me-2 max-md:before:text-[10px] max-md:before:font-semibold max-md:before:uppercase max-md:before:text-muted-foreground max-md:before:content-[attr(data-label)]" data-label={t("hitl.conversationColumn", "Conversation")}>
          {/* Two rows of "conv-awaitin…" were indistinguishable: lead with the
              agent's (or group's) name, and keep the id as the secondary line. */}
          {displayName && (
            <p className="text-sm font-medium text-foreground" data-testid={`name-${item.conversationId}`}>
              {displayName}
            </p>
          )}
          {item.groupId && !groupHref ? (
            // Held until the group's current version is known — see `groupHrefFor`.
            <span
              className="font-mono text-xs text-muted-foreground"
              title={t(
                "hitl.groupLinkUnavailable",
                "This group could not be located, so there is no safe link to it.",
              )}
              data-testid={`link-pending-${item.conversationId}`}
            >
              {item.conversationId.slice(0, 12)}…
            </span>
          ) : (
            <Link
              to={groupHref ?? `/manage/conversationview/${item.conversationId}`}
              className="font-mono text-xs text-primary hover:underline"
              title={item.conversationId}
            >
              {item.conversationId.slice(0, 12)}…
              <ExternalLink className="ms-1 inline h-3 w-3" />
            </Link>
          )}
        </td>
        <td className="px-4 py-3 text-muted-foreground max-w-xs truncate max-md:block max-md:max-w-none max-md:whitespace-normal max-md:px-0 max-md:py-0.5 max-md:before:me-2 max-md:before:text-[10px] max-md:before:font-semibold max-md:before:uppercase max-md:before:text-muted-foreground max-md:before:content-[attr(data-label)]" data-label={t("hitl.pauseReason", "Reason")}>
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
                  member:
                    turnMemberName ||
                    item.pendingMemberId ||
                    t("hitl.humanTurnMemberFallback", "a member"),
                })
              : item.pauseReason || "—"}
        </td>
        <td className="px-4 py-3 text-muted-foreground text-xs max-md:block max-md:px-0 max-md:py-0.5 max-md:before:me-2 max-md:before:text-[10px] max-md:before:font-semibold max-md:before:uppercase max-md:before:text-muted-foreground max-md:before:content-[attr(data-label)]" data-label={t("hitl.pausedAt", "Paused")}>
          {item.pausedAt
            ? new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "medium" }).format(new Date(item.pausedAt))
            : "—"}
        </td>
        <td className="px-4 py-3 max-md:block max-md:px-0 max-md:py-0.5 max-md:before:me-2 max-md:before:text-[10px] max-md:before:font-semibold max-md:before:uppercase max-md:before:text-muted-foreground max-md:before:content-[attr(data-label)]" data-label={t("hitl.timeoutPolicy", "Timeout")}>
          <span className="text-xs text-muted-foreground">
            {timeoutPolicyLabel(t, item.timeoutPolicy) || "—"}
          </span>
        </td>
        <td className="px-4 py-3 max-md:block max-md:px-0 max-md:pt-2">
          <div className="flex flex-wrap items-center justify-end gap-1 max-md:justify-start">
            {isToolCall && (
              <>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setExpanded((v) => !v)}
                  aria-expanded={expanded}
                  className="gap-1 bg-warning/10 text-warning hover:bg-warning/20"
                  data-testid={`review-${item.conversationId}`}
                >
                  {expanded ? t("common.close", "Close") : t("hitl.review", "Review")}
                  <ChevronDown
                    className={cn("transition-transform", expanded && "rotate-180")}
                    aria-hidden="true"
                  />
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => onRequestConfirm(item, "CANCEL")}
                  disabled={busy}
                  data-testid={`cancel-${item.conversationId}`}
                >
                  {t("hitl.cancel", "Cancel")}
                </Button>
              </>
            )}
            {!item.groupId && item.pauseType !== "TOOL_CALL" && !isHumanTurn && (
              <>
                <Button
                  variant="primary"
                  size="sm"
                  onClick={() => onRequestConfirm(item, "APPROVED")}
                  disabled={busy}
                  data-testid={`approve-${item.conversationId}`}
                >
                  {t("hitl.approve", "Approve")}
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={() => onRequestConfirm(item, "REJECTED")}
                  disabled={busy}
                  data-testid={`reject-${item.conversationId}`}
                >
                  {t("hitl.reject", "Reject")}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => onRequestConfirm(item, "CANCEL")}
                  disabled={busy}
                  data-testid={`cancel-${item.conversationId}`}
                >
                  {t("hitl.cancel", "Cancel")}
                </Button>
              </>
            )}
            {/* A group phase pause needs nothing but a verdict, so it is
                decided here like a 1:1 pause. A HUMAN_TURN is not a decision —
                a member owes the discussion their contribution — so it keeps
                the link only, and the link now opens that discussion. */}
            {isGroupDecision && (
              <>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setExpanded((v) => !v)}
                  aria-expanded={expanded}
                  className="gap-1 bg-warning/10 text-warning hover:bg-warning/20"
                  data-testid={`review-${item.conversationId}`}
                >
                  {expanded ? t("common.close", "Close") : t("hitl.review", "Review")}
                  <ChevronDown
                    className={cn("transition-transform", expanded && "rotate-180")}
                    aria-hidden="true"
                  />
                </Button>
                <Button
                  variant="primary"
                  size="sm"
                  onClick={() => onRequestConfirm(item, "APPROVED")}
                  disabled={groupDecisionPending}
                  data-testid={`approve-${item.conversationId}`}
                >
                  {t("hitl.approve", "Approve")}
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={() => onRequestConfirm(item, "REJECTED")}
                  disabled={groupDecisionPending}
                  data-testid={`reject-${item.conversationId}`}
                >
                  {t("hitl.reject", "Reject")}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => onRequestConfirm(item, "CANCEL")}
                  disabled={groupDecisionPending}
                  data-testid={`cancel-${item.conversationId}`}
                >
                  {t("hitl.cancel", "Cancel")}
                </Button>
              </>
            )}
            {item.groupId &&
              (groupHref ? (
                <Link
                  to={groupHref}
                  className="rounded-md border border-border px-2.5 py-1 text-xs text-primary hover:bg-muted transition-colors"
                  data-testid={`view-${item.conversationId}`}
                >
                  {t("common.view", "View")}
                </Link>
              ) : (
                <span
                  className="rounded-md border border-border px-2.5 py-1 text-xs text-muted-foreground opacity-50"
                  aria-disabled="true"
                  title={t(
                    "hitl.groupLinkUnavailable",
                    "This group could not be located, so there is no safe link to it.",
                  )}
                  data-testid={`view-pending-${item.conversationId}`}
                >
                  {t("common.view", "View")}
                </span>
              ))}
          </div>
        </td>
      </tr>
      {isGroupDecision && expanded && (
        <tr className="max-md:block" data-testid={`group-review-row-${item.conversationId}`}>
          <td colSpan={6} className="bg-muted/10 p-0 max-md:block">
            <div className="w-0 min-w-full space-y-3 px-4 py-4 max-md:w-auto">
              {/* Enough of the discussion to decide on: where it stopped and the
                  last things said — not just why it paused. */}
              <div className="rounded-lg border border-border bg-background/60 p-3" data-testid={`group-review-context-${item.conversationId}`}>
                {groupConversation.isLoading ? (
                  <p className="text-xs text-muted-foreground">{t("common.loading", "Loading…")}</p>
                ) : groupConversation.isError || !groupConversation.data ? (
                  <div className="flex items-center gap-2 text-xs text-destructive">
                    <AlertTriangle className="h-3.5 w-3.5" aria-hidden="true" />
                    {t("hitl.groupReviewLoadError", "The discussion could not be loaded.")}
                    <Button variant="link" size="sm" onClick={() => void groupConversation.refetch()}>
                      {t("common.retry", "Retry")}
                    </Button>
                  </div>
                ) : (
                  <GroupReviewContext conversation={groupConversation.data} />
                )}
              </div>
              <ApprovalBanner
                surface="group"
                pauseReason={item.pauseReason ?? undefined}
                pausedAt={item.pausedAt}
                timeoutPolicy={item.timeoutPolicy ?? undefined}
                approvalTimeout={item.approvalTimeout ?? undefined}
                pausedPhaseName={groupConversation.data?.pausedPhaseName}
                granularity={groupConversation.data?.hitlPauseType}
                pendingTaskIds={(groupConversation.data?.taskList?.tasks ?? [])
                  .filter((task) => task.status === "AWAITING_APPROVAL")
                  .map((task) => task.id)}
                isSubmitting={isSubmitting}
                onDecide={(verdict, note, taskApprovals) =>
                  onGroupDecide(item, verdict, note, taskApprovals)
                }
                onCancel={() => onRequestConfirm(item, "CANCEL")}
              />
            </div>
          </td>
        </tr>
      )}
      {isToolCall && expanded && (
        <tr className="max-md:block" data-testid={`tool-decision-row-${item.conversationId}`}>
          <td colSpan={6} className="bg-muted/10 p-0 max-md:block">
            {/* `w-0 min-w-full`, not padding on the cell: an auto-layout table
                sizes its columns from their content's min-content width, and
                the banner's redacted-arguments <pre> is a single very long
                line — it stretched the whole table to several thousand px,
                pushing every other row's columns off-screen. The <pre>'s own
                `max-w-full` cannot help, because no ancestor inside the cell
                has a definite width to resolve against. A wrapper with
                `width: 0` contributes nothing to the column calculation, and
                `min-width: 100%` then fills the cell the OTHER rows sized.
                The inner panels keep scrolling within themselves as before. */}
            <div className="w-0 min-w-full px-4 py-4">
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
                // The status the banner rendered goes along, so the decision is
                // bound to the calls the reviewer actually saw.
                onDecide={(verdict, note, _taskApprovals, toolDecisions) =>
                  onToolDecide(item, approvalStatus.data, verdict, note, toolDecisions)
                }
                onCancel={() => onToolCancel(item)}
              />
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

/** How much of each recent entry the review shows. */
const REVIEW_EXCERPT_CHARS = 280;
/** How many of the latest transcript entries the review shows. */
const REVIEW_ENTRY_COUNT = 3;

/** The phase a group paused in and the last things said — the context for a verdict. */
function GroupReviewContext({ conversation }: { conversation: Awaited<ReturnType<typeof getGroupConversation>> }) {
  const { t } = useTranslation();
  const recent = (conversation.transcript ?? [])
    .filter((e) => e.type !== "QUESTION" && !!e.content)
    .slice(-REVIEW_ENTRY_COUNT);
  return (
    <div className="space-y-2 text-xs">
      <p className="text-muted-foreground">
        <span className="font-medium text-foreground">{t("groups.question", "Question")}: </span>
        {conversation.originalQuestion}
      </p>
      {conversation.pausedPhaseName && (
        <p className="text-muted-foreground">
          <span className="font-medium text-foreground">{t("hitl.phase", "Phase")}: </span>
          {conversation.pausedPhaseName}
        </p>
      )}
      {recent.length > 0 ? (
        <ul className="space-y-1.5" data-testid="group-review-entries">
          {recent.map((entry, idx) => (
            <li key={`${entry.speakerAgentId}-${entry.timestamp}-${idx}`} className="rounded-md bg-muted/40 p-2">
              <span className="font-medium text-foreground">{entry.speakerDisplayName}</span>
              {entry.phaseName && <span className="text-muted-foreground"> · {entry.phaseName}</span>}
              <p className="mt-0.5 whitespace-pre-wrap break-words text-muted-foreground">
                {entry.content!.length > REVIEW_EXCERPT_CHARS
                  ? `${entry.content!.slice(0, REVIEW_EXCERPT_CHARS)}…`
                  : entry.content}
              </p>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-muted-foreground">{t("hitl.groupReviewNoEntries", "Nothing has been said yet.")}</p>
      )}
    </div>
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
  const groupApproveMutation = useApproveGroupPhase();
  const groupCancelMutation = useCancelGroupDiscussion();

  /** Conversation ids with a decision or cancel in flight — see `ApprovalQueueRowProps.busy`. */
  const [inFlight, setInFlight] = useState<ReadonlySet<string>>(() => new Set());

  /**
   * Run one row's action, keeping its row busy until THIS call settles.
   *
   * `mutateAsync` rather than `mutate` with callbacks: TanStack runs per-call
   * `mutate` callbacks only for the latest call, so deciding two rows in quick
   * succession dropped the first one's toast — including its failure.
   */
  const runForRow = useCallback(
    async (conversationId: string, action: () => Promise<unknown>, ok: () => void) => {
      setInFlight((prev) => new Set(prev).add(conversationId));
      try {
        await action();
        ok();
      } catch (err) {
        toast.error(
          isPauseChanged(err)
            ? t(
                "hitl.pauseChanged",
                "This request changed since you opened it — nothing was decided. Review it again.",
              )
            : getErrorMessage(err),
        );
      } finally {
        setInFlight((prev) => {
          const next = new Set(prev);
          next.delete(conversationId);
          return next;
        });
      }
    },
    [t],
  );

  /**
   * groupId → current version, for the links out.
   *
   * `PendingApprovalSummary` carries no version and the group page needs one,
   * so every group link defaulted to version 1: a group that had ever been
   * edited opened at its ORIGINAL configuration — old name, old member list —
   * while the discussion underneath was the current one. One descriptor call
   * for the whole page fixes every row.
   */
  // The descriptor endpoint returns one row per VERSION, which
  // `groupGroupsByName` then dedupes — so N rows can be far fewer than N
  // distinct groups, and a group outside the window gets no link at all. A
  // wider window rather than a per-row fetch: this list is already bounded and
  // one call is cheaper than one per queued approval.
  const { data: groupDescriptors } = useGroupDescriptors(500);
  const groupVersions = useMemo(() => {
    const map = new Map<string, number>();
    for (const group of groupGroupsByName(groupDescriptors ?? [])) {
      map.set(group.id, group.version);
    }
    return map;
  }, [groupDescriptors]);
  // Names for the rows. A conversation id says nothing about who it belongs to,
  // and two rows used to read identically. Best effort: a row whose agent or
  // group is outside the window (or the list is still loading) shows its id.
  const { data: agentDescriptors } = useAgentDescriptors(500);
  const agentNames = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of agentDescriptors ?? []) {
      if (d.name) map.set(parseResourceUri(d.resource).id, d.name);
    }
    return map;
  }, [agentDescriptors]);
  const groupNames = useMemo(() => {
    const map = new Map<string, string>();
    for (const group of groupGroupsByName(groupDescriptors ?? [])) {
      if (group.name) map.set(group.id, group.name);
    }
    return map;
  }, [groupDescriptors]);
  const nameFor = useCallback(
    (item: PendingApprovalSummary): string | null =>
      (item.groupId ? groupNames.get(item.groupId) : item.agentId ? agentNames.get(item.agentId) : null) ?? null,
    [agentNames, groupNames],
  );

  /**
   * Where a row's View link should land: the group, at its current version,
   * with the paused discussion already selected.
   *
   * `null` when the version is not known — while the descriptor list is still
   * loading, and also for a group it does not carry (deleted, or past the first
   * hundred). The group page defaults a missing version to 1, which for an
   * edited group is its ORIGINAL configuration: a different member list and a
   * different name from the one the paused discussion is actually running
   * under. This is the screen where someone approves an action without the
   * surrounding context, so showing them the wrong context is the worse of the
   * two failures — worse than making them find the group themselves.
   */
  const groupHrefFor = useCallback(
    (item: PendingApprovalSummary): string | null => {
      if (!item.groupId) return null;
      const version = groupVersions.get(item.groupId);
      if (version == null) return null;
      const params = new URLSearchParams({
        version: String(version),
        conversation: item.conversationId,
      });
      return `/manage/groups/${item.groupId}?${params.toString()}`;
    },
    [groupVersions],
  );

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
      a.groupId?.toLowerCase().includes(q) ||
      nameFor(a)?.toLowerCase().includes(q) ||
      a.pendingMemberId?.toLowerCase().includes(q) ||
      a.pauseReason?.toLowerCase().includes(q) ||
      a.toolNames?.some((n) => n.toLowerCase().includes(q)) ||
      a.userId?.toLowerCase().includes(q)
    );
  });

  const doQuickAction = (item: PendingApprovalSummary, verdict: HitlVerdict) => {
    const ok = () =>
      toast.success(
        verdict === "APPROVED" ? t("hitl.approved", "Approved") : t("hitl.rejected", "Rejected"),
      );
    // What the reviewer decided on is the row: its kind and when it paused. A
    // row is up to ten seconds old, and a verdict sent without a pause identity
    // applies to whatever the conversation is paused on when it arrives —
    // including a tool-call batch nobody reviewed.
    const shown: ShownPause = { pausedAt: item.pausedAt, pauseType: item.pauseType ?? null };

    if (item.groupId) {
      const groupId = item.groupId;
      // The non-streaming approve endpoint, which existed with no caller. The
      // group page uses the streaming variant because it has a transcript to
      // play the resume into; a queue row has nowhere to put one.
      void runForRow(
        item.conversationId,
        () =>
          groupApproveMutation.mutateAsync({
            groupId,
            gcId: item.conversationId,
            request: { decision: { verdict } },
            shown,
          }),
        ok,
      );
      return;
    }
    void runForRow(
      item.conversationId,
      () => resumeMutation.mutateAsync({ conversationId: item.conversationId, decision: { verdict }, shown }),
      ok,
    );
  };

  /** Decide a group pause from its review panel — the note and per-task verdicts ride along. */
  const decideGroupPhase = (
    item: PendingApprovalSummary,
    verdict: HitlVerdict,
    note?: string,
    taskApprovals?: Record<string, string>,
  ) => {
    if (!item.groupId) return;
    const groupId = item.groupId;
    const shown: ShownPause = { pausedAt: item.pausedAt, pauseType: item.pauseType ?? null };
    void runForRow(
      item.conversationId,
      () =>
        groupApproveMutation.mutateAsync({
          groupId,
          gcId: item.conversationId,
          request: { decision: { verdict, note }, taskApprovals },
          shown,
        }),
      () =>
        toast.success(verdict === "APPROVED" ? t("hitl.approved", "Approved") : t("hitl.rejected", "Rejected")),
    );
  };

  /**
   * Decide a TOOL_CALL pause from the inline panel — per-call verdicts included.
   *
   * Bound to `shownStatus`, the approval-status the panel rendered: the per-call
   * verdicts are keyed by call id, and a call not listed inherits the top-level
   * verdict — so a decision landing on a later batch would approve every call
   * in it unseen.
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
    shownStatus: ApprovalStatusSummary | undefined,
    verdict: HitlVerdict,
    note?: string,
    toolDecisions?: Record<string, ToolCallDecision>,
  ) => {
    // No status means nothing was shown; fall back to the row's own identity
    // rather than sending an unbound verdict.
    const shown: ShownPause = shownStatus
      ? shownPauseOf(shownStatus)
      : { pausedAt: item.pausedAt, pauseType: item.pauseType ?? null };
    void runForRow(
      item.conversationId,
      () =>
        resumeMutation.mutateAsync({
          conversationId: item.conversationId,
          decision: { verdict, note, toolDecisions },
          shown,
        }),
      () => {
        toast.success(verdict === "APPROVED" ? t("hitl.approved", "Approved") : t("hitl.rejected", "Rejected"));
        queryClient.removeQueries({ queryKey: ["approval-status", item.conversationId] });
      },
    );
  };

  const doCancel = (item: PendingApprovalSummary) => {
    const ok = () => toast.success(t("hitl.cancelled", "Cancelled"));

    if (item.groupId) {
      const groupId = item.groupId;
      void runForRow(
        item.conversationId,
        () => groupCancelMutation.mutateAsync({ groupId, gcId: item.conversationId }),
        ok,
      );
      return;
    }
    void runForRow(item.conversationId, () => cancelMutation.mutateAsync(item.conversationId), ok);
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
        // A group verdict applies to the whole paused phase, including every
        // task waiting in it. The group page can approve tasks one by one; this
        // queue cannot, so it says what it is about to do rather than implying
        // a narrower decision.
        if (confirm.item.groupId) {
          return {
            title: t("hitl.confirmApproveTitle", "Approve request?"),
            description: t(
              "hitl.confirmApproveGroupDescription",
              "Approve the whole paused phase and resume the discussion. Every task waiting on this pause is approved with it — open the group to decide them individually.",
            ),
            confirmLabel: t("hitl.approve", "Approve"),
            variant: "warning" as const,
            isPending: inFlight.has(confirm.item.conversationId),
          };
        }
        return {
          title: t("hitl.confirmApproveTitle", "Approve request?"),
          description: t("hitl.confirmApproveDescription", "Approve and resume this conversation?"),
          confirmLabel: t("hitl.approve", "Approve"),
          variant: "warning" as const,
          isPending: inFlight.has(confirm.item.conversationId),
        };
      case "REJECTED":
        // Same asymmetry as APPROVED above: a group verdict is decided for the
        // whole paused phase, and the generic wording describes one request.
        if (confirm.item.groupId) {
          return {
            title: t("hitl.confirmRejectTitle", "Reject request?"),
            description: t(
              "hitl.confirmRejectGroupDescription",
              "Reject the whole paused phase. Every task waiting on this pause is rejected with it, and the discussion does not continue past it — open the group to decide them individually.",
            ),
            confirmLabel: t("hitl.reject", "Reject"),
            variant: "destructive" as const,
            isPending: inFlight.has(confirm.item.conversationId),
          };
        }
        return {
          title: t("hitl.confirmRejectTitle", "Reject request?"),
          description: t("hitl.confirmRejectDescription", "Reject this request? The conversation will not proceed."),
          confirmLabel: t("hitl.reject", "Reject"),
          variant: "destructive" as const,
          isPending: inFlight.has(confirm.item.conversationId),
        };
      case "CANCEL":
        // A group row cancels a discussion, not a conversation. Reusing the
        // group page's own wording keeps the two surfaces saying the same
        // thing about the same action.
        return confirm.item.groupId
          ? {
              title: t("hitl.confirmCancelGroupTitle", "Cancel discussion?"),
              description: t(
                "hitl.confirmCancelGroupDescription",
                "Cancel this discussion? Any in-progress work is aborted.",
              ),
              confirmLabel: t("hitl.confirmCancelGroupButton", "Cancel discussion"),
              variant: "destructive" as const,
              isPending: inFlight.has(confirm.item.conversationId),
            }
          : {
              title: t("hitl.confirmCancelTitle", "Cancel conversation?"),
              description: t("hitl.confirmCancelDescription", "Cancel this conversation? Any in-progress work is aborted."),
              confirmLabel: t("hitl.confirmCancelButton", "Cancel conversation"),
              variant: "destructive" as const,
              isPending: inFlight.has(confirm.item.conversationId),
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
            <Hand className="h-8 w-8 text-primary" />
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
          <Hand className="h-8 w-8 text-primary" />
          {t("pages.approvals", "Pending Approvals")}
        </h1>
        <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-8 text-center">
          <AlertTriangle className="mx-auto h-8 w-8 text-destructive" />
          <p className="mt-2 text-destructive">{t("common.loadError", "Failed to load data")}</p>
          <Button variant="link" size="sm" onClick={handleRefresh} className="mt-3">
            {t("common.retry", "Retry")}
          </Button>
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
            <Hand className="h-8 w-8 text-primary" />
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
          <Button
            variant="outline"
            size="icon"
            onClick={handleRefresh}
            aria-label={t("common.refresh", "Refresh")}
            title={t("common.refresh", "Refresh")}
            data-testid="refresh-approvals"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
          </Button>
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
        // `overflow-x-auto`, not `overflow-hidden`: six columns do not fit a
        // phone, and clipping them put Review/Approve/Reject permanently out
        // of reach there — the decision buttons sat ~350px past the card edge
        // with no way to scroll to them. Now the table scrolls inside its card.
        //
        // On a phone the table is laid out as cards (each row a block, each cell
        // labelled by `data-label`) rather than scrolled: a horizontal scroll
        // still left Approve/Reject a swipe away from the row they belong to.
        <div className="rounded-xl border bg-card shadow-sm overflow-x-auto">
          <table className="w-full text-sm max-md:block" data-testid="approval-queue-table">
            <thead className="max-md:hidden">
              <tr className="border-b bg-muted/30">
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.surface", "Surface")}</th>
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.conversationColumn", "Conversation")}</th>
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.pauseReason", "Reason")}</th>
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.pausedAt", "Paused")}</th>
                <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("hitl.timeoutPolicy", "Timeout")}</th>
                <th className="px-4 py-3 text-end font-medium text-muted-foreground">{t("common.actions", "Actions")}</th>
              </tr>
            </thead>
            <tbody className="divide-y max-md:block">
              {filtered.map((item) => (
                <ApprovalQueueRow
                  key={item.conversationId}
                  item={item}
                  groupHref={groupHrefFor(item)}
                  displayName={nameFor(item)}
                  groupVersion={item.groupId ? (groupVersions.get(item.groupId) ?? null) : null}
                  onGroupDecide={decideGroupPhase}
                  onRequestConfirm={(row, action) => setConfirm({ item: row, action })}
                  onToolDecide={decideToolCall}
                  onToolCancel={doCancel}
                  busy={inFlight.has(item.conversationId)}
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
