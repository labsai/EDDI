import { useQuery } from "@tanstack/react-query";
import { verifyAgentAudit, verifyConversationAudit } from "@/lib/api/audit-verify";

/**
 * Ask the backend to verify the audit trail the screen is showing.
 *
 * One query per scope: a conversation (HMACs and the sequence chain) or an
 * agent (HMACs only). Disabled until there is something to verify. The backend
 * checks the most recent entries (its default window), independent of how many
 * pages the screen has loaded — the report says how many it checked.
 */
export function useAuditVerification(args: {
  mode: "conversation" | "agent";
  id: string;
  agentVersion?: number;
  refetchInterval?: number | false;
}) {
  const { mode, id, agentVersion, refetchInterval = false } = args;
  return useQuery({
    queryKey: ["audit", "verify", mode, id, mode === "agent" ? (agentVersion ?? null) : null],
    queryFn: () =>
      mode === "conversation" ? verifyConversationAudit(id) : verifyAgentAudit(id, agentVersion),
    enabled: !!id,
    refetchInterval,
  });
}
