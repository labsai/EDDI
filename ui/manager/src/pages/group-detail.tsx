import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useParams, useSearchParams, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQueryClient, useMutation } from "@tanstack/react-query";
import {
  Users, Trash2, MessageSquareQuote, Clock, Settings2,
  PanelRightOpen, PanelRightClose,
  PanelLeftOpen, PanelLeftClose,
  Maximize2, Minimize2, History, X,
  AlertTriangle, Plus, Boxes,
} from "lucide-react";
import { toast } from "sonner";
import {
  useGroup,
  useGroupConversations,
  useGroupConversation,
  useDeleteGroupConversation,
} from "@/hooks/use-groups";
import { useGroupDiscussionStream } from "@/hooks/use-group-discussion-stream";
import { useCancelGroupDiscussion, useSubmitHumanInput } from "@/hooks/use-hitl";
import { DiscussionTranscript } from "@/components/groups/discussion-transcript";
import { DiscussionInput } from "@/components/groups/discussion-input";
import { DiscussionActions } from "@/components/groups/discussion-actions";
import { GroupConfigPanel } from "@/components/groups/group-config-panel";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { BackLink } from "@/components/shared/back-link";
import { ErrorState } from "@/components/shared/error-state";
import { cn } from "@/lib/utils";
import { getErrorMessage, isApiError } from "@/lib/api-client";
import {
  STYLE_INFO,
  followupGroupMember,
  closeGroupConversation,
  type DiscussionStyle,
  type AgentGroupConfiguration,
  type GroupAttachmentRef,
} from "@/lib/api/groups";
import type { HitlVerdict } from "@/lib/api/hitl";
import { STYLE_THEME } from "@/components/groups/discussion-transcript";
import { safeFormatDate } from "@/components/groups/group-utils";

const DEFAULT_STATE = { label: "Created", color: "text-muted-foreground", dot: "bg-muted-foreground" } as const;

const STATE_CONFIG: Record<string, { label: string; color: string; dot: string }> = {
  COMPLETED: { label: "Completed", color: "text-emerald-500", dot: "bg-emerald-500" },
  IN_PROGRESS: { label: "In Progress", color: "text-amber-500", dot: "bg-amber-500" },
  SYNTHESIZING: { label: "Synthesizing", color: "text-amber-500", dot: "bg-amber-500" },
  FAILED: { label: "Failed", color: "text-destructive", dot: "bg-destructive" },
  CREATED: DEFAULT_STATE,
  AWAITING_APPROVAL: { label: "Awaiting Approval", color: "text-orange-500", dot: "bg-orange-500" },
  AWAITING_HUMAN_INPUT: { label: "Awaiting Human Input", color: "text-primary", dot: "bg-primary" },
  CANCELLED: { label: "Cancelled", color: "text-muted-foreground", dot: "bg-muted-foreground" },
  ERROR: { label: "Error", color: "text-destructive", dot: "bg-destructive" },
};

/**
 * Map a lifecycle-action failure (followup / continue / close) to a friendly,
 * actionable message. The backend uses distinct HTTP codes: 409 = a concurrent
 * operation is in progress or the conversation left the state that accepts the
 * action (retry), 502 = a member agent could not be reached (unreachable),
 * 504 = a member agent did not respond in time (timeout). Everything else falls
 * back to the generic extracted message.
 */
function friendlyGroupActionError(
  err: unknown,
  t: (key: string, fallback: string) => string,
): string {
  if (isApiError(err)) {
    if (err.status === 409)
      return t(
        "groups.actionConflict",
        "Another operation is still in progress, or this discussion no longer accepts that action. Please retry.",
      );
    if (err.status === 502)
      return t(
        "groups.actionUnreachable",
        "A member agent could not be reached. Please try again.",
      );
    if (err.status === 504)
      return t(
        "groups.actionTimeout",
        "A member agent did not respond in time. Please try again.",
      );
  }
  return getErrorMessage(err);
}

export function GroupDetailPage() {
  const { id: groupId } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  // Backend requires version — default to 1 if missing from URL (e.g. wizard link).
  // To update after a save: pull setSearchParams from useSearchParams() and call
  // setSearchParams(p => { p.set("version", String(newVersion)); return p }, { replace: true })
  const version = useMemo(
    () => (searchParams.get("version") ? Number(searchParams.get("version")) : 1),
    [searchParams],
  );
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [selectedConvId, setSelectedConvId] = useState<string | null>(null);
  const [showConfig, setShowConfig] = useState(true);
  const [showDiscussions, setShowDiscussions] = useState(true);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  // Discussion id awaiting a cancel confirmation. The hover "X" sits right next
  // to the Delete trash icon, so a mis-click must not abort a live discussion —
  // route it through a confirmation before the cancel mutation fires.
  const [cancelTarget, setCancelTarget] = useState<string | null>(null);

  const {
    data: groupConfig,
    isLoading: configLoading,
    isError: configError,
    refetch: refetchConfig,
  } = useGroup(groupId || "", version);

  const {
    data: conversations,
    isLoading: convsLoading,
  } = useGroupConversations(groupId || "");

  const {
    data: selectedConversation,
    isLoading: convLoading,
  } = useGroupConversation(groupId || "", selectedConvId || "");

  // SSE streaming hook
  const { streamState, startStream, continueStream, approveAndStream, abortStream, resetStream } = useGroupDiscussionStream();

  const deleteConvMutation = useDeleteGroupConversation();
  const cancelDiscussionMutation = useCancelGroupDiscussion();
  const submitHumanInputMutation = useSubmitHumanInput();

  // Track an in-flight HITL decision so we can toast on the ACTUAL outcome
  // (hitl_resume ack / FAILED) rather than optimistically.
  const pendingDecisionRef = useRef<HitlVerdict | null>(null);

  // Auto-select the first conversation on load — but never override the
  // conversation the stream is driving (its settle effect handles selection).
  useEffect(() => {
    if (
      !selectedConvId &&
      !streamState.isStreaming &&
      !streamState.conversationId &&
      conversations &&
      conversations.length > 0
    ) {
      setSelectedConvId(conversations[0]!.id);
    }
  }, [conversations, selectedConvId, streamState.isStreaming, streamState.conversationId]);

  // ─── Context-aware input mode ─────────────────────────────────
  const inputMode = useMemo((): "new" | "continue" | "disabled" => {
    if (streamState.isStreaming) return "disabled";
    if (!selectedConvId) return "new";
    if (!selectedConversation) return "disabled"; // still loading
    const actions = selectedConversation.availableActions ?? [];
    if (actions.includes("continue")) return "continue";
    return "disabled";
  }, [streamState.isStreaming, selectedConvId, selectedConversation]);

  const disabledMessage = useMemo(() => {
    if (streamState.isStreaming) return t("groups.inputDisabledInProgress", "Discussion in progress…");
    if (!selectedConvId) return undefined;
    if (!selectedConversation) return t("common.loading", "Loading…");
    const state = selectedConversation.state;
    if (state === "CLOSED") return t("groups.inputDisabledClosed", "This discussion is closed");
    if (state === "FAILED" || state === "CANCELLED") return t("groups.inputDisabledEnded", "This discussion has ended");
    if (state === "AWAITING_APPROVAL") return t("groups.inputDisabledApproval", "Awaiting approval…");
    if (state === "AWAITING_HUMAN_INPUT") return t("groups.inputDisabledHumanTurn", "Awaiting a member's turn…");
    if (state === "IN_PROGRESS" || state === "SYNTHESIZING") return t("groups.inputDisabledInProgress", "Discussion in progress…");
    if (state === "COMPLETED") return t("groups.inputDisabledCompleted", "Discussion completed");
    return undefined;
  }, [streamState.isStreaming, selectedConvId, selectedConversation, t]);

  const handleInputSubmit = useCallback((question: string, attachments?: GroupAttachmentRef[]) => {
    if (!groupId) return;
    if (inputMode === "continue" && selectedConvId) {
      // SSE streaming continuation. Attachments are deliberately not forwarded:
      // the backend only shares files with member agents when a discussion
      // starts, and rejects a continuation carrying any. DiscussionInput hides
      // the affordance in this mode, so there should be none to drop.
      continueStream(groupId, selectedConvId, question);
      toast.info(t("groups.continueStreamStarted", "Continuation started — streaming live"));
    } else {
      // New discussion
      pendingDecisionRef.current = null;
      setSelectedConvId(null);
      startStream(groupId, question, attachments);
      toast.info(t("groups.discussionStarted", "Discussion started — streaming live"));
    }
  }, [groupId, inputMode, selectedConvId, continueStream, startStream, t]);

  const handleNewDiscussion = useCallback(() => {
    resetStream();
    pendingDecisionRef.current = null;
    setSelectedConvId(null);
  }, [resetStream]);

  // Approve/reject a paused group discussion. Resumes over the approve/stream
  // SSE endpoint so the continued discussion renders live in the transcript.
  const handleApproveDiscussion = useCallback(
    (gcId: string, verdict: HitlVerdict, note?: string, taskApprovals?: Record<string, string>) => {
      if (!groupId) return;
      // Feedback is driven off the resume outcome (see effect below), not fired
      // optimistically — the resume can fail (409 stale, 400 invalid decision).
      pendingDecisionRef.current = verdict;
      setSelectedConvId(null); // switch the transcript to the live resumed stream
      approveAndStream(groupId, gcId, { decision: { verdict, note }, taskApprovals });
    },
    [groupId, approveAndStream],
  );

  // Toast the decision outcome once the resumed stream confirms (hitl_resume) or
  // fails (FAILED). approveAndStream never rejects, so we cannot use .catch/.then.
  useEffect(() => {
    const verdict = pendingDecisionRef.current;
    if (!verdict) return;
    if (streamState.hitlResume) {
      toast.success(
        verdict === "APPROVED" ? t("hitl.approved", "Approved") : t("hitl.rejected", "Rejected"),
      );
      // The conversation left AWAITING_APPROVAL — clear it from the cross-group
      // Approvals inbox now (mirrors the cancel path) rather than waiting for the poll.
      queryClient.invalidateQueries({ queryKey: ["all-group-pending-approvals"] });
      pendingDecisionRef.current = null;
    } else if (streamState.state === "FAILED") {
      toast.error(streamState.error || t("common.error", "Something went wrong"));
      pendingDecisionRef.current = null;
    }
  }, [streamState.hitlResume, streamState.state, streamState.error, queryClient, t]);

  // Submit a HUMAN member's pending turn (I6). Unlike approve/reject, this is a
  // plain synchronous mutation — the backend has no streaming variant, so there
  // is no live progress to switch to; the settled conversation lands via the
  // mutation's own query invalidation, and the transcript re-renders once it refetches.
  const handleSubmitHumanInput = useCallback(
    (gcId: string, memberId: string, content: string) => {
      if (!groupId) return;
      submitHumanInputMutation.mutate(
        { groupId, gcId, memberId, content },
        {
          onSuccess: () => {
            toast.success(t("groups.humanTurnSubmitted", "Your response was recorded"));
          },
          onError: (err) => {
            toast.error(friendlyGroupActionError(err, t));
          },
        },
      );
    },
    [groupId, submitHumanInputMutation, t],
  );

  const handleCancelDiscussion = useCallback(
    (gcId: string) => {
      if (!groupId) return;
      cancelDiscussionMutation.mutate(
        { groupId, gcId },
        {
          onSuccess: () => {
            toast.success(t("hitl.discussionCancelled", "Discussion cancelled"));
            queryClient.invalidateQueries({ queryKey: ["groupConversations", groupId] });
          },
          onError: (err) => {
            toast.error(getErrorMessage(err));
          },
        },
      );
    },
    [groupId, cancelDiscussionMutation, queryClient, t],
  );

  // ─── Post-COMPLETED lifecycle: continue / follow-up / close ───────
  // These act on a persisted (selected) conversation, not the live stream.
  // Each returns the updated GroupConversation (with recomputed availableActions);
  // we invalidate both the list and the single-conversation query so the action
  // bar and transcript reflect the new state (a new round, a follow-up exchange,
  // or the terminal CLOSED state that hides the bar entirely).
  const invalidateConversations = useCallback(() => {
    if (groupId) {
      queryClient.invalidateQueries({ queryKey: ["groupConversations", groupId] });
    }
  }, [groupId, queryClient]);

  const followupMutation = useMutation({
    mutationFn: ({
      gcId,
      targetAgentId,
      question,
    }: {
      gcId: string;
      targetAgentId: string;
      question: string;
    }) => followupGroupMember(groupId!, gcId, question, targetAgentId),
    onSuccess: () => {
      toast.success(t("groups.followupSent", "Follow-up sent"));
      invalidateConversations();
    },
    onError: (err) => toast.error(friendlyGroupActionError(err, t)),
  });

  const closeMutation = useMutation({
    mutationFn: ({ gcId }: { gcId: string }) =>
      closeGroupConversation(groupId!, gcId),
    onSuccess: () => {
      toast.success(t("groups.discussionClosed", "Discussion closed"));
      invalidateConversations();
    },
    onError: (err) => toast.error(friendlyGroupActionError(err, t)),
  });

  const handleFollowupMember = useCallback(
    (targetAgentId: string, question: string) => {
      if (!groupId || !selectedConvId) return;
      followupMutation.mutate({ gcId: selectedConvId, targetAgentId, question });
    },
    [groupId, selectedConvId, followupMutation],
  );

  const handleCloseConversation = useCallback(() => {
    if (!groupId || !selectedConvId) return;
    closeMutation.mutate({ gcId: selectedConvId });
  }, [groupId, selectedConvId, closeMutation]);

  const actionPending =
    followupMutation.isPending ||
    closeMutation.isPending;

  // Invalidate conversation list when stream starts (so the new entry appears in sidebar)
  // AND when it completes (so the state updates to COMPLETED)
  useEffect(() => {
    if (
      streamState.conversationId &&
      groupId &&
      (streamState.state === "IN_PROGRESS" ||
        streamState.state === "COMPLETED" ||
        streamState.state === "AWAITING_APPROVAL" ||
        streamState.state === "AWAITING_HUMAN_INPUT")
    ) {
      queryClient.invalidateQueries({ queryKey: ["groupConversations", groupId] });
    }
    // When the stream settles (completed) or pauses (awaiting approval / a
    // member's turn), switch the transcript to the persisted conversation so it
    // shows the full pause metadata (pausedAt, timeout policy/countdown,
    // per-task awaiting list, or — for a human turn — the rendered prompt).
    if (
      (streamState.state === "COMPLETED" ||
        streamState.state === "AWAITING_APPROVAL" ||
        streamState.state === "AWAITING_HUMAN_INPUT") &&
      streamState.conversationId
    ) {
      setSelectedConvId(streamState.conversationId);
    }
  }, [streamState.state, streamState.conversationId, groupId, queryClient]);

  function handleDeleteConversation(convId: string) {
    if (!groupId) return;
    deleteConvMutation.mutate(
      { groupId, conversationId: convId },
      {
        onSuccess: () => {
          toast.success(t("common.delete") + " ✓");
          if (selectedConvId === convId) setSelectedConvId(null);
        },
      }
    );
  }

  function handleSelectConversation(convId: string) {
    if (streamState.isStreaming) abortStream();
    pendingDecisionRef.current = null; // abandon any un-acked prior decision
    setSelectedConvId(convId);
    setHistoryOpen(false);
  }

  if (configLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-1/3" />
        <Skeleton className="h-[70vh] w-full" />
      </div>
    );
  }

  if (configError || !groupConfig) {
    return (
      <div className="space-y-4">
        <BackLink to="/manage/groups" label={t("groups.backToGroups", "Back to Groups")} />
        <ErrorState message={t("common.error")} onRetry={() => refetchConfig()} retryLabel={t("common.retry")} />
      </div>
    );
  }

  const styleInfo = STYLE_INFO[groupConfig.style] ?? STYLE_INFO.ROUND_TABLE;
  const styleTheme = STYLE_THEME[groupConfig.style as DiscussionStyle] ?? STYLE_THEME.ROUND_TABLE;

  // Normalize: ensure members is always an array so downstream components
  // (GroupConfigPanel, badge, etc.) never crash on null/undefined.
  const safeConfig: AgentGroupConfiguration = groupConfig.members
    ? groupConfig
    : { ...groupConfig, members: [] };

  // Determine whether to show streaming or static transcript
  const isStreamActive = streamState.isStreaming || (streamState.state !== "CREATED" && !selectedConvId);

  // On a live pause/complete the settle effect switches to the persisted
  // conversation, whose detail may not be cached yet. Keep showing the live
  // streamState (banner from hitlPause) instead of a loading skeleton until the
  // persisted conversation has loaded — avoids a flash at the decision moment.
  const showStreamFallback =
    !isStreamActive &&
    convLoading &&
    streamState.state !== "CREATED" &&
    selectedConvId === streamState.conversationId;

  const conversationCount = conversations?.length ?? 0;

  // ─── Discussion history list (reused in sidebar + popover) ─────
  const discussionListContent = (
    <>
      {convsLoading ? (
        <div className="p-3 space-y-2">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : conversationCount > 0 ? (
        <div className="p-1 space-y-0.5">
          {conversations!.map((conv) => (
            <div
              key={conv.id}
              role="button"
              tabIndex={0}
              onClick={() => handleSelectConversation(conv.id)}
              onKeyDown={(e) => {
                if ((e.key === "Enter" || e.key === " ") && e.target === e.currentTarget) {
                  e.preventDefault();
                  handleSelectConversation(conv.id);
                }
              }}
              className={cn(
                "w-full text-start rounded-lg px-3 py-2 transition-all group/item cursor-pointer",
                streamState.isStreaming && conv.id === streamState.conversationId
                  ? "bg-primary/10 border border-primary/30"
                  : selectedConvId === conv.id && !isStreamActive
                    ? "bg-primary/10 border border-primary/30"
                    : "hover:bg-secondary/50 border border-transparent"
              )}
              aria-current={
                (streamState.isStreaming && conv.id === streamState.conversationId) ||
                (selectedConvId === conv.id && !isStreamActive)
                  ? true
                  : undefined
              }
              data-testid={`discussion-item-${conv.id}`}
            >
              <p className="text-xs font-medium text-foreground line-clamp-2">
                {conv.originalQuestion}
              </p>
              <div className="flex items-center gap-1.5 mt-1">
                {(() => {
                  const cfg = STATE_CONFIG[conv.state] ?? DEFAULT_STATE;
                  const isLive = streamState.isStreaming && conv.id === streamState.conversationId;
                  return (
                    <span
                      className={cn("text-[10px] font-medium flex items-center gap-1", cfg.color)}
                      aria-live={isLive ? "polite" : undefined}
                      aria-label={isLive ? t("groups.liveDiscussion", "Live") : t(`groups.state.${conv.state}`, cfg.label)}
                      data-testid={isLive ? "discussion-live-badge" : `discussion-state-${conv.id}`}
                    >
                      <span
                        className={cn("h-1.5 w-1.5 rounded-full shrink-0", isLive ? "bg-current animate-pulse" : cfg.dot)}
                        data-testid={`state-dot-${conv.id}`}
                      />
                      {isLive
                        ? t("groups.liveDiscussion", "Live")
                        : t(`groups.state.${conv.state}`, cfg.label)
                      }
                    </span>
                  );
                })()}
                <span className="text-[10px] text-muted-foreground">
                  {safeFormatDate(conv.created, "date")}
                </span>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteConversation(conv.id);
                  }}
                  className="ms-auto opacity-0 group-hover/item:opacity-100 rounded p-0.5 text-muted-foreground hover:text-destructive transition-all"
                  title={t("common.delete")}
                  aria-label={t("common.delete")}
                >
                  <Trash2 className="h-3 w-3" />
                </button>
                {(conv.state === "AWAITING_APPROVAL" || conv.state === "AWAITING_HUMAN_INPUT" || conv.state === "IN_PROGRESS") && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setCancelTarget(conv.id);
                    }}
                    className="opacity-0 group-hover/item:opacity-100 rounded p-0.5 text-muted-foreground hover:text-destructive transition-all"
                    title={t("hitl.cancelDiscussion", "Cancel discussion")}
                    aria-label={t("hitl.cancelDiscussion", "Cancel discussion")}
                    disabled={cancelDiscussionMutation.isPending}
                  >
                    <X className="h-3 w-3" />
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center py-8 px-4 text-center">
          <MessageSquareQuote className="h-8 w-8 text-muted-foreground/20 mb-2" />
          <p className="text-xs text-muted-foreground">
            {t("groups.noDiscussions", "No discussions yet")}
          </p>
          <p className="text-[10px] text-muted-foreground/50 mt-1">
            {t("groups.askBelow", "Ask a question below to start")}
          </p>
        </div>
      )}
    </>
  );

  return (
    <div className={cn(
      "flex flex-col",
      isFullscreen
        ? "fixed inset-0 z-50 bg-background p-4"
        : "h-[calc(100vh-(--spacing(16))-(--spacing(12)))]"
    )}>
      {/* Header */}
      <div className="flex items-center gap-3 pb-3 border-b border-border shrink-0">
        {!isFullscreen && <BackLink to="/manage/groups" label="" />}
        <Users className="h-6 w-6 text-primary shrink-0" />
        <div className="flex-1 min-w-0">
          <h1 className="text-xl font-bold text-foreground truncate">{groupConfig.name}</h1>
          {groupConfig.description && (
            <p className="text-xs text-muted-foreground truncate">{groupConfig.description}</p>
          )}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {styleInfo && (
            <Badge variant="outline" className={cn("hidden sm:inline-flex", styleTheme.flowText)} title={styleInfo.flow}>
              {styleInfo.icon} {styleInfo.label}
            </Badge>
          )}
          <Badge variant="secondary">
            <Users className="me-1 h-3 w-3" />
            {safeConfig.members.length}
          </Badge>

          {/* History dropdown — visible on mobile, and on all sizes when fullscreen */}
          <HistoryDropdown
            historyOpen={historyOpen}
            setHistoryOpen={setHistoryOpen}
            conversationCount={conversationCount}
            isFullscreen={isFullscreen}
            onNewDiscussion={handleNewDiscussion}
          >
            {discussionListContent}
          </HistoryDropdown>

          {/* Discussions panel re-open — only shows when panel is hidden */}
          {!isFullscreen && !showDiscussions && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowDiscussions(true)}
              title={t("groups.showDiscussions", "Show discussions panel")}
              aria-label={t("groups.showDiscussions", "Show discussions panel")}
              className="max-lg:hidden"
            >
              <PanelLeftOpen className="h-4 w-4" />
            </Button>
          )}

          {/* Config panel re-open — only shows when panel is hidden */}
          {!isFullscreen && !showConfig && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowConfig(true)}
              title={t("groups.showConfig", "Show config panel")}
              aria-label={t("groups.showConfig", "Show config panel")}
              className="max-xl:hidden"
            >
              <PanelRightOpen className="h-4 w-4" />
            </Button>
          )}

          {/* Standing-team workspace (I13) — persistent backlog + cadences. */}
          {!isFullscreen && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate(`/manage/groups/${groupId}/workspace?version=${version}`)}
              title={t("groupWorkspace.title", "Standing Team Workspace")}
              data-testid="open-workspace-btn"
            >
              <Boxes className="h-4 w-4" />
              <span className="hidden sm:inline">{t("groupWorkspace.navLabel", "Workspace")}</span>
            </Button>
          )}

          {/* Fullscreen toggle */}
          <Button
            variant="outline"
            size="sm"
            onClick={() => setIsFullscreen(!isFullscreen)}
            title={isFullscreen
              ? t("groups.exitFullscreen", "Exit fullscreen")
              : t("groups.enterFullscreen", "Fullscreen")}
          >
            {isFullscreen ? (
              <Minimize2 className="h-4 w-4" />
            ) : (
              <Maximize2 className="h-4 w-4" />
            )}
          </Button>
        </div>
      </div>

      {/* Three-panel layout */}
      <div className="flex flex-1 min-h-0 mt-2 gap-2">
        {/* LEFT: Discussion history — hidden on small screens and in fullscreen */}
        {!isFullscreen && showDiscussions && (
          <div className="w-64 shrink-0 flex flex-col rounded-xl border border-border bg-card overflow-hidden max-lg:hidden">
            <div className="p-3 border-b border-border flex items-center justify-between">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                <Clock className="h-3 w-3" />
                {t("groups.discussions", "Discussions")}
              </h3>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={handleNewDiscussion}
                  className="flex items-center gap-0.5 rounded px-1.5 py-0.5 text-[10px] font-medium text-primary hover:bg-primary/10 transition-colors"
                  title={t("groups.newDiscussion", "New Discussion")}
                  aria-label={t("groups.newDiscussion", "New Discussion")}
                  data-testid="new-discussion-btn"
                >
                  <Plus className="h-3 w-3" />
                  {t("groups.newShort", "New")}
                </button>
                <button
                  type="button"
                  onClick={() => setShowDiscussions(false)}
                  className="p-0.5 rounded hover:bg-secondary/50 text-muted-foreground hover:text-foreground transition-colors"
                  title={t("groups.hideDiscussions", "Hide discussions panel")}
                  aria-label={t("groups.hideDiscussions", "Hide discussions panel")}
                >
                  <PanelLeftClose className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
            <div className="flex-1 overflow-y-auto">
              {discussionListContent}
            </div>
          </div>
        )}

        {/* CENTER: Transcript + Input */}
        <div className="flex-1 min-w-0 rounded-xl border border-border bg-card overflow-hidden flex flex-col">
          {/* Config-drift recovery guidance — the resume was aborted but the
              discussion is still awaiting approval; the pause is recoverable. */}
          {streamState.state === "FAILED" && streamState.errorKind === "config_drift" && (
            <div
              className="flex items-start gap-2 border-b border-amber-500/30 bg-amber-500/5 px-4 py-2.5"
              data-testid="group-drift-banner"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" aria-hidden="true" />
              <div className="text-xs">
                <p className="font-medium text-amber-600 dark:text-amber-400">
                  {t("groups.driftTitle", "Group configuration changed while paused")}
                </p>
                <p className="mt-0.5 text-muted-foreground">
                  {t("groups.driftRecovery", "The resume was aborted because the group's phases changed. The discussion is still awaiting approval — fix the configuration (Edit, in the Configuration panel) and approve again, or cancel it.")}
                </p>
              </div>
            </div>
          )}
          <div className="flex-1 min-h-0 overflow-hidden">
            <DiscussionTranscript
              conversation={isStreamActive ? null : (selectedConversation ?? null)}
              streamState={isStreamActive || showStreamFallback ? streamState : undefined}
              isLoading={convLoading && !!selectedConvId && !showStreamFallback}
              discussionStyle={groupConfig.style as DiscussionStyle}
              preConfiguredTasks={groupConfig.tasks}
              onApprove={handleApproveDiscussion}
              onCancelDiscussion={handleCancelDiscussion}
              isDeciding={cancelDiscussionMutation.isPending}
              onSubmitHumanInput={handleSubmitHumanInput}
              isSubmittingHumanInput={submitHumanInputMutation.isPending}
              humanTurnTimeout={groupConfig.humanMemberConfig?.turnTimeout}
            />
          </div>
          {/* Post-COMPLETED lifecycle action bar — driven entirely by the
              backend's availableActions (never hardcoded). Hidden while a live
              stream is active and absent once the conversation is CLOSED (empty
              availableActions). Distinct from the DiscussionInput below, which
              always starts a brand-new discussion. */}
          {!isStreamActive &&
            selectedConversation &&
            (selectedConversation.availableActions?.length ?? 0) > 0 && (
              <DiscussionActions
                availableActions={selectedConversation.availableActions!}
                members={safeConfig.members}
                isPending={actionPending}
                onFollowup={handleFollowupMember}
                onCloseDiscussion={handleCloseConversation}
              />
            )}
          {/* Input always at the bottom of the transcript panel */}
          <DiscussionInput
            onSubmit={handleInputSubmit}
            isLoading={streamState.isStreaming}
            mode={inputMode === "continue" ? "continue" : "new"}
            disabled={inputMode === "disabled"}
            disabledMessage={disabledMessage}
          />
        </div>

        {/* RIGHT: Config panel — hidden on small screens and in fullscreen */}
        {showConfig && !isFullscreen && (
          <div className="w-72 shrink-0 rounded-xl border border-border bg-card overflow-hidden flex flex-col max-xl:hidden">
            <div className="p-3 border-b border-border flex items-center justify-between">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                <Settings2 className="h-3 w-3" />
                {t("groups.configuration", "Configuration")}
              </h3>
              <button
                type="button"
                onClick={() => setShowConfig(false)}
                className="p-0.5 rounded hover:bg-secondary/50 text-muted-foreground hover:text-foreground transition-colors"
                title={t("groups.hideConfig", "Hide config panel")}
                aria-label={t("groups.hideConfig", "Hide config panel")}
              >
                <PanelRightClose className="h-3.5 w-3.5" />
              </button>
            </div>
            <GroupConfigPanel key={groupId} config={safeConfig} groupId={groupId} groupVersion={version} className="flex-1 min-h-0" />
          </div>
        )}
      </div>

      {/* Cancel confirmation — the hover "X" must not abort a discussion on a
          single (mis-)click next to the Delete icon. */}
      <AlertDialog
        open={cancelTarget !== null}
        onOpenChange={(open) => {
          if (!open) setCancelTarget(null);
        }}
        title={t("hitl.confirmCancelGroupTitle", "Cancel discussion?")}
        description={t("hitl.confirmCancelGroupDescription", "Cancel this discussion? Any in-progress work is aborted.")}
        confirmLabel={t("hitl.confirmCancelGroupButton", "Cancel discussion")}
        cancelLabel={t("hitl.confirmDismiss", "Go back")}
        variant="destructive"
        isPending={cancelDiscussionMutation.isPending}
        onConfirm={() => {
          if (cancelTarget) handleCancelDiscussion(cancelTarget);
          setCancelTarget(null);
        }}
      />
    </div>
  );
}

/** Mobile-friendly dropdown for discussion history (replaces Popover) */
function HistoryDropdown({
  historyOpen,
  setHistoryOpen,
  conversationCount,
  isFullscreen,
  onNewDiscussion,
  children,
}: {
  historyOpen: boolean;
  setHistoryOpen: (v: boolean) => void;
  conversationCount: number;
  isFullscreen?: boolean;
  onNewDiscussion?: () => void;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Click-outside to close
  useEffect(() => {
    if (!historyOpen) return;
    function handleClick(e: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setHistoryOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, [historyOpen, setHistoryOpen]);

  return (
    <div ref={dropdownRef} className={cn("relative", !isFullscreen && "lg:hidden")}>
      <Button
        variant="outline"
        size="sm"
        className="relative"
        onClick={() => setHistoryOpen(!historyOpen)}
      >
        <History className="h-4 w-4" />
        {conversationCount > 0 && (
          <span className="absolute -top-1 -end-1 flex h-4 w-4 items-center justify-center rounded-full bg-primary text-[9px] font-bold text-primary-foreground">
            {conversationCount}
          </span>
        )}
      </Button>
      {historyOpen && (
        <div className="absolute end-0 top-full mt-1 z-50 w-72 rounded-xl border border-border bg-card shadow-lg max-h-80 overflow-y-auto animate-in fade-in-0 zoom-in-95 duration-150">
          <div className="p-3 border-b border-border flex items-center justify-between">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
              <Clock className="h-3 w-3" />
              {t("groups.discussions", "Discussions")}
            </h3>
            {onNewDiscussion && (
              <button
                type="button"
                onClick={() => {
                  onNewDiscussion();
                  setHistoryOpen(false);
                }}
                className="flex items-center gap-0.5 rounded px-1.5 py-0.5 text-[10px] font-medium text-primary hover:bg-primary/10 transition-colors"
                title={t("groups.newDiscussion", "New Discussion")}
                aria-label={t("groups.newDiscussion", "New Discussion")}
                data-testid="new-discussion-btn-mobile"
              >
                <Plus className="h-3 w-3" />
                {t("groups.newShort", "New")}
              </button>
            )}
          </div>
          {children}
        </div>
      )}
    </div>
  );
}
