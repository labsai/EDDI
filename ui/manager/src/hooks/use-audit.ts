import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { isApiError } from "@/lib/api-client";
import {
  getAdminActions,
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

/**
 * Administrative actions, loaded a page at a time (newest first). `actor` is a
 * server-side filter, so a new actor starts a new list. Not retried on 401/403/404:
 * those answer "not an admin" and "this EDDI does not record them", and
 * retrying only delays saying so.
 */
export function useAdminActions(actor: string, pageSize = 100) {
  return useInfiniteQuery({
    queryKey: ["audit", "admin-actions", actor, pageSize] as const,
    queryFn: ({ pageParam }) => getAdminActions(actor || null, pageParam, pageSize),
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) =>
      lastPage.length < pageSize ? undefined : allPages.reduce((n, page) => n + page.length, 0),
    retry: (failureCount, error) =>
      !(isApiError(error) && [401, 403, 404].includes(error.status)) && failureCount < 2,
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
