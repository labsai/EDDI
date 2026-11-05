import { useRef, useEffect, useState, useCallback } from "react";

interface SmartAutoScrollOptions {
  /** Dependencies that should trigger auto-scroll checks (e.g. [messages, isProcessing]) */
  deps?: unknown[];
  /** Distance in px from bottom to consider "near bottom" (default 80) */
  bottomThreshold?: number;
}

/**
 * Two auto-scrolls closer together than this are treated as one stream of
 * updates, and the second is performed instantly rather than smoothly.
 *
 * Smooth scrolling is an animation, and a token stream restarts it every few
 * milliseconds. Each restart re-targets the animation at the new bottom, so the
 * container is in continuous programmatic motion for the whole reply — visibly
 * janky, and unfightable: a wheel gesture is overridden by the next token before
 * the user has moved a full line. Scrolling instantly during a stream leaves the
 * container stationary between updates, which is what makes moving away from the
 * bottom possible at all. A message arriving on its own still animates.
 */
const RAPID_UPDATE_MS = 400;

/** Keys that scroll a container upwards. */
const UPWARD_KEYS = new Set(["ArrowUp", "PageUp", "Home"]);

/**
 * Smart auto-scroll hook for chat windows:
 * 1. Instantly jumps to bottom on initial mount / conversation load.
 * 2. Scrolls to bottom when new content arrives IF the user is at the bottom.
 * 3. Pauses auto-scroll as soon as the user scrolls up — including mid-stream.
 * 4. Shows floating "Scroll to bottom" button state with new-content indicator.
 *
 * (3) recognises a wheel, a touch drag and the upward navigation keys. Dragging
 * the scrollbar itself is not among them: it produces nothing but a `scroll`
 * event, which is the one signal our own auto-scroll is indistinguishable from.
 * The "Scroll to bottom" control is the deliberate way back for that case.
 */
export function useSmartAutoScroll<T extends HTMLElement = HTMLDivElement>({
  deps = [],
  bottomThreshold = 80,
}: SmartAutoScrollOptions = {}) {
  const scrollRef = useRef<T>(null);
  const isNearBottomRef = useRef(true);
  const isInitialMountRef = useRef(true);
  const lastAutoScrollAtRef = useRef(0);
  const [showScrollFab, setShowScrollFab] = useState(false);
  const [hasNewContent, setHasNewContent] = useState(false);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = "smooth") => {
    const el = scrollRef.current;
    if (!el) return;
    if (typeof el.scrollTo === "function") {
      el.scrollTo({ top: el.scrollHeight, behavior });
    } else {
      el.scrollTop = el.scrollHeight;
    }
    isNearBottomRef.current = true;
    setShowScrollFab(false);
    setHasNewContent(false);
  }, []);

  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const distFromBottom = Math.max(
      0,
      el.scrollHeight - el.scrollTop - el.clientHeight,
    );
    const isAtBottom = distFromBottom <= bottomThreshold;
    isNearBottomRef.current = isAtBottom;
    setShowScrollFab(distFromBottom > 120);
    if (isAtBottom) {
      setHasNewContent(false);
    }
  }, [bottomThreshold]);

  /**
   * Records a deliberate upward gesture, independently of the scroll event it
   * causes.
   *
   * `handleScroll` alone cannot tell "the user scrolled up" from "our own
   * scrollTo is passing through this offset" — both arrive as the same `scroll`
   * event, so during a stream the user's intent was continuously overwritten by
   * the position our next auto-scroll had just produced, and the view snapped
   * back down. A wheel, touch or key gesture is unambiguous: nothing but a
   * person produces one. Detaching here means the very next token no longer
   * drags the viewport back.
   */
  const noteUserScrolledUp = useCallback(() => {
    const el = scrollRef.current;
    // A gesture that cannot actually move the container must not pause
    // following. Nothing scrolls, so no `scroll` event fires, so `handleScroll`
    // never runs to undo the pause — and the view would sit paused with the
    // "Scroll to bottom" control showing while the user is still at the bottom,
    // with new tokens no longer following. `scrollTop === 0` covers both ways
    // that happens: content that fits the viewport never leaves 0, and neither
    // does a container already scrolled to the top.
    if (!el || el.scrollTop <= 0) return;
    isNearBottomRef.current = false;
    setShowScrollFab(true);
  }, []);

  // The element the tracking state belongs to. The hook can outlive its
  // scroll container (the drawer unmounts its children while closed but stays
  // mounted itself); without this, closing while scrolled up leaves
  // isNearBottomRef=false, and REOPENING lands the fresh container at the TOP
  // because the stale ref routes the effect to the new-content branch.
  const trackedElRef = useRef<T | null>(null);

  // Gesture listeners, attached natively rather than as React props so the four
  // call sites keep their existing `ref` + `onScroll` wiring unchanged. `wheel`
  // and `touchmove` are passive — this only reads direction, never preventDefault.
  const listenerElRef = useRef<T | null>(null);
  const detachListenersRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) {
      // The container unmounted while the hook stayed mounted (the drawer does
      // exactly this). Release the listeners rather than keeping the detached
      // node alive through the detach closure until something else mounts.
      detachListenersRef.current?.();
      return;
    }
    if (listenerElRef.current === el) return;

    detachListenersRef.current?.();

    const onWheel = (e: WheelEvent) => {
      if (e.deltaY < 0) noteUserScrolledUp();
    };

    let touchStartY = 0;
    const onTouchStart = (e: TouchEvent) => {
      touchStartY = e.touches[0]?.clientY ?? 0;
    };
    const onTouchMove = (e: TouchEvent) => {
      const y = e.touches[0]?.clientY ?? 0;
      // A finger travelling DOWN the screen scrolls the content UP.
      if (y > touchStartY) noteUserScrolledUp();
      touchStartY = y;
    };

    // Only reaches us when focus is inside the container — a focused link or
    // button in a message, say. The container itself is not focusable, and
    // making it so would insert four new tab stops for a path the wheel and
    // touch handlers already cover.
    const onKeyDown = (e: KeyboardEvent) => {
      if (UPWARD_KEYS.has(e.key)) noteUserScrolledUp();
    };

    el.addEventListener("wheel", onWheel, { passive: true });
    el.addEventListener("touchstart", onTouchStart, { passive: true });
    el.addEventListener("touchmove", onTouchMove, { passive: true });
    el.addEventListener("keydown", onKeyDown);

    listenerElRef.current = el;
    detachListenersRef.current = () => {
      el.removeEventListener("wheel", onWheel);
      el.removeEventListener("touchstart", onTouchStart);
      el.removeEventListener("touchmove", onTouchMove);
      el.removeEventListener("keydown", onKeyDown);
      listenerElRef.current = null;
      detachListenersRef.current = null;
    };
    // No dependency array on purpose: the container mounts and remounts
    // independently of `deps`, and the identity check above makes re-running
    // this on every render free.
  });

  useEffect(() => () => detachListenersRef.current?.(), []);

  // Trigger auto-scroll on dependency updates if user is near bottom
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (trackedElRef.current !== el) {
      // A freshly mounted container starts at scrollTop 0 with no user scroll
      // history — treat it as at-bottom so the initial jump happens.
      trackedElRef.current = el;
      isNearBottomRef.current = true;
      setShowScrollFab(false);
      setHasNewContent(false);
    }
    if (isNearBottomRef.current) {
      const now = Date.now();
      const rapid = now - lastAutoScrollAtRef.current < RAPID_UPDATE_MS;
      lastAutoScrollAtRef.current = now;

      const behavior: ScrollBehavior =
        isInitialMountRef.current || rapid ? "auto" : "smooth";
      if (typeof el.scrollTo === "function") {
        el.scrollTo({ top: el.scrollHeight, behavior });
      } else {
        el.scrollTop = el.scrollHeight;
      }
      if (isInitialMountRef.current) {
        isInitialMountRef.current = false;
      }
    } else {
      setHasNewContent(true);
      setShowScrollFab(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return {
    scrollRef,
    showScrollFab,
    hasNewContent,
    scrollToBottom,
    handleScroll,
  };
}
