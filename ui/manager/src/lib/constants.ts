/**
 * Shared constants used across the EDDI Manager application.
 * Extracting these here avoids coupling page-level modules to
 * the agents API just for configuration constants.
 */

/** Available deployment environments */
export const ENVIRONMENTS = ["production", "test"] as const;
export type Environment = (typeof ENVIRONMENTS)[number];
