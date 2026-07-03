import { useTranslation } from "react-i18next";
import { Layers, ArrowUpRight, Check } from "lucide-react";
import { cascadeReasonText } from "@/lib/cascade-reason";
import type { CascadeStepInfo } from "@/hooks/use-debug-events";

/**
 * Shared model-cascade step trace: one row per attempted model tier. The
 * escalation reason (its own confidence vs threshold) renders on the step that
 * escalated, and an "accepted" marker on the final step. Rendered by both the
 * chat activity card and the debug-drawer pipeline trace — `testId` also drives
 * the per-step test ids (`${testId}-step-${n}`).
 */
export function CascadeStepTrace({
  steps,
  testId,
  className,
}: {
  steps: CascadeStepInfo[];
  testId: string;
  className?: string;
}) {
  const { t } = useTranslation();
  if (steps.length === 0) return null;

  return (
    <div className={className} data-testid={testId}>
      <div className="mb-1 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-purple-600 dark:text-purple-400">
        <Layers className="h-3 w-3" />
        {t("cascadeTrace.title", "Model Cascade")}
      </div>
      <div className="space-y-0.5">
        {steps.map((step, i) => {
          const isLast = i === steps.length - 1;
          return (
            <div
              key={step.stepIndex}
              className="flex flex-wrap items-center gap-1.5 text-[10px]"
              data-testid={`${testId}-step-${step.stepIndex}`}
            >
              <span className="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-purple-500/10 text-[9px] font-bold text-purple-600 dark:text-purple-400">
                {step.stepIndex + 1}
              </span>
              <span className="truncate font-mono text-foreground">
                {step.modelName ?? step.modelType ?? "—"}
              </span>
              {step.escalation && (
                <span className="inline-flex items-center gap-0.5 text-amber-600 dark:text-amber-400">
                  <ArrowUpRight className="h-2.5 w-2.5" />
                  {step.escalation.confidence != null && step.escalation.threshold != null
                    ? t("cascadeTrace.escalated", "conf {{c}} < {{th}}", {
                        c: step.escalation.confidence.toFixed(2),
                        th: step.escalation.threshold.toFixed(2),
                      })
                    : t("cascadeTrace.escalatedShort", "escalated")}
                  {step.escalation.reason && (
                    <span className="text-muted-foreground">
                      · {cascadeReasonText(t, step.escalation.reason)}
                    </span>
                  )}
                </span>
              )}
              {isLast && (
                <span className="inline-flex items-center gap-0.5 text-emerald-600 dark:text-emerald-400">
                  <Check className="h-2.5 w-2.5" />
                  {t("cascadeTrace.accepted", "accepted")}
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
