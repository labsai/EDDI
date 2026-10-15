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
  visibility?: "private" | "space" | "internal" | "published";
  /**
   * The owner's name from the user directory, for showing a person to a person.
   * Absent when the directory does not know them or workspaces are not enforced
   * — fall back to `ownerId`.
   */
  ownerName?: string;
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
  /**
   * The agent's id on the instance it was imported or synced from. It is what
   * recognises the local copy of a remote agent — a name can be changed on
   * either side, or shared by two agents.
   */
  originId?: string;
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
  /**
   * Whether the agent's maintainers may read other people's conversations with
   * it. Off unless set; the chat shows `notice` (or a standard wording) before
   * anyone types.
   */
  conversationReview?: { enabled?: boolean; notice?: string | null };
  // HITL — Human-in-the-Loop approval configuration
  hitlConfig?: import("./hitl").AgentHitlConfig;
  /**
   * The version's compatibility generation — SERVER-OWNED. Two versions of an
   * agent with the same generation are compatible: a running conversation moves
   * between them on its next turn once the newer one is deployed. `null` or
   * absent on versions stored before version following existed, which are
   * compatible only with themselves.
   *
   * Read-only: the store assigns it on every save and ignores whatever a PUT
   * body carries, so sending the value back is harmless but changes nothing.
   * Whether a save continues the chain is the `compatible` option of
   * {@link updateAgent}.
   */
  compatibilityGeneration?: number | null;
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
  /**
   * Why a deployment is in `ERROR` — only with `format=detailed`, and only for a
   * caller who may edit the agent. Absent on every other answer.
   */
  failure?: DeploymentFailure;
}

/** The code of a deployment refused because the agent uses a vault secret it is not granted. */
export const VAULT_GRANT_MISSING = "VAULT_GRANT_MISSING";

/** One secret a refused deployment names. `tenantId`/`keyName` are absent for a reference that is not a plain vault one. */
export interface DeploymentFailureSecret {
  tenantId?: string;
  keyName?: string;
  reference: string;
}

/**
 * Why a deployment failed, as the backend reports it on the waited deploy and on
 * `deploymentstatus?format=detailed`. Only non-null fields are serialised.
 *
 * `code` is `VAULT_GRANT_MISSING` (the agent uses a restricted secret it is not
 * granted — `secrets` and `fix` say which and how) or `DEPLOYMENT_FAILED` (any
 * other cause; `message` carries it). Typed as `string` beyond those two so a
 * newer backend's code is shown rather than rejected.
 */
export interface DeploymentFailure {
  code: typeof VAULT_GRANT_MISSING | "DEPLOYMENT_FAILED" | (string & {});
  message: string;
  secrets?: DeploymentFailureSecret[];
  fix?: {
    addAgentId: string;
    /** e.g. `POST /secretstore/secrets/default/k/grant/agents/<id>` */
    endpoints: string[];
    dryRunFirst: boolean;
  };
}

/** Body of `POST …/deploy/{id}?waitForCompletion=true`. */
export interface DeployResult {
  status: DeploymentStatus["status"];
  agentId?: string;
  version?: number;
  environment?: string;
  /** Set when the deploy failed; also filled from `failure.message` when the deploy itself raised nothing. */
  error?: string;
  failure?: DeploymentFailure;
}

/** `eddi.vault.grant-enforcement` as the preflight reports it. */
export type GrantEnforcement = "ENFORCE" | "WARN" | "OFF";

/** One restricted secret an agent uses but is not granted. */
export interface PreflightGrantIssue {
  /** Null/absent for a reference that is not a plain vault reference. */
  tenantId?: string | null;
  keyName?: string | null;
  reference: string;
  grantsAllAgents: boolean;
  /** Null when the grant could not be read. */
  allowedAgentCount?: number | null;
  /** The agent ids on the grant — returned to an `eddi-admin` only. */
  allowedAgents?: string[] | null;
}

/** `GET /administration/{env}/deploy/{agentId}/preflight?version=` */
export interface DeploymentPreflight {
  agentId: string;
  version: number;
  enforcement: GrantEnforcement;
  /** False when the check did not run (mode OFF, or it could not read what it needed) — the deploy is then let through. */
  checked: boolean;
  /** False only in ENFORCE mode with at least one issue: exactly when the deploy would be refused. */
  ready: boolean;
  grantIssues: PreflightGrantIssue[];
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
/**
 * Which agents a listing returns by owner: everything reachable, only the
 * caller's own, or only what somebody else owns and has let them reach.
 */
export type Ownership = "" | "mine" | "shared";

export function getAgentDescriptors(
  limit = 20,
  index = 0,
  filter = "",
  space = "",
  ownership: Ownership = ""
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
  // Also in the query, for the same paging reason.
  if (ownership) params.set("ownership", ownership);
  return api.get<AgentDescriptor[]>(
    `/agentstore/agents/descriptors?${params.toString()}`
  );
}

/** What a chat window shows about an agent — readable by anybody who may chat with it. */
export interface AgentProfile {
  agentId: string;
  name?: string | null;
  description?: string | null;
  /**
   * What to tell the person chatting when the deployed version lets its
   * maintainers read conversations. Absent when it does not.
   */
  reviewNotice?: string | null;
}

export function getAgentProfile(agentId: string, environment = "production"): Promise<AgentProfile> {
  return api.get<AgentProfile>(
    `/agents/${encodeURIComponent(agentId)}/profile?environment=${encodeURIComponent(environment)}`
  );
}

/** How much an agent is used — counts, never content. Requires view access. */
export interface AgentUsage {
  total: number;
  active: number;
  distinctUsers: number;
}

export function getAgentUsage(agentId: string): Promise<AgentUsage> {
  return api.get<AgentUsage>(`/agents/${encodeURIComponent(agentId)}/usage`);
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

export interface UpdateAgentOptions {
  /**
   * The new version is compatible with the one it replaces: conversations
   * running on the previous version switch to it on their next turn once it is
   * deployed. Absent or `false` is a breaking change — running conversations
   * stay on the version they are on. The safe default, so only an explicit
   * `true` is sent.
   */
  compatible?: boolean;
}

export function updateAgent(
  id: string,
  version: number,
  agent: Agent,
  options?: UpdateAgentOptions
): Promise<{ location: string }> {
  const params = new URLSearchParams({ version: String(version) });
  if (options?.compatible === true) params.set("compatible", "true");
  return api.put(`/agentstore/agents/${id}?${params.toString()}`, agent);
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

/** What deploying a version does to one OTHER deployed version's conversations. */
export type DeploymentImpactOutcome = "FOLLOW" | "STAY";

export interface DeployedVersionImpact {
  version: number;
  compatibilityGeneration: number | null;
  /** Active conversations currently on this version in the environment. */
  activeConversations: number;
  /**
   * `FOLLOW` — they move to the deployed version on their next turn.
   * `STAY` — they stay: a breaking change, a legacy version, or a newer version.
   */
  outcome: DeploymentImpactOutcome;
}

/** `GET /administration/{environment}/deploymentimpact/{agentId}?version=N` */
export interface DeploymentImpact {
  agentId: string;
  version: number;
  compatibilityGeneration: number | null;
  /** Every OTHER deployed version of the agent in the environment, highest first. */
  deployedVersions: DeployedVersionImpact[];
}

/**
 * Preview of what deploying `version` would do to the conversations running on
 * the agent's other deployed versions in `environment`. Read-only.
 */
export function getDeploymentImpact(
  environment: string,
  agentId: string,
  version: number
): Promise<DeploymentImpact> {
  return api.get<DeploymentImpact>(
    `/administration/${environment}/deploymentimpact/${encodeURIComponent(agentId)}?version=${version}`
  );
}

export function getDeploymentStatus(
  environment: string,
  agentId: string,
  version: number,
  options?: {
    /** `format=detailed`: an ERROR also carries `failure` (for a caller who may edit the agent). */
    detailed?: boolean;
  }
): Promise<DeploymentStatus> {
  const format = options?.detailed ? "&format=detailed" : "";
  return api.get<DeploymentStatus>(
    `/administration/${environment}/deploymentstatus/${agentId}?version=${version}${format}`
  );
}

/**
 * What a deploy of this version would run into — today, the vault-grant check.
 *
 * A grant names agent ids, so a freshly created agent is on no grant yet; the
 * Manager asks this before the first deploy rather than letting it fail. Read-only.
 */
export function preflightDeploy(
  environment: string,
  agentId: string,
  version: number
): Promise<DeploymentPreflight> {
  return api.get<DeploymentPreflight>(
    `/administration/${encodeURIComponent(environment)}/deploy/${encodeURIComponent(agentId)}/preflight?version=${version}`
  );
}

/** How long {@link deployAgentAndWait} polls when the backend did not wait itself. */
const DEPLOY_POLL_INTERVAL_MS = 2_000;
const DEPLOY_POLL_ATTEMPTS = 15;

/**
 * Deploy and wait for the outcome — `POST …/deploy/{id}?waitForCompletion=true`.
 *
 * A refused or failed deployment is NOT an exception: the backend answers 200
 * with `status: "ERROR"` and a `failure` saying why (a grant refusal included —
 * see the plan's decision 5.1), so the caller branches on the body. Only a
 * transport failure or a non-2xx (404, 429, 403) throws.
 *
 * If the answer carries no status (a backend that ignored the flag and answered
 * 202), the deployment status is polled instead, with `format=detailed` so an
 * ERROR still comes back with its reason.
 */
export async function deployAgentAndWait(
  environment: string,
  agentId: string,
  version: number,
  signal?: AbortSignal
): Promise<DeployResult> {
  const body = await api.post<DeployResult | undefined>(
    `/administration/${environment}/deploy/${agentId}?version=${version}&waitForCompletion=true`
  );
  if (body && typeof body === "object" && typeof body.status === "string") {
    return body;
  }
  let last: DeploymentStatus = { status: "IN_PROGRESS" };
  for (let attempt = 0; attempt < DEPLOY_POLL_ATTEMPTS; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, DEPLOY_POLL_INTERVAL_MS));
    if (signal?.aborted) break;
    try {
      last = await getDeploymentStatus(environment, agentId, version, { detailed: true });
    } catch (err) {
      // A failed status READ is worth retrying; only the last one counts.
      if (attempt === DEPLOY_POLL_ATTEMPTS - 1) throw err;
      continue;
    }
    if (last.status === "READY" || last.status === "ERROR") break;
  }
  return { status: last.status, agentId, version, environment, failure: last.failure };
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
