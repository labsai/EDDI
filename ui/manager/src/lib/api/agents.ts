import { api } from "../api-client";
import { getDescriptorVersions } from "./descriptors";
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
  /**
   * Workspace fields. Absent on a backend without workspaces, and on data that
   * predates ownership being recorded — so every consumer must treat them as
   * optional rather than assuming a listing carries them.
   *
   * `grants` and `accessIndex` are deliberately NOT here: the backend redacts
   * them for anyone who does not own the resource, so a listing is not a place
   * to read them. Use the share endpoint, which discloses them at OWN only.
   */
  ownerId?: string;
  spaceId?: string;
  visibility?: "private" | "space" | "published";
  /**
   * What the signed-in user may do with THIS resource — `USE`, `VIEW`, `EDIT`
   * or `OWN`.
   *
   * Per-request rather than per-resource: the same document carries a different
   * value for two callers. Absent when the backend does not enforce workspaces,
   * and on any backend that predates the field — read it through
   * `accessFor()` in `@/lib/access`, which treats absence as unrestricted.
   */
  callerLevel?: string;
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
  // HITL — Human-in-the-Loop approval configuration
  hitlConfig?: import("./hitl").AgentHitlConfig;
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

/** Parse resource URI to extract id and version.
 *
 * Accepted formats:
 *   - `eddi://ai.labs.agent/agentstore/agents/ID?version=VERSION`
 *   - `/agentstore/agents/ID?version=VERSION`   (Location header path)
 *   - `http://host/agentstore/agents/ID?version=VERSION`
 */
export function parseResourceUri(resource: string): {
  id: string;
  version: number;
} {
  const normalised = resource.startsWith("eddi://")
    ? resource.replace("eddi://", "http://")
    : resource;
  // Use a dummy base so relative paths (Location headers) parse correctly
  const url = new URL(normalised, "http://dummy");
  const parts = url.pathname.split("/").filter(Boolean);
  const id = parts[parts.length - 1] ?? resource;
  const parsedVersion = parseInt(url.searchParams.get("version") || "1", 10);
  const version = isNaN(parsedVersion) ? 1 : parsedVersion;
  return { id, version };
}

// API functions
export function getAgentDescriptors(
  limit = 20,
  index = 0,
  filter = "",
  space = ""
): Promise<AgentDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  // Narrowing happens in the query, never on the returned page: page 2 of
  // "everything" is not page 2 of "this workspace", so filtering client-side
  // would quietly break pagination.
  if (space) params.set("space", space);
  return api.get<AgentDescriptor[]>(
    `/agentstore/agents/descriptors?${params.toString()}`
  );
}

/**
 * Fetch agent descriptors for all versions of a specific agent.
 *
 * Resolves the latest version via `currentversion`, then reads each version's
 * descriptor by id and version — see `getDescriptorVersions` for why the store
 * listing (`descriptors?filter=…`) cannot answer this.
 */
export async function getAgentDescriptorsWithVersions(
  agentId: string
): Promise<AgentDescriptor[]> {
  const currentVersion = await api.get<number>(
    `/agentstore/agents/${encodeURIComponent(agentId)}/currentversion`
  );
  const flat = await getDescriptorVersions(agentId, currentVersion ?? 1);
  if (flat.length === 0) {
    return api.get<AgentDescriptor[]>(
      `/agentstore/agents/descriptors?filter=${encodeURIComponent(agentId)}`
    );
  }
  return flat;
}

/**
 * The agent's current version number — `GET /agentstore/agents/{id}/currentversion`.
 *
 * Also the reliable way to ask whether an agent exists at all: it answers 200
 * for an existing agent and 404 once the agent is deleted. `getAgent` without a
 * version is NOT: the version-less `GET /agentstore/agents/{id}` answers 400 for
 * an existing agent and an unknown id alike.
 */
export function getAgentCurrentVersion(id: string): Promise<number> {
  return api.get<number>(`/agentstore/agents/${id}/currentversion`);
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

/**
 * Undeploy an agent from an environment.
 *
 * The backend `IRestDeploymentStore` undeploy endpoint accepts two optional
 * destructive query flags (both default to `false`):
 *   - `endAllActiveConversations` — terminate every in-progress conversation
 *     on this deployment (irreversible).
 *   - `undeployThisAndAllPreviousAgentVersions` — also undeploy every earlier
 *     version of this agent from the environment.
 */
export function undeployAgent(
  environment: string,
  agentId: string,
  version: number,
  options?: {
    endAllActiveConversations?: boolean;
    undeployAllPreviousVersions?: boolean;
  }
): Promise<void> {
  const params = new URLSearchParams({ version: String(version) });
  if (options?.endAllActiveConversations) {
    params.set("endAllActiveConversations", "true");
  }
  if (options?.undeployAllPreviousVersions) {
    // Backend query-param name (IRestAgentAdministration.undeployAgent).
    params.set("undeployThisAndAllPreviousAgentVersions", "true");
  }
  return api.post(
    `/administration/${environment}/undeploy/${agentId}?${params.toString()}`
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
  /**
   * Set when `status` describes a DIFFERENT version than the one asked about —
   * the agent is live in this environment, but at this (older) version. See
   * `withAnyDeployedVersion`.
   */
  deployedVersion?: number;
}

/** One row of `GET /administration/{environment}/deploymentstatus` (backend `AgentDeploymentStatus`). */
export interface AgentDeploymentSummary {
  environment: Environment;
  agentId: string;
  agentVersion: number;
  status: DeploymentStatus["status"];
}

/**
 * Every agent deployed in an environment, each at its HIGHEST deployed version
 * (`AgentFactory.getAllLatestAgents`). The per-agent status endpoint answers for
 * one exact version only, so this is how to learn that an agent is still live
 * at an older version after a save bumped it.
 */
export function listDeploymentStatuses(environment: Environment): Promise<AgentDeploymentSummary[]> {
  return api.get<AgentDeploymentSummary[]>(`/administration/${environment}/deploymentstatus`);
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
