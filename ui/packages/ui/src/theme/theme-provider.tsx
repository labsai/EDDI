import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { ThemeContext, type ThemeState } from "./theme-context";
import {
  applyTheme,
  getSystemTheme,
  readStoredTheme,
  storeTheme,
  subscribeSystemTheme,
  type ResolvedTheme,
  type ThemeAttribute,
  type ThemeMode,
} from "./theme";

export interface ThemeProviderProps {
  children: ReactNode;
  /** The mode before the user picks one. */
  defaultTheme?: ThemeMode;
  /** Where the choice is remembered (localStorage). */
  storageKey?: string;
  /**
   * How `<html>` is marked: a `dark`/`light` class (Tailwind's `dark` variant,
   * the Manager) or `data-theme` (the Chat UI's stylesheet).
   */
  attribute?: ThemeAttribute;
}

/**
 * Dark / light / system for a React tree. "system" follows the operating system
 * live; the resolved theme is written onto `<html>` and the choice is remembered.
 * Moved from the Manager (phase 3a); `useTheme()` reads it.
 */
export function ThemeProvider({
  children,
  defaultTheme = "system",
  storageKey = "eddi-theme",
  attribute = "class",
}: ThemeProviderProps) {
  const [theme, setThemeState] = useState<ThemeMode>(() => readStoredTheme(storageKey) ?? defaultTheme);
  const [systemTheme, setSystemTheme] = useState<ResolvedTheme>(getSystemTheme);

  // "system" reacts to an OS change in real time.
  useEffect(() => subscribeSystemTheme(setSystemTheme), []);

  const resolvedTheme = theme === "system" ? systemTheme : theme;

  useEffect(() => {
    applyTheme(resolvedTheme, attribute);
  }, [resolvedTheme, attribute]);

  const setTheme = useCallback(
    (next: ThemeMode) => {
      storeTheme(storageKey, next);
      setThemeState(next);
    },
    [storageKey],
  );

  const value = useMemo<ThemeState>(() => ({ theme, resolvedTheme, setTheme }), [theme, resolvedTheme, setTheme]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
