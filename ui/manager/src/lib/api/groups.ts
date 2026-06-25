import { api } from "../api-client";
import { deleteAgent, type AgentDescriptor } from "./agents";

// ─── Enums & Types ───────────────────────────────────────────────

export const DISCUSSION_STYLES = [
  "ROUND_TABLE",
  "PEER_REVIEW",
  "DEVIL_ADVOCATE",
  "DELPHI",
  "DEBATE",
  "TASK_FORCE",
  "CUSTOM",
] as const;
export type DiscussionStyle = (typeof DISCUSSION_STYLES)[number];

export const PHASE_TYPES = [
  "OPINION",
  "CRITIQUE",
  "REVISION",
  "CHALLENGE",
  "DEFENSE",
  "ARGUE",
  "REBUTTAL",
  "SYNTHESIS",
  "PLAN",
  "EXECUTE",
  "VERIFY",
] as const;
export type PhaseType = (typeof PHASE_TYPES)[number];

export type TurnOrder = "SEQUENTIAL" | "PARALLEL";

export type ContextScope =
  | "NONE"
  | "FULL"
  | "LAST_PHASE"
  | "ANONYMOUS"
  | "OWN_FEEDBACK"
  | "TASK_ONLY"
  | "TASK_WITH_DEPS";

export type MemberType = "AGENT" | "GROUP";

export type MemberFailurePolicy = "SKIP" | "RETRY" | "ABORT";
export type MemberUnavailablePolicy = "SKIP" | "FAIL";

export type GroupConversationState =
  | "CREATED"
  | "IN_PROGRESS"
  | "SYNTHESIZING"
  | "COMPLETED"
  | "FAILED"
  | "AWAITING_APPROVAL";

export type TranscriptEntryType =
  | "QUESTION"
  | "OPINION"
  | "CRITIQUE"
  | "REVISION"
  | "CHALLENGE"
  | "DEFENSE"
  | "ARGUMENT"
  | "REBUTTAL"
  | "SYNTHESIS"
  | "ERROR"
  | "SKIPPED"
  | "PLAN"
  | "TASK_RESULT"
  | "VERIFICATION";

// ─── Data Models ─────────────────────────────────────────────────

export interface GroupMember {
  agentId: string;
  displayName: string;
  speakingOrder: number | null;
  role: string | null;
  memberType?: MemberType;
}

export interface DiscussionPhase {
  name: string;
  type: PhaseType;
  participants: string;
  turnOrder: TurnOrder;
  contextScope: ContextScope;
  targetEachPeer: boolean;
  inputTemplate: string | null;
  repeats: number;
}

export interface ProtocolConfig {
  agentTimeoutSeconds: number;
  onAgentFailure: MemberFailurePolicy;
  maxRetries: number;
  onMemberUnavailable: MemberUnavailablePolicy;
  maxTurns?: number;
}

// ─── Task Models ────────────────────────────────────────────────

export type TaskStatus =
  | "PENDING"
  | "ASSIGNED"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "VERIFIED"
  | "FAILED"
  | "BLOCKED"
  | "AWAITING_APPROVAL";

export interface TaskItem {
  id: string;
  subject: string;
  description: string;
  status: TaskStatus;
  assignedAgentId: string | null;
  assignedDisplayName: string | null;
  dependsOnIds: string[];
  result: string | null;
  verificationNote: string | null;
  verified: boolean;
  priority: number;
  createdAt: string;
  completedAt: string | null;
}

export interface SharedTaskList {
  tasks: TaskItem[];
}

export interface TaskDefinition {
  subject: string;
  description: string;
  assignToRole: string;
  dependsOn: string[] | null;
  priority: number;
}

export type LifecyclePolicy =
  | "EPHEMERAL"
  | "KEEP_DEPLOYED"
  | "UNDEPLOY_ONLY"
  | "AGENT_DECIDES";

export interface DynamicAgentConfig {
  enabled: boolean;
  allowCreation: boolean;
  allowRecruitment: boolean;
  allowDelegation: boolean;
  maxCreatedAgentsPerDiscussion: number;
  maxRecruitedAgentsPerDiscussion: number;
  maxDelegationsPerTask: number;
  allowedProviders: string[];
  allowedModels: Record<string, string[]>;
  inheritParentModel: boolean;
  lifecyclePolicy: LifecyclePolicy;
}

export interface AgentGroupConfiguration {
  name: string;
  description: string;
  members: GroupMember[];
  moderatorAgentId: string | null;
  style: DiscussionStyle;
  maxRounds: number;
  phases: DiscussionPhase[] | null;
  protocol: ProtocolConfig | null;
  /** Pre-configured tasks for TASK_FORCE style (skips PLAN phase) */
  tasks?: TaskDefinition[];
  /** Dynamic agent creation and recruitment configuration */
  dynamicAgents?: DynamicAgentConfig;
}

export interface TranscriptEntry {
  speakerAgentId: string;
  speakerDisplayName: string;
  content: string | null;
  phaseIndex: number;
  phaseName: string | null;
  type: TranscriptEntryType;
  timestamp: string;
  errorReason: string | null;
  targetAgentId: string | null;
}

export interface GroupConversation {
  id: string;
  groupId: string;
  userId: string;
  state: GroupConversationState;
  originalQuestion: string;
  transcript: TranscriptEntry[];
  memberConversationIds: Record<string, string>;
  currentPhaseIndex: number;
  currentPhaseName: string | null;
  synthesizedAnswer: string | null;
  depth: number;
  /** Task list for TASK_FORCE style discussions */
  taskList: SharedTaskList | null;
  /** Agents dynamically added during the discussion */
  dynamicMembers: GroupMember[];
  /** Agent IDs created during this discussion (for lifecycle cleanup) */
  createdAgentIds: string[];
  /** Agent IDs retained by creators (agent-decides policy) */
  retainedAgentIds: string[];
  created: string;
  lastModified: string;
}

// Re-export descriptor type for group descriptors (same shape as agent descriptors)
export type GroupDescriptor = AgentDescriptor;

// ─── API Functions ───────────────────────────────────────────────

// --- Group Config CRUD ---

export function getGroupDescriptors(
  limit = 20,
  index = 0,
  filter = ""
): Promise<GroupDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<GroupDescriptor[]>(
    `/groupstore/groups/descriptors?${params.toString()}`
  );
}

export function getGroup(
  id: string,
  version?: number
): Promise<AgentGroupConfiguration> {
  // Backend requires version — omitting it causes 400 (RuntimeUtilities.checkNotNull)
  const versionSuffix = version != null ? `?version=${version}` : "";
  return api.get<AgentGroupConfiguration>(
    `/groupstore/groups/${id}${versionSuffix}`
  );
}

export function createGroup(
  config: AgentGroupConfiguration
): Promise<{ location: string }> {
  return api.post<{ location: string }>("/groupstore/groups", config);
}

export function updateGroup(
  id: string,
  version: number,
  config: AgentGroupConfiguration
): Promise<{ location: string }> {
  return api.put(`/groupstore/groups/${id}?version=${version}`, config);
}

export function deleteGroup(
  id: string,
  version: number,
  permanent = true
): Promise<void> {
  const params = new URLSearchParams({
    version: String(version),
    permanent: String(permanent),
  });
  return api.delete(`/groupstore/groups/${id}?${params}`);
}

export function duplicateGroup(
  id: string,
  version: number
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `/groupstore/groups/${id}?version=${version}`
  );
}

export function getDiscussionStyles(): Promise<Record<string, unknown>> {
  return api.get<Record<string, unknown>>("/groupstore/groups/styles");
}

export function getGroupJsonSchema(): Promise<Record<string, unknown>> {
  return api.get<Record<string, unknown>>("/groupstore/groups/jsonSchema");
}

// --- Group Conversations ---

export function startGroupDiscussion(
  groupId: string,
  question: string,
  userId?: string
): Promise<GroupConversation> {
  return api.post<GroupConversation>(
    `/groups/${groupId}/conversations`,
    { question, userId: userId || "manager-user" }
  );
}

export function getGroupConversation(
  groupId: string,
  conversationId: string
): Promise<GroupConversation> {
  return api.get<GroupConversation>(
    `/groups/${groupId}/conversations/${conversationId}`
  );
}

export function listGroupConversations(
  groupId: string,
  limit = 20,
  index = 0
): Promise<GroupConversation[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  return api.get<GroupConversation[]>(
    `/groups/${groupId}/conversations?${params.toString()}`
  );
}

export function deleteGroupConversation(
  groupId: string,
  conversationId: string
): Promise<void> {
  return api.delete(
    `/groups/${groupId}/conversations/${conversationId}`
  );
}

// ─── SSE Streaming ──────────────────────────────────────────────

export type GroupSSEEventType =
  | "group_start"
  | "phase_start"
  | "speaker_start"
  | "speaker_complete"
  | "phase_complete"
  | "synthesis_start"
  | "group_complete"
  | "group_error"
  | "task_plan_created"
  | "task_verified";

export interface GroupSSEEvent {
  type: GroupSSEEventType;
  data: string;
}

/** Parsed event payloads for convenience.
 *  Field names match the backend's GroupStartEvent Java record. */
export interface GroupStartPayload {
  groupConversationId: string;
  groupId: string;
  question: string;
  style: string;
  totalPhases: number;
  memberAgentIds: string[];
}

export interface PhaseStartPayload {
  phaseIndex: number;
  phaseName: string;
  phaseType: string;
  participants: string;
}

export interface SpeakerStartPayload {
  agentId: string;
  displayName: string;
  phaseIndex: number;
  phaseName: string;
}

export interface SpeakerCompletePayload {
  agentId: string;
  displayName: string;
  /** Backend field name is 'response' */
  response: string;
  /** Fallback alias */
  content?: string;
  phaseIndex: number;
  phaseName: string;
  /** Peer-targeted phase: the agent this response was aimed at */
  targetAgentId?: string;
  targetDisplayName?: string;
}

export interface PhaseCompletePayload {
  phaseIndex: number;
  phaseName: string;
}

export interface SynthesisStartPayload {
  moderatorAgentId: string;
}

export interface GroupCompletePayload {
  state: GroupConversationState;
  synthesizedAnswer: string | null;
}

export interface GroupErrorPayload {
  error: string;
}

export interface TaskPlanCreatedPayload {
  tasks: { id: string; subject: string; assignedTo: string; priority: number }[];
  preConfigured: boolean;
}

export interface TaskVerifiedPayload {
  taskId: string;
  taskSubject: string;
  passed: boolean;
  feedback: string;
}

/**
 * Start a group discussion via SSE streaming.
 * Returns an async generator yielding SSE events as they arrive.
 * Same pattern as chat's `sendMessageStreaming()`.
 */
export async function* streamGroupDiscussion(
  groupId: string,
  question: string,
  userId?: string,
  signal?: AbortSignal,
): AsyncGenerator<GroupSSEEvent> {
  const response = await fetch(
    `${api.getBaseUrl()}/groups/${groupId}/conversations/stream`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...api.getAuthHeader(),
      },
      body: JSON.stringify({ question, userId: userId || "manager-user" }),
      signal,
    }
  );

  if (!response.ok) {
    // M5 fix: throw a proper Error, not a plain object
    throw new Error(`Group streaming failed: ${response.status} ${response.statusText}`);
  }

  const reader = response.body?.getReader();
  if (!reader) throw new Error("No readable stream");

  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Parse SSE lines: "event: <type>\ndata: <data>\n\n"
      const parts = buffer.split("\n\n");
      buffer = parts.pop() ?? "";

      for (const part of parts) {
        if (!part.trim()) continue;
        let eventType: GroupSSEEventType | null = null;
        let eventData = "";

        for (const line of part.split("\n")) {
          if (line.startsWith("event:")) {
            eventType = line.slice(6).trim() as GroupSSEEventType;
          } else if (line.startsWith("data:")) {
            // C3 fix: concatenate multiple data: lines per SSE spec (§9.2.4)
            eventData += (eventData ? "\n" : "") + line.slice(5).trim();
          }
        }

        // Only yield events with an explicit event: type (skip bare data-only chunks)
        if (eventType && (eventData || eventType)) {
          yield { type: eventType, data: eventData };
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}


// ─── Helpers ─────────────────────────────────────────────────────

/** Parse group resource URI to extract id and version.
 *
 * Accepted formats:
 *   - `eddi://ai.labs.group/groupstore/groups/ID?version=VERSION`
 *   - `/groupstore/groups/ID?version=VERSION`   (Location header path)
 *   - `http://host/groupstore/groups/ID?version=VERSION`
 */
export function parseGroupResourceUri(resource: string): {
  id: string;
  version: number;
} {
  const normalised = resource.startsWith("eddi://")
    ? resource.replace("eddi://", "http://")
    : resource;
  // Use a dummy base so relative paths (Location headers) parse correctly
  const url = new URL(normalised, "http://dummy");
  const parts = url.pathname.split("/").filter(Boolean);
  let id = parts[parts.length - 1] ?? resource;
  const hasQueryVersion = url.searchParams.has("version");
  let version = hasQueryVersion
    ? parseInt(url.searchParams.get("version")!, 10)
    : NaN;

  // Handle backend data bug: `version` may be concatenated into the path
  // segment instead of appearing as a `?version=` query param, e.g.
  // "eddi://…/groupstore/groups/IDversion1" instead of "…/ID?version=1"
  if (!hasQueryVersion) {
    const match = id.match(/^(.+?)version(\d+)$/);
    if (match) {
      id = match[1]!;
      version = parseInt(match[2]!, 10);
    }
  }

  return { id, version: isNaN(version) ? 1 : version };
}

/** Group descriptors by ID, keeping the latest version per group */
export function groupGroupsByName(
  groups: GroupDescriptor[]
): (GroupDescriptor & { id: string; version: number })[] {
  const grouped = new Map<
    string,
    GroupDescriptor & { id: string; version: number }
  >();

  for (const group of groups) {
    const { id, version } = parseGroupResourceUri(group.resource);
    const existing = grouped.get(id);
    if (!existing || version > existing.version) {
      grouped.set(id, { ...group, id, version });
    }
  }

  return Array.from(grouped.values()).sort(
    (a, b) => b.lastModifiedOn - a.lastModifiedOn
  );
}

/** Enriched group descriptor with config-level data (name, description, style, memberCount) */
export type EnrichedGroupDescriptor = GroupDescriptor & {
  id: string;
  version: number;
  memberCount: number;
  style?: DiscussionStyle;
};

/**
 * Fetch group descriptors and enrich them with data from full configs.
 * The backend's descriptor endpoint may return empty name/description because
 * those fields live inside AgentGroupConfiguration, not on the descriptor itself.
 * This function batch-fetches each group's config to fill the gaps.
 */
export async function getEnrichedGroupDescriptors(
  limit = 20,
  index = 0,
  filter = ""
): Promise<EnrichedGroupDescriptor[]> {
  const descriptors = await getGroupDescriptors(limit, index, filter);
  const grouped = groupGroupsByName(descriptors);

  // Batch-fetch full configs for groups with empty names
  const enriched = await Promise.all(
    grouped.map(async (g) => {
      try {
        const config = await getGroup(g.id, g.version);
        return {
          ...g,
          name: config.name || g.name,
          description: config.description || g.description,
          memberCount: config.members?.length ?? 0,
          style: config.style,
        } satisfies EnrichedGroupDescriptor;
      } catch {
        return {
          ...g,
          memberCount: 0,
        } satisfies EnrichedGroupDescriptor;
      }
    })
  );

  return enriched;
}

/** Style display info */
export const STYLE_INFO: Record<
  DiscussionStyle,
  { label: string; flow: string; icon: string }
> = {
  ROUND_TABLE: {
    label: "Round Table",
    flow: "Opinion → Discussion → Synthesis",
    icon: "🗣️",
  },
  PEER_REVIEW: {
    label: "Peer Review",
    flow: "Opinion → Critique → Revision → Synthesis",
    icon: "🔍",
  },
  DEVIL_ADVOCATE: {
    label: "Devil's Advocate",
    flow: "Opinion → Challenge → Defense → Synthesis",
    icon: "😈",
  },
  DELPHI: {
    label: "Delphi",
    flow: "Independent → Anonymous Sharing → Revised → Synthesis",
    icon: "🔮",
  },
  DEBATE: {
    label: "Debate",
    flow: "Pro Opening → Con Opening → Rebuttals → Judgment",
    icon: "⚖️",
  },
  TASK_FORCE: {
    label: "Task Force",
    flow: "Plan → Execute → Verify → Synthesize",
    icon: "🎯",
  },
  CUSTOM: {
    label: "Custom",
    flow: "User-defined phases",
    icon: "🛠️",
  },
};

/** Entry type display info */
export const ENTRY_TYPE_INFO: Record<
  TranscriptEntryType,
  { label: string; color: string }
> = {
  QUESTION: { label: "Question", color: "blue" },
  OPINION: { label: "Opinion", color: "green" },
  CRITIQUE: { label: "Critique", color: "orange" },
  REVISION: { label: "Revision", color: "teal" },
  CHALLENGE: { label: "Challenge", color: "red" },
  DEFENSE: { label: "Defense", color: "purple" },
  ARGUMENT: { label: "Argument", color: "indigo" },
  REBUTTAL: { label: "Rebuttal", color: "pink" },
  SYNTHESIS: { label: "Synthesis", color: "gold" },
  ERROR: { label: "Error", color: "destructive" },
  SKIPPED: { label: "Skipped", color: "muted" },
  PLAN: { label: "Plan", color: "sky" },
  TASK_RESULT: { label: "Task Result", color: "emerald" },
  VERIFICATION: { label: "Verification", color: "amber" },
};

// ─── Bulk Operations ─────────────────────────────────────────────

/**
 * Resolve the current (latest) version of an agent via the backend
 * currentversion endpoint. Returns 1 as fallback if lookup fails.
 */
async function getCurrentAgentVersion(agentId: string): Promise<number> {
  try {
    const version = await api.get<number>(
      `/agentstore/agents/${agentId}/currentversion`,
    );
    return version ?? 1;
  } catch {
    return 1;
  }
}

/**
 * Soft-delete a group and all its member agents.
 * Each member agent is deleted with permanent=false (soft-delete).
 * The group itself is also soft-deleted.
 */
export async function deleteGroupWithMembers(
  groupId: string,
  version: number,
  config: AgentGroupConfiguration,
): Promise<void> {
  // Collect all agent IDs to delete (members + moderator)
  const agentIds = new Set<string>();
  for (const m of config.members) {
    if (m.agentId && m.memberType !== "GROUP") agentIds.add(m.agentId);
  }
  if (config.moderatorAgentId) agentIds.add(config.moderatorAgentId);

  // Soft-delete each agent at its current version (best-effort)
  const memberDeletes = Array.from(agentIds).map(async (agentId) => {
    try {
      const currentVersion = await getCurrentAgentVersion(agentId);
      await deleteAgent(agentId, currentVersion, { permanent: false });
    } catch {
      // Ignore — agent may already be deleted
    }
  });

  await Promise.allSettled(memberDeletes);

  // Soft-delete the group itself
  await deleteGroup(groupId, version, false);
}
