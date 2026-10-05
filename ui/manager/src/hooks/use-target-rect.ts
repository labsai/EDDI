import { useEffect, useState, useCallback } from "react";

interface TargetRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

/**
 * Hook to get the bounding rect of a target element, kept in sync.
 * Used by the tooltip to position itself relative to the target.
 */
export function useTargetRect(
  targetSelector: string,
  padding = 8
): TargetRect | null {
  const [rect, setRect] = useState<TargetRect | null>(null);

  const measure = useCallback(() => {
    if (!targetSelector) {
      setRect(null);
      return;
    }
    const el = document.querySelector(targetSelector);
    if (!el) {
      setRect(null);
      return;
    }
    const r = el.getBoundingClientRect();
    const next = {
      top: r.top - padding,
      left: r.left - padding,
      width: r.width + padding * 2,
      height: r.height + padding * 2,
    };
    // Keep the previous object when nothing moved: this runs on every DOM
    // mutation, and a fresh object each time would re-render the tour for each.
    setRect((prev) =>
      prev &&
      prev.top === next.top &&
      prev.left === next.left &&
      prev.width === next.width &&
      prev.height === next.height
        ? prev
        : next,
    );
  }, [targetSelector, padding]);

  useEffect(() => {
    measure();
    const handleUpdate = () => requestAnimationFrame(measure);
    window.addEventListener("resize", handleUpdate);
    window.addEventListener("scroll", handleUpdate, true);

    // The target may not exist yet — a lazy-loaded page is still fetching its
    // chunk — or may disappear on navigation. Re-measure when the DOM changes so
    // the rect appears as soon as the element does, rather than the caller
    // guessing a delay. Coalesced to one measure per frame.
    let frame = 0;
    const observer =
      targetSelector && typeof MutationObserver !== "undefined"
        ? new MutationObserver(() => {
            if (frame) return;
            frame = requestAnimationFrame(() => {
              frame = 0;
              measure();
            });
          })
        : null;
    observer?.observe(document.body, { childList: true, subtree: true });

    return () => {
      window.removeEventListener("resize", handleUpdate);
      window.removeEventListener("scroll", handleUpdate, true);
      observer?.disconnect();
      if (frame) cancelAnimationFrame(frame);
    };
  }, [measure, targetSelector]);

  return rect;
}
