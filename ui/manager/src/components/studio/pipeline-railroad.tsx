import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { useDebugStore } from "@/hooks/use-debug-events";
import { stepResourceRef, useStepResourceNames } from "@/hooks/use-step-resource-names";
import { Workflow } from "lucide-react";
import { getExtensionIcon, getExtensionColor, getExtensionLabel } from "@/lib/api/extensions";

// ==================== Types ====================

interface WorkflowStep {
  type: string;
  extensions: Record<string, unknown>;
  config: { uri?: string; [key: string]: unknown };
}

interface PipelineRailroadProps {
  workflowSteps: WorkflowStep[];
  selectedIndex: number | null;
  onSelectStage: (index: number) => void;
}

type StageStatus = "idle" | "running" | "complete" | "error";

/** Consecutive steps of one type, drawn under a single header when there are several. */
interface StepRun {
  type: string;
  start: number;
  steps: WorkflowStep[];
}

function groupConsecutive(steps: WorkflowStep[]): StepRun[] {
  const runs: StepRun[] = [];
  steps.forEach((step, index) => {
    const last = runs[runs.length - 1];
    if (last && last.type === step.type) last.steps.push(step);
    else runs.push({ type: step.type, start: index, steps: [step] });
  });
  return runs;
}

// ==================== Component ====================

/**
 * The Studio's pipeline list. Built to stay scannable at 30+ steps: one compact
 * row per step, the step's resource NAME rather than its 24-character id, and
 * consecutive steps of one type (a run of API calls, typically) gathered under
 * one header with a count instead of repeating the type on every row.
 */
export function PipelineRailroad({
  workflowSteps,
  selectedIndex,
  onSelectStage,
}: PipelineRailroadProps) {
  const { t } = useTranslation();
  const currentTurnEvents = useDebugStore((s) => s.currentTurnEvents);
  const resourceNames = useStepResourceNames(workflowSteps);
  const runs = useMemo(() => groupConsecutive(workflowSteps), [workflowSteps]);

  // Build stage status from live SSE events
  const stageStatuses = useMemo(() => {
    const statuses = new Map<number, StageStatus>();
    const indexByTaskId = new Map<string, number>();
    for (const event of currentTurnEvents) {
      if (event.type === "task_start") {
        statuses.set(event.index, "running");
        indexByTaskId.set(event.taskId, event.index);
      } else if (event.type === "task_complete") {
        statuses.set(event.index, "complete");
      } else if (event.type === "task_failed") {
        // The failed payload carries no index — recover it from the task_start.
        statuses.set(indexByTaskId.get(event.taskId) ?? event.index, "error");
      }
    }
    return statuses;
  }, [currentTurnEvents]);

  if (workflowSteps.length === 0) {
    return (
      <div className="px-4 py-10 text-center" data-testid="pipeline-railroad">
        <Workflow className="mx-auto h-8 w-8 text-muted-foreground/40" aria-hidden="true" />
        <p className="mt-2 text-sm text-muted-foreground">
          {t("studio.noPipeline", "No pipeline stages")}
        </p>
      </div>
    );
  }

  const renderStep = (step: WorkflowStep, idx: number, grouped: boolean) => {
    const Icon = getExtensionIcon(step.type);
    const label = getExtensionLabel(step.type, t);
    const status = stageStatuses.get(idx) ?? "idle";
    const isSelected = selectedIndex === idx;
    const ref = stepResourceRef(step.config?.uri);
    const name = ref ? resourceNames.get(ref.id) : undefined;
    const hasInlineConfig = !ref && Object.keys(step.config ?? {}).length > 0;

    // Primary line: what this step IS — its resource's name, else its id, else
    // (no resource at all) the step type. The id stays reachable in the tooltip.
    const primary = name ?? ref?.id ?? label;
    const secondary = grouped
      ? undefined
      : ref
        ? label
        : hasInlineConfig
          ? t("studio.inlineConfig", "Inline configuration")
          : undefined;
    const tooltip = [label, name, ref ? `${ref.id} · v${ref.version}` : undefined]
      .filter(Boolean)
      .join("\n");

    return (
      <li key={idx}>
        <button
          type="button"
          onClick={() => onSelectStage(idx)}
          aria-current={isSelected ? "true" : undefined}
          title={tooltip}
          className={cn(
            "group flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-start transition-colors",
            isSelected
              ? "bg-primary/10 ring-1 ring-inset ring-primary/30"
              : "hover:bg-muted/60",
          )}
          data-testid={`stage-${idx}`}
          data-status={status}
        >
          <span className="w-5 shrink-0 text-end text-[10px] tabular-nums text-muted-foreground/70">
            {idx + 1}
          </span>
          {grouped ? (
            // The group header already shows the type's icon: a row inside it
            // carries only the live status, as a dot.
            <span
              className={cn(
                "h-1.5 w-1.5 shrink-0 rounded-full",
                status === "running" && "bg-warn-500 animate-pulse",
                status === "complete" && "bg-emerald-500",
                status === "error" && "bg-destructive",
                status === "idle" && (isSelected ? "bg-primary" : "bg-border"),
              )}
              data-testid={`stage-${idx}-status`}
            />
          ) : (
            <span
              className={cn(
                "flex h-7 w-7 shrink-0 items-center justify-center rounded-md transition-colors",
                status === "running" && "bg-warn-500/15 animate-pulse",
                status === "complete" && "bg-emerald-500/15",
                status === "error" && "bg-destructive/15",
                status === "idle" && (isSelected ? "bg-primary/15" : "bg-muted"),
              )}
              data-testid={`stage-${idx}-status`}
            >
              <Icon className={cn("h-3.5 w-3.5", getExtensionColor(step.type))} aria-hidden="true" />
            </span>
          )}
          <span className="min-w-0 flex-1">
            <span
              className={cn(
                "block truncate text-sm",
                isSelected ? "font-semibold text-foreground" : "font-medium text-foreground/90",
                !name && ref && "font-mono text-xs",
              )}
              data-testid={`stage-${idx}-name`}
            >
              {primary}
            </span>
            {secondary && (
              <span className="block truncate text-xs text-muted-foreground">{secondary}</span>
            )}
          </span>
        </button>
      </li>
    );
  };

  return (
    <ol className="flex flex-col gap-0.5 px-2 pb-3" data-testid="pipeline-railroad">
      {runs.map((run) => {
        if (run.steps.length === 1) return renderStep(run.steps[0]!, run.start, false);
        const Icon = getExtensionIcon(run.type);
        return (
          <li key={`run-${run.start}`} className="py-1" data-testid={`stage-group-${run.start}`}>
            <div className="flex items-center gap-2 px-2 pb-1 pt-1">
              <Icon className={cn("h-3.5 w-3.5 shrink-0", getExtensionColor(run.type))} aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate text-xs font-semibold text-muted-foreground">
                {getExtensionLabel(run.type, t)}
              </span>
              <span
                className="rounded-full bg-muted px-1.5 text-[10px] font-semibold tabular-nums text-muted-foreground"
                data-testid={`stage-group-${run.start}-count`}
              >
                {run.steps.length}
              </span>
            </div>
            <ol className="ms-4 flex flex-col gap-0.5 border-s border-border ps-1">
              {run.steps.map((step, i) => renderStep(step, run.start + i, true))}
            </ol>
          </li>
        );
      })}
    </ol>
  );
}
