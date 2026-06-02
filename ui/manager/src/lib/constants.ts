/**
 * Shared constants used across the EDDI Manager application.
 * Extracting these here avoids coupling page-level modules to
 * the agents API just for configuration constants.
 */

/** Available deployment environments */
export const ENVIRONMENTS = ["production", "test"] as const;
export type Environment = (typeof ENVIRONMENTS)[number];

/** Tailwind classes for capability confidence badges */
export const CONFIDENCE_COLORS: Record<string, string> = {
  high: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20",
  medium: "bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20",
  low: "bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/20",
};

/** Base delay for SSE reconnection (ms). Doubles on each retry up to MAX_SSE_RECONNECT_ATTEMPTS. */
export const SSE_RECONNECT_BASE_MS = 5000;

/** Maximum number of SSE reconnection attempts before giving up. */
export const SSE_RECONNECT_MAX_ATTEMPTS = 10;

/** Maximum delay between SSE reconnection attempts (ms). Caps exponential backoff. */
export const SSE_RECONNECT_MAX_DELAY_MS = 60_000;
