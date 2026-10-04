/**
 * Date/time formatting for admin tables where "which day, which zone" matters
 * (logs, audit, schedules). Time-only strings made rows from different days
 * indistinguishable and exports unusable across timezones.
 */

type Ts = number | string | Date | null | undefined;

function toDate(ts: Ts): Date | null {
  if (ts === null || ts === undefined || ts === "") return null;
  const d = ts instanceof Date ? ts : new Date(ts);
  return Number.isNaN(d.getTime()) ? null : d;
}

const pad = (n: number, w = 2) => String(Math.abs(Math.trunc(n))).padStart(w, "0");

/**
 * ISO-8601 with the viewer's UTC offset, millisecond precision - for exports
 * and clipboard copies, where the display zone is not visible.
 * `2026-10-04T11:11:05.123+02:00`
 */
export function toIsoWithOffset(ts: Ts): string {
  const d = toDate(ts);
  if (!d) return "";
  const off = -d.getTimezoneOffset();
  const sign = off >= 0 ? "+" : "-";
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}` +
    `${sign}${pad(off / 60)}:${pad(off % 60)}`
  );
}

/**
 * Locale date + time with seconds and a UTC offset
 * (`Oct 4, 2026, 11:11:05 AM GMT+2`).
 */
export function formatDateTimeWithZone(ts: Ts, withZone = true): string {
  const d = toDate(ts);
  if (!d) return "—";
  try {
    return new Intl.DateTimeFormat(undefined, {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      ...(withZone ? { timeZoneName: "shortOffset" as const } : {}),
    }).format(d);
  } catch {
    return d.toLocaleString();
  }
}

/** Calendar date only (`Oct 4, 2026`). */
export function formatDateOnly(ts: Ts): string {
  const d = toDate(ts);
  if (!d) return "—";
  return new Intl.DateTimeFormat(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  }).format(d);
}
