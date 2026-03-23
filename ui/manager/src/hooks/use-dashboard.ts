import { useQuery } from "@tanstack/react-query";
import { getDashboardStats, type DashboardStats } from "@/lib/api/dashboard";
import { useAgentDescriptors } from "./use-agents";

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
