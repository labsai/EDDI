import { useState, useMemo, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import {
  buildCascadeSteps,
  isInternalTask,
  type PipelineEvent,
  type ToolTraceEntry,
} from "@/hooks/use-debug-events";
import { pairToolTrace } from "@/lib/tool-trace";
import {
  Zap,
  ChevronDown,
  ChevronUp,
  Check,
  Loader2,
  Circle,
  AlertTriangle,
  Wrench,
  Copy,
  X,
} from "lucide-react";
import { getExtensionIcon, getExtensionColor } from "@/lib/api/extensions";
import { CascadeStepTrace } from "@/components/cascade-step-trace";

// ==================== Types ====================

interface TaskSummary {
  taskType: string;
  taskId: string;
  index: number;
  status: "pending" | "running" | "complete" | "error";
  durationMs?: number;
  toolTrace?: ToolTraceEntry[];
  actions?: string[];
  confidence?: number;
  errorType?: string;
  errorSummary?: string;
}

// ==================== Helpers ====================

function getTaskLabel(taskType: string): string {
  return taskType.replace("ai.labs.", "").replace(/store$/, "");
}

function formatDuration(ms: number): string {
  if (ms < 1) return "<1ms";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function truncate(s: string, max: number): string {
  if (s.length <= max) return s;
  return s.slice(0, max) + "…";
}

function buildTaskSummaries(events: PipelineEvent[]): TaskSummary[] {
  const tasks: TaskSummary[] = [];
  const startedMap = new Map<string, number>(); // key → index in tasks

  for (const event of events) {
    const key = `${event.taskType}-${event.index}`;
    if (event.type === "task_start") {
      const idx = tasks.length;
      startedMap.set(key, idx);
      tasks.push({
        taskType: event.taskType,
        taskId: event.taskId,
        index: event.index,
        status: "running",
      });
    } else if (event.type === "task_complete") {
      const idx = startedMap.get(key);
      if (idx !== undefined) {
        tasks[idx] = {
          ...tasks[idx]!,
          status: "complete",
          durationMs: event.durationMs,
          toolTrace: event.toolTrace,
          actions: event.actions,
          confidence: event.confidence,
        };
      } else {
        // complete without start (e.g. historical data)
        tasks.push({
          taskType: event.taskType,
          taskId: event.taskId,
          index: event.index,
          status: "complete",
          durationMs: event.durationMs,
          toolTrace: event.toolTrace,
          actions: event.actions,
          confidence: event.confidence,
        });
      }
    } else if (event.type === "task_failed") {
      // Correlate to the running task by taskId (the failed payload has no index).
      const running = tasks.find(
        (tk) => tk.taskId === event.taskId && tk.status === "running",
      );
      const failed = {
        status: "error" as const,
        durationMs: event.durationMs,
        errorType: event.errorType,
        errorSummary: event.errorSummary,
      };
      if (running) {
        Object.assign(running, failed);
      } else {
        tasks.push({
          taskType: event.taskType,
          taskId: event.taskId,
          index: event.index,
          ...failed,
        });
      }
    }
  }

  return tasks;
}

interface ChatActivityProps {
  events: PipelineEvent[];
  isLive: boolean;
  totalSteps?: number;
  /** If true, shows internal pipeline steps (expressions, behavior_rules, etc.) even when no tool calls or errors occurred. Defaults to false. */
  showInternalSteps?: boolean;
  /**
   * Tool names from live `tool_call` SSE events, in call order, current turn
   * only. The per-task toolTrace only arrives at task_complete — without this
   * the status line said "Thinking…" through an entire tool-using turn.
   */
  liveToolCalls?: string[];
  /**
   * True once the model resumed writing after its last tool call. Without it the
   * newest call renders as running forever — there is no live per-call
   * completion event, so a finished answer still sat under a spinner.
   */
  liveToolsSettled?: boolean;
}

export function ChatActivity({ events, isLive, totalSteps, showInternalSteps = false, liveToolCalls, liveToolsSettled = false }: ChatActivityProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  /** The live status pill's own expansion — the running list of tool calls. */
  const [liveExpanded, setLiveExpanded] = useState(false);

  const rawTasks = useMemo(() => buildTaskSummaries(events), [events]);
  const cascadeSteps = useMemo(() => buildCascadeSteps(events), [events]);

  const totalDuration = useMemo(
    () => rawTasks.reduce((sum, task) => sum + (task.durationMs ?? 0), 0),
    [rawTasks],
  );

  const toolCallCount = useMemo(
    () =>
      rawTasks.reduce((sum, task) => {
        if (!task.toolTrace) return sum;
        return sum + task.toolTrace.filter((t) => t.type === "tool_call").length;
      }, 0),
    [rawTasks],
  );

  // Filter tasks: in end-user chat mode, hide internal Quarkus pipeline steps unless they have tool calls or errors
  const tasks = useMemo(() => {
    if (showInternalSteps) return rawTasks;
    return rawTasks.filter((task) => {
      const hasTools = task.toolTrace?.some((e) => e.type === "tool_call");
      const hasError = task.status === "error";
      // Case-INSENSITIVE: the runtime emits camelCase ids ("httpCalls") while
      // this set is lowercase — an exact has() silently filtered nothing in
      // production while lowercase-mocked tests stayed green.
      const isInternal = isInternalTask(task.taskType);
      return !isInternal || hasTools || hasError;
    });
  }, [rawTasks, showInternalSteps]);

  // The RESTING summary describes the VISIBLE list — filtering the rows while
  // summarising the unfiltered set re-created the exact complaint the filter
  // fixed: an operator greeting showed one visible row under a header still
  // boasting "46 steps". Three metrics deliberately stay raw because they
  // describe the TURN, not the list: the live progress fraction (a stable
  // "step 3 of 5" over the whole pipeline — a visible-only denominator would
  // crawl and jump as rows stream in), totalDuration (the turn really took
  // that long; hiding plumbing must not under-report latency), and the pulse
  // (hidden steps running are still work in progress).
  const completedCount = rawTasks.filter((t) => t.status === "complete").length;
  const hasRunning = rawTasks.some((t) => t.status === "running");

  const shouldPulse = isLive && hasRunning;

  // The tool the agent is on right now. Live `tool_call` events are the
  // primary signal — they arrive the moment each tool starts. The toolTrace
  // scan stays as a fallback for backends without the event (trace only
  // arrives at task_complete, so it lags a full task behind). Null until the
  // first tool call: the turn is purely "thinking" until then.
  const currentTool = useMemo(() => {
    if (liveToolCalls?.length) return liveToolCalls[liveToolCalls.length - 1]!;
    for (let i = events.length - 1; i >= 0; i--) {
      const calls = events[i]?.toolTrace?.filter((e) => e.type === "tool_call");
      if (calls?.length) return calls[calls.length - 1]!.tool;
    }
    return null;
  }, [liveToolCalls, events]);

  // Mid-turn the live event list runs ahead of the completed-task traces;
  // at rest the traces are authoritative. Never both — that double-counts.
  const liveToolCallCount = Math.max(toolCallCount, liveToolCalls?.length ?? 0);

  // A flat call→result list across all tasks, for the end-user resting view:
  // what the agent DID, without the pipeline-task shell around it.
  const toolPairs = useMemo(() => {
    // Sequence-walked, not index-zipped — see pairToolTrace for the
    // tool_error interleaving this survives.
    const pairs: { call?: ToolTraceEntry; result?: ToolTraceEntry; error?: ToolTraceEntry }[] = [];
    for (const task of rawTasks) {
      pairs.push(...pairToolTrace(task.toolTrace));
    }
    return pairs;
  }, [rawTasks]);

  // Auto-expand only what is actionable: errors and cascade traces. In the
  // debug surface tool calls still auto-expand (inspecting them is the point);
  // in end-user chat they stay behind the collapsed "N tool calls · Xs" pill —
  // auto-opening a 8-row list under every answer was noise, not information.
  useEffect(() => {
    if (
      cascadeSteps.length > 0 ||
      rawTasks.some((t) => t.status === "error") ||
      (showInternalSteps && toolCallCount > 0)
    ) {
      setExpanded(true);
    }
  }, [toolCallCount, cascadeSteps.length, rawTasks, showInternalSteps]);

  // In end-user chat mode, if there are no tool calls, cascade steps, or errors, and processing is done, stay hidden
  const hasLiveTools = isLive && (liveToolCalls?.length ?? 0) > 0;
  if (!showInternalSteps && !isLive && tasks.length === 0 && cascadeSteps.length === 0 && toolPairs.length === 0) return null;
  // A live tool_call can arrive before the first task event — it is activity.
  if (rawTasks.length === 0 && cascadeSteps.length === 0 && !hasLiveTools) return null;

  // End-user LIVE mode: a scrolling wall of internal step rows (an
  // OpenAPI-provisioned agent's pipeline is dozens of identical httpcalls
  // rows, and a row whose completion event never pairs up spins forever) says
  // nothing a user can act on. Show one honest status line instead — what the
  // agent is doing right now — and keep the step list for the debug surface
  // and the resting summary. Two things still break through to the full view
  // mid-turn, because they ARE actionable: a failed task (its classified
  // error must not hide behind a cheerful spinner) and a model-cascade trace
  // (escalations are the point of watching one).
  const hasErrorTask = rawTasks.some((task) => task.status === "error");
  if (!showInternalSteps && isLive && !hasErrorTask && cascadeSteps.length === 0) {
    // Everything the turn has called so far, newest last. Live events are the
    // primary record; the trace scan covers backends without them.
    const liveNames = liveToolCalls?.length
      ? liveToolCalls
      : toolPairs.flatMap((p) => (p.call?.tool ? [p.call.tool] : []));
    const expandable = liveNames.length > 0;
    return (
      <div className="flex justify-center px-4 py-1" data-testid="chat-activity">
        {/* w-fit: a one-word status stretched to 85% of the chat reads as a
            banner, not a status line. */}
        <div
          className={cn(
            "w-fit max-w-[85%] border border-primary/30 bg-primary/5",
            liveExpanded ? "rounded-xl" : "rounded-full",
          )}
        >
          <button
            type="button"
            onClick={() => setLiveExpanded((v) => !v)}
            // Disabled (not merely inert) while there is nothing to disclose:
            // an enabled no-op still takes keyboard focus, and aria-expanded
            // would describe a disclosure that does not exist.
            disabled={!expandable}
            className={cn(
              "flex items-center gap-2 px-3 py-2 text-xs",
              expandable ? "cursor-pointer" : "cursor-default",
            )}
            data-testid="chat-activity-live-status"
            aria-expanded={expandable ? liveExpanded : undefined}
          >
            <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-primary" />
            <span className="font-medium text-primary">
              {currentTool && !liveToolsSettled
                ? t("chat.activity.usingTool", "Using {{tool}}…", { tool: currentTool })
                : t("chat.thinking", "Thinking...")}
            </span>
            {liveToolCallCount > 0 && (
              <span className="text-muted-foreground">
                {t("chat.activity.toolCallsCount", "{{count}} tool calls", { count: liveToolCallCount })}
              </span>
            )}
            {expandable &&
              (liveExpanded ? (
                <ChevronUp className="h-3 w-3 shrink-0 text-muted-foreground/60" />
              ) : (
                <ChevronDown className="h-3 w-3 shrink-0 text-muted-foreground/60" />
              ))}
          </button>
          {liveExpanded && expandable && (
            <div
              className="max-h-48 space-y-0.5 overflow-y-auto border-t border-primary/20 px-3 pb-2 pt-1.5"
              data-testid="chat-activity-live-list"
            >
              {liveNames.map((name, i) => (
                <div key={`${name}-${i}`} className="flex items-center gap-1.5 text-[11px]">
                  {i === liveNames.length - 1 && !liveToolsSettled ? (
                    <Loader2 className="h-3 w-3 shrink-0 animate-spin text-primary" />
                  ) : (
                    <Check className="h-3 w-3 shrink-0 text-emerald-500" />
                  )}
                  <span className="min-w-0 truncate font-mono text-foreground/80">{name}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="flex justify-center px-4 py-1" data-testid="chat-activity">
      <div
        className={cn(
          // w-fit, not w-full: at rest this is a one-line summary ("3 tool
          // calls · 23.5s"), and stretching it to 85% of the chat made a
          // footnote look like a banner wider than the answer above it. It
          // still grows to max-w when expanded content needs the room.
          "w-fit max-w-[85%] rounded-xl border transition-all duration-300",
          shouldPulse
            ? "border-primary/30 bg-primary/5"
            : "border-border/50 bg-card/50",
        )}
      >
        {/* Summary bar — always visible */}
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className={cn(
            "flex w-full items-center gap-2 px-3 py-2 text-start text-xs transition-colors",
            "hover:bg-muted/30 rounded-xl",
          )}
          aria-expanded={expanded}
          data-testid="chat-activity-toggle"
        >
          {/* Status icon */}
          {shouldPulse ? (
            <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-primary" />
          ) : (
            <Zap className="h-3.5 w-3.5 shrink-0 text-primary" />
          )}

          {/* Summary text */}
          <span className="flex-1 text-muted-foreground">
            {shouldPulse ? (
              <span className="text-primary font-medium">
                {t("chat.activity.processing", "Processing…")}
                <span className="ms-1.5 text-muted-foreground font-normal">
                  {liveToolCallCount > 0
                    ? t("chat.activity.toolCallsCount", "{{count}} tool calls", { count: liveToolCallCount })
                    : `${completedCount}/${totalSteps ?? rawTasks.length}`}
                </span>
              </span>
            ) : !showInternalSteps && toolCallCount > 0 ? (
              // End-user resting header: lead with what the agent DID.
              // "1 step" was the pipeline's plumbing count leaking through —
              // meaningless next to "8 tool calls".
              <span>
                <span className="font-medium text-foreground">
                  {t("chat.activity.toolCallsCount", "{{count}} tool calls", { count: toolCallCount })}
                </span>
                <span className="mx-1.5 text-border">·</span>
                <span className="font-mono">{formatDuration(totalDuration)}</span>
              </span>
            ) : (
              <span>
                <span className="font-medium text-foreground">
                  {t("chat.activity.stepsCount", "{{count}} steps", { count: tasks.length })}
                </span>
                <span className="mx-1.5 text-border">·</span>
                <span className="font-mono">{formatDuration(totalDuration)}</span>
                {toolCallCount > 0 && (
                  <>
                    <span className="mx-1.5 text-border">·</span>
                    <span>
                      {t("chat.activity.toolCallsCount", "{{count}} tool calls", { count: toolCallCount })}
                    </span>
                  </>
                )}
              </span>
            )}
          </span>

          {/* Expand chevron */}
          {expanded ? (
            <ChevronUp className="h-3.5 w-3.5 shrink-0 text-muted-foreground/50" />
          ) : (
            <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted-foreground/50" />
          )}
        </button>

        {/* Expanded detail */}
        <div
          className={cn(
            "overflow-hidden transition-all duration-300",
            expanded ? "max-h-[80vh] opacity-100 overflow-y-auto" : "max-h-0 opacity-0",
          )}
        >
          <div className="border-t border-border/30 px-3 pb-2.5 pt-1.5 space-y-0.5">
            {cascadeSteps.length > 0 && (
              <CascadeStepTrace
                steps={cascadeSteps}
                testId="cascade-trace"
                className="mb-1 rounded-lg border border-purple-500/20 bg-purple-500/5 p-2"
              />
            )}
            {!showInternalSteps && toolPairs.length > 0 ? (
              // End-user detail: the flat list of tool calls, plus any failed
              // step (its error is actionable). The "1 step → langchain →
              // expand again" task shell said nothing a user could act on.
              <>
                {tasks
                  .filter((task) => task.status === "error")
                  .map((task, i) => (
                    <TaskRow key={`err-${task.taskType}-${task.index}-${i}`} task={task} />
                  ))}
                {toolPairs.map((pair, i) => (
                  <ToolCallRow key={i} call={pair.call} result={pair.result} />
                ))}
              </>
            ) : (
              tasks.map((task, i) => (
                <TaskRow key={`${task.taskType}-${task.index}-${i}`} task={task} />
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ==================== Task Row ====================

function TaskRow({ task }: { task: TaskSummary }) {
  const { t } = useTranslation();
  const [toolsExpanded, setToolsExpanded] = useState(false);
  const Icon = getExtensionIcon(task.taskType);
  const color = getExtensionColor(task.taskType);
  const label = getTaskLabel(task.taskType);

  const toolCalls = task.toolTrace?.filter((e) => e.type === "tool_call") ?? [];
  const hasTools = toolCalls.length > 0;

  return (
    <div>
      <div className="flex items-center gap-2 py-0.5 text-[11px]">
        {/* Status dot */}
        <div className="w-4 flex justify-center shrink-0">
          {task.status === "complete" && (
            <Check className="h-3 w-3 text-emerald-500" />
          )}
          {task.status === "running" && (
            <Loader2 className="h-3 w-3 animate-spin text-primary" />
          )}
          {task.status === "pending" && (
            <Circle className="h-3 w-3 text-muted-foreground/30" />
          )}
          {task.status === "error" && (
            <AlertTriangle className="h-3 w-3 text-destructive" />
          )}
        </div>

        {/* Type icon + label */}
        <Icon className={cn("h-3.5 w-3.5 shrink-0", color)} />
        <span className="font-medium text-foreground min-w-0 truncate">{label}</span>

        {/* Tool call badge */}
        {hasTools && (
          <button
            type="button"
            onClick={() => setToolsExpanded(!toolsExpanded)}
            className="inline-flex items-center gap-0.5 rounded-full bg-amber-500/10 px-1.5 py-0.5 text-[9px] font-medium text-amber-600 dark:text-amber-400 hover:bg-amber-500/20 transition-colors"
          >
            <Wrench className="h-2.5 w-2.5" />
            {toolCalls.length}
          </button>
        )}

        {/* Duration */}
        <span className="ms-auto shrink-0 font-mono text-[10px] text-muted-foreground">
          {task.status === "running"
            ? "…"
            : task.durationMs != null
              ? formatDuration(task.durationMs)
              : "—"}
        </span>
      </div>

      {/* Failure detail (classified errorType + redacted summary). Always
          rendered for a failed step: this used to require errorType/errorSummary,
          so a failure that arrived with neither collapsed to a red icon and
          nothing else. And "unknown" is the classifier's shrug, not information —
          shown alone it reads like a diagnosis ("UNKNOWN") while telling the
          admin nothing, so it is dropped in favour of the summary or a pointer
          to the server log. */}
      {task.status === "error" && (
        <div
          className="ms-8 mb-1 flex items-start gap-1.5 text-[10px] text-destructive"
          data-testid="task-error-detail"
        >
          {task.errorType && task.errorType.toLowerCase() !== "unknown" && (
            <span className="shrink-0 rounded bg-destructive/10 px-1 py-0.5 font-mono uppercase">
              {task.errorType}
            </span>
          )}
          <span>
            {task.errorSummary
              ? truncate(task.errorSummary, 140)
              : t("chat.stepFailedFallback", "This step failed. The server log has the full error.")}
          </span>
        </div>
      )}

      {/* Tool calls detail (nested) */}
      {hasTools && toolsExpanded && (
        <div className="ms-8 mb-1 space-y-0.5">
          {pairToolTrace(task.toolTrace).map((pair, ci) => (
            <ToolCallRow key={ci} call={pair.call} result={pair.result} error={pair.error} />
          ))}
        </div>
      )}
    </div>
  );
}


// ==================== Tool Call Row ====================

function ToolCallRow({
  call,
  result,
  error,
}: {
  call?: ToolTraceEntry;
  result?: ToolTraceEntry;
  error?: ToolTraceEntry;
}) {
  const [showDetail, setShowDetail] = useState(false);
  const hasResult = !!result?.result;
  // A refusal (tool_error), or a result whose contractual httpCode says the
  // call FAILED — both used to render the same green check as a success, so an
  // approved-then-400'd write was indistinguishable from one that worked.
  const failedHttpCode = (() => {
    if (!result?.result) return false;
    const match = /"httpCode"\s*:\s*(\d{3})/.exec(result.result);
    return match ? Number(match[1]) >= 300 : false;
  })();
  const failed = !!error || failedHttpCode;

  return (
    <div>
      <button
        type="button"
        onClick={() => setShowDetail(!showDetail)}
        className="flex w-full items-center gap-1.5 rounded px-1.5 py-0.5 text-[10px] text-start hover:bg-muted/50 transition-colors"
        data-testid="tool-call-row"
      >
        <Wrench className="h-2.5 w-2.5 shrink-0 text-amber-500" />
        <span className="font-medium text-foreground">
          {call?.tool ?? error?.tool ?? "—"}
        </span>
        {call?.arguments && (
          <span className="text-muted-foreground truncate">
            ({truncate(call.arguments, 40)})
          </span>
        )}
        <span className="ms-auto shrink-0">
          {failed ? (
            <X className="h-2.5 w-2.5 text-destructive" data-testid="tool-row-failed" />
          ) : hasResult ? (
            <Check className="h-2.5 w-2.5 text-emerald-500" />
          ) : call ? (
            <Loader2 className="h-2.5 w-2.5 animate-spin text-primary" />
          ) : (
            <X className="h-2.5 w-2.5 text-destructive" data-testid="tool-row-failed" />
          )}
        </span>
      </button>

      {/* Detail panel */}
      {showDetail && (
        <div className="ms-4 mb-1 rounded-md border border-border/50 bg-muted/30 p-2 text-[9px] space-y-1 overflow-x-auto">
          {call?.arguments && (
            <div>
              <div className="flex items-center justify-between">
                <span className="font-semibold text-muted-foreground uppercase tracking-wider">Args</span>
                <CopyButton text={call.arguments} />
              </div>
              <pre className="mt-0.5 whitespace-pre-wrap break-all text-foreground/80 font-mono">
                {formatJsonSafe(call.arguments)}
              </pre>
            </div>
          )}
          {result?.result && (
            <div>
              <div className="flex items-center justify-between">
                <span className="font-semibold text-muted-foreground uppercase tracking-wider">Result</span>
                <CopyButton text={result.result} />
              </div>
              <pre className="mt-0.5 whitespace-pre-wrap break-all text-foreground/80 font-mono max-h-32 overflow-y-auto">
                {formatJsonSafe(result.result)}
              </pre>
            </div>
          )}
          {error && (
            <div>
              <span className="font-semibold text-destructive uppercase tracking-wider">Refused</span>
              <pre className="mt-0.5 whitespace-pre-wrap break-all text-foreground/80 font-mono max-h-32 overflow-y-auto">
                {formatJsonSafe(error.error ?? error.result ?? "")}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ==================== Copy Button ====================

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        navigator.clipboard.writeText(text);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      }}
      className="rounded p-0.5 text-muted-foreground/50 hover:text-foreground transition-colors"
      title="Copy"
    >
      {copied ? (
        <Check className="h-2.5 w-2.5 text-emerald-500" />
      ) : (
        <Copy className="h-2.5 w-2.5" />
      )}
    </button>
  );
}

// ==================== Helpers ====================

function formatJsonSafe(str: string): string {
  try {
    return JSON.stringify(JSON.parse(str), null, 2);
  } catch {
    return str;
  }
}
