/* ──────────────────────────────────────────────
   ISO-8601 durations as the group HITL config and a human member's turn
   timeout carry them ("PT1H30M"). Moved here from @eddi/app-kit's
   lib/hitl-config.ts (which re-exports them) in phase 6.
   ────────────────────────────────────────────── */

const ISO_DURATION_RE =
  /^P(?:(\d+)D)?(?:T(?=\d)(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i;

/**
 * Parse an ISO-8601 duration to milliseconds, or null if it is not a positive
 * duration the backend would accept. Single source of truth for HITL duration
 * validation and display so the two never drift.
 */
export function parseIsoDurationMs(iso: string): number | null {
  const m = iso.trim().match(ISO_DURATION_RE);
  if (!m) return null;
  const [, d, h, min, s] = m;
  if (!d && !h && !min && !s) return null; // "P" alone
  const seconds =
    parseFloat(d || "0") * 86400 +
    parseFloat(h || "0") * 3600 +
    parseFloat(min || "0") * 60 +
    parseFloat(s || "0");
  return seconds > 0 ? seconds * 1000 : null;
}

/**
 * Validate an ISO-8601 duration string (e.g. "PT15M", "PT1H30M", "P1D",
 * "PT0.5S"). Must be a positive duration accepted by the backend's
 * `java.time.Duration.parse`, so the UI never blocks a valid value nor lets an
 * invalid one through to a save-time 400.
 */
export function isValidIsoDuration(iso: string): boolean {
  return parseIsoDurationMs(iso) !== null;
}

/** Format a positive millisecond duration as "1d 2h" / "5m 3s" / "42s" (top 2 units). */
export function formatDurationMs(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  const d = Math.floor(totalSec / 86400);
  const h = Math.floor((totalSec % 86400) / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const parts: [number, string][] = [[d, "d"], [h, "h"], [m, "m"], [s, "s"]];
  const nonzero = parts.filter(([n]) => n > 0);
  const shown = (nonzero.length ? nonzero : [[0, "s"] as [number, string]]).slice(0, 2);
  return shown.map(([n, u]) => `${n}${u}`).join(" ");
}

/** Format an ISO-8601 duration for display (e.g. "PT1H30M" → "1h 30m"); falls
 *  back to the raw string if it isn't a parseable positive duration. */
export function formatIsoDuration(iso?: string | null): string {
  if (!iso) return "";
  const ms = parseIsoDurationMs(iso);
  return ms == null ? iso : formatDurationMs(ms);
}
