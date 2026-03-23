import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { scanOrphans, purgeOrphans } from "@/lib/api/orphans";

const ORPHANS_KEY = ["orphans"] as const;

export function useOrphanScan(includeDeleted = false) {
  return useQuery({
    queryKey: [...ORPHANS_KEY, { includeDeleted }],
    queryFn: () => scanOrphans(includeDeleted),
    enabled: false, // Manual trigger via refetch()
  });
}

export function usePurgeOrphans() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (includeDeleted: boolean) => purgeOrphans(includeDeleted),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ORPHANS_KEY });
      // Also invalidate resource-related queries since resources were deleted
      queryClient.invalidateQueries({ queryKey: ["agents"] });
      queryClient.invalidateQueries({ queryKey: ["packages"] });
      queryClient.invalidateQueries({ queryKey: ["resources"] });
    },
  });
}
