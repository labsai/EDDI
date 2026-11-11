import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Plus, Trash2 } from "lucide-react";
import { SECTION_IDS, type HealthProfile, type MeasureUnit, type ProfileOverride, type ProfileViolation } from "./api";
import { Button, cn } from "./deps";
import { measureLabel as labelOf, sectionTitle } from "./format";
import { fromInput, inputClass, toInput, violationsAt } from "./profile-utils";

/** Server-side violations at `path` (or beneath it) as an alert list under a field. Renders nothing when there are none. */
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

/**
 * A number the person types. The text is kept exactly as typed — "0." stays
 * "0.", a lone "-" stays — and parsed afterwards; echoing the parsed number back
 * on every keystroke is what used to swallow the decimal point. The parent gets
 * the parsed value (`undefined` when empty, `NaN` when it is not a number).
 */
export function NumberField({
  value,
  unit,
  onValue,
  label,
  invalid,
  testId,
}: {
  value: number | undefined;
  unit: MeasureUnit | undefined;
  onValue: (v: number | undefined) => void;
  label: string;
  invalid?: boolean;
  testId?: string;
}) {
  const [text, setText] = useState(() => toInput(value, unit));
  const parsed = fromInput(text, unit);
  const bad = Number.isNaN(parsed);
  return (
    <input
      aria-label={label}
      aria-invalid={bad || invalid ? true : undefined}
      data-testid={testId}
      className={cn(inputClass, (bad || invalid) && "border-destructive")}
      inputMode="decimal"
      value={text}
      onChange={(e) => {
        setText(e.target.value);
        onValue(fromInput(e.target.value, unit));
      }}
    />
  );
}

/** One editable green/red row per measure in the profile's thresholds, in the measure's own unit (ratios as percentages). Violations show beneath the row they belong to. */
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
  /** Sets one side of a measure's threshold, keeping the other. `undefined` clears that side. */
  const set = (id: string, side: "green" | "red", v: number | undefined) => {
    const next = { ...profile.thresholds[id], [side]: v };
    onChange({ ...profile, thresholds: { ...profile.thresholds, [id]: next } });
  };
  if (ids.length === 0) {
    return <p className="text-xs text-muted-foreground">{t("health.profile.noThresholds", "No thresholds: the preset's defaults apply.")}</p>;
  }
  return (
    <div className="space-y-2">
      {ids.map((id) => {
        const name = labels[id] ?? id;
        return (
          <div key={id} data-testid={`health-threshold-${id}`}>
            <div className="grid grid-cols-[1fr_6rem_6rem] items-center gap-2 text-xs">
              <span className="truncate font-medium">{name}</span>
              <NumberField
                label={t("health.profile.green", { measure: name, defaultValue: "{{measure}}: green threshold" })}
                value={profile.thresholds[id]?.green}
                unit={units[id]}
                invalid={violationsAt(violations, `thresholds.${id}.green`).length > 0}
                testId={`health-threshold-${id}-green`}
                onValue={(v) => set(id, "green", v)}
              />
              <NumberField
                label={t("health.profile.red", { measure: name, defaultValue: "{{measure}}: red threshold" })}
                value={profile.thresholds[id]?.red}
                unit={units[id]}
                invalid={violationsAt(violations, `thresholds.${id}.red`).length > 0}
                testId={`health-threshold-${id}-red`}
                onValue={(v) => set(id, "red", v)}
              />
            </div>
            <Violations violations={violations} path={`thresholds.${id}`} />
          </div>
        );
      })}
      <p className="text-xs text-muted-foreground">
        {t("health.profile.thresholdUnits", "Ratios are entered in percent; other measures in their own unit (dollars, milliseconds).")}
      </p>
    </div>
  );
}

/** An override can silence one measure or a whole section. */
export function OverrideRows({
  overrides,
  onChange,
  measureIds,
  labels,
  violations,
}: {
  overrides: ProfileOverride[];
  onChange: (next: ProfileOverride[]) => void;
  measureIds: string[];
  labels: Record<string, string>;
  violations: ProfileViolation[];
}) {
  const { t } = useTranslation();
  /** Applies a partial change to the override at index `i`. */
  const patch = (i: number, p: Partial<ProfileOverride>) => onChange(overrides.map((o, j) => (j === i ? { ...o, ...p } : o)));
  return (
    <div className="space-y-2">
      {overrides.map((o, i) => (
        <div key={i} className="rounded-lg border border-border p-2" data-testid="health-override">
          <div className="grid gap-2 sm:grid-cols-[1fr_auto]">
            <select
              aria-label={t("health.profile.overrideTarget", "Measure or section")}
              className={inputClass}
              value={o.measure}
              onChange={(e) => patch(i, { measure: e.target.value })}
            >
              <option value="">—</option>
              <optgroup label={t("health.profile.overrideSections", "Sections")}>
                {SECTION_IDS.map((id) => (
                  <option key={id} value={id}>{sectionTitle(t, id)}</option>
                ))}
              </optgroup>
              <optgroup label={t("health.profile.overrideMeasures", "Measures")}>
                {[...new Set([...measureIds, o.measure])].filter((id) => id && !SECTION_IDS.includes(id as never)).map((id) => (
                  <option key={id} value={id}>{labels[id] ?? labelOf(t, { id, label: id })}</option>
                ))}
              </optgroup>
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
        data-testid="health-add-override"
      >
        <Plus aria-hidden="true" />
        {t("health.profile.addOverride", "Add override")}
      </Button>
    </div>
  );
}

/** A labelled form field with the violations at `path` shown beneath it. */
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
