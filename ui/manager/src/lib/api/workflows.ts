import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";

export { type AgentDescriptor as WorkflowDescriptor };

export interface WorkflowExtension {
  type: string;
  extensions: Record<string, unknown>;
  config: Record<string, unknown>;
}

export interface WorkflowConfiguration {
  workflowSteps: WorkflowExtension[];
}

// API functions
export function getWorkflowDescriptors(
  limit = 100,
  index = 0,
  filter = ""
): Promise<AgentDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<AgentDescriptor[]>(
    `/workflowstore/workflows/descriptors?${params.toString()}`
  );
}

export function getWorkflow(
  id: string,
  version: number
): Promise<WorkflowConfiguration> {
  return api.get<WorkflowConfiguration>(
    `/workflowstore/workflows/${id}?version=${version}`
  );
}

export function createWorkflow(
  config: WorkflowConfiguration
): Promise<{ location: string }> {
  return api.post<{ location: string }>("/workflowstore/workflows", config);
}

export function updateWorkflow(
  id: string,
  version: number,
  config: WorkflowConfiguration
): Promise<{ location: string }> {
  return api.put(
    `/workflowstore/workflows/${id}?version=${version}`,
    config
  );
}

export function deleteWorkflow(
  id: string,
  version: number,
  options?: { cascade?: boolean; permanent?: boolean }
): Promise<void> {
  const params = new URLSearchParams({ version: String(version) });
  if (options?.cascade) params.set("cascade", "true");
  if (options?.permanent) params.set("permanent", "true");
  return api.delete(`/workflowstore/workflows/${id}?${params}`);
}

/**
 * Get all versions of a specific workflow (for version picker).
 *
 * The GET descriptors endpoint does NOT support includePreviousVersions;
 * we use the currentversion endpoint to resolve the latest version.
 */
export async function getWorkflowVersions(
  id: string
): Promise<AgentDescriptor[]> {
  // Resolve the latest version number
  const currentVersion = await api.get<number>(
    `/workflowstore/workflows/${id}/currentversion`
  );
  const latest = currentVersion ?? 1;

  // Fetch descriptor for each version in parallel
  const descriptors = await Promise.all(
    Array.from({ length: latest }, (_, i) => i + 1).map(async (v) => {
      try {
        const results = await api.get<AgentDescriptor[]>(
          `/workflowstore/workflows/descriptors?filter=${id}&version=${v}`
        );
        return results;
      } catch {
        return [];
      }
    })
  );

  const flat = descriptors.flat();
  if (flat.length === 0) {
    return api.get<AgentDescriptor[]>(
      `/workflowstore/workflows/descriptors?filter=${id}`
    );
  }
  return flat;
}

/** Duplicate a workflow (with optional deep-copy of extension resources) */
export function duplicateWorkflow(
  id: string,
  version: number,
  deepCopy = false
): Promise<{ location: string }> {
  const params = new URLSearchParams({
    version: String(version),
    deepCopy: String(deepCopy),
  });
  return api.post<{ location: string }>(
    `/workflowstore/workflows/${id}?${params}`
  );
}
