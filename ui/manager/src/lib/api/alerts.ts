import { api } from "../api-client";
import { seg } from "./path-segment";

/**
 * Alerts — rules (`/alerts/rules`) and the inbox (`/alerts/events`). See
 * docs/alerts.md.
 *
 * Scoping is the backend's: an administrator sees and writes everything; an
 * editor names an agent they hold EDIT on (400 `AGENT_ID_REQUIRED` otherwise),
 * writes rules for that one agent only (403 `ADMIN_SCOPE_REQUIRED`) and no
 * webhook targets (403 `WEBHOOK_ADMIN_ONLY`); a webhook is shown to them by host.
 */

export type AlertSource = "RATING" | "CONVERSATION_ERROR";
export type ErrorKind = "TURN_ERROR" | "TURN_INTERRUPTED" | "TURN_DEADLINE" | "COST_CEILING" | "TENANT_BUDGET";
export type DigestMode = "NONE" | "HOURLY" | "DAILY";
export type DeliveryType = "SLACK" | "WEBHOOK";
export type EventState = "PENDING" | "DELIVERED" | "FAILED" | "INBOX_ONLY" | "DIGEST_PENDING" | "DIGESTED";
export type DeliveryStatus = "PENDING" | "DELIVERED" | "FAILED";

export const ERROR_KINDS: ErrorKind[] = ["TURN_ERROR", "TURN_INTERRUPTED", "TURN_DEADLINE", "COST_CEILING", "TENANT_BUDGET"];
export const EVENT_STATES: EventState[] = ["PENDING", "DELIVERED", "FAILED", "INBOX_ONLY", "DIGEST_PENDING", "DIGESTED"];

/** Mirrors AlertRuleValidator. */
export const ALERT_LIMITS = {
  maxNameLength: 120,
  maxTargets: 5,
  maxTags: 20,
  maxWindowMinutes: 1440,
  maxPerWindow: 1000,
  defaultWindowMinutes: 60,
  defaultMaxPerWindow: 10,
} as const;

export interface AlertScope {
  allAgents: boolean;
  agentIds: string[];
}

export interface AlertCondition {
  maxNormalizedScore?: number | null;
  onComment?: boolean | null;
  tags?: string[] | null;
  errorKinds?: ErrorKind[] | null;
}

export interface DeliveryTarget {
  type: DeliveryType;
  channelId?: string | null;
  url?: string | null;
  signingSecret?: string | null;
}

export interface AlertRule {
  id?: string | null;
  name: string;
  enabled: boolean;
  scope: AlertScope;
  source: AlertSource;
  condition: AlertCondition;
  delivery: DeliveryTarget[];
  throttle?: { windowMinutes: number; maxPerWindow: number } | null;
  digest?: DigestMode | null;
  createdBy?: string | null;
  createdAt?: string | null;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

export interface RulePage {
  total: number;
  index: number;
  limit: number;
  rules: AlertRule[];
}

export interface TestDelivery {
  type: DeliveryType;
  label: string;
  delivered: boolean;
  error: string | null;
}

export interface AlertDelivery {
  targetKey: string;
  type: DeliveryType;
  label: string;
  status: DeliveryStatus;
  attempts: number;
  lastAttemptAt?: string | null;
  lastError?: string | null;
  nextAttemptAt?: string | null;
}

export interface AlertMatch {
  matched: string[];
  errorKind?: ErrorKind | null;
  ratingId?: string | null;
  stepIndex?: number | null;
  scale?: string | null;
  score?: number | null;
  normalizedScore?: number | null;
  tags?: string[] | null;
  commentExcerpt?: string | null;
}

export interface AlertEvent {
  id: string;
  ruleId: string | null;
  ruleName: string | null;
  source: AlertSource | null;
  kind: "ALERT" | "DIGEST";
  agentId: string | null;
  agentVersion?: number | null;
  conversationId?: string | null;
  firedAt: string;
  summary: string;
  match?: AlertMatch | null;
  deliveries: AlertDelivery[];
  state: EventState;
  nextAttemptAt?: string | null;
  duplicates: number;
  digestCount?: number | null;
  digestItems?: string[] | null;
  acknowledgedBy?: string | null;
  acknowledgedAt?: string | null;
}

export interface EventPage {
  total: number;
  index: number;
  limit: number;
  events: AlertEvent[];
}

export interface AlertEventQuery {
  agentId?: string;
  ruleId?: string;
  source?: AlertSource;
  acknowledged?: boolean;
  state?: EventState;
  conversationId?: string;
  from?: string;
  to?: string;
}

function queryString(query: object, extra: Record<string, string | number | undefined> = {}): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries({ ...query, ...extra })) {
    if (value === undefined || value === null || value === "") continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

// ── Rules ──────────────────────────────────────────────────────────

export function listAlertRules(agentId?: string, index = 0, limit = 50): Promise<RulePage> {
  return api.get<RulePage>(`/alerts/rules${queryString({ agentId }, { index, limit })}`);
}

export function createAlertRule(rule: AlertRule): Promise<AlertRule> {
  return api.post<AlertRule>("/alerts/rules", rule);
}

export function updateAlertRule(id: string, rule: AlertRule): Promise<AlertRule> {
  return api.put<AlertRule>(`/alerts/rules/${seg(id)}`, rule);
}

export async function deleteAlertRule(id: string): Promise<void> {
  await api.delete(`/alerts/rules/${seg(id)}`);
}

/** Validates the rule (saved or not) and sends a marked test alert to each target. */
export async function testAlertRule(rule: AlertRule): Promise<TestDelivery[]> {
  const result = await api.post<{ deliveries: TestDelivery[] }>("/alerts/rules/test", rule);
  return result?.deliveries ?? [];
}

// ── Events ─────────────────────────────────────────────────────────

export function listAlertEvents(query: AlertEventQuery, index = 0, limit = 25): Promise<EventPage> {
  return api.get<EventPage>(`/alerts/events${queryString(query, { index, limit })}`);
}

export async function getUnacknowledgedCount(agentId?: string): Promise<number> {
  const result = await api.get<{ count: number }>(`/alerts/events/unacknowledged-count${queryString({ agentId })}`);
  return result?.count ?? 0;
}

export function acknowledgeAlertEvent(id: string): Promise<AlertEvent> {
  return api.post<AlertEvent>(`/alerts/events/${seg(id)}/acknowledge`);
}

/** The listed ids, or — with none — every unacknowledged event matching the filter. */
export async function acknowledgeAlertEvents(ids: string[] | null, query: AlertEventQuery = {}): Promise<number> {
  const result = await api.post<{ acknowledged: number }>(`/alerts/events/acknowledge${queryString(query)}`, ids && ids.length ? { ids } : {});
  return result?.acknowledged ?? 0;
}

export function retryAlertEvent(id: string): Promise<AlertEvent> {
  return api.post<AlertEvent>(`/alerts/events/${seg(id)}/retry`);
}

/** A blank rule of a source, with the defaults the backend applies. */
export function blankRule(source: AlertSource, agentId: string): AlertRule {
  return {
    name: "",
    enabled: true,
    scope: agentId ? { allAgents: false, agentIds: [agentId] } : { allAgents: true, agentIds: [] },
    source,
    condition: source === "RATING" ? { maxNormalizedScore: 0.5, onComment: true } : { errorKinds: ["TURN_ERROR", "TURN_DEADLINE"] },
    delivery: [],
    throttle: { windowMinutes: ALERT_LIMITS.defaultWindowMinutes, maxPerWindow: ALERT_LIMITS.defaultMaxPerWindow },
    digest: "HOURLY",
  };
}
