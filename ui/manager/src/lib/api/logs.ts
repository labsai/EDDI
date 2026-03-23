import { api } from "../api-client";

// ==================== Types ====================

export interface LogEntry {
  timestamp: number;
  level: string;
  loggerName: string;
  message: string;
  environment?: string;
  agentId?: string;
  agentVersion?: number;
  conversationId?: string;
  userId?: string;
  instanceId?: string;
}

export interface DatabaseLogEntry {
  message?: string;
  level?: string;
  loggerName?: string;
  timestamp?: string | number;
  environment?: string;
  agentId?: string;
  agentVersion?: number;
  conversationId?: string;
  userId?: string;
  instanceId?: string;
}

export interface InstanceInfo {
  instanceId: string;
}

export interface LogFilters {
  agentId?: string;
  conversationId?: string;
  level?: string;
  limit?: number;
}

export interface HistoryFilters {
  environment?: string;
  agentId?: string;
  agentVersion?: number;
  conversationId?: string;
  userId?: string;
  instanceId?: string;
  skip?: number;
  limit?: number;
}

// ==================== API Functions ====================

const BASE = "/administration/logs";

export async function getRecentLogs(
  filters: LogFilters = {}
): Promise<LogEntry[]> {
  const params = new URLSearchParams();
  if (filters.agentId) params.set("agentId", filters.agentId);
  if (filters.conversationId)
    params.set("conversationId", filters.conversationId);
  if (filters.level) params.set("level", filters.level);
  if (filters.limit) params.set("limit", String(filters.limit));

  const qs = params.toString();
  return api.get<LogEntry[]>(`${BASE}${qs ? `?${qs}` : ""}`);
}

export async function getHistoryLogs(
  filters: HistoryFilters = {}
): Promise<DatabaseLogEntry[]> {
  const params = new URLSearchParams();
  if (filters.environment) params.set("environment", filters.environment);
  if (filters.agentId) params.set("agentId", filters.agentId);
  if (filters.agentVersion) params.set("agentVersion", String(filters.agentVersion));
  if (filters.conversationId)
    params.set("conversationId", filters.conversationId);
  if (filters.userId) params.set("userId", filters.userId);
  if (filters.instanceId) params.set("instanceId", filters.instanceId);
  if (filters.skip) params.set("skip", String(filters.skip));
  if (filters.limit) params.set("limit", String(filters.limit));

  const qs = params.toString();
  return api.get<DatabaseLogEntry[]>(
    `${BASE}/history${qs ? `?${qs}` : ""}`
  );
}

export async function getInstanceId(): Promise<InstanceInfo> {
  return api.get<InstanceInfo>(`${BASE}/instance`);
}

/**
 * Create an SSE EventSource for live log streaming.
 * Returns an EventSource that emits "log" events with LogEntry payloads.
 */
export function createLogEventSource(filters: LogFilters = {}): EventSource {
  const params = new URLSearchParams();
  if (filters.agentId) params.set("agentId", filters.agentId);
  if (filters.conversationId)
    params.set("conversationId", filters.conversationId);
  if (filters.level) params.set("level", filters.level);

  const qs = params.toString();
  return new EventSource(
    `${BASE}/stream${qs ? `?${qs}` : ""}`
  );
}
