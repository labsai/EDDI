import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  CheckCircle2,
  XCircle,
  Clock,
  AlertTriangle,
  HandMetal,
  ChevronDown,
  Wrench,
  ShieldAlert,
  Pencil,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { timeoutPolicyLabel, granularityLabel } from "@/lib/hitl-labels";
import { parseIsoDurationMs, formatDurationMs, formatIsoDuration } from "@/lib/hitl-config";
import {
  AMENDED_ARGS_MAX_BYTES,
  type HitlVerdict,
  type PauseDetails,
  type PendingToolCallView,
  type ToolCallDecision,
} from "@/lib/api/hitl";

interface ApprovalBannerProps {
  /** What kind of surface this approval is for. */
  surface: "regular" | "group";
  /** Why the conversation/discussion paused. */
  pauseReason?: string;
  /** ISO timestamp of when the pause started. */
  pausedAt?: string;
  /** Timeout policy name. */
  timeoutPolicy?: string;
  /** ISO-8601 duration string (e.g., "PT15M"). */
  approvalTimeout?: string;
  /** For group: which phase is paused. */
  pausedPhaseName?: string;
  /** For group: PHASE or TASK granularity. */
  granularity?: string;
  /** For group/TASK: list of task IDs awaiting approval. */
  pendingTaskIds?: string[];
  /** Structured pause details from GET …/approval-status. When this is a
   *  TOOL_CALL pause, the per-call tool approval UI is rendered (regular only). */
  pauseDetails?: PauseDetails | null;
  /** Whether the mutation is in-flight. */
  isSubmitting?: boolean;
  /** True while the structured pause details are still loading (or failed to
   *  load) for a paused conversation. Blocks Approve so the reviewer can't
   *  blind approve-all before knowing whether this is a RULE or TOOL_CALL pause;
   *  Reject/Cancel stay available (both are safe with details unknown). */
  pauseDetailsPending?: boolean;
  /** Called when the user submits a decision. `toolDecisions` is populated only
   *  for a TOOL_CALL pause (per-call verdicts / amended arguments). */
  onDecide: (
    verdict: HitlVerdict,
    note?: string,
    taskApprovals?: Record<string, string>,
    toolDecisions?: Record<string, ToolCallDecision>,
  ) => void;
  /** Called when the user cancels. */
  onCancel?: () => void;
}

/** Whether a date string parses to a real instant. */
function isValidDate(iso?: string): boolean {
  return !!iso && !Number.isNaN(new Date(iso).getTime());
}

/** Calculate time remaining from pausedAt + duration, evaluated at `nowMs`.
 *  Returns a structured result (not a display string) so the "overdue" branch
 *  isn't keyed off a translatable literal. */
function getTimeRemaining(
  pausedAt: string | undefined,
  duration: string | undefined,
  nowMs: number,
): { overdue: boolean; ms: number } | null {
  if (!isValidDate(pausedAt) || !duration) return null;
  const durationMs = parseIsoDurationMs(duration);
  if (durationMs == null) return null;
  const deadline = new Date(pausedAt!).getTime() + durationMs;
  const remaining = deadline - nowMs;
  return { overdue: remaining <= 0, ms: Math.max(0, remaining) };
}

/** Format a remaining-milliseconds value as "5m 3s" / "42s". */
function formatRemaining(ms: number): string {
  return formatDurationMs(ms);
}

/** UTF-8 byte length — mirrors the backend's byte-based amendedArguments cap. */
function byteLength(s: string): number {
  return new TextEncoder().encode(s).length;
}

/** True when `s` parses to a JSON object (not an array, string, or null). */
function isJsonObject(s: string): boolean {
  try {
    const parsed = JSON.parse(s);
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed);
  } catch {
    return false;
  }
}

/** Per-call reviewer state for a TOOL_CALL pause. Absent verdict = "inherit the
 *  top-level verdict"; a non-empty `amend` replaces the call's arguments. */
interface CallState {
  verdict?: HitlVerdict;
  amend?: string;
  showAmend?: boolean;
}

export function ApprovalBanner({
  surface,
  pauseReason,
  pausedAt,
  timeoutPolicy,
  approvalTimeout,
  pausedPhaseName,
  granularity,
  pendingTaskIds,
  pauseDetails,
  isSubmitting,
  pauseDetailsPending,
  onDecide,
  onCancel,
}: ApprovalBannerProps) {
  const { t } = useTranslation();
  const [note, setNote] = useState("");
  const [showNote, setShowNote] = useState(false);
  const [taskApprovals, setTaskApprovals] = useState<Record<string, string>>({});
  const [callStates, setCallStates] = useState<Record<string, CallState>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  // Which irreversible action is awaiting confirmation. Approve/Reject/Cancel
  // must never fire on a single click: resuming a TOOL_CALL pause runs real
  // gated tools, reject cannot be undone, and cancel aborts in-flight work.
  const [confirmAction, setConfirmAction] = useState<HitlVerdict | "CANCEL" | null>(null);

  // Tick every second so the countdown updates live and flips to "Overdue"
  // while the banner is on screen (the banner shows precisely when paused, so
  // no other render is forcing updates).
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    if (!pausedAt || !approvalTimeout) return;
    const id = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(id);
  }, [pausedAt, approvalTimeout]);

  const timeRemaining = getTimeRemaining(pausedAt, approvalTimeout, nowMs);
  const isTaskGranularity =
    granularity === "TASK" && !!pendingTaskIds && pendingTaskIds.length > 0;

  const toolPause = pauseDetails?.type === "TOOL_CALL" ? pauseDetails : null;
  const isToolCall = !!toolPause;

  const setCall = (callId: string, patch: Partial<CallState>) => {
    setCallStates((prev) => ({ ...prev, [callId]: { ...prev[callId], ...patch } }));
    setSubmitError(null);
  };

  const submitToolDecision = (topVerdict: HitlVerdict) => {
    if (!toolPause) return;
    const finalNote = note.trim() || undefined;
    // Rejecting the batch is all-or-nothing: mixing per-call APPROVED with a
    // top-level REJECTED is contradictory (the backend 400s it). To approve
    // some and reject others, use the Approve action with per-call toggles.
    if (topVerdict === "REJECTED") {
      onDecide("REJECTED", finalNote, undefined, undefined);
      return;
    }
    const toolDecisions: Record<string, ToolCallDecision> = {};
    for (const call of toolPause.calls) {
      const s = callStates[call.callId];
      const amend = s?.amend?.trim();
      if (!s || (!s.verdict && !amend)) continue; // inherits the top-level APPROVED
      const verdict = s.verdict ?? "APPROVED";
      const decision: ToolCallDecision = { verdict };
      if (verdict === "APPROVED" && amend) {
        if (call.argsTruncated) {
          setSubmitError(t("hitl.amendTruncated", "Cannot amend a call whose arguments were truncated — approve or reject it as-is."));
          return;
        }
        if (!isJsonObject(amend)) {
          setSubmitError(t("hitl.amendInvalidJson", "Amended arguments must be a valid JSON object."));
          return;
        }
        if (byteLength(amend) > AMENDED_ARGS_MAX_BYTES) {
          setSubmitError(t("hitl.amendTooLarge", "Amended arguments are too large."));
          return;
        }
        decision.amendedArguments = amend;
      }
      toolDecisions[call.callId] = decision;
    }
    onDecide("APPROVED", finalNote, undefined, Object.keys(toolDecisions).length ? toolDecisions : undefined);
  };

  const handleSubmit = (verdict: HitlVerdict) => {
    if (isToolCall) {
      submitToolDecision(verdict);
      return;
    }
    const finalNote = note.trim() || undefined;
    // For TASK granularity, send an explicit decision for every pending task:
    // any task the reviewer didn't individually toggle defaults to the
    // top-level verdict, so no task is left ambiguous.
    let finalTaskApprovals: Record<string, string> | undefined;
    if (isTaskGranularity && pendingTaskIds) {
      finalTaskApprovals = {};
      for (const id of pendingTaskIds) {
        finalTaskApprovals[id] = taskApprovals[id] ?? verdict;
      }
    }
    onDecide(verdict, finalNote, finalTaskApprovals);
  };

  const handleTaskToggle = (taskId: string, verdict: string) => {
    setTaskApprovals((prev) => ({ ...prev, [taskId]: verdict }));
  };

  const handleSetAll = (verdict: "APPROVED" | "REJECTED") => {
    if (!pendingTaskIds) return;
    const all: Record<string, string> = {};
    for (const id of pendingTaskIds) all[id] = verdict;
    setTaskApprovals(all);
  };

  const outcomeUnknown = new Set(toolPause?.outcomeUnknown ?? []);

  // Distinct gated tool names for the Approve confirmation on a TOOL_CALL pause,
  // so the reviewer sees exactly what will execute before it runs.
  const gatedToolNames = toolPause
    ? Array.from(new Set(toolPause.calls.map((c) => c.toolName)))
    : [];

  // Fire the pending action only after the reviewer confirms. Validation and the
  // exact decision payload (per-call verdicts, amendments, notes, task
  // approvals) are unchanged — the confirm step is inserted before them.
  const runConfirmedAction = () => {
    const action = confirmAction;
    setConfirmAction(null);
    if (!action) return;
    if (action === "CANCEL") {
      onCancel?.();
      return;
    }
    handleSubmit(action);
  };

  const confirmDialog = (() => {
    switch (confirmAction) {
      case "APPROVED":
        return isToolCall
          ? {
              title: t("hitl.confirmApproveToolTitle", "Approve tool execution?"),
              description: t(
                "hitl.confirmApproveToolDescription",
                "Approving will run: {{toolNames}}. This performs real actions and cannot be undone.",
                { toolNames: gatedToolNames.join(", ") },
              ),
              confirmLabel: t("hitl.approve", "Approve"),
              variant: "warning" as const,
            }
          : {
              title: t("hitl.confirmApproveTitle", "Approve request?"),
              description: t("hitl.confirmApproveDescription", "Approve and resume this conversation?"),
              confirmLabel: t("hitl.approve", "Approve"),
              variant: "warning" as const,
            };
      case "REJECTED":
        return {
          title: t("hitl.confirmRejectTitle", "Reject request?"),
          description: t("hitl.confirmRejectDescription", "Reject this request? The conversation will not proceed."),
          confirmLabel: t("hitl.reject", "Reject"),
          variant: "destructive" as const,
        };
      case "CANCEL":
        return surface === "group"
          ? {
              title: t("hitl.confirmCancelGroupTitle", "Cancel discussion?"),
              description: t("hitl.confirmCancelGroupDescription", "Cancel this discussion? Any in-progress work is aborted."),
              confirmLabel: t("hitl.confirmCancelGroupButton", "Cancel discussion"),
              variant: "destructive" as const,
            }
          : {
              title: t("hitl.confirmCancelTitle", "Cancel conversation?"),
              description: t("hitl.confirmCancelDescription", "Cancel this conversation? Any in-progress work is aborted."),
              confirmLabel: t("hitl.confirmCancelButton", "Cancel conversation"),
              variant: "destructive" as const,
            };
      default:
        return null;
    }
  })();

  return (
    <div
      data-testid="approval-banner"
      data-pause-type={isToolCall ? "TOOL_CALL" : "RULE"}
      className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-4 backdrop-blur-sm"
    >
      {/* Header */}
      <div className="mb-3 flex items-start gap-3">
        <div className="rounded-lg bg-amber-500/10 p-2">
          {isToolCall ? (
            <Wrench className="h-5 w-5 text-amber-500" aria-hidden="true" />
          ) : (
            <HandMetal className="h-5 w-5 text-amber-500" aria-hidden="true" />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-semibold text-foreground">
            {isToolCall
              ? t("hitl.toolApprovalRequired", "Tool Approval Required")
              : surface === "regular"
                ? t("hitl.awaitingHuman", "Awaiting Human Decision")
                : t("hitl.awaitingApproval", "Awaiting Approval")}
          </h3>
          {pauseReason && (
            <p className="mt-0.5 text-sm text-muted-foreground">{pauseReason}</p>
          )}
        </div>
      </div>

      {/* Metadata chips */}
      <div className="mb-3 flex flex-wrap gap-2 text-xs">
        {isValidDate(pausedAt) && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            <Clock className="h-3 w-3" aria-hidden="true" />
            {t("hitl.pausedAt", "Paused")}:{" "}
            {new Intl.DateTimeFormat(undefined, {
              dateStyle: "short",
              timeStyle: "medium",
            }).format(new Date(pausedAt!))}
          </span>
        )}
        {timeoutPolicy && timeoutPolicy !== "WAIT_INDEFINITELY" && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
            {timeoutPolicyLabel(t, timeoutPolicy)}
            {approvalTimeout && ` (${formatIsoDuration(approvalTimeout)})`}
          </span>
        )}
        {timeRemaining && (
          <span className={cn(
            "inline-flex items-center gap-1 rounded-full px-2.5 py-1 font-medium",
            timeRemaining.overdue
              ? "bg-destructive/10 text-destructive"
              : "bg-amber-500/10 text-amber-600",
          )}>
            <Clock className="h-3 w-3" aria-hidden="true" />
            {timeRemaining.overdue
              ? t("hitl.overdue", "Overdue")
              : `${t("hitl.timeRemaining", "Remaining")}: ${formatRemaining(timeRemaining.ms)}`}
          </span>
        )}
        {pausedPhaseName && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            {t("hitl.phase", "Phase")}: {pausedPhaseName}
          </span>
        )}
        {granularity && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            {t("hitl.granularity", "Granularity")}: {granularityLabel(t, granularity)}
          </span>
        )}
      </div>

      {/* Tool-call approvals (TOOL_CALL pause) */}
      {toolPause && (
        <div className="mb-3 space-y-2" data-testid="tool-call-approvals">
          <p className="text-xs font-medium text-muted-foreground">
            {t("hitl.toolCallsAwaiting", "Tool calls awaiting approval")}
          </p>
          {toolPause.calls.map((call) => (
            <ToolCallRow
              key={call.callId}
              call={call}
              state={callStates[call.callId] ?? {}}
              outcomeUnknown={outcomeUnknown.has(call.callId)}
              onToggle={(verdict) =>
                setCall(call.callId, {
                  verdict: callStates[call.callId]?.verdict === verdict ? undefined : verdict,
                })
              }
              onToggleAmend={() => setCall(call.callId, { showAmend: !callStates[call.callId]?.showAmend })}
              onAmendChange={(amend) => setCall(call.callId, { amend })}
            />
          ))}
          {toolPause.executedUngatedCalls.length > 0 && (
            <p className="text-xs text-muted-foreground" data-testid="executed-ungated">
              {t("hitl.executedUngated", "Already executed (ungated)")}: {toolPause.executedUngatedCalls.join(", ")}
            </p>
          )}
          {toolPause.outcomeUnknown.length > 0 && (
            <p className="flex items-center gap-1 text-xs text-destructive" data-testid="outcome-unknown">
              <ShieldAlert className="h-3.5 w-3.5" aria-hidden="true" />
              {t("hitl.outcomeUnknown", "A previous approval was interrupted mid-execution — its effect is unknown; verify externally before retrying.")}
            </p>
          )}
          <p className="text-[11px] text-muted-foreground">
            {t("hitl.toolApprovalHint", "Approve applies your per-call choices (calls you didn't change are approved). Reject rejects the whole batch.")}
          </p>
        </div>
      )}

      {/* Task-level approvals (TASK granularity) */}
      {isTaskGranularity && (
        <div className="mb-3 rounded-lg border border-border bg-background/50 p-3">
          <div className="mb-2 flex items-center justify-between">
            <p className="text-xs font-medium text-muted-foreground">
              {t("hitl.taskApprovals", "Task Approvals")}
            </p>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => handleSetAll("APPROVED")}
                className="text-xs text-emerald-600 hover:text-emerald-500 transition-colors"
                data-testid="approve-all-tasks"
              >
                {t("hitl.approveAll", "Approve All")}
              </button>
              <button
                type="button"
                onClick={() => handleSetAll("REJECTED")}
                className="text-xs text-destructive hover:text-destructive/80 transition-colors"
                data-testid="reject-all-tasks"
              >
                {t("hitl.rejectAll", "Reject All")}
              </button>
            </div>
          </div>
          <div className="space-y-1.5">
            {pendingTaskIds.map((taskId) => (
              <div
                key={taskId}
                className="flex items-center justify-between rounded-md bg-muted/50 px-3 py-2 text-sm"
              >
                <span className="truncate text-foreground">{taskId}</span>
                <div className="flex gap-1">
                  <button
                    type="button"
                    aria-pressed={taskApprovals[taskId] === "APPROVED"}
                    aria-label={`${t("hitl.approve", "Approve")} — ${taskId}`}
                    onClick={() => handleTaskToggle(taskId, "APPROVED")}
                    className={cn(
                      "rounded-md px-2 py-0.5 text-xs transition-colors",
                      taskApprovals[taskId] === "APPROVED"
                        ? "bg-emerald-500/20 text-emerald-600 font-medium"
                        : "bg-muted text-muted-foreground hover:bg-emerald-500/10",
                    )}
                    data-testid={`task-approve-${taskId}`}
                  >
                    {t("hitl.approve", "Approve")}
                  </button>
                  <button
                    type="button"
                    aria-pressed={taskApprovals[taskId] === "REJECTED"}
                    aria-label={`${t("hitl.reject", "Reject")} — ${taskId}`}
                    onClick={() => handleTaskToggle(taskId, "REJECTED")}
                    className={cn(
                      "rounded-md px-2 py-0.5 text-xs transition-colors",
                      taskApprovals[taskId] === "REJECTED"
                        ? "bg-destructive/20 text-destructive font-medium"
                        : "bg-muted text-muted-foreground hover:bg-destructive/10",
                    )}
                    data-testid={`task-reject-${taskId}`}
                  >
                    {t("hitl.reject", "Reject")}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Note toggle + input */}
      <div className="mb-3">
        <button
          type="button"
          onClick={() => setShowNote((p) => !p)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
          data-testid="toggle-note"
        >
          <ChevronDown
            className={cn("h-3 w-3 transition-transform", showNote && "rotate-180")}
            aria-hidden="true"
          />
          {t("hitl.note", "Add note (optional)")}
        </button>
        {showNote && (
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            aria-label={t("hitl.note", "Add note (optional)")}
            placeholder={t("hitl.notePlaceholder", "Add a note for the decision...")}
            className="mt-2 w-full resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            rows={2}
            maxLength={4096}
            data-testid="approval-note"
          />
        )}
      </div>

      {submitError && (
        <p className="mb-2 text-xs text-destructive" data-testid="approval-submit-error">
          {submitError}
        </p>
      )}

      {pauseDetailsPending && (
        <p className="mb-2 flex items-center gap-1 text-xs text-muted-foreground" data-testid="approval-details-pending">
          <Clock className="h-3 w-3 animate-pulse" aria-hidden="true" />
          {t("hitl.loadingApprovalDetails", "Loading approval details…")}
        </p>
      )}

      {/* Action buttons */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={isSubmitting || pauseDetailsPending}
          onClick={() => setConfirmAction("APPROVED")}
          className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed"
          data-testid="approve-button"
        >
          <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
          {t("hitl.approve", "Approve")}
        </button>
        <button
          type="button"
          disabled={isSubmitting}
          onClick={() => setConfirmAction("REJECTED")}
          className="inline-flex items-center gap-1.5 rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground transition-colors hover:bg-destructive/90 disabled:opacity-50 disabled:cursor-not-allowed"
          data-testid="reject-button"
        >
          <XCircle className="h-4 w-4" aria-hidden="true" />
          {t("hitl.reject", "Reject")}
        </button>
        {onCancel && (
          <button
            type="button"
            disabled={isSubmitting}
            onClick={() => setConfirmAction("CANCEL")}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-background px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
            data-testid="cancel-button"
          >
            {t("hitl.cancel", "Cancel")}
          </button>
        )}
      </div>

      {/* Confirmation gate — no destructive HITL action fires on a single click. */}
      {confirmDialog && (
        <AlertDialog
          open={confirmAction !== null}
          onOpenChange={(open) => {
            if (!open) setConfirmAction(null);
          }}
          title={confirmDialog.title}
          description={confirmDialog.description}
          confirmLabel={confirmDialog.confirmLabel}
          cancelLabel={t("hitl.confirmDismiss", "Go back")}
          variant={confirmDialog.variant}
          onConfirm={runConfirmedAction}
        />
      )}
    </div>
  );
}

/** A single gated tool call inside a TOOL_CALL pause — shows the redacted
 *  arguments and gate reason so a reviewer sees exactly what they are approving,
 *  with per-call APPROVE/REJECT and optional argument amendment. */
function ToolCallRow({
  call,
  state,
  outcomeUnknown,
  onToggle,
  onToggleAmend,
  onAmendChange,
}: {
  call: PendingToolCallView;
  state: CallState;
  outcomeUnknown: boolean;
  onToggle: (verdict: HitlVerdict) => void;
  onToggleAmend: () => void;
  onAmendChange: (amend: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="rounded-lg border border-border bg-background/50 p-3"
      data-testid={`tool-call-${call.callId}`}
    >
      <div className="mb-1.5 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-1.5">
            <Wrench className="h-3.5 w-3.5 text-amber-500" aria-hidden="true" />
            <span className="font-mono text-sm font-medium text-foreground">{call.toolName}</span>
            {call.source && (
              <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
                {call.source}
              </span>
            )}
            {call.gateReason && (
              <span className="rounded bg-amber-500/10 px-1.5 py-0.5 text-[10px] font-medium text-amber-600" title={t("hitl.gateReason", "Matched pattern")}>
                {call.gateReason}
              </span>
            )}
            {call.argsTruncated && (
              <span className="rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium text-destructive">
                {t("hitl.argsTruncated", "arguments truncated")}
              </span>
            )}
            {outcomeUnknown && (
              <span className="inline-flex items-center gap-0.5 rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium text-destructive">
                <ShieldAlert className="h-3 w-3" aria-hidden="true" />
                {t("hitl.outcomeUnknownShort", "outcome unknown")}
              </span>
            )}
          </div>
        </div>
        <div className="flex shrink-0 gap-1">
          <button
            type="button"
            aria-pressed={state.verdict === "APPROVED"}
            aria-label={`${t("hitl.approve", "Approve")} — ${call.toolName}`}
            onClick={() => onToggle("APPROVED")}
            className={cn(
              "rounded-md px-2 py-0.5 text-xs transition-colors",
              state.verdict === "APPROVED"
                ? "bg-emerald-500/20 text-emerald-600 font-medium"
                : "bg-muted text-muted-foreground hover:bg-emerald-500/10",
            )}
            data-testid={`tool-approve-${call.callId}`}
          >
            {t("hitl.approve", "Approve")}
          </button>
          <button
            type="button"
            aria-pressed={state.verdict === "REJECTED"}
            aria-label={`${t("hitl.reject", "Reject")} — ${call.toolName}`}
            onClick={() => onToggle("REJECTED")}
            className={cn(
              "rounded-md px-2 py-0.5 text-xs transition-colors",
              state.verdict === "REJECTED"
                ? "bg-destructive/20 text-destructive font-medium"
                : "bg-muted text-muted-foreground hover:bg-destructive/10",
            )}
            data-testid={`tool-reject-${call.callId}`}
          >
            {t("hitl.reject", "Reject")}
          </button>
        </div>
      </div>
      {call.arguments && (
        <pre
          className="max-h-40 overflow-auto rounded bg-muted/60 p-2 text-[11px] leading-relaxed text-foreground"
          data-testid={`tool-args-${call.callId}`}
        >
          {call.arguments}
        </pre>
      )}
      {!call.argsTruncated && (
        <div className="mt-1.5">
          <button
            type="button"
            onClick={onToggleAmend}
            className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
            data-testid={`tool-amend-toggle-${call.callId}`}
          >
            <Pencil className="h-3 w-3" aria-hidden="true" />
            {t("hitl.amendArguments", "Amend arguments (optional)")}
          </button>
          {state.showAmend && (
            <textarea
              value={state.amend ?? ""}
              onChange={(e) => onAmendChange(e.target.value)}
              aria-label={t("hitl.amendArguments", "Amend arguments (optional)")}
              placeholder={t("hitl.amendPlaceholder", 'Full replacement JSON object, e.g. {"to":"ops@acme.com"}. Leave blank to keep original.')}
              className="mt-1 w-full resize-none rounded-lg border border-border bg-background px-2 py-1.5 font-mono text-[11px] focus:outline-none focus:ring-2 focus:ring-ring"
              rows={3}
              // Cap at the byte budget (chars ≤ bytes in UTF-8) so the input can't
              // hold far more than the backend accepts; submit still does the exact
              // byte check (amendTooLarge) for multi-byte content.
              maxLength={AMENDED_ARGS_MAX_BYTES}
              data-testid={`tool-amend-${call.callId}`}
            />
          )}
        </div>
      )}
    </div>
  );
}
