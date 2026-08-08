import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { X, ChevronDown, Filter, SearchX } from "lucide-react";
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
  count?: number;
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

  const selected = value
    ? options.find((o) => o.value === value)
    : null;
  const buttonLabel = selected
    ? `${selected.label}${selected.count !== undefined ? ` (${selected.count})` : ""}`
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
        {buttonLabel}
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
            "absolute top-full mt-1 z-50 min-w-[180px] rounded-lg border border-border bg-card shadow-lg",
            "animate-in fade-in-0 zoom-in-95 duration-100",
          )}
        >
          <div className="p-1">
            {options.map((opt) => {
              const isDisabled = opt.value !== null && opt.count === 0;
              return (
                <button
                  key={opt.value ?? "__all__"}
                  type="button"
                  disabled={isDisabled}
                  onClick={() => {
                    onChange(opt.value);
                    setOpen(false);
                  }}
                  className={cn(
                    "flex w-full items-center justify-between rounded-md ps-2.5 pe-2.5 py-1.5 text-xs transition-colors",
                    isDisabled
                      ? "text-muted-foreground/40 cursor-not-allowed"
                      : "cursor-pointer hover:bg-muted",
                    opt.value === value
                      ? "bg-primary/10 text-primary font-medium"
                      : !isDisabled && "text-foreground",
                  )}
                >
                  <span>{opt.label}</span>
                  {opt.count !== undefined && (
                    <span
                      className={cn(
                        "tabular-nums text-[10px] rounded-full min-w-[20px] text-center py-px ps-1.5 pe-1.5",
                        opt.value === value
                          ? "bg-primary/20 text-primary"
                          : opt.count === 0
                            ? "text-muted-foreground/30"
                            : "bg-muted text-muted-foreground",
                      )}
                    >
                      {opt.count}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Main component ──────────────────────────────────────────────

const ALL_OUTCOMES: GroupConversationState[] = [
  "COMPLETED",
  "FAILED",
  "IN_PROGRESS",
  "SYNTHESIZING",
  "CREATED",
  "CANCELLED",
  "AWAITING_APPROVAL",
  "AWAITING_HUMAN_INPUT",
  "CLOSED",
];

const OUTCOME_LABELS: Record<GroupConversationState, string> = {
  COMPLETED: "Completed",
  FAILED: "Failed",
  IN_PROGRESS: "In Progress",
  SYNTHESIZING: "Synthesizing",
  CREATED: "Created",
  CANCELLED: "Cancelled",
  AWAITING_APPROVAL: "Pending",
  AWAITING_HUMAN_INPUT: "Awaiting your turn",
  CLOSED: "Closed",
};

interface AnalyticsFilterBarProps {
  outcome: GroupConversationState | null;
  style: DiscussionStyle | null;
  outcomeCounts: Partial<Record<GroupConversationState, number>>;
  styleCounts: Partial<Record<DiscussionStyle, number>>;
  dateFilters: ActiveFilter[];
  onOutcomeChange: (v: GroupConversationState | null) => void;
  onStyleChange: (v: DiscussionStyle | null) => void;
  onRemoveDateFilter: (filter: ActiveFilter) => void;
  onClearAll: () => void;
  hasActiveFilters: boolean;
  totalResults: number;
  unfilteredTotal: number;
}

function AnalyticsFilterBar({
  outcome,
  style,
  outcomeCounts,
  styleCounts,
  dateFilters,
  onOutcomeChange,
  onStyleChange,
  onRemoveDateFilter,
  onClearAll,
  hasActiveFilters,
  totalResults,
  unfilteredTotal,
}: AnalyticsFilterBarProps) {
  const { t } = useTranslation();

  // Build options with counts
  const outcomeOptions: DropdownOption<GroupConversationState>[] = [
    { value: null, label: t("analyticsPage.allOutcomes", "All outcomes") },
    ...ALL_OUTCOMES.map((s) => ({
      value: s,
      label: OUTCOME_LABELS[s],
      count: (outcomeCounts ?? {})[s] ?? 0,
    })),
  ];

  const styleOptions: DropdownOption<DiscussionStyle>[] = [
    { value: null, label: t("analyticsPage.allStyles", "All styles") },
    ...DISCUSSION_STYLES.map((s) => ({
      value: s,
      label: STYLE_INFO[s]?.label ?? s,
      count: (styleCounts ?? {})[s] ?? 0,
    })),
  ];

  return (
    <div className="space-y-2">
      {/* Dropdown controls — always visible */}
      <div className="flex flex-wrap items-center gap-2">
        <Filter className="h-3.5 w-3.5 text-muted-foreground" />

        <FilterDropdown<GroupConversationState>
          label={t("analyticsPage.allOutcomes", "All outcomes")}
          value={outcome}
          options={outcomeOptions}
          onChange={onOutcomeChange}
        />

        <FilterDropdown<DiscussionStyle>
          label={t("analyticsPage.allStyles", "All styles")}
          value={style}
          options={styleOptions}
          onChange={onStyleChange}
        />

        {/* Date filter chips */}
        {dateFilters.map((f) => (
          <button
            key={`${f.type}-${f.value}`}
            type="button"
            onClick={() => onRemoveDateFilter(f)}
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

        {hasActiveFilters && (
          <>
            <span className="text-xs text-muted-foreground ms-1">
              {t("analyticsPage.showingResults", "{{count}} of {{total}}", {
                count: totalResults,
                total: unfilteredTotal,
              })}
            </span>
            <button
              type="button"
              onClick={onClearAll}
              className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
            >
              <X className="h-3 w-3" />
              {t("analyticsPage.clearAll", "Clear all")}
            </button>
          </>
        )}
      </div>

      {/* Empty results banner */}
      {hasActiveFilters && totalResults === 0 && (
        <div className="flex items-center gap-3 rounded-lg border border-border bg-muted/30 p-4">
          <SearchX className="h-5 w-5 text-muted-foreground shrink-0" />
          <div className="flex-1">
            <p className="text-sm font-medium text-foreground">
              {t("analyticsPage.noResults", "No matching discussions")}
            </p>
            <p className="text-xs text-muted-foreground">
              {t(
                "analyticsPage.noResultsHint",
                "Try adjusting your filters or clear them to see all data.",
              )}
            </p>
          </div>
          <button
            type="button"
            onClick={onClearAll}
            className="shrink-0 rounded-md bg-primary ps-3 pe-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 transition-colors cursor-pointer"
          >
            {t("analyticsPage.clearFilters", "Clear filters")}
          </button>
        </div>
      )}
    </div>
  );
}

export { AnalyticsFilterBar };
