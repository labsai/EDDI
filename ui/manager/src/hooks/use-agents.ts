import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
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
  parseResourceUri,
} from "@/lib/api/agents";

const AGENTS_KEY = ["agents"] as const;

export function useAgentDescriptors(
  limit = 20,
  index = 0,
  filter = ""
) {
  return useQuery({
    queryKey: [...AGENTS_KEY, "descriptors", { limit, index, filter }],
    queryFn: () => getAgentDescriptors(limit, index, filter),
  });
}

export function useAgent(id: string, version?: number) {
  return useQuery({
    queryKey: [...AGENTS_KEY, id, version],
    queryFn: () => getAgent(id, version),
    enabled: !!id,
  });
}

export function useDeploymentStatus(agentId: string, version: number, environment = "production") {
  return useQuery({
    queryKey: [...AGENTS_KEY, "deployment", environment, agentId, version],
    queryFn: () => getDeploymentStatus(environment, agentId, version),
    enabled: !!agentId && version > 0,
    refetchInterval: (query) => {
      // Poll every 3s while deploying
      return query.state.data?.status === "IN_PROGRESS" ? 3000 : false;
    },
  });
}

export function useAgentVersions(agentId: string) {
  return useQuery({
    queryKey: [...AGENTS_KEY, "versions", agentId],
    queryFn: () => getAgentDescriptorsWithVersions(agentId),
    enabled: !!agentId,
    select: (descriptors) =>
      descriptors
        .map((d) => ({
          version: parseResourceUri(d.resource).version,
          lastModifiedOn: d.lastModifiedOn,
        }))
        .sort((a, b) => b.version - a.version),
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
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
    },
  });
}

export function useDeploymentStatuses(agentId: string, version: number) {
  return useQuery({
    queryKey: [...AGENTS_KEY, "deploymentStatuses", agentId, version],
    queryFn: () => getDeploymentStatuses(agentId, version),
    enabled: !!agentId && version > 0,
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
        const { updateDescriptor } = await import("@/lib/api/descriptors");
        await updateDescriptor(id, version, { name, description });
      }
      return response;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
    },
  });
}

export function useDeleteAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      deleteAgent(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
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
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
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
    }: {
      environment?: string;
      agentId: string;
      version: number;
    }) => undeployAgent(environment, agentId, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
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
