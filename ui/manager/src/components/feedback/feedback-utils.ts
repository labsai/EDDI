import type { RatingKind, RatingQuery, ReviewStatus } from "@/lib/api/ratings";

/** The periods the Feedback page offers. "all" has no previous period to compare with. */
export const PERIODS = ["7d", "30d", "90d", "all"] as const;
export type Period = (typeof PERIODS)[number];

export type FeedbackTab = "attention" | "all";

/** The page's filters, as they live in the URL. */
export interface FeedbackFilters {
  tab: FeedbackTab;
  agentId: string;
  agentVersion: string;
  environment: string;
  period: Period;
  kind: "" | RatingKind;
  tag: string;
  comment: "" | "yes" | "no";
  score: "" | "low" | "high";
  status: "" | ReviewStatus;
}

const DAY_MS = 86_400_000;

function periodDays(period: Period): number | null {
  return period === "all" ? null : Number(period.slice(0, -1));
}

export function readFilters(params: URLSearchParams): FeedbackFilters {
  const pick = <T extends string>(key: string, allowed: readonly T[], fallback: T): T => {
    const value = params.get(key) as T | null;
    return value !== null && allowed.includes(value) ? value : fallback;
  };
  return {
    tab: pick("tab", ["attention", "all"] as const, "attention"),
    agentId: params.get("agentId") ?? "",
    agentVersion: /^\d+$/.test(params.get("agentVersion") ?? "") ? params.get("agentVersion")! : "",
    environment: pick("environment", ["", "production", "test"] as const, ""),
    period: pick("period", PERIODS, "30d"),
    kind: pick("kind", ["", "conversation", "message"] as const, ""),
    tag: params.get("tag") ?? "",
    comment: pick("comment", ["", "yes", "no"] as const, ""),
    score: pick("score", ["", "low", "high"] as const, ""),
    status: pick("status", ["", "NEW", "REVIEWED"] as const, ""),
  };
}

/** The URL with one filter changed (defaults removed, so links stay short). */
export function writeFilter(params: URLSearchParams, key: keyof FeedbackFilters, value: string): URLSearchParams {
  const next = new URLSearchParams(params);
  const isDefault = value === "" || (key === "tab" && value === "attention") || (key === "period" && value === "30d");
  if (isDefault) next.delete(key);
  else next.set(key, value);
  return next;
}

/** [from, to) of a period ending now, or of the period before it (`offset` 1). */
export function periodRange(period: Period, now: number, offset = 0): { from?: string; to?: string } {
  const days = periodDays(period);
  if (days === null) return {};
  const to = now - offset * days * DAY_MS;
  return { from: new Date(to - days * DAY_MS).toISOString(), to: offset === 0 ? undefined : new Date(to).toISOString() };
}

/** The backend query of the "All ratings" tab. */
export function allRatingsQuery(f: FeedbackFilters, now: number, offset = 0): RatingQuery {
  return {
    agentId: f.agentId || undefined,
    agentVersion: f.agentVersion ? Number(f.agentVersion) : undefined,
    environment: f.environment || undefined,
    ...periodRange(f.period, now, offset),
    kind: f.kind || undefined,
    tag: f.tag || undefined,
    hasComment: f.comment === "" ? undefined : f.comment === "yes",
    minScore: f.score === "high" ? 0.75 : undefined,
    maxScore: f.score === "low" ? 0.5 : undefined,
    reviewStatus: f.status || undefined,
  };
}

/** The backend query of the "Needs attention" tab. */
export function attentionQuery(f: FeedbackFilters): RatingQuery {
  return { agentId: f.agentId || undefined, needsAttention: true };
}

/** A 0..1 share as a whole percentage, or an em dash. */
export function percent(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : `${Math.round(value * 100)}%`;
}

/**
 * The change from `previous` to `current`, as signed whole percentage points
 * ("+4 pp"), or null when either side is unknown.
 */
export function pointDelta(current: number | null | undefined, previous: number | null | undefined): string | null {
  if (current === null || current === undefined || previous === null || previous === undefined) return null;
  const points = Math.round((current - previous) * 100);
  return `${points > 0 ? "+" : ""}${points} pp`;
}

/** The change of a count, as a signed number ("+12"), or null when there is no previous period. */
export function countDelta(current: number | undefined, previous: number | undefined): string | null {
  if (current === undefined || previous === undefined) return null;
  const change = current - previous;
  return `${change > 0 ? "+" : ""}${change}`;
}
