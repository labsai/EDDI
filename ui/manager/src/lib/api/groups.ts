import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";

// ─── Enums & Types ───────────────────────────────────────────────

export const DISCUSSION_STYLES = [
  "ROUND_TABLE",
  "PEER_REVIEW",
  "DEVIL_ADVOCATE",
  "DELPHI",
  "DEBATE",
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
] as const;
export type PhaseType = (typeof PHASE_TYPES)[number];

export type TurnOrder = "SEQUENTIAL" | "PARALLEL";

export type ContextScope =
  | "NONE"
  | "FULL"
  | "LAST_PHASE"
  | "ANONYMOUS"
  | "OWN_FEEDBACK";

export type MemberType = "AGENT" | "GROUP";

export type MemberFailurePolicy = "SKIP" | "RETRY" | "ABORT";
export type MemberUnavailablePolicy = "SKIP" | "FAIL";

export type GroupConversationState =
  | "CREATED"
  | "IN_PROGRESS"
  | "SYNTHESIZING"
  | "COMPLETED"
  | "FAILED";

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
  | "SKIPPED";

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
  const versionSuffix = version ? `?version=${version}` : "";
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
  | "group_error";

export interface GroupSSEEvent {
  type: GroupSSEEventType;
  data: string;
}

/** Parsed event payloads for convenience */
export interface GroupStartPayload {
  conversationId: string;
  groupId: string;
  question: string;
  style: string;
  phaseCount: number;
  agentIds: string[];
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
  content: string;
  phaseIndex: number;
  phaseName: string;
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
        let eventType: GroupSSEEventType = "group_start";
        let eventData = "";

        for (const line of part.split("\n")) {
          if (line.startsWith("event:")) {
            eventType = line.slice(6).trim() as GroupSSEEventType;
          } else if (line.startsWith("data:")) {
            // C3 fix: concatenate multiple data: lines per SSE spec (§9.2.4)
            eventData += (eventData ? "\n" : "") + line.slice(5).trim();
          }
        }

        if (eventData || eventType) {
          yield { type: eventType, data: eventData };
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}


// ─── Helpers ─────────────────────────────────────────────────────

/** Parse group resource URI to extract id and version */
export function parseGroupResourceUri(resource: string): {
  id: string;
  version: number;
} {
  // Format: eddi://ai.labs.group/groupstore/groups/ID?version=VERSION
  const url = new URL(resource.replace("eddi://", "http://"));
  const parts = url.pathname.split("/");
  const id = parts[parts.length - 1]!;
  const version = parseInt(url.searchParams.get("version") || "1", 10);
  return { id, version };
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
};
