import { Check } from "lucide-react";
import { cn } from "@/lib/utils";

interface StepIndicatorProps {
  steps: Array<{ label: string }>;
  currentStep: number; // 0-indexed
}

function StepIndicator({ steps, currentStep }: StepIndicatorProps) {
  return (
    <div className="flex items-center justify-center gap-0">
      {steps.map((step, index) => {
        const isCompleted = index < currentStep;
        const isActive = index === currentStep;

        return (
          <div key={index} className="flex items-center gap-0">
            {/* Step circle + label */}
            <div className="flex flex-col items-center">
              <div
                className={cn(
                  "flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium transition-all duration-300",
                  isCompleted && "bg-indigo-500 text-white",
                  isActive &&
                    "bg-indigo-500 text-white ring-4 ring-indigo-500/20",
                  !isCompleted &&
                    !isActive &&
                    "bg-slate-200 text-slate-500 dark:bg-slate-700",
                )}
              >
                {isCompleted ? (
                  <Check className="h-4 w-4" />
                ) : (
                  <span>{index + 1}</span>
                )}
              </div>
              <span
                className={cn(
                  "mt-1.5 hidden text-xs sm:block",
                  isActive
                    ? "font-medium text-foreground"
                    : "text-muted-foreground",
                )}
              >
                {step.label}
              </span>
            </div>

            {/* Connecting line (not after last step) */}
            {index < steps.length - 1 && (
              <div
                className={cn(
                  "mx-1 h-0.5 min-w-8 max-w-20 flex-1 transition-colors duration-300",
                  index < currentStep
                    ? "bg-indigo-500"
                    : "bg-slate-200 dark:bg-slate-700",
                )}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

export { StepIndicator };
export type { StepIndicatorProps };
