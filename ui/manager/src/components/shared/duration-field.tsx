import { useState } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import {
  DURATION_UNITS,
  buildIsoDuration,
  parseDurationParts,
  type DurationUnit,
} from "@/lib/iso-duration-parts";

/**
 * A duration as "number + unit", stored as an ISO-8601 string.
 *
 * An empty amount means "no duration" and is reported as "". The caller's value
 * is only read for the initial amount and unit; later edits are the field's
 * own, so typing never fights a re-parse of the string it just produced.
 */
export function DurationField({
  value,
  onChange,
  label,
  invalid,
  testId,
  className,
}: {
  value: string;
  onChange: (iso: string) => void;
  label: string;
  invalid?: boolean;
  testId?: string;
  className?: string;
}) {
  const { t } = useTranslation();
  const initial = parseDurationParts(value);
  const [amount, setAmount] = useState(initial ? String(initial.amount) : "");
  const [unit, setUnit] = useState<DurationUnit>(initial?.unit ?? "hours");

  function emit(nextAmount: string, nextUnit: DurationUnit) {
    onChange(nextAmount === "" ? "" : buildIsoDuration(Number(nextAmount), nextUnit));
  }

  const unitLabels: Record<DurationUnit, string> = {
    minutes: t("groupWizard.unitMinutes", "minutes"),
    hours: t("groupWizard.unitHours", "hours"),
    days: t("groupWizard.unitDays", "days"),
  };

  return (
    <div className={cn("flex items-center gap-2", className)}>
      <input
        type="text"
        inputMode="numeric"
        value={amount}
        onChange={(e) => {
          const digits = e.target.value.replace(/\D/g, "").slice(0, 6);
          setAmount(digits);
          emit(digits, unit);
        }}
        placeholder={t("groupWizard.durationNoLimit", "No limit")}
        aria-label={label}
        aria-invalid={invalid || undefined}
        className={cn(
          "w-28 rounded-lg border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring",
          invalid ? "border-destructive" : "border-input",
        )}
        data-testid={testId}
      />
      <select
        value={unit}
        onChange={(e) => {
          const next = e.target.value as DurationUnit;
          setUnit(next);
          emit(amount, next);
        }}
        aria-label={t("groupWizard.durationUnit", "Unit")}
        className="rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
        data-testid={testId ? `${testId}-unit` : undefined}
      >
        {DURATION_UNITS.map((u) => (
          <option key={u} value={u}>
            {unitLabels[u]}
          </option>
        ))}
      </select>
    </div>
  );
}
