import { useState } from "react";
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

/** Format an ISO-8601 duration like "PT15M" into a human-readable string. */
function formatDuration(iso?: string): string {
  if (!iso) return "";
  const match = iso.match(/^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/i);
  if (!match) return iso;
  const parts: string[] = [];
  if (match[1]) parts.push(`${match[1]}h`);
  if (match[2]) parts.push(`${match[2]}m`);
  if (match[3]) parts.push(`${match[3]}s`);
  return parts.join(" ") || iso;
}

/** Calculate time remaining from pausedAt + duration. */
function getTimeRemaining(pausedAt?: string, duration?: string): string | null {
  if (!pausedAt || !duration) return null;
  const match = duration.match(/^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/i);
  if (!match) return null;
  const ms =
    (parseInt(match[1] || "0") * 3600 +
      parseInt(match[2] || "0") * 60 +
      parseInt(match[3] || "0")) *
    1000;
  const deadline = new Date(pausedAt).getTime() + ms;
  const remaining = deadline - Date.now();
  if (remaining <= 0) return "Overdue";
  const mins = Math.floor(remaining / 60_000);
  const secs = Math.floor((remaining % 60_000) / 1000);
  return mins > 0 ? `${mins}m ${secs}s` : `${secs}s`;
}

const TIMEOUT_LABELS: Record<string, string> = {
  WAIT_INDEFINITELY: "Wait Indefinitely",
  AUTO_APPROVE: "Auto-Approve",
  AUTO_REJECT: "Auto-Reject",
  ABORT: "Abort",
};

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

  const timeRemaining = getTimeRemaining(pausedAt, approvalTimeout);
  const isTaskGranularity = granularity === "TASK" && pendingTaskIds && pendingTaskIds.length > 0;

  const handleSubmit = (verdict: HitlVerdict) => {
    const finalNote = note.trim() || undefined;
    const finalTaskApprovals = isTaskGranularity && Object.keys(taskApprovals).length > 0
      ? taskApprovals
      : undefined;
    onDecide(verdict, finalNote, finalTaskApprovals);
  };

  const handleTaskToggle = (taskId: string, verdict: string) => {
    setTaskApprovals((prev) => ({ ...prev, [taskId]: verdict }));
  };

  const handleApproveAll = () => {
    if (!pendingTaskIds) return;
    const all: Record<string, string> = {};
    for (const id of pendingTaskIds) all[id] = "APPROVED";
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
        {pausedAt && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            <Clock className="h-3 w-3" aria-hidden="true" />
            {t("hitl.pausedAt", "Paused")}:{" "}
            {new Intl.DateTimeFormat(undefined, {
              dateStyle: "short",
              timeStyle: "medium",
            }).format(new Date(pausedAt))}
          </span>
        )}
        {timeoutPolicy && timeoutPolicy !== "WAIT_INDEFINITELY" && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
            {TIMEOUT_LABELS[timeoutPolicy] || timeoutPolicy}
            {approvalTimeout && ` (${formatDuration(approvalTimeout)})`}
          </span>
        )}
        {timeRemaining && (
          <span className={cn(
            "inline-flex items-center gap-1 rounded-full px-2.5 py-1 font-medium",
            timeRemaining === "Overdue"
              ? "bg-destructive/10 text-destructive"
              : "bg-amber-500/10 text-amber-600",
          )}>
            <Clock className="h-3 w-3" aria-hidden="true" />
            {timeRemaining === "Overdue"
              ? t("hitl.overdue", "Overdue")
              : `${t("hitl.timeRemaining", "Remaining")}: ${timeRemaining}`}
          </span>
        )}
        {pausedPhaseName && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            {t("hitl.phase", "Phase")}: {pausedPhaseName}
          </span>
        )}
        {granularity && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            {t("hitl.granularity", "Granularity")}: {granularity}
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
            <button
              type="button"
              onClick={handleApproveAll}
              className="text-xs text-amber-600 hover:text-amber-500 transition-colors"
              data-testid="approve-all-tasks"
            >
              {t("hitl.approveAll", "Approve All")}
            </button>
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
