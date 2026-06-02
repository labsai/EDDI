import { api } from "../api-client";
import {
  createAuthEventSource,
  type AuthEventSourceHandle,
} from "./sse-utils";

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
 * Create an auth-aware SSE stream for coordinator status updates.
 * Uses fetch + ReadableStream to support Authorization headers.
 */
export function createCoordinatorEventSource(options?: {
  onMessage?: (status: CoordinatorStatus) => void;
  onError?: (error: Error) => void;
  onOpen?: () => void;
  signal?: AbortSignal;
}): AuthEventSourceHandle {
  return createAuthEventSource(`${BASE}/stream`, {
    onMessage: (event) => {
      try {
        options?.onMessage?.(JSON.parse(event.data));
      } catch {
        /* ignore parse errors */
      }
    },
    onError: options?.onError,
    onOpen: options?.onOpen,
    signal: options?.signal,
  });
}

