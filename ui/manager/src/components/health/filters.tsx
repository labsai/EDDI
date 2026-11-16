import { useEffect, useId, useRef } from "react";
import { useTranslation } from "react-i18next";
import { HEALTH_ORIGINS, HEALTH_PERIODS, type HealthPeriod } from "./api";
import { cn } from "./deps";
import { enumLabel, formatDays, periodDays, selectClass, versionOptions } from "./format";

export interface HealthFilterState {
  period: HealthPeriod;
  version: number | null;
  origins: string[];
}

/** Period (7/30/90 days), version and origin filters of the health view. */
export function HealthFilters({
  value,
  onChange,
  latestVersion,
}: {
  value: HealthFilterState;
  onChange: (next: HealthFilterState) => void;
  latestVersion: number;
}) {
  const { t } = useTranslation();
  const versionId = useId();
  const originsRef = useRef<HTMLDetailsElement>(null);
  /** Adds the origin to the selection, or removes it when already selected. An empty selection means all origins. */
  const toggleOrigin = (origin: string) =>
    onChange({
      ...value,
      origins: value.origins.includes(origin) ? value.origins.filter((o) => o !== origin) : [...value.origins, origin],
    });

  // The origin list is a popover: it closes on a click elsewhere and on Escape.
  useEffect(() => {
    /** Closes the origins popover on a click outside it or on Escape (returning focus to its summary, so keyboard users are not dropped). */
    const close = (e: Event) => {
      const el = originsRef.current;
      if (!el?.open) return;
      if (e instanceof KeyboardEvent) {
        if (e.key !== "Escape") return;
        el.open = false;
        el.querySelector("summary")?.focus();
      } else if (!el.contains(e.target as Node)) {
        el.open = false;
      }
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, []);

  return (
    <div className="flex flex-wrap items-center gap-3" data-testid="health-filters">
      <div role="group" aria-label={t("health.filters.period", "Period")} className="inline-flex overflow-hidden rounded-lg border border-input">
        {HEALTH_PERIODS.map((p) => (
          <button
            key={p}
            type="button"
            aria-pressed={value.period === p}
            onClick={() => onChange({ ...value, period: p })}
            className={cn(
              "px-3 py-1 text-xs font-medium transition-colors",
              value.period === p ? "bg-primary text-primary-foreground" : "bg-background text-foreground hover:bg-secondary",
            )}
            data-testid={`health-period-${p}`}
          >
            {formatDays(periodDays(p))}
          </button>
        ))}
      </div>

      <label htmlFor={versionId} className="flex items-center gap-1.5 text-xs text-muted-foreground">
        {t("health.filters.version", "Version")}
        <select
          id={versionId}
          className={selectClass}
          value={value.version ?? ""}
          onChange={(e) => onChange({ ...value, version: e.target.value === "" ? null : Number(e.target.value) })}
          data-testid="health-version-filter"
        >
          <option value="">{t("health.filters.allVersions", "All versions")}</option>
          {versionOptions(latestVersion).map((v) => (
            <option key={v} value={v}>
              {`v${v}`}
            </option>
          ))}
        </select>
      </label>

      <details ref={originsRef} className="relative text-xs">
        <summary className="cursor-pointer rounded-lg border border-input px-2 py-1.5 text-muted-foreground hover:bg-secondary" data-testid="health-origin-filter">
          {value.origins.length === 0
            ? t("health.filters.originsDefault", "Origins: default")
            : t("health.filters.originsCount", { count: value.origins.length, defaultValue: "Origins: {{count}} selected" })}
        </summary>
        <ul className="absolute z-10 mt-1 max-h-64 w-52 overflow-auto rounded-lg border border-border bg-popover p-2 shadow-lg">
          {HEALTH_ORIGINS.map((o) => (
            <li key={o}>
              <label className="flex items-center gap-2 py-0.5">
                <input type="checkbox" checked={value.origins.includes(o)} onChange={() => toggleOrigin(o)} data-testid={`health-origin-${o}`} />
                <span>{enumLabel(t, "origin", o)}</span>
              </label>
            </li>
          ))}
          <li className="pt-1 text-muted-foreground">
            {t("health.filters.originsHint", "Nothing selected uses the preset's interactive origins. Agent tests are always excluded unless chosen.")}
          </li>
        </ul>
      </details>
    </div>
  );
}
