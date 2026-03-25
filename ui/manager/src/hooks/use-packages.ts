import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getWorkflowDescriptors,
  getWorkflow,
  getWorkflowVersions,
  createWorkflow,
  updateWorkflow,
  deleteWorkflow,
  type WorkflowConfiguration,
} from "@/lib/api/packages";

const WORKFLOWS_KEY = ["workflows"] as const;

export function useWorkflowDescriptors(limit = 100, index = 0, filter = "") {
  return useQuery({
    queryKey: [...WORKFLOWS_KEY, "descriptors", { limit, index, filter }],
    queryFn: () => getWorkflowDescriptors(limit, index, filter),
  });
}

export function useWorkflow(id: string, version: number) {
  return useQuery({
    queryKey: [...WORKFLOWS_KEY, id, version],
    queryFn: () => getWorkflow(id, version),
    enabled: !!id && version > 0,
  });
}

/** Fetch all versions of a specific package (for version picker) */
export function useWorkflowVersions(id: string) {
  return useQuery({
    queryKey: [...WORKFLOWS_KEY, id, "versions"],
    queryFn: () => getWorkflowVersions(id),
    enabled: !!id,
  });
}

export function useCreateWorkflow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (config: WorkflowConfiguration) => createWorkflow(config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: WORKFLOWS_KEY });
    },
  });
}

export function useUpdateWorkflow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      config,
    }: {
      id: string;
      version: number;
      config: WorkflowConfiguration;
    }) => updateWorkflow(id, version, config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: WORKFLOWS_KEY });
    },
  });
}

export function useDeleteWorkflow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      deleteWorkflow(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: WORKFLOWS_KEY });
    },
  });
}

export function useUpdateAgentWorkflows() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      agentId,
      version,
      workflows,
    }: {
      agentId: string;
      version: number;
      workflows: string[];
    }) => {
      const { updateAgent } = await import("@/lib/api/agents");
      return updateAgent(agentId, version, { workflows });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["agents"] });
    },
  });
}
