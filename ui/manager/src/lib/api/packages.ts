import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";

export { type AgentDescriptor as WorkflowDescriptor };

export interface WorkflowExtension {
  type: string;
  extensions: Record<string, unknown>;
  config: Record<string, unknown>;
}

export interface WorkflowConfiguration {
  packageExtensions: WorkflowExtension[];
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

/** Get all versions of a specific package (for version picker) */
export function getWorkflowVersions(
  id: string
): Promise<AgentDescriptor[]> {
  return api.get<AgentDescriptor[]>(
    `/workflowstore/workflows/descriptors?filter=${id}&includePreviousVersions=true`
  );
}
