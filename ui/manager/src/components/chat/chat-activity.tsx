import { useState, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import type { PipelineEvent, ToolTraceEntry } from "@/hooks/use-debug-events";
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
} from "lucide-react";
import { getExtensionIcon, getExtensionColor } from "@/lib/api/extensions";

// ==================== Types ====================

interface ChatActivityProps {
  events: PipelineEvent[];
  isLive: boolean;
  totalSteps?: number;
}

interface TaskSummary {
  taskType: string;
  taskId: string;
  index: number;
  status: "pending" | "running" | "complete" | "error";
  durationMs?: number;
  toolTrace?: ToolTraceEntry[];
  actions?: string[];
  confidence?: number;
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
    }
  }

  return tasks;
}

// ==================== Component ====================

export function ChatActivity({ events, isLive, totalSteps }: ChatActivityProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(isLive);

  const tasks = useMemo(() => buildTaskSummaries(events), [events]);

  const totalDuration = useMemo(
    () => tasks.reduce((sum, task) => sum + (task.durationMs ?? 0), 0),
    [tasks],
  );

  const toolCallCount = useMemo(
    () =>
      tasks.reduce((sum, task) => {
        if (!task.toolTrace) return sum;
        return sum + task.toolTrace.filter((t) => t.type === "tool_call").length;
      }, 0),
    [tasks],
  );

  const completedCount = tasks.filter((t) => t.status === "complete").length;
  const hasRunning = tasks.some((t) => t.status === "running");

  // Auto-expand when live processing starts, auto-collapse when done
  const shouldPulse = isLive && hasRunning;

  if (tasks.length === 0) return null;

  return (
    <div className="flex justify-center px-4 py-1" data-testid="chat-activity">
      <div
        className={cn(
          "w-full max-w-[85%] rounded-xl border transition-all duration-300",
          shouldPulse
            ? "border-primary/30 bg-primary/5"
            : "border-border/50 bg-card/50",
        )}
      >
        {/* Summary bar — always visible */}
        <button
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
                  {completedCount}/{totalSteps ?? tasks.length}
                </span>
              </span>
            ) : (
              <span>
                <span className="font-medium text-foreground">
                  {tasks.length} {t("chat.activity.steps", "steps")}
                </span>
                <span className="mx-1.5 text-border">·</span>
                <span className="font-mono">{formatDuration(totalDuration)}</span>
                {toolCallCount > 0 && (
                  <>
                    <span className="mx-1.5 text-border">·</span>
                    <span>
                      {toolCallCount} {t("chat.activity.toolCalls", "tool calls")}
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
            {tasks.map((task, i) => (
              <TaskRow key={`${task.taskType}-${task.index}-${i}`} task={task} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// ==================== Task Row ====================

function TaskRow({ task }: { task: TaskSummary }) {
  const [toolsExpanded, setToolsExpanded] = useState(false);
  const Icon = getExtensionIcon(task.taskType);
  const color = getExtensionColor(task.taskType);
  const label = getTaskLabel(task.taskType);

  const toolCalls = task.toolTrace?.filter((e) => e.type === "tool_call") ?? [];
  const toolResults = task.toolTrace?.filter((e) => e.type === "tool_result") ?? [];
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

      {/* Tool calls detail (nested) */}
      {hasTools && toolsExpanded && (
        <div className="ms-8 mb-1 space-y-0.5">
          {toolCalls.map((call, ci) => {
            const result = toolResults[ci];
            return (
              <ToolCallRow key={ci} call={call} result={result} />
            );
          })}
        </div>
      )}
    </div>
  );
}

// ==================== Tool Call Row ====================

function ToolCallRow({
  call,
  result,
}: {
  call: ToolTraceEntry;
  result?: ToolTraceEntry;
}) {
  const [showDetail, setShowDetail] = useState(false);
  const hasResult = !!result?.result;

  return (
    <div>
      <button
        onClick={() => setShowDetail(!showDetail)}
        className="flex w-full items-center gap-1.5 rounded px-1.5 py-0.5 text-[10px] text-start hover:bg-muted/50 transition-colors"
        data-testid="tool-call-row"
      >
        <Wrench className="h-2.5 w-2.5 shrink-0 text-amber-500" />
        <span className="font-medium text-foreground">
          {call.tool}
        </span>
        {call.arguments && (
          <span className="text-muted-foreground truncate">
            ({truncate(call.arguments, 40)})
          </span>
        )}
        <span className="ms-auto shrink-0">
          {hasResult ? (
            <Check className="h-2.5 w-2.5 text-emerald-500" />
          ) : (
            <Loader2 className="h-2.5 w-2.5 animate-spin text-primary" />
          )}
        </span>
      </button>

      {/* Detail panel */}
      {showDetail && (
        <div className="ms-4 mb-1 rounded-md border border-border/50 bg-muted/30 p-2 text-[9px] space-y-1 overflow-x-auto">
          {call.arguments && (
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
