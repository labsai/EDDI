/**
 * Dark / light / system, without React: the pieces the Manager's ThemeProvider
 * and the Chat UI's `useTheme` hook both need. Each app marks the resolved theme
 * on `<html>` its own way — the Manager (Tailwind's `dark` variant) with a
 * `dark` or `light` class, the widget's BEM stylesheet with `data-theme` — so the
 * marker is a parameter, not a choice made here.
 */

export type ThemeMode = "dark" | "light" | "system";
export type ResolvedTheme = "dark" | "light";
/** How the resolved theme is written onto the root element. */
export type ThemeAttribute = "class" | "data-theme";

const DARK_QUERY = "(prefers-color-scheme: dark)";

export function isThemeMode(value: unknown): value is ThemeMode {
  return value === "dark" || value === "light" || value === "system";
}

/** The operating system's preference; light where it cannot be read. */
export function getSystemTheme(): ResolvedTheme {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return "light";
  return window.matchMedia(DARK_QUERY).matches ? "dark" : "light";
}

export function resolveTheme(mode: ThemeMode, system: ResolvedTheme = getSystemTheme()): ResolvedTheme {
  return mode === "system" ? system : mode;
}

/**
 * The remembered mode, or null. Storage throws when site data is blocked (a
 * sandboxed iframe, a third-party storage block, some private modes), and both
 * callers sit above every error boundary — so a throw is "nothing remembered",
 * never a blank page. A value that is not a mode is ignored, not trusted.
 */
export function readStoredTheme(storageKey: string): ThemeMode | null {
  try {
    const value = window.localStorage.getItem(storageKey);
    return isThemeMode(value) ? value : null;
  } catch {
    return null;
  }
}

/** Remember the mode; when storage is unavailable it applies to this page only. */
export function storeTheme(storageKey: string, mode: ThemeMode): void {
  try {
    window.localStorage.setItem(storageKey, mode);
  } catch {
    // Not persisted.
  }
}

/** Mark `root` (by default `<html>`) with the resolved theme. */
export function applyTheme(
  resolved: ResolvedTheme,
  attribute: ThemeAttribute,
  root: HTMLElement = document.documentElement,
): void {
  if (attribute === "class") {
    root.classList.remove("light", "dark");
    root.classList.add(resolved);
  } else {
    root.setAttribute("data-theme", resolved);
  }
}

/** Call `listener` whenever the operating system's preference flips. */
export function subscribeSystemTheme(listener: (theme: ResolvedTheme) => void): () => void {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return () => {};
  const mql = window.matchMedia(DARK_QUERY);
  // Re-read rather than trust the event: older engines and test doubles call
  // the handler without one.
  const handler = () => listener(getSystemTheme());
  mql.addEventListener("change", handler);
  return () => mql.removeEventListener("change", handler);
}

/** dark → light → system → dark: the order a single toggle button cycles. */
export function nextTheme(mode: ThemeMode): ThemeMode {
  return mode === "dark" ? "light" : mode === "light" ? "system" : "dark";
}
