import type { MeasureUnit, ProfileViolation } from "@/lib/api/health";

export const inputClass =
  "h-8 w-full rounded-lg border border-input bg-background px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";

/** Violations whose path is `path` or lies under it (`thresholds.x` covers `thresholds.x.red`). */
export function violationsAt(violations: ProfileViolation[], path: string): ProfileViolation[] {
  return violations.filter((v) => v.path === path || v.path.startsWith(`${path}.`) || v.path.startsWith(`${path}[`));
}

/** A ratio threshold is edited as a percentage; everything else in its own unit. */
export function toInput(value: number | undefined, unit: MeasureUnit | undefined): string {
  if (value === undefined || value === null) return "";
  return String(unit === "ratio" ? Math.round(value * 1e6) / 1e4 : value);
}

export function fromInput(text: string, unit: MeasureUnit | undefined): number | undefined {
  if (text.trim() === "") return undefined;
  const n = Number(text);
  if (!Number.isFinite(n)) return undefined;
  return unit === "ratio" ? n / 100 : n;
}
