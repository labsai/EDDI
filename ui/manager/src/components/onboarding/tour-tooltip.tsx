import { useTranslation } from "react-i18next";
import { ArrowLeft, ArrowRight, X, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";
import type { TourPlacement } from "./tour-chapters";

interface TourTooltipProps {
  chapterTitleKey: string;
  titleKey: string;
  descriptionKey: string;
  currentStep: number;
  totalSteps: number;
  placement: TourPlacement;
  /** Position of the target element (used to place the tooltip) */
  targetRect: { top: number; left: number; width: number; height: number };
  onNext: () => void;
  onPrev: () => void;
  onSkip: () => void;
  isFirstStep: boolean;
  isLastStep: boolean;
}

const TOOLTIP_WIDTH = 340;
const TOOLTIP_GAP = 12;

/** Compute tooltip position based on target rect and preferred placement */
function computePosition(
  targetRect: TourTooltipProps["targetRect"],
  placement: TourPlacement
): { top: number; left: number; resolved: "top" | "bottom" | "left" | "right" } {
  const vw = window.innerWidth;
  const vh = window.innerHeight;

  // Auto-detect best placement based on available space
  let resolved: "top" | "bottom" | "left" | "right";
  if (placement === "auto") {
    const spaceBelow = vh - (targetRect.top + targetRect.height);
    const spaceAbove = targetRect.top;
    const spaceRight = vw - (targetRect.left + targetRect.width);
    const spaceLeft = targetRect.left;

    if (spaceBelow >= 200) resolved = "bottom";
    else if (spaceAbove >= 200) resolved = "top";
    else if (spaceRight >= TOOLTIP_WIDTH + TOOLTIP_GAP * 2) resolved = "right";
    else if (spaceLeft >= TOOLTIP_WIDTH + TOOLTIP_GAP * 2) resolved = "left";
    else resolved = "bottom";
  } else {
    resolved = placement;
  }

  let top = 0;
  let left = 0;

  switch (resolved) {
    case "bottom":
      top = targetRect.top + targetRect.height + TOOLTIP_GAP;
      left = targetRect.left + targetRect.width / 2 - TOOLTIP_WIDTH / 2;
      break;
    case "top":
      top = targetRect.top - TOOLTIP_GAP; // will be adjusted with transform
      left = targetRect.left + targetRect.width / 2 - TOOLTIP_WIDTH / 2;
      break;
    case "right":
      top = targetRect.top + targetRect.height / 2;
      left = targetRect.left + targetRect.width + TOOLTIP_GAP;
      break;
    case "left":
      top = targetRect.top + targetRect.height / 2;
      left = targetRect.left - TOOLTIP_WIDTH - TOOLTIP_GAP;
      break;
  }

  // Clamp horizontal to viewport
  left = Math.max(12, Math.min(left, vw - TOOLTIP_WIDTH - 12));
  // Clamp vertical
  top = Math.max(12, Math.min(top, vh - 60));

  return { top, left, resolved };
}

export function TourTooltip({
  chapterTitleKey,
  titleKey,
  descriptionKey,
  currentStep,
  totalSteps,
  placement,
  targetRect,
  onNext,
  onPrev,
  onSkip,
  isFirstStep,
  isLastStep,
}: TourTooltipProps) {
  const { t } = useTranslation();
  const { top, left, resolved } = computePosition(targetRect, placement);
  const progressPct = ((currentStep + 1) / totalSteps) * 100;

  return (
    <div
      className={cn(
        "tour-tooltip fixed z-9999",
        resolved === "top" && "-translate-y-full"
      )}
      style={{
        top,
        left,
        width: TOOLTIP_WIDTH,
      }}
      role="dialog"
      aria-label={t(titleKey)}
      data-testid="tour-tooltip"
    >
      <div className="rounded-xl border border-primary/20 bg-sidebar text-sidebar-foreground shadow-2xl shadow-black/30 overflow-hidden">
        {/* Progress bar */}
        <div className="h-1 w-full bg-sidebar-border">
          <div
            className="h-full bg-primary transition-all duration-500 ease-out"
            style={{ width: `${progressPct}%` }}
          />
        </div>

        <div className="p-4">
          {/* Chapter badge + step counter */}
          <div className="flex items-center justify-between mb-2">
            <span className="text-[11px] font-semibold uppercase tracking-wider text-primary">
              {t(chapterTitleKey)}
            </span>
            <span className="text-[11px] font-medium text-sidebar-foreground/50">
              {t("onboarding.tour.stepOf", {
                current: currentStep + 1,
                total: totalSteps,
              })}
            </span>
          </div>

          {/* Title */}
          <h3 className="text-[15px] font-semibold text-white leading-tight">
            {t(titleKey)}
          </h3>

          {/* Description */}
          <p className="mt-1.5 text-[13px] leading-relaxed text-sidebar-foreground/70">
            {t(descriptionKey)}
          </p>

          {/* Action buttons */}
          <div className="mt-4 flex items-center justify-between">
            {/* Back button */}
            <button
              onClick={onPrev}
              disabled={isFirstStep}
              className={cn(
                "inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors",
                isFirstStep
                  ? "invisible"
                  : "text-sidebar-foreground/60 hover:text-sidebar-foreground hover:bg-sidebar-accent/10"
              )}
              data-testid="tour-back"
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              {t("onboarding.tour.back")}
            </button>

            {/* Skip */}
            <button
              onClick={onSkip}
              className="inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium text-sidebar-foreground/40 hover:text-sidebar-foreground/70 transition-colors"
              data-testid="tour-skip"
            >
              <X className="h-3 w-3" />
              {t("onboarding.tour.skip")}
            </button>

            {/* Next / Finish */}
            <button
              onClick={onNext}
              className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-1.5 text-xs font-semibold text-primary-foreground shadow-sm transition-all hover:bg-primary/90 active:scale-[0.97]"
              data-testid="tour-next"
            >
              {isLastStep ? (
                <>
                  <Sparkles className="h-3.5 w-3.5" />
                  {t("onboarding.tour.finish")}
                </>
              ) : (
                <>
                  {t("onboarding.tour.next")}
                  <ArrowRight className="h-3.5 w-3.5" />
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
