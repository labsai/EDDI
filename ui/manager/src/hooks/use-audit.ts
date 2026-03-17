import { useQuery } from "@tanstack/react-query";
import {
  getAuditTrail,
  getAuditTrailByBot,
  getEntryCount,
} from "@/lib/api/audit";

/* ─── Query Keys ─── */

const KEYS = {
  trail: (conversationId: string, skip: number, limit: number) =>
    ["audit", "trail", conversationId, skip, limit] as const,
  trailByBot: (botId: string, botVersion: number | null | undefined, skip: number, limit: number) =>
    ["audit", "trail-by-bot", botId, botVersion, skip, limit] as const,
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

/** Fetch audit entries for a bot. Disabled when botId is empty. */
export function useAuditTrailByBot(
  botId: string,
  botVersion?: number | null,
  skip = 0,
  limit = 100,
) {
  return useQuery({
    queryKey: KEYS.trailByBot(botId, botVersion, skip, limit),
    queryFn: () => getAuditTrailByBot(botId, botVersion, skip, limit),
    enabled: !!botId,
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
