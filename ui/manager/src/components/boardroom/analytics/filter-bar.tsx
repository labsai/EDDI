import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { X, ChevronDown, Filter } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  DISCUSSION_STYLES,
  STYLE_INFO,
  type GroupConversationState,
  type DiscussionStyle,
} from "@/lib/api/groups";
import type { ActiveFilter } from "./filter-utils";

// ─── Dropdown ────────────────────────────────────────────────────

interface DropdownOption<T extends string> {
  value: T | null;
  label: string;
}

interface FilterDropdownProps<T extends string> {
  label: string;
  value: T | null;
  options: DropdownOption<T>[];
  onChange: (v: T | null) => void;
}

function FilterDropdown<T extends string>({
  label,
  value,
  options,
  onChange,
}: FilterDropdownProps<T>) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const handleClickOutside = useCallback((e: MouseEvent) => {
    if (ref.current && !ref.current.contains(e.target as Node)) {
      setOpen(false);
    }
  }, []);

  useEffect(() => {
    if (open) {
      document.addEventListener("mousedown", handleClickOutside);
      return () =>
        document.removeEventListener("mousedown", handleClickOutside);
    }
  }, [open, handleClickOutside]);

  const selectedLabel = value
    ? options.find((o) => o.value === value)?.label ?? value
    : label;

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((p) => !p)}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-lg border ps-3 pe-2 py-1.5 text-xs font-medium transition-all cursor-pointer",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          value
            ? "border-primary/30 bg-primary/5 text-primary"
            : "border-border bg-card text-muted-foreground hover:text-foreground hover:border-foreground/20",
        )}
      >
        {selectedLabel}
        <ChevronDown
          className={cn(
            "h-3 w-3 transition-transform",
            open && "rotate-180",
          )}
        />
      </button>

      {open && (
        <div
          className={cn(
            "absolute top-full mt-1 z-50 min-w-[160px] rounded-lg border border-border bg-card shadow-lg",
            "animate-in fade-in-0 zoom-in-95 duration-100",
          )}
        >
          <div className="p-1">
            {options.map((opt) => (
              <button
                key={opt.value ?? "__all__"}
                type="button"
                onClick={() => {
                  onChange(opt.value);
                  setOpen(false);
                }}
                className={cn(
                  "flex w-full items-center rounded-md ps-2.5 pe-2.5 py-1.5 text-xs transition-colors cursor-pointer",
                  "hover:bg-muted",
                  opt.value === value
                    ? "bg-primary/10 text-primary font-medium"
                    : "text-foreground",
                )}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Main component ──────────────────────────────────────────────

const OUTCOME_OPTIONS: DropdownOption<GroupConversationState>[] = [
  { value: null, label: "All outcomes" },
  { value: "COMPLETED", label: "Completed" },
  { value: "FAILED", label: "Failed" },
  { value: "IN_PROGRESS", label: "In Progress" },
  { value: "SYNTHESIZING", label: "Synthesizing" },
  { value: "CREATED", label: "Created" },
  { value: "CANCELLED", label: "Cancelled" },
  { value: "AWAITING_APPROVAL", label: "Pending" },
];

const STYLE_OPTIONS: DropdownOption<DiscussionStyle>[] = [
  { value: null, label: "All styles" },
  ...DISCUSSION_STYLES.map((s) => ({
    value: s,
    label: STYLE_INFO[s]?.label ?? s,
  })),
];

interface AnalyticsFilterBarProps {
  filters: ActiveFilter[];
  outcome: GroupConversationState | null;
  style: DiscussionStyle | null;
  onOutcomeChange: (v: GroupConversationState | null) => void;
  onStyleChange: (v: DiscussionStyle | null) => void;
  onRemove: (filter: ActiveFilter) => void;
  onClearAll: () => void;
}

function AnalyticsFilterBar({
  filters,
  outcome,
  style,
  onOutcomeChange,
  onStyleChange,
  onRemove,
  onClearAll,
}: AnalyticsFilterBarProps) {
  const { t } = useTranslation();

  const hasActiveFilters = filters.length > 0;

  return (
    <div className="space-y-2">
      {/* Dropdown controls — always visible */}
      <div className="flex flex-wrap items-center gap-2">
        <Filter className="h-3.5 w-3.5 text-muted-foreground" />

        <FilterDropdown<GroupConversationState>
          label={t("analyticsPage.allOutcomes", "All outcomes")}
          value={outcome}
          options={OUTCOME_OPTIONS}
          onChange={onOutcomeChange}
        />

        <FilterDropdown<DiscussionStyle>
          label={t("analyticsPage.allStyles", "All styles")}
          value={style}
          options={STYLE_OPTIONS}
          onChange={onStyleChange}
        />

        {hasActiveFilters && (
          <button
            type="button"
            onClick={onClearAll}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors cursor-pointer ms-1"
          >
            <X className="h-3 w-3" />
            {t("analyticsPage.clearAll", "Clear all")}
          </button>
        )}
      </div>

      {/* Active filter chips from chart clicks (date, etc.) */}
      {filters.filter((f) => f.type === "date").length > 0 && (
        <div
          className="flex flex-wrap items-center gap-2"
          role="status"
          aria-live="polite"
        >
          {filters
            .filter((f) => f.type === "date")
            .map((f) => (
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
                aria-label={t(
                  "analyticsPage.removeFilter",
                  "Remove filter: {{label}}",
                  { label: f.label },
                )}
              >
                📅 {f.label}
                <X className="h-3 w-3" />
              </button>
            ))}
        </div>
      )}
    </div>
  );
}

export { AnalyticsFilterBar };
