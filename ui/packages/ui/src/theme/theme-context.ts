import { createContext, useContext } from "react";
import type { ResolvedTheme, ThemeMode } from "./theme";

export interface ThemeState {
  /** The mode the user chose, which may be "system". */
  theme: ThemeMode;
  /** What is on screen: "system" resolved against the operating system. */
  resolvedTheme: ResolvedTheme;
  setTheme: (theme: ThemeMode) => void;
}

/**
 * Without a provider, `useTheme()` reports "system" resolved to light and
 * ignores `setTheme` — the Manager's behaviour before the move, which isolated
 * component tests rely on.
 */
export const ThemeContext = createContext<ThemeState>({
  theme: "system",
  resolvedTheme: "light",
  setTheme: () => {},
});

export function useTheme(): ThemeState {
  return useContext(ThemeContext);
}
