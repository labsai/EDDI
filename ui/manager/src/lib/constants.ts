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
