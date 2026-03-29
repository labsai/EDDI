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
 *
 * For elements taller/wider than the viewport, the cutout is clamped to the
 * visible portion so it remains visible and the tooltip stays on screen.
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
    const vh = window.innerHeight;
    const vw = window.innerWidth;

    // Clamp the rect to the visible viewport so oversized elements
    // (e.g. long agent lists) don't push the spotlight out of bounds
    const clampedTop = Math.max(0, r.top) - padding;
    const clampedLeft = Math.max(0, r.left) - padding;
    const clampedBottom = Math.min(vh, r.bottom) + padding;
    const clampedRight = Math.min(vw, r.right) + padding;

    setRect({
      top: clampedTop,
      left: clampedLeft,
      width: clampedRight - clampedLeft,
      height: clampedBottom - clampedTop,
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
    const vh = window.innerHeight;
    const vw = window.innerWidth;

    // Only check if the top of the element is in view (for tall lists,
    // we just need the top portion visible, not the entire element)
    const topVisible = r.top >= -50 && r.top <= vh - 100;
    const leftVisible = r.left >= 0 && r.right <= vw;
    if (!topVisible || !leftVisible) {
      el.scrollIntoView({ behavior: "smooth", block: "start" });
      // Re-measure after scroll animation
      setTimeout(measure, 400);
    }
  }, [targetSelector, measure]);

  if (!rect) return null;

  return (
    <>
      {/* Clickable backdrop — covers the viewport behind the spotlight.
          Clicking the dim area is a no-op to avoid accidental step skips. */}
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
