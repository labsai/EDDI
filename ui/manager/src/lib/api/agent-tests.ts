import { api } from "../api-client";

/**
 * Agent regression harness ("golden conversations") — mirrors the backend
 * records `AgentTestSuite` (configs/agenttests/model) and `AgentTestRun`
 * (engine/agenttests/model). The suite is an ordinary versioned resource
 * (`/testsuitestore/testsuites`, slug `testsuite`); runs are their own API.
 */

// ── Suite ─────────────────────────────────────────────────────────

export type ToolMode = "mock" | "live";
export type UnmockedToolsPolicy = "fail" | "live";

export const CONVERSATION_STATES = [
  "READY",
  "AWAITING_HUMAN",
  "ENDED",
  "ERROR",
  "IN_PROGRESS",
  "EXECUTION_INTERRUPTED",
] as const;
export type ExpectedConversationState = (typeof CONVERSATION_STATES)[number];

export interface SuiteDefaults {
  toolMode?: ToolMode;
  unmockedTools?: UnmockedToolsPolicy;
  repetitions?: number;
  passRate?: number;
  turnTimeoutSeconds?: number;
}

export interface ToolMock {
  tool?: string;
  argsSubset?: Record<string, unknown>;
  response?: { httpCode?: number; body?: unknown };
}

/** `{equals: v}`, `{matches: regex}`, `{exists: true}` or `{absent: true}` */
export interface PropertyExpectation {
  equals?: unknown;
  matches?: string;
  exists?: boolean;
  absent?: boolean;
}

export interface ToolCallExpectation {
  tool?: string;
  argsSubset?: Record<string, unknown>;
}

export interface Expectation {
  actionsContain?: string[];
  actionsExact?: string[];
  properties?: Record<string, PropertyExpectation>;
  outputContains?: string;
  outputMatches?: string;
  toolCalled?: ToolCallExpectation[];
  toolNotCalled?: string[];
  maxToolCalls?: number;
  maxCostUsd?: number;
  conversationState?: ExpectedConversationState;
}

export interface SuiteTurn {
  input?: string;
  context?: Record<string, unknown>;
  expect?: Expectation;
}

export interface SuiteCase {
  name?: string;
  context?: Record<string, unknown>;
  repetitions?: number;
  passRate?: number;
  turns?: SuiteTurn[];
}

export interface AgentTestSuite {
  name?: string;
  description?: string;
  defaults?: SuiteDefaults;
  maxCostPerRun?: number;
  toolMocks?: ToolMock[];
  cases?: SuiteCase[];
}

// ── Run ───────────────────────────────────────────────────────────

export type RunStatus = "RUNNING" | "PASSED" | "FAILED" | "ERROR" | "ABORTED" | "CANCELLED";

/** One expectation that did not hold. `expectation` is the field name, or
 *  `unmockedTool` for a tool call that had no mock in fail-closed mode. */
export interface ExpectationFailure {
  expectation: string;
  expected?: unknown;
  actual?: unknown;
}

export interface TurnResult {
  index: number;
  input?: string;
  passed: boolean;
  costUsd: number;
  durationMs: number;
  actions?: string[];
  output?: string;
  toolCalls?: string[];
  conversationState?: string;
  failures?: ExpectationFailure[];
}

export interface AttemptResult {
  repetition: number;
  conversationId?: string;
  passed: boolean;
  costUsd: number;
  durationMs: number;
  finalState?: string;
  unmockedToolCalls?: string[];
  turns?: TurnResult[];
  /** Why the attempt stopped early (cost ceiling, conversation state, crash). */
  error?: string;
}

export interface CaseResult {
  name: string;
  repetitions: number;
  passRate: number;
  passedRepetitions: number;
  passed: boolean;
  attempts?: AttemptResult[];
}

export interface AgentTestRun {
  id: string;
  suiteId?: string;
  suiteVersion?: number;
  suiteName?: string;
  agentId?: string;
  agentVersion?: number;
  status: RunStatus;
  startedBy?: string;
  startedAt?: number;
  finishedAt?: number;
  durationMs?: number;
  totalCostUsd?: number;
  maxCostPerRun?: number;
  costCeilingReached?: boolean;
  models?: string[];
  casesTotal?: number;
  casesPassed?: number;
  casesFailed?: number;
  cases?: CaseResult[];
  keepConversations?: boolean;
  /** Set for ERROR, ABORTED and CANCELLED: the reason the run did not finish. */
  error?: string;
  /** The node executing the run, `<nodeId>/<bootId>`. */
  ownerNode?: string;
  /** The owner's last sign of life while RUNNING (epoch ms). */
  heartbeatAt?: number;
}

export interface StartRunRequest {
  suiteId: string;
  agentId: string;
  suiteVersion?: number;
  agentVersion?: number;
  keepConversations?: boolean;
}

export function isRunFinished(status: RunStatus | undefined): boolean {
  return status !== undefined && status !== "RUNNING";
}

/** POST /agenttests/runs — 202 with the RUNNING record. */
export function startAgentTestRun(request: StartRunRequest): Promise<AgentTestRun> {
  return api.post<AgentTestRun>("/agenttests/runs", request);
}

/**
 * POST /agenttests/runs/{runId}/cancel — 202 with the record as it is now (still
 * RUNNING); it reads CANCELLED moments later. 409 when it has already finished or
 * runs on another node.
 */
export function cancelAgentTestRun(runId: string): Promise<AgentTestRun> {
  return api.post<AgentTestRun>(`/agenttests/runs/${encodeURIComponent(runId)}/cancel`);
}

export function getAgentTestRun(runId: string): Promise<AgentTestRun> {
  return api.get<AgentTestRun>(`/agenttests/runs/${encodeURIComponent(runId)}`);
}

/** An agent's runs, newest first. */
export function listAgentTestRuns(agentId: string, index = 0, limit = 20): Promise<AgentTestRun[]> {
  const params = new URLSearchParams({ agentId, index: String(index), limit: String(limit) });
  return api.get<AgentTestRun[]>(`/agenttests/runs?${params.toString()}`);
}
