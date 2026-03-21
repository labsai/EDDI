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
  botId: string;
  botVersion: number; // 0 = latest deployed
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

export interface ScheduleFireLog {
  id?: string;
  scheduleId: string;
  scheduleName?: string;
  botId: string;
  conversationId?: string;
  firedAt: number;
  completedAt?: number;
  durationMs?: number;
  success: boolean;
  error?: string;
  instanceId?: string;
}

// ==================== API Functions ====================

const BASE = "/schedulestore/schedules";

export async function getSchedules(
  botId?: string
): Promise<ScheduleConfiguration[]> {
  const query = botId ? `?botId=${encodeURIComponent(botId)}` : "";
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

