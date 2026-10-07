/* ──────────────────────────────────────────────
   useTheme — Dark / Light / System theme hook
   ────────────────────────────────────────────── */

import { useEffect, useCallback, useRef } from "react";

export type ThemeMode = "dark" | "light" | "system";

const STORAGE_KEY = "eddi-chat-theme";

/**
 * Storage access throws when site data is blocked — a sandboxed iframe, a
 * browser's third-party storage block, some private modes. Unguarded, that
 * exception escaped the effect and left the whole widget blank; the remembered
 * theme is a convenience, so it simply goes unremembered instead.
 */
function readStoredTheme(): ThemeMode | null {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value === "dark" || value === "light" || value === "system" ? value : null;
  } catch {
    return null;
  }
}

function storeTheme(mode: ThemeMode): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, mode);
  } catch {
    // Not persisted — applied for this page only.
  }
}

function getSystemTheme(): "dark" | "light" {
  return window.matchMedia("(prefers-color-scheme: dark)").matches
    ? "dark"
    : "light";
}

function applyTheme(mode: ThemeMode): void {
  const resolved = mode === "system" ? getSystemTheme() : mode;
  document.documentElement.setAttribute("data-theme", resolved);
}

/**
 * `?theme=` from the address. An explicit instruction from whoever embedded the
 * widget has to beat a preference remembered from an earlier visit — otherwise
 * `?theme=light` stops working the first time somebody toggles the theme.
 */
function readUrlTheme(): ThemeMode | null {
  try {
    const value = new URLSearchParams(window.location.search).get("theme");
    return value === "dark" || value === "light" || value === "system" ? value : null;
  } catch {
    return null;
  }
}

/** dark -> light -> system -> dark. */
export function nextTheme(mode: ThemeMode): ThemeMode {
  return mode === "dark" ? "light" : mode === "light" ? "system" : "dark";
}

export function useTheme(initial: ThemeMode = "dark") {
  // The mode in force on this page. Storage cannot be the record of it: when
  // storage is blocked, setTheme("system") is never persisted, and a listener
  // reading storage would fall back to `initial` and ignore every later system
  // change.
  const currentMode = useRef<ThemeMode>(initial);

  // Apply on mount
  useEffect(() => {
    const mode = readUrlTheme() ?? readStoredTheme() ?? initial;
    currentMode.current = mode;
    applyTheme(mode);

    // Listen for system theme changes
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = () => {
      if (currentMode.current === "system") {
        applyTheme("system");
      }
    };
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, [initial]);

  const setTheme = useCallback((mode: ThemeMode) => {
    currentMode.current = mode;
    storeTheme(mode);
    applyTheme(mode);
  }, []);

  /** The mode in force (may be "system", which `data-theme` never says). */
  const getMode = useCallback(() => currentMode.current, []);

  return { setTheme, getMode };
}
