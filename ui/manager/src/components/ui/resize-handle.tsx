import { useCallback, useRef } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

interface ResizeHandleProps {
  /** Axis the handle resizes along. */
  direction: "horizontal" | "vertical";
  /**
   * Called with the delta in pixels, already expressed in *logical* terms:
   * positive means "the panel this handle belongs to grows".
   */
  onResize: (delta: number) => void;
  /** Names the handle for assistive tech, e.g. "Pipeline". */
  label: string;
  /** Current size, so the separator reports a value rather than only a role. */
  value?: number;
  min?: number;
  max?: number;
  className?: string;
}

/** How far one arrow-key press moves the handle. */
const STEP = 16;
/** Shift+arrow, for crossing a panel quickly. */
const COARSE_STEP = 64;

/**
 * A draggable — and keyboard-operable — resize handle for panel layouts.
 *
 * Two things beyond capturing the pointer, both of which belong here rather
 * than at the call sites:
 *
 * **Keyboard.** A `role="separator"` the user can move is a focusable widget,
 * and a drag target with no key handling cannot be used without a mouse at
 * all. Arrows move it by `STEP`, Shift+arrow by `COARSE_STEP`.
 *
 * **Direction.** The pointer reports screen deltas, but a caller thinks in
 * terms of its own panel growing or shrinking, and the two stop agreeing the
 * moment the document is RTL — the app ships Arabic, so on a horizontal handle
 * a raw `clientX` delta means the opposite of what the caller intends. The
 * inversion is resolved against the handle's own computed direction, so no
 * call site has to remember it.
 */
export function ResizeHandle({
  direction,
  onResize,
  label,
  value,
  min,
  max,
  className,
}: ResizeHandleProps) {
  const { t } = useTranslation();
  const lastPos = useRef(0);
  const dragging = useRef(false);
  const elementRef = useRef<HTMLDivElement>(null);

  const isHorizontal = direction === "horizontal";

  /** -1 when a rightward drag should mean "smaller" — a horizontal handle in RTL. */
  const sign = useCallback(() => {
    const el = elementRef.current;
    if (!isHorizontal || !el) return 1;
    // The `dir` attribute first, because that is how this app switches: i18n's
    // `applyDirection` sets it on <html> (src/i18n/config.ts). Computed style
    // is the fallback, for a direction set purely in CSS. That order also
    // keeps the rule testable — jsdom does not compute inherited `direction`
    // at all, and a rule with no test is a rule that rots unnoticed.
    const attr = el.closest("[dir]")?.getAttribute("dir");
    if (attr === "rtl") return -1;
    if (attr === "ltr") return 1;
    return getComputedStyle(el).direction === "rtl" ? -1 : 1;
  }, [isHorizontal]);

  const handlePointerDown = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      e.preventDefault();
      dragging.current = true;
      lastPos.current = isHorizontal ? e.clientX : e.clientY;
      e.currentTarget.setPointerCapture(e.pointerId);
    },
    [isHorizontal],
  );

  const handlePointerMove = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      if (!dragging.current) return;
      const current = isHorizontal ? e.clientX : e.clientY;
      const delta = current - lastPos.current;
      if (delta === 0) return;
      lastPos.current = current;
      onResize(delta * sign());
    },
    [isHorizontal, onResize, sign],
  );

  const handlePointerUp = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    dragging.current = false;
    // Capture is released on the element that took it; guarded because a
    // pointercancel can arrive after the capture is already gone.
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId);
    }
  }, []);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLDivElement>) => {
      const [less, more] = isHorizontal
        ? ["ArrowLeft", "ArrowRight"]
        : ["ArrowUp", "ArrowDown"];
      if (e.key !== less && e.key !== more) return;
      e.preventDefault();
      const step = e.shiftKey ? COARSE_STEP : STEP;
      onResize((e.key === more ? step : -step) * sign());
    },
    [isHorizontal, onResize, sign],
  );

  return (
    <div
      ref={elementRef}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      onKeyDown={handleKeyDown}
      className={cn(
        "shrink-0 select-none touch-none transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        isHorizontal
          ? "w-1 cursor-col-resize hover:bg-primary/30 active:bg-primary/50"
          : "h-1 cursor-row-resize hover:bg-primary/30 active:bg-primary/50",
        className,
      )}
      role="separator"
      tabIndex={0}
      aria-orientation={isHorizontal ? "vertical" : "horizontal"}
      aria-label={t("common.resizePanel", "Resize {{panel}}", { panel: label })}
      aria-valuenow={value}
      aria-valuemin={min}
      aria-valuemax={max}
      data-testid="resize-handle"
    />
  );
}
