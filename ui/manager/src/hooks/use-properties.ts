import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  readProperties,
  deleteProperties,
  type Properties,
} from "@/lib/api/properties";

export function useUserProperties(userId: string) {
  return useQuery<Properties, Error>({
    queryKey: ["user-properties", userId],
    queryFn: () => readProperties(userId),
    enabled: !!userId.trim(),
  });
}

export function useDeleteProperties() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: (userId) => deleteProperties(userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["user-properties"] });
    },
  });
}
