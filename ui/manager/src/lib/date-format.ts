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

// ─── Wall-clock <-> instant in a named IANA zone ────────────────────────────

function zoneParts(ms: number, timeZone: string): Record<string, number> {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(new Date(ms));
  const out: Record<string, number> = {};
  for (const p of parts) if (p.type !== "literal") out[p.type] = Number(p.value);
  return out;
}

/** Offset of `timeZone` from UTC at instant `ms`, in milliseconds. */
function zoneOffsetMs(ms: number, timeZone: string): number {
  const p = zoneParts(ms, timeZone);
  const asUtc = Date.UTC(p.year!, p.month! - 1, p.day!, p.hour!, p.minute!, p.second!);
  return asUtc - Math.floor(ms / 1000) * 1000;
}

/**
 * Interpret a `datetime-local` value (`2026-10-04T09:30`) as wall-clock time in
 * `timeZone` and return the ISO-8601 instant, or null when empty/invalid.
 *
 * `new Date("2026-10-04T09:30")` always means the BROWSER's zone, which is wrong
 * for a form that also asks which zone the schedule runs in. Two offset passes
 * settle DST edges; a wall-clock time inside a spring-forward gap resolves to
 * the instant just after it.
 */
export function zonedLocalInputToIso(local: string, timeZone: string): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(local);
  if (!m) return null;
  const guess = Date.UTC(+m[1]!, +m[2]! - 1, +m[3]!, +m[4]!, +m[5]!, +(m[6] ?? 0));
  if (Number.isNaN(guess)) return null;
  try {
    let ms = guess - zoneOffsetMs(guess, timeZone);
    ms = guess - zoneOffsetMs(ms, timeZone);
    return new Date(ms).toISOString();
  } catch {
    return null; // unknown zone
  }
}

/** Inverse of {@link zonedLocalInputToIso}: an instant as a `datetime-local` value in `timeZone`. */
export function isoToZonedLocalInput(iso: string | null | undefined, timeZone: string): string {
  const d = toDate(iso);
  if (!d) return "";
  try {
    const p = zoneParts(d.getTime(), timeZone);
    return `${p.year}-${pad(p.month!)}-${pad(p.day!)}T${pad(p.hour!)}:${pad(p.minute!)}`;
  } catch {
    return "";
  }
}
