import { useRef, useEffect, useState, useCallback } from "react";

interface SmartAutoScrollOptions {
  /** Dependencies that should trigger auto-scroll checks (e.g. [messages, isProcessing]) */
  deps?: unknown[];
  /** Distance in px from bottom to consider "near bottom" (default 80) */
  bottomThreshold?: number;
}

/**
 * Smart auto-scroll hook for chat windows:
 * 1. Instantly jumps to bottom on initial mount / conversation load.
 * 2. Smooth-scrolls to bottom automatically when new content arrives IF user is at bottom.
 * 3. Pauses auto-scroll if user manually scrolls up to read history.
 * 4. Shows floating "Scroll to bottom" button state with new-content indicator.
 */
export function useSmartAutoScroll<T extends HTMLElement = HTMLDivElement>({
  deps = [],
  bottomThreshold = 80,
}: SmartAutoScrollOptions = {}) {
  const scrollRef = useRef<T>(null);
  const isNearBottomRef = useRef(true);
  const isInitialMountRef = useRef(true);
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

  // Trigger auto-scroll on dependency updates if user is near bottom
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (isNearBottomRef.current) {
      const behavior = isInitialMountRef.current ? "auto" : "smooth";
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
