import { api } from "../api-client";

// Types matching EDDI backend
export type ConversationState = "READY" | "IN_PROGRESS" | "ERROR" | "ENDED";

export type ViewState = "UNSEEN" | "READ" | "DONE";

export interface ConversationDescriptor {
  resource: string;
  name: string;
  description: string;
  createdOn: number;
  lastModifiedOn: number;
  botId: string;
  botVersion: number;
  conversationState: ConversationState;
  viewState?: ViewState;
  createdBy?: string;
  lastModifiedBy?: string;
}

export interface ConversationOutput {
  key: string;
  value: unknown;
}

export interface ConversationStepData {
  key: string;
  value: unknown;
  isPublic?: boolean;
}

export interface SimpleConversationStep {
  input?: string;
  output?: string;
  actions?: string[];
  conversationOutputs?: ConversationOutput[];
  data?: ConversationStepData[];
}

export interface SimpleConversationMemorySnapshot {
  botId: string;
  botVersion: number;
  conversationId: string;
  conversationState: ConversationState;
  environment: string;
  conversationSteps: SimpleConversationStep[];
  conversationProperties?: Record<string, unknown>;
  redoCache?: SimpleConversationStep[];
}

export interface ConversationMemorySnapshot {
  botId: string;
  botVersion: number;
  conversationId: string;
  conversationState: ConversationState;
  environment: string;
  conversationSteps: Record<string, unknown>[];
  conversationProperties?: Record<string, unknown>;
}

/** Parse conversation resource URI to extract ID */
export function parseConversationUri(resource: string): string {
  try {
    const url = new URL(resource.replace("eddi://", "http://"));
    const parts = url.pathname.split("/");
    return parts[parts.length - 1] || resource;
  } catch {
    return resource;
  }
}

// API functions — using low-level /conversationstore/conversations endpoints
export function getConversationDescriptors(
  limit = 20,
  index = 0,
  filter = "",
  botId = "",
  botVersion?: number,
  conversationState?: ConversationState
): Promise<ConversationDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  if (botId) params.set("botId", botId);
  if (botVersion) params.set("botVersion", String(botVersion));
  if (conversationState) params.set("conversationState", conversationState);
  return api.get<ConversationDescriptor[]>(
    `/conversationstore/conversations?${params.toString()}`
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
