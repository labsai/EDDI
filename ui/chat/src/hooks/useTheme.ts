/* ──────────────────────────────────────────────
   useTheme — Dark / Light / System theme hook
   The storage, resolution and `data-theme` marking are @eddi/ui's, shared with
   the Manager's ThemeProvider; what stays here is the widget's own rule that
   `?theme=` beats a remembered choice.
   ────────────────────────────────────────────── */

import { useEffect, useCallback, useRef } from "react";
import {
  applyTheme,
  isThemeMode,
  nextTheme,
  readStoredTheme,
  resolveTheme,
  storeTheme,
  subscribeSystemTheme,
  type ThemeMode,
} from "@eddi/ui";

export type { ThemeMode };
export { nextTheme };

const STORAGE_KEY = "eddi-chat-theme";

/** The widget's stylesheet reads `data-theme` (src/styles/variables.css). */
function apply(mode: ThemeMode): void {
  applyTheme(resolveTheme(mode), "data-theme");
}

/**
 * `?theme=` from the address. An explicit instruction from whoever embedded the
 * widget has to beat a preference remembered from an earlier visit — otherwise
 * `?theme=light` stops working the first time somebody toggles the theme.
 */
function readUrlTheme(): ThemeMode | null {
  try {
    const value = new URLSearchParams(window.location.search).get("theme");
    return isThemeMode(value) ? value : null;
  } catch {
    return null;
  }
}

export function useTheme(initial: ThemeMode = "dark") {
  // The mode in force on this page. Storage cannot be the record of it: when
  // storage is blocked, setTheme("system") is never persisted, and a listener
  // reading storage would fall back to `initial` and ignore every later system
  // change.
  const currentMode = useRef<ThemeMode>(initial);

  // Apply on mount, and follow the operating system while the mode is "system".
  useEffect(() => {
    const mode = readUrlTheme() ?? readStoredTheme(STORAGE_KEY) ?? initial;
    currentMode.current = mode;
    apply(mode);
    return subscribeSystemTheme(() => {
      if (currentMode.current === "system") apply("system");
    });
  }, [initial]);

  const setTheme = useCallback((mode: ThemeMode) => {
    currentMode.current = mode;
    storeTheme(STORAGE_KEY, mode);
    apply(mode);
  }, []);

  /** The mode in force (may be "system", which `data-theme` never says). */
  const getMode = useCallback(() => currentMode.current, []);

  return { setTheme, getMode };
}
