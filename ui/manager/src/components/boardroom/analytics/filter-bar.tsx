import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ActiveFilter } from "./filter-utils";

// ─── Component ───────────────────────────────────────────────────

interface AnalyticsFilterBarProps {
  filters: ActiveFilter[];
  onRemove: (filter: ActiveFilter) => void;
  onClearAll: () => void;
}

function AnalyticsFilterBar({
  filters,
  onRemove,
  onClearAll,
}: AnalyticsFilterBarProps) {
  const { t } = useTranslation();

  if (filters.length === 0) return null;

  return (
    <div
      className={cn(
        "flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card/50 p-2.5",
        "br-card-enter",
      )}
      role="status"
      aria-live="polite"
    >
      <span className="text-xs font-medium text-muted-foreground">
        {t("analyticsPage.filtering", "Filtering")}:
      </span>

      {filters.map((f) => (
        <button
          key={`${f.type}-${f.value}`}
          type="button"
          onClick={() => onRemove(f)}
          className={cn(
            "inline-flex items-center gap-1 rounded-md ps-2 pe-1 py-0.5",
            "bg-primary/10 text-primary text-xs font-medium",
            "hover:bg-primary/20 transition-colors cursor-pointer",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          )}
          aria-label={t("analyticsPage.removeFilter", "Remove filter: {{label}}", {
            label: f.label,
          })}
        >
          {f.label}
          <X className="h-3 w-3" />
        </button>
      ))}

      {filters.length > 1 && (
        <button
          type="button"
          onClick={onClearAll}
          className="text-xs text-muted-foreground hover:text-foreground transition-colors ms-1 cursor-pointer"
        >
          {t("analyticsPage.clearAll", "Clear all")}
        </button>
      )}
    </div>
  );
}

export { AnalyticsFilterBar };
