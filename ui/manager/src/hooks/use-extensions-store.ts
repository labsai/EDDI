import { useQuery } from "@tanstack/react-query";
import { getExtensionTypes } from "@/lib/api/extensions";

const EXTENSIONS_STORE_KEY = ["extensionStore"] as const;

/**
 * Fetch available extension types from /extensionstore/extensions.
 * Data is fairly static so we can cache aggressively.
 */
export function useExtensionTypes(filter = "") {
  return useQuery({
    queryKey: [...EXTENSIONS_STORE_KEY, { filter }],
    queryFn: () => getExtensionTypes(filter),
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
}
