import { useQuery, useInfiniteQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import {
  getWorkflowDescriptors,
  getWorkflow,
  getWorkflowVersions,
  createWorkflow,
  updateWorkflow,
  deleteWorkflow,
  duplicateWorkflow,
  type WorkflowConfiguration,
} from "@eddi/app-kit/lib/api/workflows";
import { parseResourceUri, type AgentDescriptor } from "@eddi/app-kit/lib/api/agents";
import { updateDescriptor } from "@eddi/app-kit/lib/api/descriptors";

const WORKFLOWS_KEY = ["workflows"] as const;
const PAGE_SIZE = 50;

export function useWorkflowDescriptors(limit = 100, index = 0, filter = "") {
  return useQuery({
    queryKey: [...WORKFLOWS_KEY, "descriptors", { limit, index, filter }],
    queryFn: () => getWorkflowDescriptors(limit, index, filter),
    // Keep showing the previous results while a new filter loads, instead of
    // dropping the list back to its loading skeleton on every keystroke.
    placeholderData: keepPreviousData,
  });
}

/**
 * Infinite-scroll workflow list. The page param is a page INDEX (the backend
 * skips `index * limit` rows) — see useInfiniteAgentDescriptors.
 */
export function useInfiniteWorkflowDescriptors(filter = "") {
  return useInfiniteQuery({
    queryKey: [...WORKFLOWS_KEY, "descriptors-infinite", { filter }],
    queryFn: ({ pageParam = 0 }) => getWorkflowDescriptors(PAGE_SIZE, pageParam, filter),
    // Keep showing the previous results while a new filter loads, instead of
    // dropping the list back to its loading skeleton on every keystroke.
    placeholderData: keepPreviousData,
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
      if (lastPage.length === PAGE_SIZE) {
        return allPages.length;
      }
      return undefined;
    },
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

/**
 * Parse a Location header path like "/workflowstore/workflows/abc123?version=1"
 * into { id, version }. Unlike parseResourceUri, this handles plain URL paths
 * rather than eddi:// resource URIs.
 */
function parseLocationPath(location: string): { id: string; version: number } {
  // Use a dummy base to parse relative paths
  const url = new URL(location, "http://dummy");
  const parts = url.pathname.split("/").filter(Boolean);
  const id = parts[parts.length - 1]!;
  const version = parseInt(url.searchParams.get("version") || "1", 10);
  return { id, version };
}

export function useCreateWorkflow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      config,
      name,
      description,
    }: {
      config: WorkflowConfiguration;
      name?: string;
      description?: string;
    }) => {
      const response = await createWorkflow(config);
      if ((name || description) && response.location) {
        const { id, version } = parseLocationPath(response.location);
        await updateDescriptor(id, version, { name, description });
      }
      return response;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: WORKFLOWS_KEY });
    },
  });
}

/** Rename a workflow — its name and description live on the descriptor. */
export function useUpdateWorkflowDescriptor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      name,
      description,
    }: {
      id: string;
      version: number;
      name: string;
      description: string;
    }) => updateDescriptor(id, version, { name, description }),
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

export function useDuplicateWorkflow() {
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
    }) => duplicateWorkflow(id, version, deepCopy),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: WORKFLOWS_KEY });
    },
  });
}

/** Group workflow descriptors by resource ID, keeping the latest version per workflow */
export function groupWorkflowsByName(
  workflows: AgentDescriptor[]
): (AgentDescriptor & { id: string; version: number })[] {
  const grouped = new Map<
    string,
    AgentDescriptor & { id: string; version: number }
  >();

  for (const wf of workflows) {
    const { id, version } = parseResourceUri(wf.resource);
    const existing = grouped.get(id);
    if (!existing || version > existing.version) {
      grouped.set(id, { ...wf, id, version });
    }
  }

  return Array.from(grouped.values()).sort(
    (a, b) => b.lastModifiedOn - a.lastModifiedOn
  );
}

