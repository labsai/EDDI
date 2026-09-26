/* ──────────────────────────────────────────────
   useTheme — Dark / Light / System theme hook
   ────────────────────────────────────────────── */

import { useEffect, useCallback } from "react";

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

export function useTheme(initial: ThemeMode = "dark") {
  // Apply on mount
  useEffect(() => {
    const mode = readStoredTheme() ?? initial;
    applyTheme(mode);

    // Listen for system theme changes
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = () => {
      if ((readStoredTheme() ?? initial) === "system") {
        applyTheme("system");
      }
    };
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, [initial]);

  const setTheme = useCallback((mode: ThemeMode) => {
    storeTheme(mode);
    applyTheme(mode);
  }, []);

  return { setTheme };
}
