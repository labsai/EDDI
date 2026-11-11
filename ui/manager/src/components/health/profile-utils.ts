import type { HealthPreset, HealthProfile, MeasureUnit, ProfileViolation } from "./api";

export const inputClass =
  "h-8 w-full rounded-lg border border-input bg-background px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";

/**
 * The measures the contract names, with their units, so the editor can offer a
 * threshold or a critical flag for a measure that has no data on screen right
 * now (a section the period left empty, a measure the backend has not computed).
 */
export const KNOWN_MEASURE_UNITS: Record<string, MeasureUnit> = {
  agentFailureRate: "ratio",
  platformFailureRate: "ratio",
  apdex: "score",
  turnDurationP95: "ms",
  fallbackRate: "ratio",
  resolutionRate: "ratio",
  escalationRate: "ratio",
  abandonmentRate: "ratio",
  deflectionRate: "ratio",
  outcomeCoverage: "ratio",
  ratingScore: "score",
  ratingResponseRate: "ratio",
  replyThumbsUp: "ratio",
  guardrailTripRate: "ratio",
  costPerSession: "usd",
  costPerResolvedSession: "usd",
  tokensPerTurn: "tokens",
  sessions: "count",
  engagedSessions: "count",
  distinctUsers: "count",
  returningUserShare: "ratio",
  turnsPerSession: "count",
};

/**
 * Every measure id the editor can talk about: the known ones, those on screen,
 * and anything the stored profile or the chosen preset already mentions.
 * Units come from the data when it has the measure, else from the known table.
 */
export function profileMeasureCatalog(
  onScreen: Record<string, MeasureUnit>,
  profile: HealthProfile,
  preset: HealthPreset | undefined,
): { ids: string[]; units: Record<string, MeasureUnit> } {
  const units: Record<string, MeasureUnit> = { ...KNOWN_MEASURE_UNITS, ...onScreen };
  const ids = new Set<string>([
    ...Object.keys(KNOWN_MEASURE_UNITS),
    ...Object.keys(onScreen),
    ...profile.critical,
    ...Object.keys(profile.thresholds),
    ...(preset?.critical ?? []),
    ...Object.keys(preset?.thresholds ?? {}),
  ]);
  return { ids: [...ids].sort(), units };
}

/** Violations whose path is `path` or lies under it (`thresholds.x` covers `thresholds.x.red`). */
export function violationsAt(violations: ProfileViolation[], path: string): ProfileViolation[] {
  return violations.filter((v) => v.path === path || v.path.startsWith(`${path}.`) || v.path.startsWith(`${path}[`));
}

const BUDGET_PATHS = ["budgets.costPerResolvedUsd"];
const SLO_PATHS = ["slo.agentTurnSuccess", "slo.apdexTMs", "slo.minTurnsForBurnAlert"];

/** Whether the editor draws a field a violation at `path` could be shown under. */
function hasField(path: string, draft: HealthProfile): boolean {
  if (path === "preset" || path === "critical" || path.startsWith("critical[") || path.startsWith("critical.")) return true;
  const threshold = /^thresholds\.([^.[]+)/.exec(path);
  if (threshold) return threshold[1]! in draft.thresholds;
  if (BUDGET_PATHS.includes(path)) return true;
  if (SLO_PATHS.includes(path)) return true;
  const override = /^overrides\[(\d+)\]/.exec(path);
  if (override) return Number(override[1]) < draft.overrides.length;
  return false;
}

/** Violations with no field to sit under: shown in a general list, never dropped. */
export function unmatchedViolations(violations: ProfileViolation[], draft: HealthProfile): ProfileViolation[] {
  return violations.filter((v) => !hasField(v.path, draft));
}

/**
 * Reads a decimal a person typed, in either convention: "0.5", "0,5", "1,234.5"
 * and "1.234,5" all work. Returns `undefined` for an empty field and `NaN` for
 * text that is not a number (a lone "-" or "0." is only ever an intermediate
 * state, which is why the text is kept as typed and parsed afterwards).
 */
export function parseDecimal(text: string): number | undefined {
  const t = text.trim().replace(/\s/g, "");
  if (t === "") return undefined;
  const lastComma = t.lastIndexOf(",");
  const lastDot = t.lastIndexOf(".");
  let normalized = t;
  if (lastComma >= 0 && lastDot >= 0) {
    // The later separator is the decimal one; the other groups thousands.
    normalized = lastComma > lastDot ? t.replace(/\./g, "").replace(",", ".") : t.replace(/,/g, "");
  } else if (lastComma >= 0) {
    // A lone comma is a decimal comma, unless there are several (a group separator).
    normalized = t.split(",").length > 2 ? t.replace(/,/g, "") : t.replace(",", ".");
  }
  if (!/^[-+]?(\d+\.?\d*|\.\d+)$/.test(normalized)) return Number.NaN;
  return Number(normalized);
}

/** A ratio threshold is edited as a percentage; everything else in its own unit. */
export function toInput(value: number | undefined, unit: MeasureUnit | undefined): string {
  if (value === undefined || value === null) return "";
  return String(unit === "ratio" ? Math.round(value * 1e6) / 1e4 : value);
}

/** The stored number for what was typed, or `undefined` when empty, `NaN` when invalid. */
export function fromInput(text: string, unit: MeasureUnit | undefined): number | undefined {
  const n = parseDecimal(text);
  if (n === undefined || Number.isNaN(n)) return n;
  return unit === "ratio" ? Math.round(n * 1e6) / 1e8 : n;
}
