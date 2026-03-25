import { api } from "../api-client";

export const ENVIRONMENTS = ["production", "test"] as const;
export type Environment = (typeof ENVIRONMENTS)[number];

// Types matching EDDI backend
export interface AgentDescriptor {
  resource: string;
  name: string;
  description: string;
  createdOn: number;
  lastModifiedOn: number;
  createdBy?: string;
  lastModifiedBy?: string;
}

export interface Agent {
  workflows?: string[];
  channels?: string[];
}

export interface DeploymentStatus {
  status: "NOT_FOUND" | "IN_PROGRESS" | "READY" | "ERROR";
}

/** Parse resource URI to extract id and version */
export function parseResourceUri(resource: string): {
  id: string;
  version: number;
} {
  // Format: eddi://ai.labs.agent/agentstore/agents/ID?version=VERSION
  const url = new URL(resource.replace("eddi://", "http://"));
  const parts = url.pathname.split("/");
  const id = parts[parts.length - 1]!;
  const version = parseInt(url.searchParams.get("version") || "1", 10);
  return { id, version };
}

// API functions
export function getAgentDescriptors(
  limit = 20,
  index = 0,
  filter = ""
): Promise<AgentDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<AgentDescriptor[]>(
    `/agentstore/agents/descriptors?${params.toString()}`
  );
}

export function getAgentDescriptorsWithVersions(
  agentId: string
): Promise<AgentDescriptor[]> {
  return api.get<AgentDescriptor[]>(
    `/agentstore/agents/descriptors?includePreviousVersions=true&filter=${agentId}`
  );
}

export function getAgent(id: string, version?: number): Promise<Agent> {
  const versionSuffix = version ? `?version=${version}` : "";
  return api.get<Agent>(`/agentstore/agents/${id}${versionSuffix}`);
}

export function createAgent(agent: Agent): Promise<{ location: string }> {
  return api.post<{ location: string }>("/agentstore/agents", agent);
}

export function updateAgent(
  id: string,
  version: number,
  agent: Agent
): Promise<{ location: string }> {
  return api.put(`/agentstore/agents/${id}?version=${version}`, agent);
}

export function deleteAgent(
  id: string,
  version: number,
  options?: { cascade?: boolean; permanent?: boolean }
): Promise<void> {
  const params = new URLSearchParams({ version: String(version) });
  if (options?.cascade) params.set("cascade", "true");
  if (options?.permanent) params.set("permanent", "true");
  return api.delete(`/agentstore/agents/${id}?${params}`);
}

export function duplicateAgent(
  id: string,
  version: number,
  deepCopy = false
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `/agentstore/agents/${id}?version=${version}&deepCopy=${deepCopy}`
  );
}

export function deployAgent(
  environment: string,
  agentId: string,
  version: number
): Promise<void> {
  return api.post(
    `/administration/${environment}/deploy/${agentId}?version=${version}`
  );
}

export function undeployAgent(
  environment: string,
  agentId: string,
  version: number
): Promise<void> {
  return api.post(
    `/administration/${environment}/undeploy/${agentId}?version=${version}`
  );
}

export function getDeploymentStatus(
  environment: string,
  agentId: string,
  version: number
): Promise<DeploymentStatus> {
  return api.get<DeploymentStatus>(
    `/administration/${environment}/deploymentstatus/${agentId}?version=${version}`
  );
}

export interface EnvironmentStatus {
  environment: Environment;
  status: DeploymentStatus["status"];
}

export async function getDeploymentStatuses(
  agentId: string,
  version: number
): Promise<EnvironmentStatus[]> {
  const results = await Promise.allSettled(
    ENVIRONMENTS.map(async (env) => {
      try {
        const result = await getDeploymentStatus(env, agentId, version);
        return { environment: env, status: result.status };
      } catch {
        return { environment: env, status: "NOT_FOUND" as const };
      }
    })
  );

  return results.map((r, i) =>
    r.status === "fulfilled"
      ? r.value
      : { environment: ENVIRONMENTS[i]!, status: "NOT_FOUND" as const }
  );
}
