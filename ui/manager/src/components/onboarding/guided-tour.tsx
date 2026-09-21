import { useEffect, useCallback } from "react";
import { createPortal } from "react-dom";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTargetRect } from "@/hooks/use-target-rect";
import { TOUR_CHAPTERS } from "./tour-chapters";
import { SpotlightOverlay } from "./spotlight-overlay";
import { TourTooltip } from "./tour-tooltip";

/**
 * Main tour orchestrator. Rendered once in AppLayout.
 * Reads active chapter + step from Zustand store, renders
 * spotlight overlay + tooltip via React Portal.
 */
export function GuidedTour() {
  const activeChapter = useOnboarding((s) => s.activeChapter);
  const currentStep = useOnboarding((s) => s.currentStep);
  const nextStep = useOnboarding((s) => s.nextStep);
  const prevStep = useOnboarding((s) => s.prevStep);
  const skipChapter = useOnboarding((s) => s.skipChapter);
  const completeChapter = useOnboarding((s) => s.completeChapter);

  // Get current chapter and step data
  const chapter = activeChapter ? TOUR_CHAPTERS[activeChapter] : null;
  const totalSteps = chapter?.steps.length ?? 0;

  // Guard: if currentStep is somehow out of bounds, use last valid step
  const safeStep = Math.min(currentStep, Math.max(0, totalSteps - 1));
  const step = chapter?.steps[safeStep] ?? null;
  const isFirstStep = safeStep === 0;
  const isLastStep = safeStep === totalSteps - 1;

  // Track target element position for tooltip placement
  const targetRect = useTargetRect(
    step?.target ?? "",
    step?.padding ?? 8
  );

  // Handle next: advance step or complete chapter if on last step
  const handleNext = useCallback(() => {
    if (isLastStep) {
      completeChapter();
    } else {
      nextStep();
    }
  }, [isLastStep, completeChapter, nextStep]);

  // Keyboard navigation
  useEffect(() => {
    if (!activeChapter) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't intercept if user is typing in an input/textarea
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;

      switch (e.key) {
        case "ArrowRight":
        case "Enter":
          e.preventDefault();
          handleNext();
          break;
        case "ArrowLeft":
          e.preventDefault();
          if (!isFirstStep) prevStep();
          break;
        case "Escape":
          e.preventDefault();
          skipChapter();
          break;
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [activeChapter, handleNext, isFirstStep, prevStep, skipChapter]);

  // Prevent body scroll while tour is active
  useEffect(() => {
    if (!activeChapter) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [activeChapter]);

  // Don't render anything if no tour is active or target element not found
  if (!activeChapter || !chapter || !step || !targetRect) return null;

  return createPortal(
    <>
      <SpotlightOverlay
        targetSelector={step.target}
        padding={step.padding ?? 8}
        // Overlay click is intentionally a no-op — users advance via tooltip buttons only.
        // This prevents accidental step-skips from stray clicks on the dim area.
      />
      <TourTooltip
        chapterTitleKey={chapter.titleKey}
        titleKey={step.titleKey}
        descriptionKey={step.descriptionKey}
        currentStep={safeStep}
        totalSteps={totalSteps}
        placement={step.placement}
        targetRect={targetRect}
        onNext={handleNext}
        onPrev={prevStep}
        onSkip={skipChapter}
        isFirstStep={isFirstStep}
        isLastStep={isLastStep}
      />
    </>,
    document.body
  );
}
