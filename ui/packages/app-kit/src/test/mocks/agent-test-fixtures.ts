/**
 * Fixtures for the agent regression harness (`/testsuitestore/testsuites`,
 * `/agenttests/runs`), shaped like the backend records `AgentTestSuite` and
 * `AgentTestRun` (docs/agent-testing.md → "Results"). Inlined, no imports from
 * `src/`: e2e/fixtures.ts loads handlers.ts under Node.
 */

export const MOCK_SUITE = {
  name: "Booking agent — core flows",
  description: "Greeting, availability lookup and a refused booking.",
  defaults: { toolMode: "mock", unmockedTools: "fail", repetitions: 1, passRate: 1.0, turnTimeoutSeconds: 120 },
  maxCostPerRun: 1.0,
  toolMocks: [
    {
      tool: "getAvailability",
      argsSubset: { city: "Vienna" },
      response: { httpCode: 200, body: { rooms: 3 } },
    },
    { tool: "createBooking", response: { httpCode: 403, body: "forbidden" } },
  ],
  cases: [
    {
      name: "greeting then availability",
      context: { language: "en" },
      turns: [
        { input: "hello", expect: { actionsContain: ["greet"] } },
        {
          input: "rooms in Vienna tomorrow?",
          expect: {
            toolCalled: [{ tool: "getAvailability", argsSubset: { city: "Vienna" } }],
            toolNotCalled: ["createBooking"],
            properties: { city: { equals: "Vienna" } },
            outputMatches: "(?i)3 rooms",
            maxCostUsd: 0.02,
          },
        },
      ],
    },
    {
      name: "booking is refused for guests",
      repetitions: 5,
      passRate: 0.8,
      turns: [{ input: "book it", expect: { actionsContain: ["refuse"], conversationState: "READY" } }],
    },
  ],
};

type Turn = {
  index: number;
  input: string;
  passed: boolean;
  costUsd: number;
  durationMs: number;
  actions?: string[];
  output?: string;
  toolCalls?: string[];
  conversationState?: string;
  failures?: Array<{ expectation: string; expected?: unknown; actual?: unknown }>;
};

function attempt(repetition: number, turns: Turn[], extra: Record<string, unknown> = {}) {
  return {
    repetition,
    conversationId: `conv-${repetition}`,
    passed: turns.every((t) => t.passed) && !extra.error,
    costUsd: turns.reduce((s, t) => s + t.costUsd, 0),
    durationMs: turns.reduce((s, t) => s + t.durationMs, 0),
    finalState: "READY",
    unmockedToolCalls: [] as string[],
    turns,
    ...extra,
  };
}

const GREETING_TURN: Turn = {
  index: 1,
  input: "hello",
  passed: true,
  costUsd: 0.0021,
  durationMs: 640,
  actions: ["greet"],
  output: "Hello! How can I help you today?",
  toolCalls: [],
  conversationState: "READY",
};

const AVAILABILITY_PASS: Turn = {
  index: 2,
  input: "rooms in Vienna tomorrow?",
  passed: true,
  costUsd: 0.0111,
  durationMs: 2130,
  actions: ["check_availability"],
  output: "We have 3 rooms available in Vienna tomorrow.",
  toolCalls: ["getAvailability"],
  conversationState: "READY",
};

const AVAILABILITY_FAIL: Turn = {
  index: 2,
  input: "rooms in Vienna tomorrow?",
  passed: false,
  costUsd: 0.0132,
  durationMs: 1980,
  actions: ["ask_date"],
  output: "For which date?",
  toolCalls: ["searchHotels"],
  conversationState: "READY",
  failures: [
    {
      expectation: "toolCalled",
      expected: { tool: "getAvailability", argsSubset: { city: "Vienna" } },
      actual: ["searchHotels"],
    },
    { expectation: "properties.city", expected: { equals: "Vienna" }, actual: null },
    { expectation: "outputMatches", expected: "(?i)3 rooms", actual: "For which date?" },
    { expectation: "unmockedTool", expected: "a mock for 'searchHotels'", actual: "NOT_EXECUTED" },
  ],
};

const REFUSE_TURN: Turn = {
  index: 1,
  input: "book it",
  passed: true,
  costUsd: 0.004,
  durationMs: 900,
  actions: ["refuse"],
  output: "I can't book on your behalf.",
  toolCalls: ["createBooking"],
  conversationState: "READY",
};

const base = {
  suiteId: "suite00000000000000000001",
  suiteVersion: 3,
  suiteName: "Booking agent — core flows",
  agentVersion: 7,
  startedBy: "alice",
  maxCostPerRun: 1.0,
  models: ["gpt-4o-mini"],
  keepConversations: false,
};

/** The four terminal shapes plus one in flight; `agentId` is stamped by the handler. */
export function buildMockRuns(agentId: string, now = Date.now()) {
  const passing = {
    ...base,
    id: "run-passed-0000000000000001",
    agentId,
    status: "PASSED",
    startedAt: now - 3_600_000,
    finishedAt: now - 3_600_000 + 6_800,
    durationMs: 6_800,
    totalCostUsd: 0.0311,
    costCeilingReached: false,
    casesTotal: 2,
    casesPassed: 2,
    casesFailed: 0,
    cases: [
      {
        name: "greeting then availability",
        repetitions: 1,
        passRate: 1,
        passedRepetitions: 1,
        passed: true,
        attempts: [attempt(1, [GREETING_TURN, AVAILABILITY_PASS])],
      },
      {
        name: "booking is refused for guests",
        repetitions: 5,
        passRate: 0.8,
        passedRepetitions: 5,
        passed: true,
        attempts: [1, 2, 3, 4, 5].map((r) => attempt(r, [REFUSE_TURN])),
      },
    ],
  };

  const failing = {
    ...base,
    id: "run-failed-00000000000000002",
    agentId,
    agentVersion: 8,
    status: "FAILED",
    startedAt: now - 1_800_000,
    finishedAt: now - 1_800_000 + 5_100,
    durationMs: 5_100,
    totalCostUsd: 0.0293,
    costCeilingReached: false,
    casesTotal: 2,
    casesPassed: 1,
    casesFailed: 1,
    cases: [
      {
        name: "greeting then availability",
        repetitions: 1,
        passRate: 1,
        passedRepetitions: 0,
        passed: false,
        attempts: [
          attempt(1, [GREETING_TURN, AVAILABILITY_FAIL], { unmockedToolCalls: ["searchHotels"] }),
        ],
      },
      {
        name: "booking is refused for guests",
        repetitions: 5,
        passRate: 0.8,
        passedRepetitions: 5,
        passed: true,
        attempts: [1, 2, 3, 4, 5].map((r) => attempt(r, [REFUSE_TURN])),
      },
    ],
  };

  const aborted = {
    ...base,
    id: "run-aborted-000000000000003",
    agentId,
    status: "ABORTED",
    startedAt: now - 7_200_000,
    finishedAt: now - 7_200_000 + 41_000,
    durationMs: 41_000,
    totalCostUsd: 0.0021,
    costCeilingReached: false,
    casesTotal: 2,
    casesPassed: 0,
    casesFailed: 0,
    cases: [],
    error: "The node running this test stopped before the run finished (it was RUNNING at the node's last shutdown).",
  };

  const errored = {
    ...base,
    id: "run-error-00000000000000004",
    agentId,
    status: "ERROR",
    startedAt: now - 10_800_000,
    finishedAt: now - 10_800_000 + 900,
    durationMs: 900,
    totalCostUsd: 0,
    costCeilingReached: false,
    casesTotal: 0,
    casesPassed: 0,
    casesFailed: 0,
    cases: [],
    error: "The run could not execute: the agent version could not be deployed to the test environment.",
  };

  const ceiling = {
    ...base,
    id: "run-ceiling-0000000000000005",
    agentId,
    status: "FAILED",
    startedAt: now - 14_400_000,
    finishedAt: now - 14_400_000 + 9_000,
    durationMs: 9_000,
    totalCostUsd: 1.0042,
    costCeilingReached: true,
    casesTotal: 1,
    casesPassed: 0,
    casesFailed: 1,
    cases: [
      {
        name: "greeting then availability",
        repetitions: 1,
        passRate: 1,
        passedRepetitions: 0,
        passed: false,
        attempts: [
          attempt(1, [GREETING_TURN], {
            error: "Stopped before turn 2: the run's cost ceiling of $1.0 was reached.",
          }),
        ],
      },
    ],
  };

  // Newest first, as the backend returns them.
  return [failing, passing, aborted, errored, ceiling];
}

/** A run that was just started: RUNNING, nothing recorded yet. */
export function buildRunningRun(
  id: string,
  agentId: string,
  startedAt: number,
  suiteId: string,
  suiteVersion?: number,
  agentVersion?: number
) {
  return {
    ...base,
    id,
    agentId,
    suiteId,
    ...(suiteVersion !== undefined ? { suiteVersion } : {}),
    ...(agentVersion !== undefined ? { agentVersion } : {}),
    status: "RUNNING",
    startedAt,
    casesTotal: 2,
    casesPassed: 0,
    casesFailed: 0,
    totalCostUsd: 0,
    cases: [],
  };
}
