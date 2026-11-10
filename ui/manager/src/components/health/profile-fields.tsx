import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Plus, Trash2 } from "lucide-react";
import type { HealthProfile, MeasureUnit, ProfileOverride, ProfileViolation } from "@/lib/api/health";
import { Button, cn } from "./deps";
import { fromInput, inputClass, toInput, violationsAt } from "./profile-utils";

export function Violations({ violations, path }: { violations: ProfileViolation[]; path: string }) {
  const own = violationsAt(violations, path);
  if (own.length === 0) return null;
  return (
    <ul className="mt-0.5 space-y-0.5 text-xs text-destructive" role="alert" data-testid={`health-violation-${path}`}>
      {own.map((v) => (
        <li key={`${v.path}-${v.message}`}>{v.message}</li>
      ))}
    </ul>
  );
}

export function ThresholdRows({
  profile,
  onChange,
  units,
  labels,
  violations,
}: {
  profile: HealthProfile;
  onChange: (next: HealthProfile) => void;
  units: Record<string, MeasureUnit>;
  labels: Record<string, string>;
  violations: ProfileViolation[];
}) {
  const { t } = useTranslation();
  const ids = Object.keys(profile.thresholds);
  const set = (id: string, side: "green" | "red", text: string) => {
    const next = { ...profile.thresholds[id], [side]: fromInput(text, units[id]) };
    onChange({ ...profile, thresholds: { ...profile.thresholds, [id]: next } });
  };
  if (ids.length === 0) {
    return <p className="text-xs text-muted-foreground">{t("health.profile.noThresholds", "No thresholds: the preset's defaults apply.")}</p>;
  }
  return (
    <div className="space-y-2">
      {ids.map((id) => (
        <div key={id} data-testid={`health-threshold-${id}`}>
          <div className="grid grid-cols-[1fr_6rem_6rem] items-center gap-2 text-xs">
            <span className="truncate font-medium">{labels[id] ?? id}</span>
            <input
              aria-label={t("health.profile.green", { measure: labels[id] ?? id, defaultValue: "{{measure}}: green threshold" })}
              className={cn(inputClass, violationsAt(violations, `thresholds.${id}.green`).length > 0 && "border-destructive")}
              inputMode="decimal"
              value={toInput(profile.thresholds[id]?.green, units[id])}
              onChange={(e) => set(id, "green", e.target.value)}
            />
            <input
              aria-label={t("health.profile.red", { measure: labels[id] ?? id, defaultValue: "{{measure}}: red threshold" })}
              className={cn(inputClass, violationsAt(violations, `thresholds.${id}.red`).length > 0 && "border-destructive")}
              inputMode="decimal"
              value={toInput(profile.thresholds[id]?.red, units[id])}
              onChange={(e) => set(id, "red", e.target.value)}
            />
          </div>
          <Violations violations={violations} path={`thresholds.${id}`} />
        </div>
      ))}
      <p className="text-xs text-muted-foreground">
        {t("health.profile.thresholdUnits", "Ratios are entered in percent; other measures in their own unit (dollars, milliseconds).")}
      </p>
    </div>
  );
}

export function OverrideRows({
  overrides,
  onChange,
  measureIds,
  violations,
}: {
  overrides: ProfileOverride[];
  onChange: (next: ProfileOverride[]) => void;
  measureIds: string[];
  violations: ProfileViolation[];
}) {
  const { t } = useTranslation();
  const patch = (i: number, p: Partial<ProfileOverride>) => onChange(overrides.map((o, j) => (j === i ? { ...o, ...p } : o)));
  return (
    <div className="space-y-2">
      {overrides.map((o, i) => (
        <div key={i} className="rounded-lg border border-border p-2" data-testid="health-override">
          <div className="grid gap-2 sm:grid-cols-[1fr_auto]">
            <select
              aria-label={t("health.profile.overrideMeasure", "Measure")}
              className={inputClass}
              value={o.measure}
              onChange={(e) => patch(i, { measure: e.target.value })}
            >
              <option value="">—</option>
              {[...new Set([...measureIds, o.measure].filter(Boolean))].map((id) => (
                <option key={id} value={id}>{id}</option>
              ))}
            </select>
            <input
              type="date"
              aria-label={t("health.profile.overrideUntil", "Ignore until")}
              className={inputClass}
              value={o.until}
              onChange={(e) => patch(i, { until: e.target.value })}
            />
          </div>
          <div className="mt-2 flex items-start gap-2">
            <input
              aria-label={t("health.profile.overrideReason", "Reason")}
              placeholder={t("health.profile.overrideReasonPlaceholder", "Why is this measure ignored?")}
              className={inputClass}
              value={o.reason}
              onChange={(e) => patch(i, { reason: e.target.value })}
            />
            <Button
              type="button"
              variant="ghost"
              size="iconSm"
              aria-label={t("health.profile.removeOverride", "Remove override")}
              onClick={() => onChange(overrides.filter((_, j) => j !== i))}
            >
              <Trash2 />
            </Button>
          </div>
          <Violations violations={violations} path={`overrides[${i}]`} />
        </div>
      ))}
      <Button
        type="button"
        size="sm"
        variant="outline"
        onClick={() => onChange([...overrides, { measure: "", state: "IGNORED", reason: "", until: "" }])}
      >
        <Plus aria-hidden="true" />
        {t("health.profile.addOverride", "Add override")}
      </Button>
    </div>
  );
}

export function Field({ label, children, violations, path }: { label: string; children: ReactNode; violations: ProfileViolation[]; path: string }) {
  return (
    <div>
      <label className="flex flex-col gap-1 text-xs font-medium text-foreground">
        {label}
        {children}
      </label>
      <Violations violations={violations} path={path} />
    </div>
  );
}
