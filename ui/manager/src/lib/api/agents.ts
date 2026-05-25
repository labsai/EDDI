import { api } from "../api-client";
import { ENVIRONMENTS, type Environment } from "../constants";

// Re-export from shared constants for backward compatibility
export { ENVIRONMENTS, type Environment };

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
  channels?: ChannelConnector[];
  a2aEnabled?: boolean;
  description?: string;
  a2aSkills?: string[];
  // Phase 15.4 — Security, Identity, Capabilities, Memory
  identity?: AgentIdentity;
  security?: SecurityConfig;
  capabilities?: Capability[];
  enableMemoryTools?: boolean;
  userMemoryConfig?: UserMemoryConfig;
  memoryPolicy?: MemoryPolicy;
  // Wave 6 — Session Management
  sessionManagement?: SessionManagement;
}

export interface ChannelConnector {
  type: string;
  config: Record<string, string>;
}

export interface MemoryPolicy {
  strictWriteDiscipline?: StrictWriteDiscipline;
}

export interface StrictWriteDiscipline {
  enabled?: boolean;
  onFailure?: string; // "digest" | "exclude_all" | "keep_all"
}

export interface AgentIdentity {
  agentDid?: string;
  publicKey?: string;
  /** Versioned key list for rotation. Falls back to publicKey when empty. */
  keys?: AgentPublicKey[];
}

export interface AgentPublicKey {
  version?: number;
  publicKeyB64?: string;
  validFromMs?: number;
  validUntilMs?: number;
}

export interface SecurityConfig {
  signInterAgentMessages?: boolean;
  signMcpInvocations?: boolean;
  requirePeerVerification?: boolean;
}

export interface Capability {
  skill: string;
  attributes?: Record<string, string>;
  confidence?: string;
}

export interface UserMemoryConfig {
  defaultVisibility?: string;
  maxRecallEntries?: number;
  maxEntriesPerUser?: number;
  onCapReached?: string;
  recallOrder?: string;
  autoRecallCategories?: string[];
  guardrails?: MemoryGuardrails;
  dream?: DreamConfig;
}

export interface MemoryGuardrails {
  maxKeyLength?: number;
  maxValueLength?: number;
  maxWritesPerTurn?: number;
  allowedCategories?: string[];
}

export interface DreamConfig {
  enabled?: boolean;
  schedule?: string;
  detectContradictions?: boolean;
  contradictionResolution?: string;
  pruneStaleAfterDays?: number;
  summarizeInteractions?: boolean;
  llmProvider?: string;
  llmModel?: string;
  maxCostPerRun?: number;
  batchSize?: number;
  maxUsersPerRun?: number;
}

// Wave 6 — Session Management

export interface SessionManagement {
  autoSnapshot?: AutoSnapshot;
  forkingEnabled?: boolean;
  maxForksPerConversation?: number;
  maxCheckpointsPerConversation?: number;
}

export interface AutoSnapshot {
  enabled?: boolean;
  /** Events that trigger auto-snapshots: "before_tool", "before_action" */
  triggerOn?: string[];
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

/**
 * Fetch agent descriptors for all versions of a specific agent.
 *
 * The GET descriptors endpoint does NOT support includePreviousVersions;
 * we use the currentversion endpoint to resolve the latest version.
 */
export async function getAgentDescriptorsWithVersions(
  agentId: string
): Promise<AgentDescriptor[]> {
  // Resolve the latest version number
  const currentVersion = await api.get<number>(
    `/agentstore/agents/${agentId}/currentversion`
  );
  const latest = currentVersion ?? 1;

  // Fetch descriptor for each version in parallel
  const descriptors = await Promise.all(
    Array.from({ length: latest }, (_, i) => i + 1).map(async (v) => {
      try {
        const results = await api.get<AgentDescriptor[]>(
          `/agentstore/agents/descriptors?filter=${agentId}&version=${v}`
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
      `/agentstore/agents/descriptors?filter=${agentId}`
    );
  }
  return flat;
}

export function getAgent(id: string, version?: number): Promise<Agent> {
  const versionSuffix = version != null && version > 0 ? `?version=${version}` : "";
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
