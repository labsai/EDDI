import { useQuery } from "@tanstack/react-query";
import { getDashboardStats, type DashboardStats } from "@/lib/api/dashboard";
import { useBotDescriptors } from "./use-bots";

export function useDashboardStats() {
  return useQuery<DashboardStats>({
    queryKey: ["dashboard", "stats"],
    queryFn: getDashboardStats,
    staleTime: 30_000,
  });
}

/** Fetch a small number of recent bots for the dashboard */
export function useRecentBots() {
  return useBotDescriptors(4, 0);
}
