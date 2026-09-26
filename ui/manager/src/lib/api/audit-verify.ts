import { api } from "../api-client";

/**
 * The audit ledger's integrity check, as the backend computes it.
 *
 * Kept apart from `audit.ts` (the trail reads) because it answers a different
 * question: the trail says what was recorded, this says whether the record can
 * be trusted. The audit screen used to answer the second question from the
 * first — a green shield whenever every entry merely *had* an `hmac` field —
 * so a forged or edited entry, or a deleted one, looked exactly as intact as a
 * genuine one.
 */

/** Per-entry outcome (`AuditVerificationStatus`). */
export type AuditEntryStatus =
  | "VALID"
  | "VALID_RECOVERED"
  | "INVALID"
  | "UNSIGNED"
  | "SIGNING_DISABLED"
  /** A key this deployment recorded as having signed entries, but no longer holds. */
  | "UNKNOWN_KEY";

/** Continuity of the per-conversation sequence chain. */
export type AuditChainStatus = "INTACT" | "BROKEN" | "INCOMPLETE" | "UNAVAILABLE" | "NOT_APPLICABLE";

export interface AuditEntryProblem {
  entryId: string;
  conversationId: string;
  sequence: number;
  timestamp: string;
  status: AuditEntryStatus;
  hmacVersion?: string | null;
}

/** `AuditVerificationReport` — see the Java record for the full semantics. */
export interface AuditVerificationReport {
  scope: "conversation" | "agent";
  scopeId: string;
  /** False: this deployment has no signing key, so nothing was checked. */
  signingEnabled: boolean;
  entriesChecked: number;
  valid: number;
  recovered: number;
  /** Pre-v4 entries counted in `invalid` that were not searched — "not proven", not "disproven". */
  recoverySkipped: number;
  invalid: number;
  unsigned: number;
  chainStatus: AuditChainStatus;
  missingSequences: number[];
  undeliveredSequences: number[];
  duplicateSequences: number[];
  problems: AuditEntryProblem[];
  verifiedAt: string;
}

const BASE = "/auditstore/verify";

/** `GET /auditstore/verify/{conversationId}` — HMACs plus the sequence chain. */
export function verifyConversationAudit(conversationId: string): Promise<AuditVerificationReport> {
  return api.get<AuditVerificationReport>(`${BASE}/${encodeURIComponent(conversationId)}`);
}

/** `GET /auditstore/verify/agent/{agentId}` — HMACs only; the chain is not applicable across conversations. */
export function verifyAgentAudit(agentId: string, agentVersion?: number | null): Promise<AuditVerificationReport> {
  const query = agentVersion != null ? `?agentVersion=${agentVersion}` : "";
  return api.get<AuditVerificationReport>(`${BASE}/agent/${encodeURIComponent(agentId)}${query}`);
}

/**
 * What the screen may claim about a report.
 *
 * - `verified` — every checked entry's signature recomputed and the chain has
 *   no hole: the only state that earns a green shield.
 * - `tampered` — at least one signature is disproven, or the chain is broken
 *   (an entry was removed).
 * - `unverified` — nothing is disproven, but something could not be checked:
 *   unsigned entries, a key this deployment no longer holds, legacy entries the
 *   recovery budget did not reach, ledger-side gaps, duplicates.
 * - `signing-disabled` — no signing key, so nothing was checked at all.
 *
 * Mirrors `AuditVerificationReport.intact()` / `tamperingSuspected()`, which the
 * backend does not serialize, with one refinement: an `UNKNOWN_KEY` entry is
 * counted in `invalid` there, but it means "the key to check this is missing",
 * not "this was forged", so it lands in `unverified`.
 */
export type AuditVerdict = "verified" | "tampered" | "unverified" | "signing-disabled";

export function auditVerdict(report: AuditVerificationReport): AuditVerdict {
  if (!report.signingEnabled) return "signing-disabled";
  const unknownKey = unknownKeyCount(report);
  const disproven = Math.max(0, report.invalid - Math.min(report.recoverySkipped, report.invalid) - unknownKey);
  if (disproven > 0 || report.chainStatus === "BROKEN") return "tampered";
  const chainOk = report.chainStatus === "INTACT" || report.chainStatus === "NOT_APPLICABLE";
  const clean =
    report.invalid === 0 && report.unsigned === 0 && chainOk && (report.duplicateSequences?.length ?? 0) === 0;
  return clean ? "verified" : "unverified";
}

/** Entries signed with a key this deployment no longer holds. */
export function unknownKeyCount(report: AuditVerificationReport): number {
  return (report.problems ?? []).filter((p) => p.status === "UNKNOWN_KEY").length;
}
