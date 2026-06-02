import { api } from "../api-client";
import { BearerEventSource } from "../bearer-event-source";

// ==================== Types ====================

export interface CoordinatorStatus {
  coordinatorType: string;
  connected: boolean;
  connectionStatus: string;
  activeConversations: number;
  totalProcessed: number;
  totalDeadLettered: number;
  queueDepths: Record<string, number>;
}

export interface DeadLetterEntry {
  id: string;
  conversationId: string;
  error: string;
  timestamp: number;
  payload: string;
}

// ==================== API Functions ====================

const BASE = "/administration/coordinator";

export async function getCoordinatorStatus(): Promise<CoordinatorStatus> {
  return api.get<CoordinatorStatus>(`${BASE}/status`);
}

export async function getDeadLetters(): Promise<DeadLetterEntry[]> {
  return api.get<DeadLetterEntry[]>(`${BASE}/dead-letters`);
}

export async function replayDeadLetter(entryId: string): Promise<void> {
  return api.post(`${BASE}/dead-letters/${entryId}/replay`);
}

export async function discardDeadLetter(entryId: string): Promise<void> {
  return api.delete(`${BASE}/dead-letters/${entryId}`);
}

export async function purgeDeadLetters(): Promise<number> {
  return api.delete<number>(`${BASE}/dead-letters`);
}

/**
 * Subscribe to the coordinator SSE stream.
 * Uses BearerEventSource (fetch+ReadableStream) instead of the native EventSource
 * because EventSource cannot send custom headers like Authorization.
 */
export function createCoordinatorEventSource(): BearerEventSource {
  return new BearerEventSource(
    `${window.location.origin}${BASE}/stream`,
    api.getAuthHeader()
  );
}
