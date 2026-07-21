import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useDebugStore, buildCascadeSteps, type PipelineTurn, type PipelineEvent } from "@/hooks/use-debug-events";
import { useQuery } from "@tanstack/react-query";
import { getAuditTrail, type AuditEntry } from "@/lib/api/audit";
import { cn, formatDuration } from "@/lib/utils";
import { CascadeStepTrace } from "@/components/cascade-step-trace";
import { Clock, Zap, ChevronDown, AlertTriangle, ArrowUp, ArrowDown } from "lucide-react";

// ==================== Task Type Colors ====================
// Softened colors
const TASK_TYPE_COLORS: Record<string, string> = {
  parser: "bg-blue-400/80",
  expressions: "bg-blue-400/80",
  behavior: "bg-violet-400/80",
  rules: "bg-violet-400/80",
  httpcalls: "bg-amber-400/80",
  apicalls: "bg-amber-400/80",
  langchain: "bg-emerald-400/80",
  llm: "bg-emerald-400/80",
  output: "bg-rose-400/80",
  property: "bg-cyan-400/80",
  propertysetter: "bg-cyan-400/80",
  mcpcalls: "bg-orange-400/80",
  dictionary: "bg-indigo-400/80",
  rag: "bg-teal-400/80",
};

function getTaskColor(taskType: string): string {
  const key = taskType.toLowerCase().replace("ai.labs.", "");
  return TASK_TYPE_COLORS[key] ?? "bg-muted-foreground/60";
}

function getTaskLabel(taskType: string): string {
  return taskType.replace("ai.labs.", "").replace(/store$/, "");
}

function fmtCost(n: number | undefined): string | null {
  if (n === undefined || n === null || n === 0) return null;
  if (n < 0.01) return `$${n.toFixed(4)}`;
  return `$${n.toFixed(2)}`;
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

  const { data: auditEntries, isError: auditError } = useQuery({
    queryKey: ["audit", "debugger", conversationId],
    queryFn: () => getAuditTrail(conversationId!, 0, 200),
    enabled: !!conversationId,
    staleTime: 30_000,
  });

  const historicalTurns = useMemo(() => {
    if (!auditEntries?.length) return [];
    return auditEntriesToTurns(auditEntries);
  }, [auditEntries]);

  const allTurns = turns.length > 0 ? turns : historicalTurns;
  const displayTurn =
    selectedTurnIndex !== null ? allTurns[selectedTurnIndex] : allTurns[allTurns.length - 1];

  const showLiveEvents =
    selectedTurnIndex === null && currentTurnEvents.length > 0;

  return (
    <div className="flex flex-col gap-3 p-3" data-testid="pipeline-trace">
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

      {auditError && allTurns.length === 0 && !showLiveEvents && (
        <div className="flex flex-col items-center gap-2 py-6 text-center" data-testid="pipeline-trace-error">
          <AlertTriangle className="h-8 w-8 text-destructive/50" />
          <p className="text-sm text-muted-foreground">
            {t("debugDrawer.pipelineError", "Failed to load pipeline trace")}
          </p>
        </div>
      )}

      {!auditError || allTurns.length > 0 || showLiveEvents ? (
        showLiveEvents ? (
          <LiveEventsChart events={currentTurnEvents} auditEntries={auditEntries ?? []} />
        ) : displayTurn ? (
          <TurnChart turn={displayTurn} auditEntries={auditEntries ?? []} />
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

function TurnChart({ turn, auditEntries }: { turn: PipelineTurn; auditEntries: AuditEntry[] }) {
  const { t } = useTranslation();
  const tasks = useMemo(() => buildTaskBars(turn.events, auditEntries, turn.turnIndex), [turn.events, auditEntries, turn.turnIndex]);
  const maxDuration = Math.max(...tasks.map((bar) => bar.durationMs), 1);
  const totalCost = tasks.reduce((sum, task) => sum + (task.auditEntry?.cost ?? 0), 0);
  
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <Clock className="h-3 w-3" />
          {t("debugDrawer.turn", "Turn")} {turn.turnIndex + 1}
        </span>
        <div className="flex items-center gap-2 font-mono">
          {totalCost > 0 && <span className="text-emerald-500/80 bg-emerald-500/10 px-1 rounded">{fmtCost(totalCost)}</span>}
          <span>{formatDuration(turn.totalDurationMs)}</span>
        </div>
      </div>

      <div className="space-y-1">
        {tasks.map((task, i) => (
          <div key={i} className="animate-in fade-in slide-in-from-bottom-2 fill-mode-both" style={{ animationDelay: `${i * 30}ms`, animationDuration: '300ms' }}>
            <TaskBar task={task} maxDuration={maxDuration} />
          </div>
        ))}
      </div>

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

      <CascadeStepTrace
        steps={buildCascadeSteps(turn.events)}
        testId="cascade-summary"
        className="rounded-md border border-purple-500/20 bg-purple-500/5 p-2"
      />
    </div>
  );
}

function LiveEventsChart({ events, auditEntries }: { events: PipelineEvent[]; auditEntries: AuditEntry[] }) {
  const { t } = useTranslation();
  const tasks = useMemo(() => buildTaskBars(events, auditEntries, undefined), [events, auditEntries]);
  const maxDuration = Math.max(...tasks.map((bar) => bar.durationMs || 100), 1);

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-1 text-xs text-primary animate-pulse">
        <Zap className="h-3 w-3" />
        {t("debugDrawer.processing", "Processing...")}
      </div>
      <div className="space-y-1">
        {tasks.map((task, i) => (
          <div key={i} className="animate-in fade-in slide-in-from-bottom-2 fill-mode-both" style={{ animationDelay: `${i * 30}ms`, animationDuration: '300ms' }}>
            <TaskBar task={task} maxDuration={maxDuration} />
          </div>
        ))}
      </div>
      <CascadeStepTrace
        steps={buildCascadeSteps(events)}
        testId="cascade-summary"
        className="rounded-md border border-purple-500/20 bg-purple-500/5 p-2"
      />
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
  auditEntry?: AuditEntry;
}

function TaskBar({ task, maxDuration }: { task: TaskBarData; maxDuration: number }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const widthPercent = Math.max((task.durationMs / maxDuration) * 100, 8);
  
  const audit = task.auditEntry;
  const isLLM = task.taskType.includes("langchain") || task.taskType.includes("llm");
  
  let inputTk = 0, outputTk = 0;
  if (audit?.llmDetail?.tokenUsage) {
    const tu = audit.llmDetail.tokenUsage as Record<string, number>;
    inputTk = tu.inputTokens ?? 0;
    outputTk = tu.outputTokens ?? 0;
  }
  
  const costLabel = fmtCost(audit?.cost);

  return (
    <div className="flex flex-col">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        aria-expanded={expanded}
        className="group flex w-full items-center gap-2 rounded-md px-1 py-0.5 text-start transition-colors hover:bg-muted/50"
        data-testid="task-bar"
      >
        <span className="w-20 shrink-0 truncate text-[11px] font-medium text-foreground">
          {getTaskLabel(task.taskType)}
        </span>

        <div className="flex-1 flex items-center relative h-5 rounded-sm bg-muted/30 overflow-hidden">
          <div
            className={cn(
              "absolute inset-y-0 start-0 rounded-sm transition-all duration-500",
              getTaskColor(task.taskType),
              task.isRunning && "animate-pulse",
            )}
            style={{ width: `${widthPercent}%` }}
          />
          <div className="relative z-10 flex items-center gap-1.5 px-2 text-[9px] font-mono whitespace-nowrap overflow-hidden">
            {isLLM && (inputTk > 0 || outputTk > 0) && (
              <span className="flex items-center gap-0.5 text-foreground/90 mix-blend-luminosity">
                <ArrowUp className="h-2.5 w-2.5" />{inputTk}
                <ArrowDown className="h-2.5 w-2.5 ms-0.5" />{outputTk}
              </span>
            )}
            {costLabel && (
              <span className="bg-background/40 px-1 rounded text-foreground/90 mix-blend-luminosity">
                {costLabel}
              </span>
            )}
          </div>
        </div>

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

      {/* Expandable Detail */}
      <div 
        className={cn(
          "grid transition-[grid-template-rows] duration-200 ease-in-out",
          expanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
        )}
      >
        <div className="overflow-hidden">
          <div className="ms-22 mb-1 mt-1 rounded-md border border-border bg-card p-2 text-[10px] text-muted-foreground space-y-1.5">
            {audit?.llmDetail && (
              <div className="flex flex-wrap gap-x-3 gap-y-1">
                {Boolean(audit.llmDetail.modelName) && (
                  <p><span className="font-medium">{t("debugDrawer.model", "Model")}:</span> {String(audit.llmDetail.modelName)}</p>
                )}
                {costLabel && (
                  <p><span className="font-medium">{t("debugDrawer.cost", "Cost")}:</span> {costLabel}</p>
                )}
                {(inputTk > 0 || outputTk > 0) && (
                  <p><span className="font-medium">{t("debugDrawer.tokens", "Tokens")}:</span> {inputTk} {t("debugDrawer.in", "in")} / {outputTk} {t("debugDrawer.out", "out")} = {inputTk + outputTk}</p>
                )}
              </div>
            )}
            
            {audit?.toolCalls && audit.toolCalls.length > 0 && (
              <div>
                <span className="font-medium">{t("debugDrawer.toolCalls", "Tools")}:</span>
                <div className="space-y-1 mt-1">
                  {audit.toolCalls.map((tc, idx) => (
                    <div key={idx} className="bg-muted/30 p-1 rounded font-mono text-[9px]">
                      <span className="text-primary font-semibold">{String(tc.name || "tool")}</span>
                      <span className="text-muted-foreground ms-1">
                        {JSON.stringify(tc.arguments || {}).substring(0, 80)}
                        {JSON.stringify(tc.arguments || {}).length > 80 ? "..." : ""}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

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

            {(audit?.input || audit?.output) && (
              <div className="grid grid-cols-1 gap-2 mt-2">
                {audit.input && (
                  <div>
                    <span className="font-medium block mb-0.5">{t("debugDrawer.input", "Input")}</span>
                    <pre className="max-h-24 overflow-auto bg-muted/40 p-1.5 rounded text-[9px] font-mono leading-tight whitespace-pre-wrap">
                      {JSON.stringify(audit.input, null, 2)}
                    </pre>
                  </div>
                )}
                {audit.output && (
                  <div>
                    <span className="font-medium block mb-0.5">{t("debugDrawer.output", "Output")}</span>
                    <pre className="max-h-24 overflow-auto bg-muted/40 p-1.5 rounded text-[9px] font-mono leading-tight whitespace-pre-wrap">
                      {JSON.stringify(audit.output, null, 2)}
                    </pre>
                  </div>
                )}
              </div>
            )}
            
            {(!audit?.input && !audit?.output && !audit?.toolCalls && !audit?.llmDetail && !task.actions?.length && task.confidence == null) && (
              <p>
                <span className="font-medium">{t("debugDrawer.duration", "Duration")}:</span>{" "}
                {formatDuration(task.durationMs)}
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ==================== Helpers ====================

function buildTaskBars(events: PipelineEvent[], auditEntries: AuditEntry[], stepIndex?: number): TaskBarData[] {
  const tasks: TaskBarData[] = [];
  const started = new Map<string, PipelineEvent>();
  
  const stepEntries = stepIndex !== undefined ? auditEntries.filter(a => a.stepIndex === stepIndex) : auditEntries;

  for (const event of events) {
    const key = `${event.taskType}-${event.index}`;
    if (event.type === "task_start") {
      started.set(key, event);
    } else if (event.type === "task_complete") {
      const start = started.get(key);
      const audit = stepEntries.find(a => a.taskType === event.taskType && a.taskIndex === event.index);
      
      tasks.push({
        taskType: event.taskType,
        durationMs: event.durationMs ?? (start ? event.timestamp - start.timestamp : 0),
        actions: event.actions,
        confidence: event.confidence,
        isRunning: false,
        auditEntry: audit,
      });
      started.delete(key);
    }
  }

  for (const [, start] of started) {
    const audit = stepEntries.find(a => a.taskType === start.taskType && a.taskIndex === start.index);
    tasks.push({
      taskType: start.taskType,
      durationMs: Date.now() - start.timestamp,
      isRunning: true,
      auditEntry: audit,
    });
  }

  return tasks;
}

function auditEntriesToTurns(entries: AuditEntry[]): PipelineTurn[] {
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
