/* ──────────────────────────────────────────────
   useTheme — Dark / Light / System theme hook
   ────────────────────────────────────────────── */

import { useEffect, useCallback } from "react";

export type ThemeMode = "dark" | "light" | "system";

const STORAGE_KEY = "eddi-chat-theme";

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
    const stored = localStorage.getItem(STORAGE_KEY) as ThemeMode | null;
    const mode = stored ?? initial;
    applyTheme(mode);

    // Listen for system theme changes
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = () => {
      const current = localStorage.getItem(STORAGE_KEY) as ThemeMode | null;
      if ((current ?? initial) === "system") {
        applyTheme("system");
      }
    };
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, [initial]);

  const setTheme = useCallback((mode: ThemeMode) => {
    localStorage.setItem(STORAGE_KEY, mode);
    applyTheme(mode);
  }, []);

  return { setTheme };
}
