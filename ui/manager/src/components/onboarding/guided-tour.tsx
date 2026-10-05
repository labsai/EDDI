import { useEffect, useCallback, useRef } from "react";
import { useLocation } from "react-router-dom";
import { createPortal } from "react-dom";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTargetRect } from "@/hooks/use-target-rect";
import { TOUR_CHAPTERS } from "./tour-chapters";
import { SpotlightOverlay } from "./spotlight-overlay";
import { TourTooltip } from "./tour-tooltip";

/** How long to wait for a step's target to appear before abandoning the chapter. */
const TARGET_WAIT_MS = 8000;

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
  const abandonChapter = useOnboarding((s) => s.abandonChapter);
  const { pathname } = useLocation();

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

  // The tour is only "live" while its target is on screen. Everything below that
  // hijacks the page — the global keys, the scroll lock — is attached only then.
  // It used to attach whenever a chapter was active, so a tour whose target was
  // not rendered (the user navigated away mid-tour, or the page is still loading)
  // was invisible yet still swallowed Enter app-wide and froze body scroll.
  const visible = !!(activeChapter && chapter && step && targetRect);

  // A chapter belongs to the page it started on: leave it and the tour ends,
  // without being recorded as completed.
  const startPathRef = useRef<string | null>(null);
  useEffect(() => {
    if (!activeChapter) {
      startPathRef.current = null;
      return;
    }
    if (startPathRef.current === null) startPathRef.current = pathname;
    else if (startPathRef.current !== pathname) abandonChapter();
  }, [activeChapter, pathname, abandonChapter]);

  // A target that never shows up (a step for a section the page does not render)
  // must not leave a tour parked invisibly forever. Wait for it, then move on.
  const targetMissing = !!(activeChapter && chapter && step && !targetRect);
  useEffect(() => {
    if (!targetMissing) return;
    // Skip the step rather than ending the chapter: the first dashboard step
    // points at the sidebar, which is not rendered on a phone while the drawer
    // is closed, and abandoning there made the whole tour unavailable on mobile.
    // Only the last step has nothing left to skip to.
    const timer = setTimeout(isLastStep ? abandonChapter : nextStep, TARGET_WAIT_MS);
    return () => clearTimeout(timer);
  }, [targetMissing, safeStep, activeChapter, isLastStep, nextStep, abandonChapter]);

  // Keyboard navigation
  useEffect(() => {
    if (!visible) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't intercept if user is typing in an input/textarea
      const el = e.target instanceof HTMLElement ? e.target : null;
      const tag = el?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el?.isContentEditable) return;
      // Enter on a focused button or link must do what that control says — on
      // the tooltip's Back and Skip it used to advance instead.
      const onControl =
        tag === "BUTTON" || tag === "A" || el?.getAttribute("role") === "button";

      switch (e.key) {
        case "ArrowRight":
          e.preventDefault();
          handleNext();
          break;
        case "Enter":
          if (onControl) return;
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
  }, [visible, handleNext, isFirstStep, prevStep, skipChapter]);

  // Prevent body scroll while the tour is on screen
  useEffect(() => {
    if (!visible) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [visible]);

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
