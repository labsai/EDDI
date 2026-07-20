import { api } from "../api-client";

// ==================== Types ====================

export type TriggerType = "CRON" | "HEARTBEAT";

export type FireStatus =
  | "PENDING"
  | "CLAIMED"
  | "EXECUTING"
  | "COMPLETED"
  | "FAILED"
  | "DEAD_LETTERED";

export interface ScheduleConfiguration {
  id?: string;
  name: string;

  // Type
  triggerType: TriggerType;

  // Target
  agentId: string;
  agentVersion: number; // 0 = latest deployed
  environment: string;
  tenantId?: string;

  // Timing
  cronExpression?: string;
  heartbeatIntervalSeconds?: number;
  oneTimeAt?: string;
  timeZone?: string;

  // Trigger
  message: string;
  userId?: string;
  conversationStrategy?: "new" | "persistent";
  persistentConversationId?: string;

  // State (read-only from server)
  enabled: boolean;
  nextFire?: number;
  lastFired?: number;
  fireStatus: FireStatus;
  claimedBy?: string;
  claimedAt?: number;
  fireId?: string;
  failCount: number;
  nextRetryAt?: number;

  // Security
  maxCostPerFire?: number;
  allowSelfScheduling?: boolean;
  createdBy?: string;

  // Metadata
  metadata?: Record<string, unknown>;
  createdAt?: number;
  updatedAt?: number;

  // Computed
  cronDescription?: string;
}

/** Terminal outcome of a fire, mirroring the backend ScheduleFireLog.status. */
export type FireLogStatus = "COMPLETED" | "FAILED" | "DEAD_LETTERED";

/**
 * One fire-execution record. Mirrors the backend `ScheduleFireLog` record
 * exactly — its JSON keys are the record component names, and the three
 * timestamps are ISO-8601 strings (Jackson-serialized `Instant`), NOT epoch
 * millis. (The previous shape — firedAt/success/durationMs/error — was never
 * emitted by the backend and rendered "Invalid Date"/always-failed for every
 * row against a real EDDI; only the wrong mocks hid it.)
 */
export interface ScheduleFireLog {
  id?: string;
  scheduleId: string;
  fireId?: string;
  fireTime: string;
  startedAt?: string;
  completedAt?: string;
  status: FireLogStatus;
  instanceId?: string;
  conversationId?: string;
  errorMessage?: string;
  attemptNumber?: number;
  cost?: number;
}

/** Duration in ms between startedAt and completedAt, or null if unavailable. */
export function fireLogDurationMs(log: ScheduleFireLog): number | null {
  if (!log.startedAt || !log.completedAt) return null;
  const start = Date.parse(log.startedAt);
  const end = Date.parse(log.completedAt);
  if (Number.isNaN(start) || Number.isNaN(end)) return null;
  return Math.max(0, end - start);
}

// ==================== API Functions ====================

const BASE = "/schedulestore/schedules";

export async function getSchedules(
  agentId?: string
): Promise<ScheduleConfiguration[]> {
  const query = agentId ? `?agentId=${encodeURIComponent(agentId)}` : "";
  return api.get<ScheduleConfiguration[]>(`${BASE}${query}`);
}

export async function getSchedule(
  scheduleId: string
): Promise<ScheduleConfiguration> {
  return api.get<ScheduleConfiguration>(`${BASE}/${scheduleId}`);
}

export async function createSchedule(
  config: Partial<ScheduleConfiguration>
): Promise<{ location: string }> {
  return api.post<{ location: string }>(BASE, config);
}

export async function updateSchedule(
  scheduleId: string,
  config: Partial<ScheduleConfiguration>
): Promise<void> {
  return api.put(`${BASE}/${scheduleId}`, config);
}

export async function deleteSchedule(scheduleId: string): Promise<void> {
  return api.delete(`${BASE}/${scheduleId}`);
}

export async function enableSchedule(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/enable`);
}

export async function disableSchedule(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/disable`);
}

export async function fireNow(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/fire`);
}

export async function retryDeadLetter(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/retry`);
}

export async function dismissDeadLetter(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/dismiss`);
}

export async function getFireLogs(
  scheduleId: string,
  limit = 20
): Promise<ScheduleFireLog[]> {
  return api.get<ScheduleFireLog[]>(
    `${BASE}/${scheduleId}/fires?limit=${limit}`
  );
}

export async function getFailedFires(limit = 50): Promise<ScheduleFireLog[]> {
  return api.get<ScheduleFireLog[]>(`${BASE}/admin/failed?limit=${limit}`);
}

