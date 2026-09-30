import { useQuery } from "@tanstack/react-query";
import {
  verifyAgentAudit,
  verifyConversationAudit,
  type AuditVerificationReport,
} from "@/lib/api/audit-verify";

/** A report plus when it was requested (epoch ms, taken before the request is sent). */
export type TimedAuditVerificationReport = AuditVerificationReport & { requestedAt: number };

/**
 * Ask the backend to verify the audit trail the screen is showing.
 *
 * One query per scope: a conversation (HMACs and the sequence chain) or an
 * agent (HMACs only). Disabled until there is something to verify. The backend
 * checks the most recent entries (its default window), independent of how many
 * pages the screen has loaded — the report says how many it checked, and the
 * screen stops showing a verified verdict once it has loaded more rows than
 * that (`uncoveredCount`).
 *
 * No timer of its own: a report only means something relative to the rows on
 * screen, so the page decides when to re-verify — after the trail has refreshed
 * — and compares `requestedAt` with when those rows arrived. A report requested
 * before the newest rows were read never looked at them.
 */
export function useAuditVerification(args: {
  mode: "conversation" | "agent";
  id: string;
  agentVersion?: number;
  enabled?: boolean;
}) {
  const { mode, id, agentVersion, enabled = true } = args;
  return useQuery({
    queryKey: ["audit", "verify", mode, id, mode === "agent" ? (agentVersion ?? null) : null],
    queryFn: async (): Promise<TimedAuditVerificationReport> => {
      const requestedAt = Date.now();
      const report = await (mode === "conversation"
        ? verifyConversationAudit(id)
        : verifyAgentAudit(id, agentVersion));
      return { ...report, requestedAt };
    },
    enabled: !!id && enabled,
  });
}
