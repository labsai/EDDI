import { useQuery } from "@tanstack/react-query";
import { getCatalogAgents, getCatalogGroups } from "@/lib/api/catalog";

/** Keys of the end-user catalog. Group writes invalidate `catalogKeys.all` too. */
export const catalogKeys = {
  all: ["catalog"] as const,
  agents: ["catalog", "agents"] as const,
  groups: ["catalog", "groups"] as const,
};

/** `GET /catalog/agents` — listed agents the caller may use. */
export function useCatalogAgents(enabled = true) {
  return useQuery({
    queryKey: catalogKeys.agents,
    queryFn: getCatalogAgents,
    enabled,
    staleTime: 30_000,
  });
}

/** `GET /catalog/groups` — listed groups the caller may use. */
export function useCatalogGroups(enabled = true) {
  return useQuery({
    queryKey: catalogKeys.groups,
    queryFn: getCatalogGroups,
    enabled,
    staleTime: 30_000,
  });
}

/** One catalog group by id, from the cached list; `undefined` while loading or when not listed. */
export function useCatalogGroup(groupId: string | undefined, enabled = true) {
  const query = useCatalogGroups(enabled && !!groupId);
  return {
    ...query,
    group: groupId ? query.data?.find((g) => g.groupId === groupId) : undefined,
  };
}
