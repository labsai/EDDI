/**
 * Number + unit view of the simple ISO-8601 durations the group wizard writes
 * (`PT30M`, `PT24H`, `P2D`), so people pick "24 hours" instead of typing the
 * grammar. Anything else is not representable: `parseDurationParts` returns
 * null and the caller keeps the raw string.
 */
export type DurationUnit = "minutes" | "hours" | "days";

export const DURATION_UNITS: readonly DurationUnit[] = ["minutes", "hours", "days"];

export function parseDurationParts(
  iso: string,
): { amount: number; unit: DurationUnit } | null {
  const trimmed = iso.trim();
  let match = /^PT(\d+)M$/.exec(trimmed);
  if (match) return { amount: Number(match[1]), unit: "minutes" };
  match = /^PT(\d+)H$/.exec(trimmed);
  if (match) return { amount: Number(match[1]), unit: "hours" };
  match = /^P(\d+)D$/.exec(trimmed);
  if (match) return { amount: Number(match[1]), unit: "days" };
  return null;
}

/** Serialise to ISO-8601. A zero amount yields a (deliberately non-positive) duration the validators reject. */
export function buildIsoDuration(amount: number, unit: DurationUnit): string {
  switch (unit) {
    case "minutes":
      return `PT${amount}M`;
    case "hours":
      return `PT${amount}H`;
    case "days":
      return `P${amount}D`;
  }
}
