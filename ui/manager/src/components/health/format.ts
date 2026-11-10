import { formatDuration, formatUsd } from "./deps";
import type { TFunction } from "i18next";
import {
  SECTION_IDS,
  type AgentHealth,
  type Band,
  type CompareDelta,
  type HealthStatus,
  type Measure,
  type MeasureUnit,
} from "@/lib/api/health";

/**
 * Pure formatting and interpretation for the Health UI — no React, no I/O.
 * Numbers arrive raw from the backend; units, locale and sign are decided here
 * (contract §10).
 */

function locale(): string | undefined {
  if (typeof document === "undefined") return undefined;
  return document.documentElement.lang || undefined;
}

function number(value: number, opts: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(locale(), opts).format(value);
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
 * point, not "+100 %".
 */
export function formatDelta(delta: number | null | undefined, unit: MeasureUnit): string {
  if (delta === null || delta === undefined || !Number.isFinite(delta)) return "—";
  const sign = delta > 0 ? "+" : delta < 0 ? "−" : "±";
  const abs = Math.abs(delta);
  if (unit === "ratio") {
    const pp = abs * 100;
    return `${sign}${number(pp, { maximumFractionDigits: pp < 10 ? 1 : 0 })} pp`;
  }
  return `${sign}${formatMeasureValue(abs, unit)}`;
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
  /** Border, tint and text classes — semantic tokens only. */
  classes: string;
  /** Text-colour classes; SVG marks use `currentColor` so they follow the theme. */
  stroke: string;
}

export const BAND_META: Record<Band, BandMeta> = {
  GREEN: { key: "green", fallback: "On target", classes: "border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400", stroke: "text-emerald-600 dark:text-emerald-400" },
  AMBER: { key: "amber", fallback: "Watch", classes: "border-warning/40 bg-warning/10 text-warning", stroke: "text-warning" },
  RED: { key: "red", fallback: "Off target", classes: "border-destructive/40 bg-destructive/10 text-destructive", stroke: "text-destructive" },
  GREY: { key: "grey", fallback: "Not rated", classes: "border-border bg-muted text-muted-foreground", stroke: "text-muted-foreground" },
};

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

export const STATUS_META: Record<HealthStatus, StatusMeta> = {
  NEEDS_ATTENTION: { key: "needsAttention", fallback: "Needs attention", classes: BAND_META.RED.classes, rank: 3 },
  WATCH: { key: "watch", fallback: "Watch", classes: BAND_META.AMBER.classes, rank: 2 },
  HEALTHY: { key: "healthy", fallback: "Healthy", classes: BAND_META.GREEN.classes, rank: 1 },
  NOT_ENOUGH_DATA: { key: "notEnoughData", fallback: "Not enough data", classes: BAND_META.GREY.classes, rank: 0 },
};

// ── Resolution split (plan §4.4, decision 3) ──────────────────────

export const RESOLUTION_KEYS = ["confirmed", "agentClosed", "silence"] as const;
export type ResolutionKey = (typeof RESOLUTION_KEYS)[number];

export interface ResolutionSplit {
  /** Each source's value, as the backend sent it. */
  parts: Array<{ key: ResolutionKey; value: number; n: number; share: number }>;
  total: number;
  /** Silence share of the resolutions, 0..1. */
  silenceShare: number;
  /** More than half of the resolutions rest on users going quiet. */
  mostlySilence: boolean;
}

/** The silence share beyond which the rate is flagged as mostly assumed (plan §4.4). */
export const SILENCE_WARN_SHARE = 0.5;

/**
 * Reads a `resolutionRate` breakdown. Shares are computed from the parts, so
 * the result is right whether the backend sends each source as a contribution
 * to the rate (summing to it) or as counts. Returns null when the measure has
 * no usable breakdown — the card then says so rather than faking a split.
 */
export function resolutionSplit(measure: Pick<Measure, "breakdown">): ResolutionSplit | null {
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
  return { parts, total, silenceShare, mostlySilence: total > 0 && silenceShare > SILENCE_WARN_SHARE };
}

/** True when the measure is the resolution rate (always shown split). */
export const isResolutionRate = (m: Pick<Measure, "id">): boolean => m.id === "resolutionRate";

// ── Labels and small pure helpers shared by several components ─────

/** A measure the UI knows by id gets a translated label; an unknown id renders the backend's own. */
export function measureLabel(t: TFunction, m: Pick<Measure, "id" | "label">): string {
  return t(`health.measures.${m.id.replace(/[^A-Za-z0-9]/g, "_")}`, m.label);
}

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

export function statusLabelKey(status: HealthStatus): [key: string, fallback: string] {
  const meta = STATUS_META[status] ?? STATUS_META.NOT_ENOUGH_DATA;
  return [`health.status.${meta.key}`, meta.fallback];
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
  return [...known, ...Object.keys(sections).filter((id) => !known.includes(id))];
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
