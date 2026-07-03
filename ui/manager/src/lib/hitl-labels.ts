import type { TFunction } from "i18next";

/**
 * Shared, localized labels for HITL enum values. Kept in one place so the
 * approval banner and the approvals queue render identical, translated text
 * instead of two hardcoded English copies.
 */

const TIMEOUT_LABEL: Record<string, [key: string, fallback: string]> = {
  WAIT_INDEFINITELY: ["hitl.timeoutWaitIndefinitely", "Wait Indefinitely"],
  AUTO_APPROVE: ["hitl.timeoutAutoApprove", "Auto-Approve"],
  AUTO_REJECT: ["hitl.timeoutAutoReject", "Auto-Reject"],
  ABORT: ["hitl.timeoutAbort", "Abort"],
};

/** Localized label for a HITL timeout-policy enum value. */
export function timeoutPolicyLabel(t: TFunction, policy?: string | null): string {
  if (!policy) return "";
  const entry = TIMEOUT_LABEL[policy];
  return entry ? t(entry[0], entry[1]) : policy;
}

const GRANULARITY_LABEL: Record<string, [key: string, fallback: string]> = {
  PHASE: ["hitl.granularityPhase", "Phase"],
  TASK: ["hitl.granularityTask", "Task"],
};

/** Localized label for a HITL granularity enum value. */
export function granularityLabel(t: TFunction, granularity?: string | null): string {
  if (!granularity) return "";
  const entry = GRANULARITY_LABEL[granularity];
  return entry ? t(entry[0], entry[1]) : granularity;
}

const REJECTION_LABEL: Record<string, [key: string, fallback: string]> = {
  FAIL: ["hitl.rejectionFail", "Fail the task"],
  RETRY: ["hitl.rejectionRetry", "Retry the task"],
};

/** Localized label for a HITL task-rejection policy enum value. */
export function rejectionPolicyLabel(t: TFunction, policy?: string | null): string {
  if (!policy) return "";
  const entry = REJECTION_LABEL[policy];
  return entry ? t(entry[0], entry[1]) : policy;
}
