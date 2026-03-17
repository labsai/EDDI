import { api } from "../api-client";

/* ─── Types ─── */

export interface AuditEntry {
  id: string;
  conversationId: string;
  botId: string;
  botVersion: number | null;
  userId: string | null;
  environment: string | null;
  stepIndex: number;
  taskId: string;
  taskType: string;
  taskIndex: number;
  durationMs: number;
  input: Record<string, unknown> | null;
  output: Record<string, unknown> | null;
  llmDetail: Record<string, unknown> | null;
  toolCalls: Record<string, unknown> | null;
  actions: string[] | null;
  cost: number;
  timestamp: string; // ISO instant
  hmac: string | null;
}

/* ─── API Functions ─── */

const BASE = "/auditstore";

/** Get the audit trail for a specific conversation. */
export async function getAuditTrail(
  conversationId: string,
  skip = 0,
  limit = 100,
): Promise<AuditEntry[]> {
  return api.get<AuditEntry[]>(
    `${BASE}/${conversationId}?skip=${skip}&limit=${limit}`,
  );
}

/** Get the audit trail for a specific bot. */
export async function getAuditTrailByBot(
  botId: string,
  botVersion?: number | null,
  skip = 0,
  limit = 100,
): Promise<AuditEntry[]> {
  const params = new URLSearchParams({ skip: String(skip), limit: String(limit) });
  if (botVersion != null) {
    params.set("botVersion", String(botVersion));
  }
  return api.get<AuditEntry[]>(`${BASE}/bot/${botId}?${params.toString()}`);
}

/** Get the number of audit entries for a conversation. */
export async function getEntryCount(conversationId: string): Promise<number> {
  return api.get<number>(`${BASE}/${conversationId}/count`);
}
