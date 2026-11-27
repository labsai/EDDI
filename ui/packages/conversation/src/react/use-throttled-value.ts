import { useEffect, useState } from "react";

/** How often a streaming message re-parses its markdown, at most. */
export const STREAM_RENDER_INTERVAL_MS = 50;

/**
 * The value, refreshed at most once per `ms` while `active`. A streaming reply
 * grows by a token at a time, and parsing the whole markdown document (raw
 * HTML, sanitise, highlight) per token is quadratic over the reply. When it
 * stops being active the real value is returned at once, so the final text
 * never lags.
 */
export function useThrottledValue<T>(value: T, ms: number, active: boolean): T {
  const [shown, setShown] = useState(value);
  const [shownAt, setShownAt] = useState(0);
  // Leading edge, during render: the first token after a quiet spell shows in
  // the same pass instead of a frame later.
  if (active && value !== shown && Date.now() - shownAt >= ms) {
    setShown(value);
    setShownAt(Date.now());
  }
  // Trailing edge: whatever arrived inside the window is shown when it ends.
  useEffect(() => {
    if (!active || value === shown) return;
    const id = setTimeout(
      () => {
        setShown(value);
        setShownAt(Date.now());
      },
      Math.max(0, ms - (Date.now() - shownAt)),
    );
    return () => clearTimeout(id);
  }, [value, shown, shownAt, ms, active]);
  return active ? shown : value;
}
