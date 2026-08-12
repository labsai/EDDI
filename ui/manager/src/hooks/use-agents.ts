import { useQuery, useInfiniteQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { updateDescriptor } from "@/lib/api/descriptors";
import { agentKeys } from "@/lib/query-keys";
import {
  getAgentDescriptors,
  getAgentDescriptorsWithVersions,
  getAgent,
  createAgent,
  updateAgent,
  deleteAgent,
  duplicateAgent,
  deployAgent,
  undeployAgent,
  getDeploymentStatus,
  getDeploymentStatuses,
  type Agent,
  type AgentDescriptor,
  type EnvironmentStatus,
  parseResourceUri,
} from "@/lib/api/agents";

const PAGE_SIZE = 50;

export function useAgentDescriptors(
  limit = 20,
  index = 0,
  filter = ""
) {
  return useQuery({
    queryKey: agentKeys.descriptors(limit, index, filter),
    queryFn: () => getAgentDescriptors(limit, index, filter),
  });
}

/** Infinite-scroll agent list with offset-based pagination */
export function useInfiniteAgentDescriptors(filter = "") {
  return useInfiniteQuery({
    queryKey: agentKeys.descriptorsInfinite(filter),
    queryFn: ({ pageParam = 0 }) => getAgentDescriptors(PAGE_SIZE, pageParam, filter),
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
      // If we got a full page, there are probably more
      if (lastPage.length === PAGE_SIZE) {
        return allPages.length * PAGE_SIZE;
      }
      return undefined; // no more pages
    },
  });
}

export function useAgent(id: string, version?: number) {
  return useQuery({
    queryKey: [...agentKeys.all, id, version],
    queryFn: () => getAgent(id, version),
    enabled: !!id,
    placeholderData: keepPreviousData,
  });
}

export function useDeploymentStatus(agentId: string, version: number, environment = "production") {
  return useQuery({
    queryKey: [...agentKeys.all, "deployment", environment, agentId, version],
    queryFn: () => getDeploymentStatus(environment, agentId, version),
    enabled: !!agentId && version > 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) => {
      // Poll every 3s while deploying
      return query.state.data?.status === "IN_PROGRESS" ? 3000 : false;
    },
  });
}

export function useAgentVersions(agentId: string) {
  return useQuery({
    queryKey: [...agentKeys.all, "versions", agentId],
    queryFn: () => getAgentDescriptorsWithVersions(agentId),
    enabled: !!agentId,
    // `getAgentDescriptorsWithVersions` issues one descriptor query per version
    // and flattens the results, and the backend's `filter=` is a TEXT match
    // rather than an id lookup. Two consequences the four consumers of this hook
    // all inherited:
    //
    //  - the same version can come back from more than one of those queries, so
    //    the list held duplicates. Rendered as `key={v.version}` in the version
    //    picker, React logged "Encountered two children with the same key" and
    //    reserves the right to drop or duplicate those options.
    //  - a different agent whose id merely CONTAINS this one as a substring
    //    matches the filter, so its versions were offered in this agent's picker
    //    and selecting one navigated to a version that does not exist here.
    //
    // Both are fixed by resolving the id alongside the version and keeping only
    // this agent's, one entry per version.
    select: (descriptors) => {
      const byVersion = new Map<number, { version: number; lastModifiedOn: number; name: string }>();
      for (const d of descriptors) {
        const { id, version } = parseResourceUri(d.resource);
        if (id !== agentId) continue;
        if (!byVersion.has(version)) {
          byVersion.set(version, { version, lastModifiedOn: d.lastModifiedOn, name: d.name });
        }
      }
      return [...byVersion.values()].sort((a, b) => b.version - a.version);
    },
  });
}

export function useUpdateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      agent,
    }: {
      id: string;
      version: number;
      agent: Agent;
    }) => updateAgent(id, version, agent),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useDeploymentStatuses(agentId: string, version: number) {
  return useQuery({
    queryKey: [...agentKeys.all, "deploymentStatuses", agentId, version],
    queryFn: () => getDeploymentStatuses(agentId, version),
    enabled: !!agentId && version > 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data?.some((d) => d.status === "IN_PROGRESS")) return 3000;
      return false;
    },
  });
}

export function useCreateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      agent,
      name,
      description,
    }: {
      agent: Agent;
      name?: string;
      description?: string;
    }) => {
      const response = await createAgent(agent);
      if ((name || description) && response.location) {
        // Location header is a URL path (e.g. /agentstore/agents/id?version=1),
        // not an eddi:// resource URI, so we parse it with a dummy base.
        const url = new URL(response.location, "http://dummy");
        const parts = url.pathname.split("/").filter(Boolean);
        const id = parts[parts.length - 1]!;
        const version = parseInt(url.searchParams.get("version") || "1", 10);
        await updateDescriptor(id, version, { name, description });
      }
      return response;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useDeleteAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      deleteAgent(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useDuplicateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      deepCopy,
    }: {
      id: string;
      version: number;
      deepCopy?: boolean;
    }) => duplicateAgent(id, version, deepCopy),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useDeployAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      environment = "production",
      agentId,
      version,
    }: {
      environment?: string;
      agentId: string;
      version: number;
    }) => deployAgent(environment, agentId, version),
    onMutate: async ({ environment = "production", agentId, version }) => {
      const depKey = [...agentKeys.all, "deployment", environment, agentId, version];
      const depsKey = [...agentKeys.all, "deploymentStatuses", agentId, version];
      await queryClient.cancelQueries({ queryKey: depKey });
      await queryClient.cancelQueries({ queryKey: depsKey });

      const prevDep = queryClient.getQueryData(depKey);
      const prevDeps = queryClient.getQueryData(depsKey);

      queryClient.setQueryData(depKey, (old: Record<string, unknown> | undefined) => ({ ...old, status: "IN_PROGRESS" }));
      queryClient.setQueryData(depsKey, (old: EnvironmentStatus[] | undefined) =>
        old ? old.map((s) => (s.environment === environment ? { ...s, status: "IN_PROGRESS" as const } : s)) : undefined
      );

      return { prevDep, prevDeps, depKey, depsKey };
    },
    onError: (_err, _vars, context) => {
      if (context) {
        queryClient.setQueryData(context.depKey, context.prevDep);
        queryClient.setQueryData(context.depsKey, context.prevDeps);
      }
    },
    onSuccess: (_, { environment = "production", agentId, version }) => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
      queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });

      const depKey = [...agentKeys.all, "deployment", environment, agentId, version];
      const depsKey = [...agentKeys.all, "deploymentStatuses", agentId, version];

      [1000, 2500, 4500, 7000].forEach((delay) => {
        setTimeout(() => {
          queryClient.invalidateQueries({ queryKey: depKey });
          queryClient.invalidateQueries({ queryKey: depsKey });
        }, delay);
      });
    },
  });
}

export function useUndeployAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      environment = "production",
      agentId,
      version,
      endAllActiveConversations,
      undeployAllPreviousVersions,
    }: {
      environment?: string;
      agentId: string;
      version: number;
      endAllActiveConversations?: boolean;
      undeployAllPreviousVersions?: boolean;
    }) =>
      undeployAgent(environment, agentId, version, {
        endAllActiveConversations,
        undeployAllPreviousVersions,
      }),
    onMutate: async ({ environment = "production", agentId, version }) => {
      const depKey = [...agentKeys.all, "deployment", environment, agentId, version];
      const depsKey = [...agentKeys.all, "deploymentStatuses", agentId, version];
      await queryClient.cancelQueries({ queryKey: depKey });
      await queryClient.cancelQueries({ queryKey: depsKey });

      const prevDep = queryClient.getQueryData(depKey);
      const prevDeps = queryClient.getQueryData(depsKey);

      queryClient.setQueryData(depKey, (old: Record<string, unknown> | undefined) => ({ ...old, status: "NOT_FOUND" }));
      queryClient.setQueryData(depsKey, (old: EnvironmentStatus[] | undefined) =>
        old ? old.map((s) => (s.environment === environment ? { ...s, status: "NOT_FOUND" as const } : s)) : undefined
      );

      return { prevDep, prevDeps, depKey, depsKey };
    },
    onError: (_err, _vars, context) => {
      if (context) {
        queryClient.setQueryData(context.depKey, context.prevDep);
        queryClient.setQueryData(context.depsKey, context.prevDeps);
      }
    },
    onSuccess: (_, { environment = "production", agentId, version }) => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
      queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });

      const depKey = [...agentKeys.all, "deployment", environment, agentId, version];
      const depsKey = [...agentKeys.all, "deploymentStatuses", agentId, version];

      [1000, 2500].forEach((delay) => {
        setTimeout(() => {
          queryClient.invalidateQueries({ queryKey: depKey });
          queryClient.invalidateQueries({ queryKey: depsKey });
        }, delay);
      });
    },
  });
}

/** Group agent descriptors by resource ID, keeping the latest version per agent */
export function groupAgentsByName(
  agents: AgentDescriptor[]
): (AgentDescriptor & { id: string; version: number })[] {
  const grouped = new Map<
    string,
    AgentDescriptor & { id: string; version: number }
  >();

  for (const agent of agents) {
    const { id, version } = parseResourceUri(agent.resource);
    const existing = grouped.get(id);
    if (!existing || version > existing.version) {
      grouped.set(id, { ...agent, id, version });
    }
  }

  return Array.from(grouped.values()).sort(
    (a, b) => b.lastModifiedOn - a.lastModifiedOn
  );
}
