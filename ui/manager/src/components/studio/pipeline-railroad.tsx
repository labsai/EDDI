import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { useDebugStore } from "@/hooks/use-debug-events";
import {
  Circle,
  ChevronRight,
  type LucideIcon,
  FileCode,
  Bot,
  Workflow,
  Zap,
  MessageSquareCode,
  Settings,
  BookOpen,
  Wrench,
} from "lucide-react";

// ==================== Extension Type Icons ====================

const TYPE_ICONS: Record<string, LucideIcon> = {
  "ai.labs.parser": FileCode,
  "ai.labs.rules": BookOpen,
  "ai.labs.property": Settings,
  "ai.labs.apicalls": Zap,
  "ai.labs.llm": MessageSquareCode,
  "ai.labs.output": Bot,
  "ai.labs.output.template": Bot,
  "ai.labs.mcpcalls": Wrench,
};

// Labels are provided via i18n in the component below

const TYPE_COLORS: Record<string, string> = {
  "ai.labs.parser": "text-blue-500",
  "ai.labs.rules": "text-violet-500",
  "ai.labs.property": "text-cyan-500",
  "ai.labs.apicalls": "text-amber-500",
  "ai.labs.llm": "text-emerald-500",
  "ai.labs.output": "text-rose-500",
  "ai.labs.output.template": "text-rose-400",
  "ai.labs.mcpcalls": "text-orange-500",
};

// ==================== Types ====================

interface WorkflowStep {
  type: string;
  extensions: Record<string, unknown>;
  config: { uri?: string };
}

interface PipelineRailroadProps {
  workflowSteps: WorkflowStep[];
  selectedIndex: number | null;
  onSelectStage: (index: number) => void;
}

// ==================== Component ====================

export function PipelineRailroad({
  workflowSteps,
  selectedIndex,
  onSelectStage,
}: PipelineRailroadProps) {
  const { t } = useTranslation();
  const currentTurnEvents = useDebugStore((s) => s.currentTurnEvents);

  // Build stage status from live SSE events
  const stageStatuses = useMemo(() => {
    const statuses = new Map<number, "idle" | "running" | "complete" | "error">();
    for (const event of currentTurnEvents) {
      if (event.type === "task_start") {
        statuses.set(event.index, "running");
      } else if (event.type === "task_complete") {
        statuses.set(event.index, "complete");
      }
    }
    return statuses;
  }, [currentTurnEvents]);

  return (
    <div className="flex flex-col gap-0 py-4 px-3" data-testid="pipeline-railroad">
      {workflowSteps.map((step, idx) => {
        const Icon = TYPE_ICONS[step.type] ?? Workflow;
        const typeKey = step.type.replace("ai.labs.", "");
        const label = t(`studio.type.${typeKey}`, typeKey.charAt(0).toUpperCase() + typeKey.slice(1));
        const color = TYPE_COLORS[step.type] ?? "text-muted-foreground";
        const status = stageStatuses.get(idx) ?? "idle";
        const isSelected = selectedIndex === idx;
        const isLast = idx === workflowSteps.length - 1;

        // Extract resource name from URI
        const uri = step.config?.uri ?? "";
        const resourceId = uri.split("/").pop()?.split("?")[0] ?? "";

        return (
          <div key={idx}>
            {/* Stage button */}
            <button
              onClick={() => onSelectStage(idx)}
              aria-current={isSelected ? "true" : undefined}
              className={cn(
                "group flex w-full items-center gap-2.5 rounded-lg px-3 py-2.5 text-start transition-all",
                isSelected
                  ? "bg-primary/10 border border-primary/30"
                  : "hover:bg-muted/50 border border-transparent",
              )}
              data-testid={`stage-${idx}`}
            >
              {/* Status indicator */}
              <div className="relative shrink-0">
                <div
                  className={cn(
                    "flex h-9 w-9 items-center justify-center rounded-lg transition-colors",
                    status === "running" && "bg-amber-500/10 animate-pulse",
                    status === "complete" && "bg-emerald-500/10",
                    status === "idle" && "bg-muted/50",
                    isSelected && "bg-primary/15",
                  )}
                >
                  <Icon className={cn("h-4 w-4", color)} />
                </div>
                {/* Live indicator dot */}
                {status === "running" && (
                  <Circle className="absolute -inset-e-0.5 -top-0.5 h-2.5 w-2.5 fill-amber-500 text-amber-500 animate-pulse" />
                )}
                {status === "complete" && (
                  <Circle className="absolute -inset-e-0.5 -top-0.5 h-2.5 w-2.5 fill-emerald-500 text-emerald-500" />
                )}
              </div>

              {/* Label */}
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-foreground truncate">{label}</p>
                {resourceId && (
                  <p className="text-[10px] text-muted-foreground truncate font-mono">
                    {resourceId}
                  </p>
                )}
              </div>

              {/* Arrow */}
              <ChevronRight
                className={cn(
                  "h-4 w-4 shrink-0 transition-colors",
                  isSelected ? "text-primary" : "text-muted-foreground/30 group-hover:text-muted-foreground",
                )}
              />
            </button>

            {/* Connector line */}
            {!isLast && (
              <div className="flex justify-center py-0.5">
                <div
                  className={cn(
                    "w-0.5 h-4 rounded-full transition-colors",
                    stageStatuses.has(idx) ? "bg-emerald-500/40" : "bg-border",
                  )}
                />
              </div>
            )}
          </div>
        );
      })}

      {workflowSteps.length === 0 && (
        <div className="text-center py-8">
          <Workflow className="mx-auto h-8 w-8 text-muted-foreground/30" />
          <p className="mt-2 text-sm text-muted-foreground">
            {t("studio.noPipeline", "No pipeline stages")}
          </p>
        </div>
      )}
    </div>
  );
}
