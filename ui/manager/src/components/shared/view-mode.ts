export type ViewMode = "card" | "list";

const STORAGE_KEY = "eddi-view-mode";

/** Read persisted view preference from localStorage */
export function getStoredViewMode(page: string): ViewMode {
  try {
    const stored = localStorage.getItem(`${STORAGE_KEY}-${page}`);
    if (stored === "card" || stored === "list") return stored;
  } catch {
    // SSR or localStorage not available
  }
  return "card";
}

/** Persist view preference to localStorage */
export function setStoredViewMode(page: string, mode: ViewMode) {
  try {
    localStorage.setItem(`${STORAGE_KEY}-${page}`, mode);
  } catch {
    // Ignore
  }
}
