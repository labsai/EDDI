import { useTranslation } from "react-i18next";
import { LayoutGrid, List } from "lucide-react";
import { cn } from "@/lib/utils";
import { useCallback } from "react";

export type ViewMode = "card" | "list";

interface ViewToggleProps {
  view: ViewMode;
  onChange: (view: ViewMode) => void;
}

/**
 * Toggle between card (grid) and list (table) views.
 * Used on agents, packages, resources, and conversations pages.
 */
export function ViewToggle({ view, onChange }: ViewToggleProps) {
  const { t } = useTranslation();

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
        e.preventDefault();
        const next = view === "card" ? "list" : "card";
        onChange(next);
        // Focus the newly active radio
        requestAnimationFrame(() => {
          const btn = document.querySelector<HTMLElement>(`[data-testid="view-toggle-${next}"]`);
          btn?.focus();
        });
      }
    },
    [view, onChange],
  );

  return (
    <div
      className="inline-flex items-center rounded-lg border border-input bg-background p-0.5"
      role="radiogroup"
      aria-label={t("common.viewMode", "View mode")}
      data-testid="view-toggle"
      onKeyDown={handleKeyDown}
    >
      <button
        type="button"
        role="radio"
        aria-checked={view === "card"}
        aria-label={t("common.cardView", "Card view")}
        tabIndex={view === "card" ? 0 : -1}
        onClick={() => onChange("card")}
        className={cn(
          "inline-flex items-center justify-center rounded-md p-1.5 transition-colors",
          view === "card"
            ? "bg-secondary text-foreground shadow-sm"
            : "text-muted-foreground hover:text-foreground"
        )}
        data-testid="view-toggle-card"
      >
        <LayoutGrid className="h-4 w-4" aria-hidden="true" />
      </button>
      <button
        type="button"
        role="radio"
        aria-checked={view === "list"}
        aria-label={t("common.listView", "List view")}
        tabIndex={view === "list" ? 0 : -1}
        onClick={() => onChange("list")}
        className={cn(
          "inline-flex items-center justify-center rounded-md p-1.5 transition-colors",
          view === "list"
            ? "bg-secondary text-foreground shadow-sm"
            : "text-muted-foreground hover:text-foreground"
        )}
        data-testid="view-toggle-list"
      >
        <List className="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
  );
}

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
