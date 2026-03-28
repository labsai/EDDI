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
    setRect({
      top: r.top - padding,
      left: r.left - padding,
      width: r.width + padding * 2,
      height: r.height + padding * 2,
    });
  }, [targetSelector, padding]);

  useEffect(() => {
    measure();
    const handleUpdate = () => requestAnimationFrame(measure);
    window.addEventListener("resize", handleUpdate);
    window.addEventListener("scroll", handleUpdate, true);
    return () => {
      window.removeEventListener("resize", handleUpdate);
      window.removeEventListener("scroll", handleUpdate, true);
    };
  }, [measure]);

  return rect;
}
