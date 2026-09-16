import { useQuery } from "@tanstack/react-query";
import {
  getAuditTrail,
  getAuditTrailByAgent,
  getEntryCount,
} from "@/lib/api/audit";

/* ─── Query Keys ─── */

const KEYS = {
  trail: (conversationId: string, skip: number, limit: number) =>
    ["audit", "trail", conversationId, skip, limit] as const,
  trailByAgent: (agentId: string, agentVersion: number | null | undefined, skip: number, limit: number) =>
    ["audit", "trail-by-agent", agentId, agentVersion, skip, limit] as const,
  count: (conversationId: string) =>
    ["audit", "count", conversationId] as const,
};

/* ─── Queries ─── */

/** Fetch audit entries for a conversation. Disabled when conversationId is empty. */
export function useAuditTrail(conversationId: string, skip = 0, limit = 100) {
  return useQuery({
    queryKey: KEYS.trail(conversationId, skip, limit),
    queryFn: () => getAuditTrail(conversationId, skip, limit),
    enabled: !!conversationId,
  });
}

/** Fetch audit entries for a agent. Disabled when agentId is empty. */
export function useAuditTrailByAgent(
  agentId: string,
  agentVersion?: number | null,
  skip = 0,
  limit = 100,
) {
  return useQuery({
    queryKey: KEYS.trailByAgent(agentId, agentVersion, skip, limit),
    queryFn: () => getAuditTrailByAgent(agentId, agentVersion, skip, limit),
    enabled: !!agentId,
  });
}

/** Fetch audit entry count for a conversation. Disabled when conversationId is empty. */
export function useAuditEntryCount(conversationId: string) {
  return useQuery({
    queryKey: KEYS.count(conversationId),
    queryFn: () => getEntryCount(conversationId),
    enabled: !!conversationId,
  });
}
