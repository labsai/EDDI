import { useEffect, useState, useCallback } from "react";

interface SpotlightOverlayProps {
  /** CSS selector for the target element */
  targetSelector: string;
  /** Extra padding around the cutout (px) */
  padding?: number;
  /** Called when clicking the dimmed overlay area (not the target) */
  onOverlayClick?: () => void;
}

interface TargetRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

/**
 * Full-viewport dim overlay with a transparent cutout around the target element.
 * Uses the box-shadow technique: the overlay element IS the cutout, and a giant
 * box-shadow dims everything else.
 */
export function SpotlightOverlay({
  targetSelector,
  padding = 8,
  onOverlayClick,
}: SpotlightOverlayProps) {
  const [rect, setRect] = useState<TargetRect | null>(null);

  const measure = useCallback(() => {
    const el = document.querySelector(targetSelector);
    if (!el) {
      setRect(null);
      return;
    }
    const r = el.getBoundingClientRect();
    setRect({
      top: r.top - padding,
      left: r.left - padding,
      width: r.width + padding * 2,
      height: r.height + padding * 2,
    });
  }, [targetSelector, padding]);

  // Measure + re-measure on scroll/resize (throttled via rAF)
  useEffect(() => {
    measure();

    let rafId = 0;
    const handleUpdate = () => {
      cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(measure);
    };

    window.addEventListener("resize", handleUpdate);
    window.addEventListener("scroll", handleUpdate, true);

    return () => {
      cancelAnimationFrame(rafId);
      window.removeEventListener("resize", handleUpdate);
      window.removeEventListener("scroll", handleUpdate, true);
    };
  }, [measure]);

  // Scroll target into view if not visible
  useEffect(() => {
    const el = document.querySelector(targetSelector);
    if (!el) return;
    const r = el.getBoundingClientRect();
    const inView =
      r.top >= 0 &&
      r.bottom <= window.innerHeight &&
      r.left >= 0 &&
      r.right <= window.innerWidth;
    if (!inView) {
      el.scrollIntoView({ behavior: "smooth", block: "center" });
      // Re-measure after scroll animation
      setTimeout(measure, 400);
    }
  }, [targetSelector, measure]);

  if (!rect) return null;

  return (
    <>
      {/* Clickable backdrop — covers the viewport behind the spotlight.
          Clicking the dim area is a no-op (doesn't advance) to avoid
          accidental step skips. Only the tooltip buttons advance. */}
      <div
        className="fixed inset-0 z-9997"
        onClick={onOverlayClick}
        data-testid="tour-overlay-backdrop"
        aria-hidden="true"
      />
      {/* Spotlight cutout — the box-shadow dims everything except this rect */}
      <div
        className="tour-spotlight"
        style={{
          position: "fixed",
          top: rect.top,
          left: rect.left,
          width: rect.width,
          height: rect.height,
          zIndex: 9998,
          pointerEvents: "none",
        }}
        data-testid="tour-spotlight"
        aria-hidden="true"
      />
    </>
  );
}
