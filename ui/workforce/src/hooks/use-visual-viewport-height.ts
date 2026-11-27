import { useEffect } from "react";

/** The CSS variable the Workforce layout takes its height from. */
export const VIEWPORT_HEIGHT_VAR = "--wf-viewport-height";

/**
 * Keep `--wf-viewport-height` on `<html>` at the height of the visual viewport
 * while an on-screen keyboard shrinks it (T1.11).
 *
 * Chrome on Android honours `interactive-widget=resizes-content` (the shells'
 * viewport meta), so `100dvh` already shrinks with the keyboard there. iOS
 * Safari only shrinks the *visual* viewport: the layout stays full height and
 * the keyboard covers the composer at its bottom. This follows
 * `window.visualViewport` and sets the variable only while the visual viewport
 * is shorter than the window; otherwise it removes it, and the layout falls
 * back to `100dvh`.
 */
export function useVisualViewportHeight(): void {
  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const root = document.documentElement;
    const update = () => {
      // A keyboard leaves less than the window to show. A pinch-zoom does too,
      // and must not shrink the layout: only at scale 1.
      if (viewport.scale <= 1.01 && viewport.height < window.innerHeight - 1) {
        root.style.setProperty(VIEWPORT_HEIGHT_VAR, `${Math.round(viewport.height)}px`);
      } else {
        root.style.removeProperty(VIEWPORT_HEIGHT_VAR);
      }
    };
    update();
    viewport.addEventListener("resize", update);
    return () => {
      viewport.removeEventListener("resize", update);
      root.style.removeProperty(VIEWPORT_HEIGHT_VAR);
    };
  }, []);
}
