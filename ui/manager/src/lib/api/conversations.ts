import { api } from "../api-client";

// Types matching EDDI backend
export type ConversationState = "READY" | "IN_PROGRESS" | "ENDED" | "EXECUTION_INTERRUPTED" | "ERROR" | "AWAITING_HUMAN";

export type ViewState = "UNSEEN" | "SEEN";

/** Raw shape returned by the backend GET /conversationstore/conversations */
export interface ConversationDescriptorRaw {
  resource: string;
  createdOn: number;
  lastModifiedOn: number;
  deleted?: boolean;
  /** Full EDDI URI, e.g. eddi://ai.labs.agent/agentstore/agents/{id}?version=N */
  agentResource?: string;
  agentName?: string;
  userId?: string;
  conversationStepSize?: number;
  environment?: string;
  conversationState: ConversationState;
  viewState?: ViewState;
  // Legacy fields (older backend versions)
  name?: string;
  description?: string;
  agentId?: string;
  agentVersion?: number;
}

/** Normalized descriptor used throughout the Manager UI */
export interface ConversationDescriptor {
  resource: string;
  name: string;
  description: string;
  createdOn: number;
  lastModifiedOn: number;
  agentId: string;
  agentVersion: number;
  conversationState: ConversationState;
  viewState?: ViewState;
  conversationStepSize?: number;
  environment?: string;
  userId?: string;
}

/** Parse an agentResource URI into { agentId, agentVersion }.
 *  Example: "eddi://ai.labs.agent/agentstore/agents/abc123?version=2" → { agentId: "abc123", agentVersion: 2 } */
export function parseAgentResource(uri?: string): { agentId: string; agentVersion: number } {
  if (!uri) return { agentId: "", agentVersion: 0 };
  try {
    const normalized = uri.startsWith("eddi://")
      ? uri.replace("eddi://", "http://")
      : uri;
    const url = new URL(normalized, "http://dummy");
    const parts = url.pathname.split("/");
    const agentId = parts[parts.length - 1] || "";
    const version = parseInt(url.searchParams.get("version") || "0", 10);
    return { agentId, agentVersion: isNaN(version) ? 0 : version };
  } catch {
    return { agentId: uri, agentVersion: 0 };
  }
}

/** Normalize a raw backend descriptor into the shape the UI expects */
function normalizeDescriptor(raw: ConversationDescriptorRaw): ConversationDescriptor {
  const parsed = parseAgentResource(raw.agentResource);
  return {
    resource: raw.resource,
    name: raw.name || raw.agentName || "",
    description: raw.description || "",
    createdOn: raw.createdOn,
    lastModifiedOn: raw.lastModifiedOn,
    agentId: raw.agentId || parsed.agentId,
    agentVersion: raw.agentVersion ?? parsed.agentVersion,
    conversationState: raw.conversationState,
    viewState: raw.viewState,
    conversationStepSize: raw.conversationStepSize,
    environment: raw.environment,
    userId: raw.userId,
  };
}

/** Java backend serializes ConversationOutput as a LinkedHashMap<String, Object> */
export type ConversationOutput = Record<string, unknown>;

export interface ConversationStepData {
  key: string;
  value: unknown;
  timestamp?: string;
  originWorkflowId?: string | null;
  isPublic?: boolean;
}

export interface SimpleConversationStep {
  conversationStep: ConversationStepData[];
  timestamp?: string;
}

export interface SimpleConversationMemorySnapshot {
  agentId: string;
  agentVersion: number;
  conversationId: string;
  conversationState: ConversationState;
  environment: string;
  conversationSteps: SimpleConversationStep[];
  conversationOutputs?: ConversationOutput[];
  conversationProperties?: Record<string, unknown>;
  undoAvailable?: boolean;
  redoAvailable?: boolean;
  /**
   * Why the conversation ended, when it was ended for a reason the backend
   * records — today only {@link END_REASON_AGENT_VERSION_RETIRED}. Absent
   * otherwise. Note `agentVersion` is the version the conversation is on NOW:
   * with version following it can change between turns.
   */
  endReason?: string;
  // HITL bookmark fields (set when conversationState === "AWAITING_HUMAN")
  hitlPausedWorkflowId?: string;
  hitlPausedAbsoluteTaskIndex?: number;
  hitlPausedAt?: string;
  // NOTE deliberately ABSENT: hitlPauseReason. The simple snapshot never
  // carries it — SimpleConversationMemorySnapshot has only hitlPausedAt and
  // hitlPauseType, and the converter sets no reason. The field used to be
  // declared here, which let four call sites read it and silently get
  // undefined on every path; the reason genuinely lives on
  // GET /agents/{conversationId}/approval-status (useApprovalStatus).
  hitlTimeoutPolicy?: string;
  hitlApprovalTimeout?: string;
}

/**
 * Placeholder the backend persists in `input:initial` (and the echoed `input`)
 * for a secret-flagged turn — see Conversation.scrubSecretUserInput. The raw
 * text is scrubbed server-side, so a rebuilt transcript never carries it.
 */
export const SECRET_INPUT_PLACEHOLDER = "<secret input>";

/** Neutral mask shown in place of the placeholder token (no i18n needed). */
export const SECRET_INPUT_MASK = "••••••••";

/**
 * Render a user input for display: map the backend secret placeholder to a mask
 * so a secret turn shows a masked bubble rather than the raw `<secret input>`
 * token, and pass anything else through unchanged. Use this at every site that
 * displays `input:initial`.
 */
export function displayUserInput(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  return value === SECRET_INPUT_PLACEHOLDER ? SECRET_INPUT_MASK : value;
}

/** Extract user input from a conversation step's key/value pairs */
export function extractInput(step: SimpleConversationStep): string | undefined {
  const entry = step.conversationStep?.find(
    (d) => d.key === "input:initial"
  );
  return entry?.value as string | undefined;
}

/** Extract individual output parts from a conversationOutput map.
 * Returns an array of non-empty strings — one per output item.
 * Handles two formats:
 * 1. Nested (from conversationOutputs): { output: [{ type, text, delay }], quickReplies: [...] }
 * 2. Flat (from conversationSteps): { "output:text:action_name": { text: "..." }, ... }
 */
export function extractOutputParts(conversationOutput?: ConversationOutput): string[] {
  if (!conversationOutput) return [];

  const texts: string[] = [];

  // Format 1: Nested "output" array (from conversationOutputs)
  const outputArray = conversationOutput.output;
  if (Array.isArray(outputArray)) {
    for (const item of outputArray) {
      if (typeof item === "string" && item.trim()) texts.push(item);
      else if (item?.text && typeof item.text === "string" && (item.text as string).trim()) {
        texts.push(item.text as string);
      }
    }
    if (texts.length > 0) return texts;
  }

  // Format 2: Flat keys like "output:text:*" (from conversationSteps)
  for (const [key, val] of Object.entries(conversationOutput)) {
    if (!key.startsWith("output:text:")) continue;

    if (typeof val === "string" && val.trim()) {
      texts.push(val);
    } else if (Array.isArray(val)) {
      for (const item of val) {
        if (typeof item === "string" && item.trim()) texts.push(item);
        else if (item?.text && typeof item.text === "string" && item.text.trim()) texts.push(item.text);
      }
    } else if (val && typeof val === "object" && typeof (val as Record<string, unknown>).text === "string") {
      const t = (val as Record<string, unknown>).text as string;
      if (t.trim()) texts.push(t);
    }
  }
  return texts;
}

/**
 * Why a turn failed, from the `taskErrors` the backend records in the turn's
 * output — one line per failed task, already redacted server-side. `null` when
 * nothing failed.
 */
export function extractTaskErrors(conversationOutput?: ConversationOutput): string | null {
  const entries = conversationOutput?.taskErrors;
  if (!Array.isArray(entries)) return null;
  const texts = entries
    .map((entry) =>
      entry && typeof entry === "object" && typeof (entry as Record<string, unknown>).text === "string"
        ? ((entry as Record<string, unknown>).text as string).trim()
        : "",
    )
    .filter(Boolean);
  return texts.length > 0 ? texts.join("\n") : null;
}

/**
 * The notice to show for a failed turn, or `null` for one that did not fail.
 *
 * A turn whose LLM call was rejected used to come back as `ERROR` with an empty
 * output and render as nothing at all — the spinner stopped and no bubble
 * appeared. The backend's reason is preferred; `fallback` covers a turn that
 * errored without one.
 */
export function describeTurnFailure(
  conversationOutput: ConversationOutput | undefined,
  conversationState: string | undefined,
  fallback: string,
): string | null {
  const reported = extractTaskErrors(conversationOutput);
  if (reported) return reported;
  if (conversationState === "ERROR" && extractOutputParts(conversationOutput).length === 0) {
    return fallback;
  }
  return null;
}

/** Extract agent output from a conversationOutput map as a single string.
 * Multiple parts are joined with double-newline for proper markdown paragraphs.
 * For multi-bubble rendering, use extractOutputParts() instead.
 */
export function extractOutput(conversationOutput?: ConversationOutput): string | undefined {
  const parts = extractOutputParts(conversationOutput);
  return parts.length > 0 ? parts.join("\n\n") : undefined;
}

/** An input field requested by the backend (from InputFieldOutputItem). */
export interface InputField {
  subType: string;       // "password" | "text" | "email" etc.
  placeholder?: string;
  label?: string;
  defaultValue?: string;
}

/** Extract an input field request from a conversationOutput, if present.
 *  The backend sends InputFieldOutputItem with type "inputField" in the output array. */
export function extractInputField(conversationOutput?: ConversationOutput): InputField | undefined {
  if (!conversationOutput) return undefined;

  const outputArray = conversationOutput.output;
  if (!Array.isArray(outputArray)) return undefined;

  for (const item of outputArray) {
    if (item && typeof item === "object" && (item as Record<string, unknown>).type === "inputField") {
      const obj = item as Record<string, unknown>;
      return {
        subType: (obj.subType as string) || "password",
        placeholder: obj.placeholder as string | undefined,
        label: obj.label as string | undefined,
        defaultValue: obj.defaultValue as string | undefined,
      };
    }
  }
  return undefined;
}

/** Extract quick reply values from a conversationOutput */
export function extractQuickReplies(conversationOutput?: ConversationOutput): string[] {
  if (!conversationOutput) return [];

  // Nested format: { quickReplies: [{ value: "...", expressions: "..." }] }
  const qrArray = conversationOutput.quickReplies;
  if (Array.isArray(qrArray)) {
    return qrArray
      .map((qr: unknown) => {
        if (typeof qr === "string") return qr;
        if (qr && typeof qr === "object" && "value" in qr) return (qr as { value: string }).value;
        return null;
      })
      .filter((v): v is string => v !== null);
  }

  return [];
}

/** Extract actions from a conversation step's key/value pairs */
export function extractActions(step: SimpleConversationStep): string[] {
  const entry = step.conversationStep?.find(
    (d) => d.key === "actions"
  );
  if (!entry?.value) return [];
  if (Array.isArray(entry.value)) return entry.value as string[];
  if (typeof entry.value === "string") return [entry.value];
  return [];
}

/**
 * `endReason` of a conversation ended because the agent version it ran on was
 * undeployed with "end all active conversations" (backend
 * `IConversationService.END_REASON_AGENT_VERSION_RETIRED`).
 */
export const END_REASON_AGENT_VERSION_RETIRED = "agent-version-retired";

/** Anything carrying a step's key/value entries — simple or detailed snapshot. */
interface StepEntries {
  conversationStep?: { key: string; value: unknown }[] | null;
}

/**
 * The agent version that ran this step — step data `agent:version`
 * (`MemoryKeys.AGENT_VERSION`). Not a public key: only detailed views carry it,
 * and steps recorded before version following never do. `null` when absent.
 */
export function extractAgentVersion(step: StepEntries | undefined): number | null {
  const entry = step?.conversationStep?.find((d) => d.key === "agent:version");
  const value = entry?.value;
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/**
 * The version move recorded on the first step a new version ran — step data
 * `agent:switch` = `{ from, to }` (`MemoryKeys.AGENT_VERSION_CHANGE`). `null`
 * when the step carries none, or one that is not shaped like a move.
 */
export function extractAgentSwitch(
  step: StepEntries | undefined
): { from: number; to: number } | null {
  const entry = step?.conversationStep?.find((d) => d.key === "agent:switch");
  const value = entry?.value;
  if (!value || typeof value !== "object") return null;
  const { from, to } = value as { from?: unknown; to?: unknown };
  return typeof from === "number" && typeof to === "number" ? { from, to } : null;
}

export interface ConversationMemorySnapshot {
  agentId: string;
  agentVersion: number;
  conversationId: string;
  conversationState: ConversationState;
  environment: string;
  conversationSteps: Record<string, unknown>[];
  conversationProperties?: Record<string, unknown>;
}

/** Parse conversation resource URI to extract ID */
export function parseConversationUri(resource: string): string {
  try {
    const normalised = resource.startsWith("eddi://")
      ? resource.replace("eddi://", "http://")
      : resource;
    const url = new URL(normalised, "http://dummy");
    const parts = url.pathname.split("/");
    return parts[parts.length - 1] || resource;
  } catch {
    return resource;
  }
}

// API functions — using low-level /conversationstore/conversations endpoints
//
// NOTE on pagination: `index` is a 0-based page of RESULTS, not a row offset:
// page N ⇒ index = N, and it holds the (N*limit)th to ((N+1)*limit - 1)th
// conversation that passes every filter — never more than `limit` rows, and no
// row is repeated on the next page. `limit` is clamped by the backend to
// [1, 100] (default 20). See RestConversationStore.readConversationDescriptors.
export async function getConversationDescriptors(
  limit = 20,
  index = 0,
  filter = "",
  agentId = "",
  agentVersion?: number,
  conversationState?: ConversationState,
  viewState?: ViewState
): Promise<ConversationDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  if (agentId) params.set("agentId", agentId);
  if (agentVersion) params.set("agentVersion", String(agentVersion));
  if (conversationState) params.set("conversationState", conversationState);
  if (viewState) params.set("viewState", viewState);
  const raw = await api.get<ConversationDescriptorRaw[] | { value: ConversationDescriptorRaw[]; Count?: number }>(
    `/conversationstore/conversations?${params.toString()}`
  );
  // Backend may return a raw array or a { value: [...], Count } wrapper
  const items = Array.isArray(raw) ? raw : (raw?.value ?? []);
  return items.map(normalizeDescriptor);
}

/** Highest page size the backend will honour (it clamps `limit` to this). */
export const MAX_CONVERSATION_LIMIT = 100;

// ─── Active-conversation monitoring + bulk lifecycle ────────────────

/** Status of an active conversation for a given agent+version.
 *  Mirrors ai.labs.eddi.engine.memory.model.ConversationStatus. */
export interface ConversationStatus {
  conversationId: string;
  agentId: string;
  agentVersion: number;
  conversationState: ConversationState;
  /** Epoch millis (Jackson serializes java.util.Date as a timestamp). */
  lastInteraction: number;
}

/** List active conversations for one agent+version.
 *  GET /conversationstore/conversations/active/{agentId}?agentVersion=N
 *  (agentVersion is required by the backend). */
export function getActiveConversations(
  agentId: string,
  agentVersion: number
): Promise<ConversationStatus[]> {
  return api.get<ConversationStatus[]>(
    `/conversationstore/conversations/active/${agentId}?agentVersion=${agentVersion}`
  );
}

/** Bulk-end active conversations.
 *  POST /conversationstore/conversations/end  (body: ConversationStatus[])
 *  The backend safely ends paused/AWAITING_HUMAN ones through the HITL-aware
 *  service path; all others are set to ENDED. */
export function endActiveConversations(
  statuses: ConversationStatus[]
): Promise<void> {
  return api.post<void>(`/conversationstore/conversations/end`, statuses);
}

/** Bulk-purge ENDED conversations older than N days (admin action).
 *  DELETE /conversationstore/conversations/?deleteOlderThanDays=N
 *  Returns the number of conversations permanently deleted. */
export function purgeEndedConversations(
  deleteOlderThanDays: number
): Promise<number> {
  return api.delete<number>(
    `/conversationstore/conversations/?deleteOlderThanDays=${deleteOlderThanDays}`
  );
}

export function getSimpleConversationLog(
  conversationId: string,
  returnDetailed = false,
  returnCurrentStepOnly = false
): Promise<SimpleConversationMemorySnapshot> {
  const params = new URLSearchParams({
    returnDetailed: String(returnDetailed),
    returnCurrentStepOnly: String(returnCurrentStepOnly),
  });
  return api.get<SimpleConversationMemorySnapshot>(
    `/conversationstore/conversations/simple/${conversationId}?${params.toString()}`
  );
}

export function getRawConversationLog(
  conversationId: string
): Promise<ConversationMemorySnapshot> {
  return api.get<ConversationMemorySnapshot>(
    `/conversationstore/conversations/${conversationId}`
  );
}

export function deleteConversation(
  conversationId: string,
  deletePermanently = false
): Promise<void> {
  return api.delete(
    `/conversationstore/conversations/${conversationId}?deletePermanently=${deletePermanently}`
  );
}

// ─── Detailed conversation (debug memory inspector) ─────────────

export interface DetailedConversationStepItem {
  key: string;
  value: unknown;
  timestamp: string | null;
  originWorkflowId: string | null;
}

export interface DetailedConversationStep {
  conversationStep: DetailedConversationStepItem[];
  timestamp: string | null;
}

export interface DetailedConversation {
  conversationSteps: DetailedConversationStep[];
  conversationProperties: Record<string, unknown>;
}

/** Fetch a fully-detailed conversation snapshot including all step data.
 *  Used by the Memory Inspector debug tab.
 *
 *  `returnCurrentStepOnly=false` is explicit because the backend DEFAULTS it to
 *  `true` on `GET /agents/{conversationId}` — without it the inspector's step
 *  tabs only ever showed the latest step. */
export function getDetailedConversation(
  conversationId: string,
): Promise<DetailedConversation> {
  const params = new URLSearchParams({
    returnDetailed: "true",
    returnCurrentStepOnly: "false",
  });
  return api.get<DetailedConversation>(
    `/agents/${conversationId}?${params.toString()}`,
  );
}
