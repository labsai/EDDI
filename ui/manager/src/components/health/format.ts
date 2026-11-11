import { activeLocale, formatDuration, formatUsd } from "./deps";
import type { TFunction } from "i18next";
import {
  SECTION_IDS,
  type AgentHealth,
  type Band,
  type CompareDelta,
  type HealthStatus,
  type Measure,
  type MeasureUnit,
} from "./api";

/**
 * Pure formatting and interpretation for the Health UI — no React, no I/O.
 * Numbers arrive raw from the backend; units, locale and sign are decided here
 * (contract §10).
 */

// One locale for every number and date here: the app's language, via the same
// helper the cost formatter uses, so a figure and its neighbour never disagree.
/** Formats a number with the app's active locale (see the note above). */
function number(value: number, opts: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(activeLocale(), opts).format(value);
}

/** A whole number of days in the app's language ("7 days", "7 Tage"), with its plural forms from ICU. */
export function formatDays(days: number): string {
  return number(days, { style: "unit", unit: "day", unitDisplay: "long" });
}

/** The number of days a period id stands for ("30d" is 30). */
export const periodDays = (p: string): number => Number.parseInt(p, 10);

const DAY_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * A date as the person reads it in their language ("3 Nov", "3. Nov."). A bare day
 * (`YYYY-MM-DD`, which is what periods, `dataThrough` and incidents carry) is a UTC day and is
 * shown as that day whatever the browser's time zone; an instant is shown in the local zone.
 */
export function formatShortDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(activeLocale(), {
    day: "numeric",
    month: "short",
    ...(DAY_ONLY.test(iso) ? { timeZone: "UTC" } : {}),
  }).format(d);
}

/** A date-time in the app's language. */
export function formatDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(activeLocale(), { dateStyle: "medium", timeStyle: "short" }).format(d);
}

/** "2 days ago" in the app's language; "—" for an unreadable timestamp. */
export function formatAge(iso: string | null | undefined, now: number = Date.now()): string {
  const at = iso ? Date.parse(iso) : Number.NaN;
  if (!Number.isFinite(at)) return "—";
  const seconds = Math.round((at - now) / 1000);
  const rtf = new Intl.RelativeTimeFormat(activeLocale(), { numeric: "auto" });
  const steps: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ["day", 86_400],
    ["hour", 3_600],
    ["minute", 60],
  ];
  for (const [unit, size] of steps) {
    if (Math.abs(seconds) >= size) return rtf.format(Math.trunc(seconds / size), unit);
  }
  return rtf.format(0, "second");
}

/** A ratio as a percentage: whole numbers from 10 %, one decimal below. */
function percent(ratio: number): string {
  const digits = Math.abs(ratio) < 0.1 && ratio !== 0 ? 1 : 0;
  return number(ratio, { style: "percent", minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/** The value of a measure in its unit; "—" when there is none. */
export function formatMeasureValue(value: number | null | undefined, unit: MeasureUnit): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "—";
  switch (unit) {
    case "ratio":
      return percent(value);
    case "usd":
      return formatUsd(value);
    case "ms":
      return formatDuration(value);
    case "score":
      return number(value, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    case "tokens":
      return number(value, { notation: "compact", maximumFractionDigits: 1 });
    case "count":
    default:
      return number(value, { maximumFractionDigits: value % 1 === 0 ? 0 : 1 });
  }
}

/**
 * A change in a measure's unit with an explicit sign. Ratios are percentage
 * POINTS ("+0.8 pp"), never a relative change — a move from 1 % to 2 % is one
 * point, not "+100 %". `ppLabel` is the translated unit (`health.unit.pp`).
 */
export function formatDelta(delta: number | null | undefined, unit: MeasureUnit, ppLabel = "pp"): string {
  if (delta === null || delta === undefined || !Number.isFinite(delta)) return "—";
  const sign = delta > 0 ? "+" : delta < 0 ? "−" : "±";
  const abs = Math.abs(delta);
  if (unit === "ratio") {
    const pp = abs * 100;
    return `${sign}${number(pp, { maximumFractionDigits: pp < 10 ? 1 : 0 })} ${ppLabel}`;
  }
  return `${sign}${formatMeasureValue(abs, unit)}`;
}

/** A relative change with a sign ("−8%", "+12%"), for figures that are a ratio of themselves (session counts). */
export function formatSignedPercent(change: number | null | undefined): string {
  if (change === null || change === undefined || !Number.isFinite(change)) return "—";
  const sign = change > 0 ? "+" : change < 0 ? "−" : "±";
  return `${sign}${number(Math.abs(change), { style: "percent", maximumFractionDigits: 0 })}`;
}

/** "9 %–16 %" style range of a confidence interval, or null when there is none. */
export function formatInterval(interval: { low: number; high: number } | null | undefined, unit: MeasureUnit): string | null {
  if (!interval) return null;
  return `${formatMeasureValue(interval.low, unit)}–${formatMeasureValue(interval.high, unit)}`;
}

export type DeltaTone = "better" | "worse" | "neutral";

/**
 * Whether a change is an improvement. Only a scored measure with thresholds has
 * a direction; an informational one (sessions, users) never reads as good or
 * bad. A change that is not significant is neutral however large it looks.
 */
export function deltaTone(measure: Pick<Measure, "delta" | "significant" | "thresholds">): DeltaTone {
  const { delta, significant, thresholds } = measure;
  if (!thresholds || !significant || delta === null || delta === undefined || delta === 0) return "neutral";
  return (delta > 0) === thresholds.higherIsBetter ? "better" : "worse";
}

export interface BandMeta {
  /** i18n key suffix under `health.band.` */
  key: "green" | "amber" | "red" | "grey";
  fallback: string;
  /** Border, tint and text classes for a badge. */
  classes: string;
  /** The text colour alone, for text and for SVG marks (`currentColor`). */
  text: string;
  /** Border colour alone, for rings. */
  border: string;
}

// Good/bad colours follow the Manager's existing convention (emerald for good,
// `warning` for caution, `destructive` for bad); they are not brand tokens, so
// a white-label brand does not recolour them.
const GOOD_TEXT = "text-emerald-700 dark:text-emerald-400";

/** Display metadata per band: label key, fallback text and Tailwind classes. */
export const BAND_META: Record<Band, BandMeta> = {
  GREEN: { key: "green", fallback: "On target", classes: `border-emerald-500/40 bg-emerald-500/10 ${GOOD_TEXT}`, text: GOOD_TEXT, border: "border-emerald-500" },
  AMBER: { key: "amber", fallback: "Watch", classes: "border-warning/40 bg-warning/10 text-warning", text: "text-warning", border: "border-warning" },
  RED: { key: "red", fallback: "Off target", classes: "border-destructive/40 bg-destructive/10 text-destructive", text: "text-destructive", border: "border-destructive" },
  GREY: { key: "grey", fallback: "Not rated", classes: "border-border bg-muted text-muted-foreground", text: "text-muted-foreground", border: "border-border" },
};

/** Text colour of a change: better, worse or neutral. */
export const TONE_CLASS = { better: GOOD_TEXT, worse: "text-destructive", neutral: "text-muted-foreground" } as const;

/** One look for every `<select>` in the health UI. */
export const selectClass =
  "h-8 rounded-lg border border-input bg-background px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";

/** Display metadata for a band. A missing or unrecognised band falls back to GREY ("not rated"), so a band the backend adds later still renders. */
export function bandMeta(band: Band | string | undefined): BandMeta {
  return BAND_META[(band as Band) ?? "GREY"] ?? BAND_META.GREY;
}

export interface StatusMeta {
  key: "healthy" | "watch" | "needsAttention" | "notEnoughData";
  fallback: string;
  classes: string;
  /** Severity order for "needs me first": higher is more urgent. */
  rank: number;
}

/** Display metadata per overall status, reusing the band colours. `rank` orders agents for the attention sort (3 = most urgent). */
export const STATUS_META: Record<HealthStatus, StatusMeta> = {
  NEEDS_ATTENTION: { key: "needsAttention", fallback: "Needs attention", classes: BAND_META.RED.classes, rank: 3 },
  WATCH: { key: "watch", fallback: "Watch", classes: BAND_META.AMBER.classes, rank: 2 },
  HEALTHY: { key: "healthy", fallback: "Healthy", classes: BAND_META.GREEN.classes, rank: 1 },
  NOT_ENOUGH_DATA: { key: "notEnoughData", fallback: "Not enough data", classes: BAND_META.GREY.classes, rank: 0 },
};

// ── Resolution split (plan §4.4, decision 3) ──────────────────────

/** `confirmed` is external events and explicit agent actions; `selfReported` is the weak LLM report. */
export const RESOLUTION_KEYS = ["confirmed", "selfReported", "agentClosed", "silence"] as const;
export type ResolutionKey = (typeof RESOLUTION_KEYS)[number];

export interface ResolutionSplit {
  /** Each source's value as the backend sent it, and its share of all resolutions (0..1). */
  parts: Array<{ key: ResolutionKey; value: number; n: number; share: number }>;
  total: number;
  /** Silence share of the resolutions, 0..1. */
  silenceShare: number;
  /** More than half of the resolutions rest on users going quiet. */
  mostlySilence: boolean;
  /**
   * The rate is withheld (outcome coverage below the minimum): the measure and every part have
   * `value: null` and only `n` is kept, so no shares can be drawn.
   */
  withheld: boolean;
}

/** The silence share beyond which the rate is flagged as mostly assumed (plan §4.4). */
export const SILENCE_WARN_SHARE = 0.5;

/**
 * Reads a `resolutionRate` breakdown. Shares are computed from the parts, so
 * the result is right whether the backend sends each source as a contribution
 * to the rate (summing to it) or as counts. Returns null when the measure has
 * no usable breakdown — the card then says so rather than faking a split.
 */
export function resolutionSplit(measure: Pick<Measure, "breakdown"> & Partial<Pick<Measure, "value">>): ResolutionSplit | null {
  const breakdown = measure.breakdown;
  if (!breakdown || breakdown.length === 0) return null;
  const byKey = new Map(breakdown.map((b) => [b.key, b]));
  const raw = RESOLUTION_KEYS.map((key) => ({
    key,
    value: Math.max(0, byKey.get(key)?.value ?? 0),
    n: byKey.get(key)?.n ?? 0,
  }));
  const total = raw.reduce((sum, p) => sum + p.value, 0);
  const parts = raw.map((p) => ({ ...p, share: total > 0 ? p.value / total : 0 }));
  const silenceShare = parts.find((p) => p.key === "silence")?.share ?? 0;
  // A null rate with null parts is a withheld rate, not a rate of zero.
  const withheld = (measure.value === null || measure.value === undefined) && breakdown.every((b) => b.value === null || b.value === undefined);
  return { parts, total, silenceShare, mostlySilence: total > 0 && silenceShare > SILENCE_WARN_SHARE, withheld };
}

/** True when the measure is the resolution rate (always shown split). */
export const isResolutionRate = (m: Pick<Measure, "id">): boolean => m.id === "resolutionRate";

// ── Labels and small pure helpers shared by several components ─────

/** The threshold T the backend puts in the Apdex label ("Response time (Apdex, T = 4 s)"). */
const APDEX_T = /T\s*=\s*([^)]+)\)/;

/**
 * A measure the UI knows by id gets a translated label; an unknown id renders the backend's
 * own. The Apdex label names its threshold T, which is data and not wording, so the translated
 * label takes it from the backend's: "Response time (Apdex, T = 4 s)" must not shrink to "Apdex".
 */
export function measureLabel(t: TFunction, m: Pick<Measure, "id" | "label">): string {
  if (m.id === "apdex") {
    const threshold = APDEX_T.exec(m.label)?.[1]?.trim();
    if (threshold) return t("health.measures.apdexWithT", { t: threshold, defaultValue: "Response time (Apdex, T = {{t}})" });
  }
  return t(`health.measures.${m.id.replace(/[^A-Za-z0-9]/g, "_")}`, m.label);
}

/**
 * Measures with no variance to test: their `significant` is always false, which means "not
 * tested", not "within normal variation" (planning/agent-health-api.md section 12).
 */
const UNTESTABLE = new Set(["turnDurationP95", "costPerSession", "tokensPerTurn", "turnsPerSession", "guardrailTripRate", "returningUserShare", "outcomeCoverage"]);

/** True when the backend never tests this measure's change for significance. */
export const isUntestable = (m: Pick<Measure, "id">): boolean => UNTESTABLE.has(m.id);

const SECTION_TITLE: Record<string, string> = {
  reliability: "Reliability",
  effectiveness: "Effectiveness",
  quality: "Quality",
  efficiency: "Efficiency",
  adoption: "Adoption",
  business: "Business outcomes",
};

/** Translated section title; an unknown section id renders as itself. */
export function sectionTitle(t: TFunction, id: string): string {
  return t(`health.sections.${id}`, SECTION_TITLE[id] ?? id);
}

/** The i18n key and English fallback for a status; a null or unknown status is shown as "not enough data". */
export function statusLabelKey(status: HealthStatus): [key: string, fallback: string] {
  const meta = STATUS_META[status] ?? STATUS_META.NOT_ENOUGH_DATA;
  return [`health.status.${meta.key}`, meta.fallback];
}

/**
 * A raw backend enum (outcome, evidence, origin) in the app's language. An
 * unknown value renders as itself rather than as a missing key.
 */
export function enumLabel(t: TFunction, group: "outcome" | "evidence" | "origin", value: string): string {
  return t(`health.enums.${group}.${value.replace(/[^A-Za-z0-9_]/g, "_")}`, value);
}

/** The version options: newest first, capped so a 200-version agent stays usable. */
export function versionOptions(latest: number, cap = 25): number[] {
  const out: number[] = [];
  for (let v = latest; v >= 1 && out.length < cap; v--) out.push(v);
  return out;
}

/** Known sections first, in the contract's order; any section the backend adds follows. */
export function orderedSections(sections: AgentHealth["sections"]): string[] {
  const known: string[] = SECTION_IDS.filter((id) => sections[id]);
  // A section the backend sent as null is skipped, not rendered.
  return [...known, ...Object.keys(sections).filter((id) => !known.includes(id) && sections[id])];
}

/** The sections an owner report can carry. `quality` needs the creator to read ratings. */
export const REPORT_SECTIONS = ["reliability", "effectiveness", "quality", "efficiency", "adoption", "business"] as const;
export const EXPIRY_DAYS = [7, 30, 90] as const;

/** Sections the creator may include: quality only when the panel could read it. */
export function allowedReportSections(canReadQuality: boolean): string[] {
  return REPORT_SECTIONS.filter((s) => s !== "quality" || canReadQuality);
}

/** better === null means the measure has no direction (informational). */
export function compareVerdict(d: Pick<CompareDelta, "better" | "significant" | "delta">): "better" | "worse" | "neutral" {
  if (!d.significant || d.better === null || d.better === undefined || !d.delta) return "neutral";
  return d.better ? "better" : "worse";
}
