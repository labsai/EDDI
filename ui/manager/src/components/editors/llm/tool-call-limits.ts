/**
 * Server defaults of the tool-call caps (`LlmConfiguration.Task`, EDDI 6.6+).
 * Shown as placeholders only — never written into a configuration.
 */
export const DEFAULT_MAX_TOOL_CALLS_PER_ITERATION = 20;
export const DEFAULT_MAX_TOOL_CALLS_PER_TURN = 100;

/** The engine reads `-1` and `0` (any value ≤ 0) as "no cap". Absent is the default, not uncapped. */
export function isUncappedToolCalls(value: number | undefined | null): boolean {
  return typeof value === "number" && value <= 0;
}
