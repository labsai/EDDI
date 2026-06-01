import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useDebugStore, type PipelineTurn, type PipelineEvent } from "@/hooks/use-debug-events";
import { useQuery } from "@tanstack/react-query";
import { getAuditTrail, type AuditEntry } from "@/lib/api/audit";
import { cn } from "@/lib/utils";
import { Clock, Zap, ChevronDown, AlertTriangle } from "lucide-react";


// ==================== Task Type Colors ====================

const TASK_TYPE_COLORS: Record<string, string> = {
  parser: "bg-blue-500/80",
  expressions: "bg-blue-500/80",
  behavior: "bg-violet-500/80",
  rules: "bg-violet-500/80",
  httpcalls: "bg-amber-500/80",
  apicalls: "bg-amber-500/80",
  langchain: "bg-emerald-500/80",
  llm: "bg-emerald-500/80",
  output: "bg-rose-500/80",
  property: "bg-cyan-500/80",
  propertysetter: "bg-cyan-500/80",
  mcpcalls: "bg-orange-500/80",
  dictionary: "bg-indigo-500/80",
  rag: "bg-teal-500/80",
};

function getTaskColor(taskType: string): string {
  const key = taskType.toLowerCase().replace("ai.labs.", "");
  return TASK_TYPE_COLORS[key] ?? "bg-muted-foreground/60";
}

function getTaskLabel(taskType: string): string {
  return taskType.replace("ai.labs.", "").replace(/store$/, "");
}

// ==================== Component ====================

interface PipelineTraceProps {
  conversationId: string | null;
}

export function PipelineTrace({ conversationId }: PipelineTraceProps) {
  const { t } = useTranslation();
  const turns = useDebugStore((s) => s.turns);
  const currentTurnEvents = useDebugStore((s) => s.currentTurnEvents);
  const selectedTurnIndex = useDebugStore((s) => s.selectedTurnIndex);
  const setSelectedTurn = useDebugStore((s) => s.setSelectedTurn);

  // Fetch historical audit data when no live SSE events available
  const { data: auditEntries, isError: auditError } = useQuery({
    queryKey: ["audit", "debugger", conversationId],
    queryFn: () => getAuditTrail(conversationId!, 0, 200),
    enabled: !!conversationId && turns.length === 0,
    staleTime: 30_000,
  });

  // Convert audit entries to pipeline turns for historical view
  const historicalTurns = useMemo(() => {
    if (!auditEntries?.length) return [];
    return auditEntriesToTurns(auditEntries);
  }, [auditEntries]);

  // Use live turns if available, otherwise historical
  const allTurns = turns.length > 0 ? turns : historicalTurns;
  const displayTurn =
    selectedTurnIndex !== null ? allTurns[selectedTurnIndex] : allTurns[allTurns.length - 1];

  // Show in-progress events if we're viewing the latest turn and there are current events
  const showLiveEvents =
    selectedTurnIndex === null && currentTurnEvents.length > 0;

  return (
    <div className="flex flex-col gap-3 p-3" data-testid="pipeline-trace">
      {/* Turn selector */}
      {allTurns.length > 1 && (
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-muted-foreground">
            {t("debugDrawer.turn", "Turn")}
          </span>
          <select
            value={selectedTurnIndex ?? "latest"}
            onChange={(e) => {
              const v = e.target.value;
              setSelectedTurn(v === "latest" ? null : Number(v));
            }}
            aria-label={t("debugDrawer.turn", "Turn")}
            className="rounded-md border border-input bg-card px-2 py-1 text-xs"
            data-testid="turn-selector"
          >
            <option value="latest">
              {t("debugDrawer.latest", "Latest")} ({t("debugDrawer.turn", "Turn")} {allTurns.length})
            </option>
            {allTurns.map((turn, idx) => (
              <option key={idx} value={idx}>
                {t("debugDrawer.turn", "Turn")} {idx + 1} — {formatDuration(turn.totalDurationMs)}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Error state — early return when no data at all */}
      {auditError && allTurns.length === 0 && !showLiveEvents && (
        <div className="flex flex-col items-center gap-2 py-6 text-center" data-testid="pipeline-trace-error">
          <AlertTriangle className="h-8 w-8 text-destructive/50" />
          <p className="text-sm text-muted-foreground">
            {t("debugDrawer.pipelineError", "Failed to load pipeline trace")}
          </p>
        </div>
      )}

      {/* Current / selected turn chart */}
      {!auditError || allTurns.length > 0 || showLiveEvents ? (
        showLiveEvents ? (
          <LiveEventsChart events={currentTurnEvents} />
        ) : displayTurn ? (
          <TurnChart turn={displayTurn} />
        ) : (
          <div className="flex flex-col items-center gap-2 py-6 text-center">
            <Zap className="h-8 w-8 text-muted-foreground/30" />
            <p className="text-sm text-muted-foreground">
              {t("debugDrawer.noPipeline", "Send a message to see the pipeline trace")}
            </p>
          </div>
        )
      ) : null}
    </div>
  );
}

// ==================== Turn Chart ====================

function TurnChart({ turn }: { turn: PipelineTurn }) {
  const { t } = useTranslation();
  // Group events into task pairs (start + complete)
  const tasks = useMemo(() => buildTaskBars(turn.events), [turn.events]);
  const maxDuration = Math.max(...tasks.map((bar) => bar.durationMs), 1);

  return (
    <div className="space-y-1.5">
      {/* Header */}
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <Clock className="h-3 w-3" />
          {t("debugDrawer.turn", "Turn")} {turn.turnIndex + 1}
        </span>
        <span className="font-mono">{formatDuration(turn.totalDurationMs)}</span>
      </div>

      {/* Bars */}
      {tasks.map((task, i) => (
        <TaskBar key={i} task={task} maxDuration={maxDuration} />
      ))}

      {/* Actions summary */}
      {turn.events.some((e) => e.actions?.length) && (
        <div className="flex flex-wrap gap-1 pt-1">
          <span className="text-[10px] font-medium text-muted-foreground me-1">
            {t("debugDrawer.actions", "Actions")}:
          </span>
          {turn.events
            .flatMap((e) => e.actions ?? [])
            .filter((v, i, a) => a.indexOf(v) === i)
            .map((action) => (
              <span
                key={action}
                className="rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary"
              >
                {action}
              </span>
            ))}
        </div>
      )}
    </div>
  );
}

// ==================== Live Events Chart ====================

function LiveEventsChart({ events }: { events: PipelineEvent[] }) {
  const { t } = useTranslation();
  const tasks = useMemo(() => buildTaskBars(events), [events]);
  const maxDuration = Math.max(...tasks.map((bar) => bar.durationMs || 100), 1);

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-1 text-xs text-primary animate-pulse">
        <Zap className="h-3 w-3" />
        {t("debugDrawer.processing", "Processing...")}
      </div>
      {tasks.map((task, i) => (
        <TaskBar key={i} task={task} maxDuration={maxDuration} />
      ))}
    </div>
  );
}

// ==================== Task Bar ====================

interface TaskBarData {
  taskType: string;
  durationMs: number;
  actions?: string[];
  confidence?: number;
  isRunning: boolean;
}

function TaskBar({ task, maxDuration }: { task: TaskBarData; maxDuration: number }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const widthPercent = Math.max((task.durationMs / maxDuration) * 100, 8);

  return (
    <div>
      <button
        onClick={() => setExpanded(!expanded)}
        aria-expanded={expanded}
        className="group flex w-full items-center gap-2 rounded-md px-1 py-0.5 text-start transition-colors hover:bg-muted/50"
        data-testid="task-bar"
      >
        {/* Label */}
        <span className="w-20 shrink-0 truncate text-[11px] font-medium text-foreground">
          {getTaskLabel(task.taskType)}
        </span>

        {/* Bar */}
        <div className="flex-1 h-4 rounded-sm bg-muted/30 overflow-hidden">
          <div
            className={cn(
              "h-full rounded-sm transition-all duration-500",
              getTaskColor(task.taskType),
              task.isRunning && "animate-pulse",
            )}
            style={{ width: `${widthPercent}%` }}
          />
        </div>

        {/* Duration */}
        <span className="w-14 shrink-0 text-end font-mono text-[10px] text-muted-foreground">
          {task.isRunning ? "..." : formatDuration(task.durationMs)}
        </span>

        <ChevronDown
          className={cn(
            "h-3 w-3 shrink-0 text-muted-foreground/50 transition-transform",
            expanded && "rotate-180",
          )}
        />
      </button>

      {expanded && (
        <div className="ms-22 mb-1 rounded-md border border-border bg-card p-2 text-[10px] text-muted-foreground space-y-0.5">
          {task.actions?.length ? (
            <p>
              <span className="font-medium">{t("debugDrawer.actions", "Actions")}:</span>{" "}
              {task.actions.join(", ")}
            </p>
          ) : null}
          {task.confidence != null && (
            <p>
              <span className="font-medium">{t("debugDrawer.confidence", "Confidence")}:</span>{" "}
              {(task.confidence * 100).toFixed(0)}%
            </p>
          )}
          <p>
            <span className="font-medium">{t("debugDrawer.duration", "Duration")}:</span>{" "}
            {formatDuration(task.durationMs)}
          </p>
        </div>
      )}
    </div>
  );
}

// ==================== Helpers ====================

function formatDuration(ms: number): string {
  if (ms < 1) return "<1ms";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function buildTaskBars(events: PipelineEvent[]): TaskBarData[] {
  const tasks: TaskBarData[] = [];
  const started = new Map<string, PipelineEvent>();

  for (const event of events) {
    const key = `${event.taskType}-${event.index}`;
    if (event.type === "task_start") {
      started.set(key, event);
    } else if (event.type === "task_complete") {
      const start = started.get(key);
      tasks.push({
        taskType: event.taskType,
        durationMs: event.durationMs ?? (start ? event.timestamp - start.timestamp : 0),
        actions: event.actions,
        confidence: event.confidence,
        isRunning: false,
      });
      started.delete(key);
    }
  }

  // Still-running tasks
  for (const [, start] of started) {
    tasks.push({
      taskType: start.taskType,
      durationMs: Date.now() - start.timestamp,
      isRunning: true,
    });
  }

  return tasks;
}

function auditEntriesToTurns(entries: AuditEntry[]): PipelineTurn[] {
  // Group audit entries by stepIndex
  const byStep = new Map<number, AuditEntry[]>();
  for (const entry of entries) {
    const step = entry.stepIndex ?? 0;
    if (!byStep.has(step)) byStep.set(step, []);
    byStep.get(step)!.push(entry);
  }

  const turns: PipelineTurn[] = [];
  for (const [stepIndex, stepEntries] of byStep) {
    const events: PipelineEvent[] = stepEntries.flatMap((entry) => [
      {
        type: "task_start" as const,
        taskId: entry.taskId,
        taskType: entry.taskType,
        index: entry.taskIndex,
        timestamp: new Date(entry.timestamp).getTime(),
      },
      {
        type: "task_complete" as const,
        taskId: entry.taskId,
        taskType: entry.taskType,
        index: entry.taskIndex,
        durationMs: entry.durationMs,
        actions: entry.actions ?? undefined,
        timestamp: new Date(entry.timestamp).getTime() + (entry.durationMs ?? 0),
      },
    ]);

    const totalDurationMs = stepEntries.reduce((sum, e) => sum + (e.durationMs ?? 0), 0);
    turns.push({
      turnIndex: stepIndex,
      events,
      totalDurationMs,
      startTime: new Date(stepEntries[0]!.timestamp).getTime(),
    });
  }

  return turns.sort((a, b) => a.turnIndex - b.turnIndex);
}
