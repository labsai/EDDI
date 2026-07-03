import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  CheckCircle2,
  XCircle,
  Clock,
  AlertTriangle,
  HandMetal,
  ChevronDown,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { timeoutPolicyLabel, granularityLabel } from "@/lib/hitl-labels";
import { parseIsoDurationMs } from "@/lib/hitl-config";
import type { HitlVerdict } from "@/lib/api/hitl";

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
  /** Whether the mutation is in-flight. */
  isSubmitting?: boolean;
  /** Called when the user submits a decision. */
  onDecide: (verdict: HitlVerdict, note?: string, taskApprovals?: Record<string, string>) => void;
  /** Called when the user cancels. */
  onCancel?: () => void;
}

/** Format a millisecond duration as "1d 2h", "5m 3s", "42s", … (top 2 units). */
function formatMs(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  const d = Math.floor(totalSec / 86400);
  const h = Math.floor((totalSec % 86400) / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const parts: [number, string][] = [[d, "d"], [h, "h"], [m, "m"], [s, "s"]];
  const nonzero = parts.filter(([n]) => n > 0);
  const shown = (nonzero.length ? nonzero : [[0, "s"] as [number, string]]).slice(0, 2);
  return shown.map(([n, u]) => `${n}${u}`).join(" ");
}

/** Format an ISO-8601 duration (e.g. "PT15M", "P1DT2H") for display.
 *  Falls back to the raw string if it isn't a parseable positive duration. */
function formatDuration(iso?: string): string {
  if (!iso) return "";
  const ms = parseIsoDurationMs(iso);
  return ms == null ? iso : formatMs(ms);
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
  return formatMs(ms);
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
  isSubmitting,
  onDecide,
  onCancel,
}: ApprovalBannerProps) {
  const { t } = useTranslation();
  const [note, setNote] = useState("");
  const [showNote, setShowNote] = useState(false);
  const [taskApprovals, setTaskApprovals] = useState<Record<string, string>>({});

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

  const handleSubmit = (verdict: HitlVerdict) => {
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

  return (
    <div
      data-testid="approval-banner"
      className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-4 backdrop-blur-sm"
    >
      {/* Header */}
      <div className="mb-3 flex items-start gap-3">
        <div className="rounded-lg bg-amber-500/10 p-2">
          <HandMetal className="h-5 w-5 text-amber-500" aria-hidden="true" />
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-semibold text-foreground">
            {surface === "regular"
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
            {approvalTimeout && ` (${formatDuration(approvalTimeout)})`}
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

      {/* Action buttons */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={isSubmitting}
          onClick={() => handleSubmit("APPROVED")}
          className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed"
          data-testid="approve-button"
        >
          <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
          {t("hitl.approve", "Approve")}
        </button>
        <button
          type="button"
          disabled={isSubmitting}
          onClick={() => handleSubmit("REJECTED")}
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
            onClick={onCancel}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-background px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
            data-testid="cancel-button"
          >
            {t("hitl.cancel", "Cancel")}
          </button>
        )}
      </div>
    </div>
  );
}
