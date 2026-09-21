/**
 * Shared constants used across the EDDI Manager application.
 * Extracting these here avoids coupling page-level modules to
 * the agents API just for configuration constants.
 */

/** Available deployment environments */
export const ENVIRONMENTS = ["production", "test"] as const;
export type Environment = (typeof ENVIRONMENTS)[number];

/**
 * Narrows a free-form string to an {@link Environment}.
 *
 * Some environments arrive as plain strings — the operator's own config is read
 * back from a backend global variable, so it is data, not a literal. Falling
 * back to production matches the backend's own `@DefaultValue("production")`
 * rather than inventing a third behaviour.
 */
export function toEnvironment(value: string | undefined | null): Environment {
  return (ENVIRONMENTS as readonly string[]).includes(value ?? "")
    ? (value as Environment)
    : "production";
}

/** Tailwind classes for capability confidence badges */
export const CONFIDENCE_COLORS: Record<string, string> = {
  high: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20",
  medium: "bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20",
  low: "bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/20",
};

/**
 * SSE reconnect policy. Applied by `sse-reconnect.ts`, which is the only place
 * these are read — change the numbers here, not the behaviour there.
 */

/** Delay before the first retry (ms). Doubles each attempt, capped by MAX_DELAY. */
export const SSE_RECONNECT_BASE_MS = 5000;

/** Retries before giving up. A stream that is refused will stay refused. */
export const SSE_RECONNECT_MAX_ATTEMPTS = 10;

/** Ceiling on the doubling (ms), so a long outage settles at one try a minute. */
export const SSE_RECONNECT_MAX_DELAY_MS = 60_000;
