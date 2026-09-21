import type { TFunction } from "i18next";

/** Maps a cascade escalation `reason` to its i18n key + English fallback. */
const REASON_KEYS: Record<string, [string, string]> = {
  low_confidence: ["cascadeTrace.reason.lowConfidence", "low confidence"],
  timeout: ["cascadeTrace.reason.timeout", "timeout"],
  error: ["cascadeTrace.reason.error", "error"],
  retryable_error: ["cascadeTrace.reason.retryableError", "retryable error"],
};

/** Human-readable label for a cascade escalation reason. */
export function cascadeReasonText(t: TFunction, reason?: string): string {
  const entry = reason ? REASON_KEYS[reason] : undefined;
  return entry ? t(entry[0], entry[1]) : (reason ?? "");
}
