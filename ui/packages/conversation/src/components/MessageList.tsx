/* ──────────────────────────────────────────────
   MessageList — the transcript, following new content while the reader is at
   the bottom

   A live region (role="log") so replies are announced; aria-busy while a turn
   runs so a streamed reply is read once, not token by token. It follows new
   content only while the reader is at (or near) the bottom: scrolling up to
   re-read ends following, and a turn the user sends starts it again. Not
   windowed — transcripts are short enough that the throttled markdown of the
   streaming message (Message) is what costs, not the count.
   ────────────────────────────────────────────── */

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { ScrollToBottom } from "./ScrollToBottom";

export interface MessageListProps {
  children: ReactNode;
  /** The content that may grow — the messages, and whether a turn runs. */
  content: unknown;
  busy: boolean;
  /**
   * How many turns the user has sent. When it rises, the list follows again:
   * sending always brings the conversation back to the bottom.
   */
  sentCount: number;
  label: string;
  className?: string;
  testId?: string;
  /** Rendered between the transcript and the scroll button (status regions, toasts). */
  between?: ReactNode;
}

/** Within this many pixels of the end counts as "at the bottom". */
const AT_BOTTOM_PX = 24;

export function MessageList({ children, content, busy, sentCount, label, className, testId, between }: MessageListProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [showScrollBtn, setShowScrollBtn] = useState(false);
  /** Whether the view follows new content. */
  const stickToBottomRef = useRef(true);
  /**
   * Where the transcript was last scrolled to, by the reader or by us. Moving
   * up from here is what tells "the reader left the bottom" apart from a smooth
   * scroll still on its way down.
   */
  const lastScrollTopRef = useRef(0);
  const lastSentRef = useRef(sentCount);

  // A layout effect, so the jump lands before the browser paints the new
  // content instead of one frame after it. Following is an instant jump: a
  // smooth animation never caught up with the tokens.
  useLayoutEffect(() => {
    if (sentCount > lastSentRef.current) stickToBottomRef.current = true;
    lastSentRef.current = sentCount;
    const el = containerRef.current;
    if (!el || !stickToBottomRef.current) return;
    el.scrollTop = el.scrollHeight;
    lastScrollTopRef.current = el.scrollTop;
  }, [content, busy, sentCount]);

  // Content also grows without a state change: the throttled trailing render of
  // a streaming reply, lazy images, KaTeX and highlight.js arriving late, the
  // paused card filling in, the viewport shrinking under an on-screen keyboard.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const repin = () => {
      if (!stickToBottomRef.current) return;
      el.scrollTop = el.scrollHeight;
      lastScrollTopRef.current = el.scrollTop;
    };
    const mutations = typeof MutationObserver === "function" ? new MutationObserver(repin) : null;
    mutations?.observe(el, { childList: true, subtree: true, characterData: true });
    const resizes = typeof ResizeObserver === "function" ? new ResizeObserver(repin) : null;
    resizes?.observe(el);
    // `load` does not bubble, so listen in the capture phase for images.
    el.addEventListener("load", repin, true);
    return () => {
      mutations?.disconnect();
      resizes?.disconnect();
      el.removeEventListener("load", repin, true);
    };
  }, []);

  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= AT_BOTTOM_PX;
    const scrolledUp = el.scrollTop < lastScrollTopRef.current - 1;
    lastScrollTopRef.current = el.scrollTop;
    // Only the reader moving UP ends following. A smooth scroll towards the
    // bottom fires "not at bottom yet" events all the way down; treating those
    // as leaving turned following off right after the button was pressed.
    if (atBottom) stickToBottomRef.current = true;
    else if (scrolledUp) stickToBottomRef.current = false;
    setShowScrollBtn(!atBottom && !stickToBottomRef.current);
  }, []);

  const scrollToBottom = useCallback(() => {
    const el = containerRef.current;
    stickToBottomRef.current = true;
    setShowScrollBtn(false);
    if (!el) return;
    const reduced =
      typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    // Scroll the transcript itself: scrollIntoView would also scroll the host
    // page when the list is embedded in an iframe.
    if (typeof el.scrollTo === "function") {
      el.scrollTo({ top: el.scrollHeight, behavior: reduced ? "auto" : "smooth" });
    } else {
      el.scrollTop = el.scrollHeight;
    }
  }, []);

  return (
    <>
      <div
        className={className}
        ref={containerRef}
        onScroll={handleScroll}
        role="log"
        aria-live="polite"
        aria-label={label}
        aria-busy={busy}
        data-testid={testId}
      >
        {children}
      </div>
      {between}
      <div style={{ position: "relative" }}>
        <ScrollToBottom visible={showScrollBtn} onClick={scrollToBottom} />
      </div>
    </>
  );
}
