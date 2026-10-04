import { api } from "../api-client";
import { BearerEventSource } from "../bearer-event-source";

/**
 * The cluster console API (`/administration/cluster`, EDDI 6.6+).
 *
 * Shapes mirror `ClusterAdminModels` in the backend. Read endpoints accept
 * `eddi-admin` and `eddi-viewer`; the dead-letter listing (it carries captured
 * input) and every action are `eddi-admin` only.
 */

// ==================== Types ====================

export type ClusterVerdict = "HEALTHY" | "DEGRADED" | "PARTITIONED" | "SINGLE_NODE";
export type NodeState = "LIVE" | "STALE" | "LOST" | "LEFT" | "UNKNOWN";

export interface ClusterNode {
  nodeId: string;
  boot: string | null;
  host: string | null;
  version: string | null;
  startedAt: number;
  lastHeartbeat: number;
  heartbeatAgeMs: number;
  state: NodeState;
  self: boolean;
  degraded: boolean;
  draining: boolean;
  natsRtt: number;
  activeConversations: number;
  leasesHeld: number;
  queueDepthTotal: number;
  localDeadLetters: number;
  hitlLeader: boolean;
  goneSince: number | null;
}

export interface PeerView {
  name: string;
  current: boolean;
  offline: boolean;
  lag: number;
  activeMillis: number | null;
}

export interface ConsumerView {
  name: string;
  pending: number;
  ackPending: number;
  redelivered: number;
}

export interface StreamView {
  name: string;
  role: "events" | "dead-letters" | "activity" | "archives" | "other";
  replicas: number;
  messages: number;
  bytes: number;
  firstSequence: number;
  lastSequence: number;
  consumerCount: number;
  leader: string | null;
  peers: PeerView[];
  consumers: ConsumerView[];
}

export interface BucketView {
  bucket: string;
  name: string;
  replicas: number;
  values: number;
  bytes: number;
  ttlMillis: number | null;
  leader: string | null;
  peers: PeerView[];
}

export interface NatsView {
  status: string;
  connectedUrl: string | null;
  serverName: string | null;
  serverVersion: string | null;
  clusterName: string | null;
  knownServers: string[];
  reconnects: number;
  rttMillis: number;
  streams: StreamView[];
  buckets: BucketView[];
  leaseEpoch: { firstRevision: number; createdAt: number | null; lastRevision: number } | null;
  account: { memoryBytes: number; storageBytes: number; streams: number; consumers: number } | null;
  error: string | null;
}

export interface ClusterOverview {
  mode: "single-node" | "cluster";
  verdict: ClusterVerdict;
  reasons: string[];
  answeredBy: string;
  generatedAt: number;
  degradedTurnsPolicy: "local" | "reject" | string;
  degradedSince: number | null;
  nodes: ClusterNode[];
  nats: NatsView | null;
  deadLetters: { shared: number; local: number };
  hitlLeader: string | null;
  settings: Record<string, unknown>;
}

export type LeaseFlag = "HOLDER_GONE" | "HOLDER_RESTARTED" | "NOT_RENEWED" | "LONG_RUNNING" | "CONTENDED";

export interface LeaseView {
  key: string;
  kind: "conversation" | "group" | "leader" | "other";
  conversationId: string | null;
  agentId: string | null;
  conversationState: string | null;
  holderNode: string;
  holderBoot: string;
  revision: number;
  since: number;
  ageMs: number;
  renewedAt: number;
  sinceRenewalMs: number;
  holderStatus: "LIVE" | "RESTARTED" | "GONE" | "UNKNOWN";
  flags: LeaseFlag[];
  waitingNode: string | null;
}

export interface LeasePage {
  leases: LeaseView[];
  total: number;
  suspicious: number;
  truncated: boolean;
}

export type ActivitySeverity = "info" | "warning" | "error";

export interface ActivityEvent {
  id: string;
  type: string;
  severity: ActivitySeverity;
  node: string;
  ts: number;
  payload: Record<string, unknown>;
}

export type DeadLetterReason = "fenced" | "timeout" | "failed";

export interface DeadLetterView {
  id: string;
  conversationId: string;
  agentId: string | null;
  agentVersion: number | null;
  environment: string | null;
  error: string;
  timestamp: number;
  reason: DeadLetterReason;
  nodeId: string | null;
  fence: { token?: number; storedFence?: number } | null;
  input: string | null;
  secretInput: boolean;
  replayable: boolean;
  notReplayableReason: "SECRET_INPUT" | "INPUT_NOT_CAPTURED" | "NOT_A_TURN" | null;
  local: boolean;
}

export interface DeadLetterPage {
  entries: DeadLetterView[];
  nextCursor: string | null;
  scanned: number;
}

export interface DeadLetterSummary {
  total: number;
  local: number;
  byReason: Record<string, number>;
  byNode: Record<string, number>;
  byAgent: Record<string, number>;
  oldest: number | null;
  newest: number | null;
  scanned: number;
  truncated: boolean;
  recent: DeadLetterView[];
}

export interface DeadLetterFilter {
  reason?: string;
  nodeId?: string;
  agentId?: string;
  conversationId?: string;
  from?: number;
  to?: number;
}

export type ItemOutcomeCode = "REPLAYED" | "DISCARDED" | "NOT_FOUND" | "NOT_REPLAYABLE" | "REJECTED" | "UNAVAILABLE";

export interface ItemOutcome {
  id: string;
  outcome: ItemOutcomeCode;
  message: string | null;
}

export interface BulkResult {
  results: ItemOutcome[];
  succeeded: number;
  failed: number;
}

export interface ActionResult {
  action: string;
  outcome: string;
  message: string;
  details: Record<string, unknown>;
}

export interface Finding {
  code: string;
  severity: ActivitySeverity;
  action: "WAIT" | "FORCE_RELEASE" | "REPLAY" | "OPEN_APPROVALS" | "CANCEL" | "NONE";
  details: Record<string, unknown>;
}

export interface Diagnosis {
  conversationId: string;
  verdict: "OK" | "BUSY" | "STUCK" | "NEEDS_ATTENTION" | "NOT_FOUND";
  exists: boolean;
  agentId: string | null;
  agentVersion: number | null;
  state: string | null;
  steps: number;
  lease: LeaseView | null;
  queuedOn: Record<string, number>;
  deadLetters: DeadLetterView[];
  findings: Finding[];
}

// ==================== API Functions ====================

const BASE = "/administration/cluster";

function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : "";
}

export function getClusterOverview(): Promise<ClusterOverview> {
  return api.get<ClusterOverview>(`${BASE}/overview`);
}

export function getClusterLeases(q?: string, flagged = false, limit = 200): Promise<LeasePage> {
  return api.get<LeasePage>(`${BASE}/leases${query({ q, flagged: flagged || undefined, limit })}`);
}

export function releaseLease(conversationId: string, expectedRevision: number | null): Promise<ActionResult> {
  return api.post<ActionResult>(`${BASE}/leases/${encodeURIComponent(conversationId)}/release`, { expectedRevision });
}

export function getClusterActivity(limit = 200): Promise<ActivityEvent[]> {
  return api.get<ActivityEvent[]>(`${BASE}/activity${query({ limit })}`);
}

export function diagnoseConversation(conversationId: string): Promise<Diagnosis> {
  return api.get<Diagnosis>(`${BASE}/diagnose/${encodeURIComponent(conversationId)}`);
}

export function getClusterDeadLetters(filter: DeadLetterFilter, after?: string | null, limit = 50): Promise<DeadLetterPage> {
  return api.get<DeadLetterPage>(`${BASE}/dead-letters${query({ ...filter, after, limit })}`);
}

export function getDeadLetterSummary(): Promise<DeadLetterSummary> {
  return api.get<DeadLetterSummary>(`${BASE}/dead-letters/summary`);
}

export function replayDeadLetters(ids: string[]): Promise<BulkResult> {
  return api.post<BulkResult>(`${BASE}/dead-letters/replay`, { ids });
}

export function discardDeadLetters(ids: string[]): Promise<BulkResult> {
  return api.post<BulkResult>(`${BASE}/dead-letters/discard`, { ids });
}

export function forwardLocalDeadLetters(): Promise<ActionResult> {
  return api.post<ActionResult>(`${BASE}/dead-letters/forward-local`);
}

export function resyncCaches(): Promise<ActionResult> {
  return api.post<ActionResult>(`${BASE}/caches/resync`);
}

export function reconcileDeployments(): Promise<ActionResult> {
  return api.post<ActionResult>(`${BASE}/deployments/reconcile`);
}

export function drainNode(nodeId: string, drain: boolean): Promise<ActionResult> {
  return api.post<ActionResult>(`${BASE}/nodes/${encodeURIComponent(nodeId)}/${drain ? "drain" : "undrain"}`);
}

/**
 * The live activity stream. `BearerEventSource`, not `EventSource`: the native
 * one cannot send the Authorization header.
 */
export function createClusterActivitySource(): BearerEventSource {
  return new BearerEventSource(`${window.location.origin}${BASE}/activity/stream`, api.getAuthHeader());
}
