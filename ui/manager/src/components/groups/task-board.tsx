import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  ClipboardList,
  Loader2,
  CheckCircle2,
  XCircle,
  Clock,
  Zap,
  Shield,
  ChevronDown,
  ChevronUp,
  Hand,
  UserPlus,
  Gavel,
} from "lucide-react";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type { AwardedBid, SharedTaskList } from "@/lib/api/groups";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface Task {
  id: string;
  subject: string;
  description?: string;
  assignedTo: string;
  displayName?: string;
  priority: number;
  /**
   * Display name (or id) of the member that filed this task via `addGroupTask`
   * (EDDI I5). Absent for the tasks the PLAN phase or the config authored — which
   * is every task in a group that has not enabled agent-filed tasks.
   *
   * Worth showing because a task nobody planned is the one a reviewer most needs
   * to notice: it is work the team discovered rather than work it was given.
   */
  filedBy?: string | null;
  /**
   * The winning bid this task was assigned by (I18) — only ever present on a
   * PERSISTED task list (`SharedTaskList.awardedBids`); the live `task_plan_created`
   * SSE payload carries no bid data at all, so a bid award only becomes visible
   * once the conversation is reloaded/re-fetched, same as the negotiation ledger.
   */
  awardedBid?: AwardedBid | null;
}

interface TaskVerification {
  passed: boolean;
  feedback: string;
}

interface TaskBoardProps {
  /** Task plan from task_plan_created SSE event */
  taskPlan: Task[] | null;
  /** Set of task IDs currently being executed */
  tasksInProgress: Set<string>;
  /** Set of task IDs that have been completed */
  tasksCompleted: Set<string>;
  /** Verification results per task ID */
  taskVerifications: Map<string, TaskVerification>;
  /** Whether the stream is still active */
  isStreaming: boolean;
  /** Set of task IDs awaiting HITL approval */
  tasksAwaitingApproval?: Set<string>;
}

type TaskStatus = "pending" | "in-progress" | "awaiting-approval" | "completed" | "verified";

/* ------------------------------------------------------------------ */
/*  Constants                                                          */
/* ------------------------------------------------------------------ */

const PRIORITY_CONFIG: Record<number, { label: string; className: string }> = {
  0: { label: "P0", className: "bg-red-500/15 text-red-700 dark:text-red-400 border-red-500/30" },
  1: { label: "P1", className: "bg-orange-500/15 text-orange-700 dark:text-orange-400 border-orange-500/30" },
  2: { label: "P2", className: "bg-blue-500/15 text-blue-700 dark:text-blue-400 border-blue-500/30" },
  3: { label: "P3", className: "bg-muted text-muted-foreground border-border" },
};

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function deriveStatus(
  taskId: string,
  tasksInProgress: Set<string>,
  tasksCompleted: Set<string>,
  taskVerifications: Map<string, TaskVerification>,
  tasksAwaitingApproval?: Set<string>,
): TaskStatus {
  if (taskVerifications.has(taskId)) return "verified";
  // A task awaiting human approval must surface in the Awaiting Approval column
  // even though it has already "completed" execution (both sets contain it).
  if (tasksAwaitingApproval?.has(taskId)) return "awaiting-approval";
  if (tasksCompleted.has(taskId)) return "completed";
  if (tasksInProgress.has(taskId)) return "in-progress";
  return "pending";
}

/* ------------------------------------------------------------------ */
/*  Sub-components                                                     */
/* ------------------------------------------------------------------ */

function TaskCard({
  task,
  status,
  verification,
}: {
  task: Task;
  status: TaskStatus;
  verification?: TaskVerification;
}) {
  const { t } = useTranslation();
  const avatarColor = hashColor(task.assignedTo);
  const initials = getInitials(task.assignedTo);
  const priority = PRIORITY_CONFIG[task.priority] ?? PRIORITY_CONFIG[3]!;
  const [expanded, setExpanded] = useState(false);

  return (
    <div
      data-testid={`task-card-${task.id}`}
      className={cn(
        "rounded-lg border p-3 transition-all duration-500 ease-out",
        "transform-gpu",
        // Status-specific styles
        status === "pending" && "bg-secondary/30 border-border",
        status === "in-progress" &&
          "bg-amber-500/10 border-amber-500/40 animate-[pulse-border_2s_ease-in-out_infinite]",
        status === "awaiting-approval" &&
          "bg-orange-500/10 border-orange-500/40",
        status === "completed" && "bg-sky-500/10 border-sky-500/40",
        status === "verified" && verification?.passed &&
          "bg-emerald-500/10 border-emerald-500/40",
        status === "verified" && verification && !verification.passed &&
          "bg-destructive/10 border-destructive/40",
      )}
    >
      {/* Subject */}
      <p className="text-sm font-bold text-foreground line-clamp-2" title={task.subject}>
        {task.subject}
      </p>
      {task.description && (
        <div className="mt-1">
          <p className={cn("text-xs text-muted-foreground", !expanded && "line-clamp-2")}>
            {task.description}
          </p>
          {task.description.length > 120 && (
            <button
              type="button"
              onClick={() => setExpanded(!expanded)}
              className="text-[10px] text-primary/70 hover:text-primary font-medium mt-0.5 transition-colors"
            >
              {expanded
                ? t("common.showLess", "Show less")
                : t("common.showMore", "Show more")}
            </button>
          )}
        </div>
      )}
      {/* Agent + Priority row */}
      <div className="mt-2 flex items-center justify-between gap-2">
        {/* Agent avatar + name */}
        <div className="flex items-center gap-1.5 min-w-0">
          <div
            className={cn(
              "flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[9px] font-bold text-white",
              avatarColor,
            )}
            title={task.assignedTo}
          >
            {initials}
          </div>
          <span className="text-xs text-muted-foreground truncate" title={task.displayName || task.assignedTo}>
            {task.displayName || task.assignedTo}
          </span>
        </div>

        {/* Priority badge */}
        <span
          className={cn(
            "inline-flex shrink-0 items-center rounded-full border px-1.5 py-0 text-[10px] font-semibold",
            priority.className,
          )}
        >
          {priority.label}
        </span>
      </div>

      {/* Filed by a member rather than planned — see Task.filedBy */}
      {task.filedBy && (
        <p
          className="mt-1.5 flex items-center gap-1 text-[10px] text-muted-foreground"
          data-testid={`task-filed-by-${task.id}`}
        >
          <UserPlus className="h-2.5 w-2.5 shrink-0" aria-hidden="true" />
          {t("taskBoard.filedBy", "Filed by {{agent}}", { agent: task.filedBy })}
        </p>
      )}

      {/* Won by bid auction (I18) */}
      {task.awardedBid && (
        <div
          className="mt-1.5 flex items-start gap-1 rounded-md bg-emerald-500/10 px-1.5 py-1 text-[10px] text-emerald-700 dark:text-emerald-400"
          data-testid={`task-award-${task.id}`}
          title={task.awardedBid.rationale}
        >
          <Gavel className="mt-0.5 h-2.5 w-2.5 shrink-0" aria-hidden="true" />
          <span>
            {t("taskBoard.wonByBid", "Won by bid — {{confidence}}% confidence, {{complexity}}", {
              confidence: Math.round(task.awardedBid.confidence * 100),
              complexity: task.awardedBid.estimatedComplexity,
            })}
          </span>
        </div>
      )}

      {/* Verification feedback */}
      {status === "verified" && verification && (
        <div
          className={cn(
            "mt-2 flex items-start gap-1.5 rounded-md px-2 py-1.5 text-xs",
            verification.passed
              ? "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
              : "bg-destructive/10 text-destructive",
          )}
        >
          {verification.passed ? (
            <CheckCircle2 className="h-3.5 w-3.5 shrink-0 mt-0.5" />
          ) : (
            <XCircle className="h-3.5 w-3.5 shrink-0 mt-0.5" />
          )}
          <span className="line-clamp-2" title={verification.feedback || undefined}>
            {verification.feedback ||
              t("taskBoard.verified", "Verified")}
          </span>
        </div>
      )}
    </div>
  );
}

function ColumnHeader({
  icon,
  label,
  count,
  colorClass,
}: {
  icon: React.ReactNode;
  label: string;
  count: number;
  colorClass: string;
}) {
  return (
    <div
      className={cn(
        "flex items-center justify-between rounded-t-xl px-3 py-2",
        colorClass,
      )}
    >
      <div className="flex items-center gap-2">
        {icon}
        <span className="text-sm font-semibold">{label}</span>
      </div>
      <Badge variant="secondary" className="text-[10px] px-1.5 py-0 bg-background/60">
        {count}
      </Badge>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Progress bar                                                       */
/* ------------------------------------------------------------------ */

function ProgressBar({
  total,
  completed,
  verified,
  isStreaming,
}: {
  total: number;
  completed: number;
  verified: number;
  isStreaming: boolean;
}) {
  const { t } = useTranslation();
  const done = completed + verified;
  const pct = total > 0 ? Math.round((done / total) * 100) : 0;

  return (
    <div className="mb-4" data-testid="task-board-progress">
      <div className="flex items-center justify-between mb-1.5">
        <span className="text-xs font-medium text-muted-foreground">
          {t("taskBoard.progress", "{{done}} of {{total}} tasks done", { done, total })}
        </span>
        <div className="flex items-center gap-2">
          {isStreaming && (
            <Loader2 className="h-3 w-3 animate-spin text-primary" />
          )}
          <span className="text-xs font-bold text-foreground tabular-nums">
            {done}/{total} ({pct}%)
          </span>
        </div>
      </div>
      <div
        className="h-2 w-full overflow-hidden rounded-full bg-secondary/50"
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={t("taskBoard.progress", "{{done}} of {{total}} tasks done", { done, total })}
      >
        <div
          className="h-full rounded-full bg-linear-to-r from-primary via-primary/80 to-emerald-500 transition-all duration-700 ease-out"
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Main component                                                     */
/* ------------------------------------------------------------------ */

export function TaskBoard({
  taskPlan,
  tasksInProgress,
  tasksCompleted,
  taskVerifications,
  isStreaming,
  tasksAwaitingApproval,
}: TaskBoardProps) {
  const { t } = useTranslation();
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem("eddi-task-board-collapsed") === "true"; } catch { return false; }
  });

  // Bucket tasks into columns
  const { pending, inProgress, awaitingApproval, completed, verified } = useMemo(() => {
    const buckets = {
      pending: [] as Task[],
      inProgress: [] as Task[],
      awaitingApproval: [] as Task[],
      completed: [] as Task[],
      verified: [] as Task[],
    };

    if (!taskPlan) return buckets;

    for (const task of taskPlan) {
      const status = deriveStatus(
        task.id,
        tasksInProgress,
        tasksCompleted,
        taskVerifications,
        tasksAwaitingApproval,
      );
      switch (status) {
        case "pending":
          buckets.pending.push(task);
          break;
        case "in-progress":
          buckets.inProgress.push(task);
          break;
        case "awaiting-approval":
          buckets.awaitingApproval.push(task);
          break;
        case "completed":
          buckets.completed.push(task);
          break;
        case "verified":
          buckets.verified.push(task);
          break;
      }
    }

    return buckets;
  }, [taskPlan, tasksInProgress, tasksCompleted, taskVerifications, tasksAwaitingApproval]);

  const total = taskPlan?.length ?? 0;

  // ------------------------------------------------------------------
  //  Empty state
  // ------------------------------------------------------------------
  if (!taskPlan) {
    return (
      <div
        className="flex flex-col items-center justify-center gap-3 py-16 text-muted-foreground"
        data-testid="task-board-empty"
      >
        <ClipboardList className="h-10 w-10 opacity-40" />
        <p className="text-sm text-center max-w-xs">
          {t(
            "taskBoard.emptyState",
            "Task plan will appear here when the moderator creates it",
          )}
        </p>
      </div>
    );
  }

  // ------------------------------------------------------------------
  //  Column definitions
  // ------------------------------------------------------------------
  const columns = [
    {
      key: "pending" as const,
      label: t("taskBoard.pending", "Pending"),
      icon: <Clock className="h-4 w-4" />,
      colorClass: "bg-muted/60 text-muted-foreground",
      tasks: pending,
    },
    {
      key: "in-progress" as const,
      label: t("taskBoard.inProgress", "Active"),
      icon: <Zap className="h-4 w-4 text-amber-500" />,
      colorClass: "bg-amber-500/15 text-amber-700 dark:text-amber-300",
      tasks: inProgress,
    },
    {
      key: "awaiting-approval" as const,
      label: t("taskBoard.awaitingApproval", "Awaiting Approval"),
      icon: <Hand className="h-4 w-4 text-orange-500" />,
      colorClass: "bg-orange-500/15 text-orange-700 dark:text-orange-300",
      tasks: awaitingApproval,
    },
    {
      key: "completed" as const,
      label: t("taskBoard.completed", "Done"),
      icon: <CheckCircle2 className="h-4 w-4 text-sky-500" />,
      colorClass: "bg-sky-500/15 text-sky-700 dark:text-sky-300",
      tasks: completed,
    },
    {
      key: "verified" as const,
      label: t("taskBoard.verified", "Verified"),
      icon: <Shield className="h-4 w-4 text-emerald-500" />,
      colorClass: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-300",
      tasks: verified,
    },
  ];

  // ------------------------------------------------------------------
  //  Render
  // ------------------------------------------------------------------
  return (
    <div data-testid="task-board" role="region" aria-label={t("taskBoard.title", "Task Board")}>
      {/* Section heading — clickable to toggle */}
      <button
        onClick={() => {
          setCollapsed((c) => {
            try { localStorage.setItem("eddi-task-board-collapsed", String(!c)); } catch { /* ignore */ }
            return !c;
          });
        }}
        className="w-full flex items-center gap-2 text-sm font-semibold text-foreground mb-3 hover:text-primary transition-colors cursor-pointer"
        aria-expanded={!collapsed}
        aria-controls="task-board-content"
      >
        <ClipboardList className="h-4 w-4 text-muted-foreground" />
        {t("taskBoard.title", "Task Board")}
        {collapsed ? (
          <ChevronDown className="h-3.5 w-3.5 text-muted-foreground ms-auto" />
        ) : (
          <ChevronUp className="h-3.5 w-3.5 text-muted-foreground ms-auto" />
        )}
      </button>

      {/* Progress bar — always visible */}
      <ProgressBar
        total={total}
        completed={completed.length}
        verified={verified.length}
        isStreaming={isStreaming}
      />

      {/* ---- Desktop: 4-column kanban ---- */}
      {!collapsed && (
      <div className="hidden md:grid md:grid-cols-5 gap-3" id="task-board-content">
        {columns.map((col) => (
          <div
            key={col.key}
            className="rounded-xl border border-border bg-card/50 overflow-hidden flex flex-col"
            data-testid={`task-column-${col.key}`}
            role="region"
            aria-label={col.label}
          >
            <ColumnHeader
              icon={col.icon}
              label={col.label}
              count={col.tasks.length}
              colorClass={col.colorClass}
            />
            <div className="flex-1 space-y-2 p-2 min-h-[120px]">
              {col.tasks.length === 0 && (
                <p className="text-xs text-muted-foreground/50 text-center pt-8">
                  —
                </p>
              )}
              {col.tasks.map((task) => (
                <TaskCard
                  key={task.id}
                  task={task}
                  status={col.key === "in-progress" ? "in-progress" : col.key}
                  verification={
                    col.key === "verified"
                      ? taskVerifications.get(task.id)
                      : undefined
                  }
                />
              ))}
            </div>
          </div>
        ))}
      </div>
      )}

      {/* ---- Mobile: vertical list with status indicators ---- */}
      {!collapsed && (
      <div className="md:hidden space-y-2">
        {columns.map((col) =>
          col.tasks.length > 0 ? (
            <div key={col.key}>
              {/* Section header */}
              <div className="flex items-center gap-2 mb-1.5">
                {col.icon}
                <span className="text-xs font-semibold text-foreground">
                  {col.label}
                </span>
                <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
                  {col.tasks.length}
                </Badge>
              </div>
              <div className="space-y-1.5 ps-1">
                {col.tasks.map((task) => (
                  <TaskCard
                    key={task.id}
                    task={task}
                    status={col.key === "in-progress" ? "in-progress" : col.key}
                    verification={
                      col.key === "verified"
                        ? taskVerifications.get(task.id)
                        : undefined
                    }
                  />
                ))}
              </div>
            </div>
          ) : null,
        )}
      </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Persisted variant                                                  */
/* ------------------------------------------------------------------ */

/**
 * TaskBoard fed from a conversation's persisted `taskList` rather than live SSE
 * state — for reloaded/completed discussions, and for surfaces that never hold
 * the stream (the Workforce board browsing history, the history viewer).
 *
 * One component rather than per-surface mappings: the status → column
 * derivation, the filed-by attribution and the awarded-bid lookup all have
 * details that are easy to drop when copied (the Manager had all three; the
 * Workforce surfaces had none of them — they showed no board at all).
 */
export function PersistedTaskBoard({
  taskList,
  memberDisplayNames,
}: {
  taskList: SharedTaskList;
  /** agentId → display name, so a filed-by attribution is a name and not a hex id. */
  memberDisplayNames?: Record<string, string>;
}) {
  const { t } = useTranslation();
  const taskPlan = useMemo(
    () =>
      taskList.tasks.map((task) => ({
        id: task.id,
        subject: task.subject,
        assignedTo:
          task.assignedDisplayName || task.assignedAgentId || t("taskBoard.unassigned", "Unassigned"),
        priority: task.priority,
        // Only the persisted task list carries this; the live `task_plan_created`
        // event predates agent-filed tasks and describes the PLAN phase's output,
        // which by definition has no filer.
        filedBy: memberDisplayNames?.[task.createdByAgentId ?? ""] ?? task.createdByAgentId ?? null,
        // Only present for a BID-mode task that received at least one bid — a
        // BID-mode task nobody bid on falls back to ROLE assignment and is
        // indistinguishable here from one configured as ROLE from the start.
        awardedBid: taskList.awardedBids?.[task.id] ?? null,
      })),
    [taskList, t, memberDisplayNames],
  );
  const tasksInProgress = useMemo(
    () => new Set(taskList.tasks.filter((task) => task.status === "IN_PROGRESS").map((task) => task.id)),
    [taskList],
  );
  const tasksCompleted = useMemo(
    () => new Set(taskList.tasks.filter((task) => task.status === "COMPLETED").map((task) => task.id)),
    [taskList],
  );
  const taskVerifications = useMemo(
    () =>
      new Map(
        taskList.tasks
          .filter((task) => task.status === "VERIFIED" || task.verificationNote != null)
          .map((task) => [task.id, { passed: task.verified, feedback: task.verificationNote || "" }] as const),
      ),
    [taskList],
  );
  // Tasks paused for per-task human approval — so the "Awaiting Approval" column
  // populates on a persisted TASK-granularity pause (undefined when none, to
  // preserve non-HITL behavior).
  const tasksAwaitingApproval = useMemo(() => {
    const s = new Set(
      taskList.tasks.filter((task) => task.status === "AWAITING_APPROVAL").map((task) => task.id),
    );
    return s.size ? s : undefined;
  }, [taskList]);

  return (
    <TaskBoard
      taskPlan={taskPlan}
      tasksInProgress={tasksInProgress}
      tasksCompleted={tasksCompleted}
      taskVerifications={taskVerifications}
      tasksAwaitingApproval={tasksAwaitingApproval}
      isStreaming={false}
    />
  );
}
