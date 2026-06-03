import { api } from "../api-client";

// Types matching EDDI backend
export type ConversationState = "READY" | "IN_PROGRESS" | "ENDED" | "EXECUTION_INTERRUPTED" | "ERROR";

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
}

/** Extract user input from a conversation step's key/value pairs */
export function extractInput(step: SimpleConversationStep): string | undefined {
  const entry = step.conversationStep?.find(
    (d) => d.key === "input:initial"
  );
  return entry?.value as string | undefined;
}

/** Extract agent output from a conversationOutput map (one per step).
 * Handles two formats:
 * 1. Nested (from conversationOutputs): { output: [{ type, text, delay }], quickReplies: [...] }
 * 2. Flat (from conversationSteps): { "output:text:action_name": { text: "..." }, ... }
 */
export function extractOutput(conversationOutput?: ConversationOutput): string | undefined {
  if (!conversationOutput) return undefined;

  const texts: string[] = [];

  // Format 1: Nested "output" array (from conversationOutputs)
  const outputArray = conversationOutput.output;
  if (Array.isArray(outputArray)) {
    for (const item of outputArray) {
      if (typeof item === "string") texts.push(item);
      else if (item?.text) texts.push(item.text as string);
    }
    if (texts.length > 0) return texts.join("\n");
  }

  // Format 2: Flat keys like "output:text:*" (from conversationSteps)
  for (const [key, val] of Object.entries(conversationOutput)) {
    if (!key.startsWith("output:text:")) continue;

    if (typeof val === "string") {
      texts.push(val);
    } else if (Array.isArray(val)) {
      for (const item of val) {
        if (typeof item === "string") texts.push(item);
        else if (item?.text) texts.push(item.text);
      }
    } else if (val && typeof val === "object" && (val as Record<string, unknown>).text) {
      texts.push((val as Record<string, unknown>).text as string);
    }
  }
  return texts.length > 0 ? texts.join("\n") : undefined;
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
export async function getConversationDescriptors(
  limit = 20,
  index = 0,
  filter = "",
  agentId = "",
  agentVersion?: number,
  conversationState?: ConversationState
): Promise<ConversationDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  if (agentId) params.set("agentId", agentId);
  if (agentVersion) params.set("agentVersion", String(agentVersion));
  if (conversationState) params.set("conversationState", conversationState);
  const raw = await api.get<ConversationDescriptorRaw[] | { value: ConversationDescriptorRaw[]; Count?: number }>(
    `/conversationstore/conversations?${params.toString()}`
  );
  // Backend may return a raw array or a { value: [...], Count } wrapper
  const items = Array.isArray(raw) ? raw : (raw?.value ?? []);
  return items.map(normalizeDescriptor);
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
 *  Used by the Memory Inspector debug tab. */
export function getDetailedConversation(
  conversationId: string,
): Promise<DetailedConversation> {
  return api.get<DetailedConversation>(
    `/agents/${conversationId}?returnDetailed=true`,
  );
}
