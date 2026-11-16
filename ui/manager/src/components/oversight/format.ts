/** A rate as a percentage, or a dash. */
export function percent(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : `${Math.round(value * 1000) / 10}%`;
}

/** A latency in the largest unit that keeps it readable. */
export function duration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "—";
  if (ms < 60_000) return `${Math.round(ms / 1000)} s`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)} min`;
  if (ms < 86_400_000) return `${Math.round((ms / 3_600_000) * 10) / 10} h`;
  return `${Math.round((ms / 86_400_000) * 10) / 10} d`;
}
