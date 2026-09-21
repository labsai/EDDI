import { useQuery } from "@tanstack/react-query";
import { getDashboardStats, type DashboardStats } from "@/lib/api/dashboard";
import { useAgentDescriptors } from "./use-agents";
import { getConversationDescriptors, type ConversationDescriptor } from "@/lib/api/conversations";
import { getCoordinatorStatus, type CoordinatorStatus } from "@/lib/api/coordinator";

export function useDashboardStats() {
  return useQuery<DashboardStats>({
    queryKey: ["dashboard", "stats"],
    queryFn: getDashboardStats,
    staleTime: 30_000,
  });
}

/** Fetch a small number of recent agents for the dashboard */
export function useRecentAgents() {
  return useAgentDescriptors(4, 0);
}

/** Fetch the N most recent conversations for the dashboard */
export function useRecentConversations(limit = 5) {
  return useQuery<ConversationDescriptor[]>({
    queryKey: ["dashboard", "recent-conversations", limit],
    queryFn: () => getConversationDescriptors(limit),
    staleTime: 30_000,
  });
}

/** Lightweight coordinator status for the dashboard health strip */
export function useCoordinatorStatusLight() {
  return useQuery<CoordinatorStatus>({
    queryKey: ["dashboard", "coordinator-status"],
    queryFn: getCoordinatorStatus,
    staleTime: 60_000,
    retry: 1,
  });
}
