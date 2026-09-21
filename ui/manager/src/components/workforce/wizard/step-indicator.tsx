import { useTranslation } from "react-i18next";
import { Check } from "lucide-react";
import { cn } from "@/lib/utils";

interface StepIndicatorProps {
  steps: Array<{ label: string }>;
  currentStep: number; // 0-indexed
}

function StepIndicator({ steps, currentStep }: StepIndicatorProps) {
  const { t } = useTranslation();

  return (
    <div
      className="flex items-center justify-center gap-0"
      role="list"
      aria-label={t("Workforce.wizard.stepIndicatorLabel", "Wizard progress")}
    >
      {steps.map((step, index) => {
        const isCompleted = index < currentStep;
        const isActive = index === currentStep;

        return (
          <div key={step.label} className="flex items-center gap-0" role="listitem">
            {/* Step circle + label */}
            <div className="flex flex-col items-center">
              <div
                className={cn(
                  "flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium transition-all duration-300",
                  isCompleted && "bg-primary text-primary-foreground",
                  isActive &&
                    "bg-primary text-primary-foreground ring-4 ring-ring/20",
                  !isCompleted &&
                    !isActive &&
                    "bg-muted text-muted-foreground",
                )}
                {...(isActive ? { "aria-current": "step" as const } : {})}
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
                  "ms-1 me-1 h-0.5 min-w-8 max-w-20 flex-1 transition-colors duration-300",
                  index < currentStep
                    ? "bg-primary"
                    : "bg-muted",
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
