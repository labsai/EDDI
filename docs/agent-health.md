# Agent Health

Agent Health answers the questions usage counts cannot: *is this agent doing its
job, is it getting better or worse, and why?* It is built in phases. This page
describes what ships today — the **turn facts** every later view is computed
from — and marks what is still coming.

| Phase | What it adds | Status |
| --- | --- | --- |
| H2a — Turn facts | One small row per turn execution, recorded after the fact | **Available** |
| H2b — Series & compare | Daily rollups, trends, version-to-version compare, change markers, REST/MCP | Coming |
| H3 — Outcomes | Sessions, engagement, resolved / escalated / abandoned, coverage | Coming |
| H4 — Status | Health profiles, presets, status with reasons, `HEALTH` alerts | Coming |
| H5 — Business join | Outcome events from other systems matched by the agent's own references | Coming |

The design and the decisions behind it are in
[`planning/agent-health-plan.md`](../planning/agent-health-plan.md).

## How it is collected

Health is **not a lifecycle task** and needs no wiring in an agent. Like
[alerts](alerts.md#design-note), it is recorded **after the fact**, off the one
point every turn passes once its outcome is final
(`ConversationStepRunner.waitForExecutionFinishOrTimeout` — say, rerun and HITL
resume) plus the `CONVERSATION_START` turn, which runs inline when a conversation
is created.

On the turn's thread the recorder copies a handful of values from the current
step — no text — into an immutable row and hands it to its **own** worker: one
thread, a bounded queue (`eddi.health.queue-capacity`). A full queue drops the
row and counts `eddi_health_dropped_total{reason="queue_full"}`; a failed write
counts `reason="store_error"`, and a row offered while the application stops
counts `reason="shutdown"`. None of them ever blocks or fails a turn.

It is independent of alerting: `eddi.alerts.enabled=false` leaves health
collection running, and the two workers never wait for each other.

Rows are written by **replace**, keyed by the execution — `(conversationId,
executionId)`; an execution id belongs to one step — never by increment:

- every step gets an **execution id** at the end of its first execution
  (`health:execution_id` on the step). A **HITL resume** and a **rerun**
  continue the same step and keep the id, so they replace their own row — the
  paused row becomes the finished one. A step that finished an execution before
  Agent Health existed is `legacy-<stepIndex>`;
- a **say after an undo** runs on a new step at the same index with a new id. The
  undone executions are listed on the new step (`health:discarded_executions`),
  and their rows are marked `discarded` — they really ran and really spent, but a
  later execution replaced them. Spend therefore matches the conversation's own
  total: the sum of `costUsd` over all rows equals the sum of
  `costUsd + discardedCostUsd` over the rows that are not discarded;
- **`day` and `timestamp` are first-recorded**: a turn paused on one day and
  resumed on the next stays on the day it started, so it is never counted on two
  days. The rest of the row is replaced by the resume — its cost, tokens,
  `paused`, outcome — so a rollup of the day it started has to be recomputed
  (H2b marks that day dirty). `updatedAt` is when the row was last written;
- **the newest capture wins**: a write whose `updatedAt` is older than the stored
  row's is ignored, so a stale paused row delayed in one node's queue cannot
  overwrite the finished row another node wrote (ordering is by the writing
  nodes' clocks);
- **`discarded` is sticky**: no later write clears it. When the discard arrives
  before the execution's own row (another node's queue held the row back), a
  marker is stored and the row joins it as discarded. Markers have no
  `timestamp`, are never returned and expire with the retention;
- **a turn that did not settle is inserted only**: a wait the watchdog abandoned
  (timeout, interrupted wait), a cancelled turn, an interrupted or
  deadline-exceeded one. Its row never replaces an existing one — an abandoned
  rerun or resume leaves the stored step as it was — and it discards nothing: a
  cancelled say after an undo leaves the undone step in the redo cache. A turn
  abandoned before its step was stamped gets a fresh id, so repeated timeouts at
  the same step are separate rows.

Health is **derived and not billing-grade**: quotas and cost ceilings stay the
authoritative counters, and a dropped row is a gap in a chart, nothing more.

## What a turn row holds

| Field | Source | Why |
| --- | --- | --- |
| `conversationId`, `stepIndex`, `executionId` | the conversation and step | The row's key (`stepIndex` follows from the execution) |
| `agentId`, `agentVersion`, `previousAgentVersion` | `agent:version`, `agent:switch` on the step | Per-version views; a conversation can move to a new version mid-way |
| `userId` | the conversation | GDPR erasure and export only — never shown in aggregates |
| `origin` | the user id and the step's own context (see below) | Interactive and automated traffic are measured apart |
| `day`, `timestamp` | when the execution was **first** recorded (UTC) | Daily rollups; kept by later replaces |
| `updatedAt` | when this version of the row was captured | Orders concurrent writes |
| `costUsd`, `discardedCostUsd` | `audit:cost`, `audit:discarded_cost` | The same values the [audit ledger](audit-ledger.md) and the cost ceiling read |
| `inputTokens`, `outputTokens`, `totalTokens` | `audit:token_usage` | Efficiency |
| `durationMs` | measured by the hook, from the start of its wait for the turn to the outcome (for the start turn, the whole `startConversation`) | Latency; for a resumed turn, the resumed part only — the human's wait is not the agent's latency |
| `errorKind` | the turn's outcome: `TURN_ERROR`, `TURN_INTERRUPTED`, `TURN_DEADLINE`, `COST_CEILING`, `TENANT_BUDGET` | Reliability |
| `paused` | the turn ended `AWAITING_HUMAN` | A paused turn is recorded; its resume replaces the row |
| `failures` | `health:llm_failures` — every failed LLM attempt, **including recovered ones**, at most 20 per turn | Provider trouble the user never saw. A turn with more failed attempts keeps its first 20 and under-reports the rest |
| `fallbackServed` | `llm:fallback:*` | The agent answered with its configured fallback |
| `toolCalls`, `toolFailures`, `toolRefusals` | the LLM tool traces: a `tool_result` entry is a call that **ran** (`toolCalls`, mocked ones included) — marked `failed` when it ran and failed (an exception, an HTTP error, a timeout, an unknown tool: `toolFailures`, a subset); a `tool_result` marked `notExecuted` (a quota, the turn deadline), a `tool_error` entry (a budget, an approval cap, a credential-reference check, an argument-schema check) or a refusal written instead of a result — `tool_repeat_refused` (the repeat guard), `tool_call_capped` (a per-response call cap), `tool_not_activated` (a lazy tool not activated yet), `hitl_self_conversation` / `hitl_self_ungating` (a call into the agent's own conversation or approval gate), `hitl_request_changed` / `hitl_not_executed` / `hitl_rejected` (a HITL resume that refused, or a reviewer who declined, the call) — is a call that **never ran** (`toolRefusals`). Guardrail findings are counted in `guardrailTrips`, and `tool_repeat_stopped` (which ends the loop) in neither | Tool reliability, apart from policy refusals |
| `guardrailTrips` | `audit:guardrail:*` | Informational |
| `userInput` | `input:initial` is not blank and the turn is not a [self-continuation](long-jobs.md) | Engagement |
| `agentOutput` | the step rendered output text | Engagement |
| `lastOutputWasQuestion` | the output text, trimmed, ends with `?` (or `？`) | A cheap hint for outcome inference (H3). It misses questions without a question mark and cannot tell a rhetorical one |
| `actions` | the turn's actions, at most 32 | Outcomes mapped from actions (H3) |
| `discarded` | an undo removed this execution | See above |

Each `failures` entry is `{class, model, recovery}`: `class` a provider failure
class (`TRANSIENT`, `RATE_LIMITED`, `TIMEOUT`, `QUOTA_EXHAUSTED`, `AUTH`, …),
`model` when the recording point knows it, and `recovery` what the engine did:

| `recovery` | Meaning |
| --- | --- |
| `retry` | A later attempt on the same model succeeded (from the task's `retry` settings). The retries of a call whose attempts **all** failed are not `retry`: they take the outcome of the failure that ended the call — `cascade` when the cascade escalated past it and answered, else `fallback` or `none` |
| `cascade` | The [model cascade](model-cascade.md) escalated to its next step, or served an earlier step's answer — and the cascade ended in an answer. When the cascade itself ends in a failure, every step it escalated past takes the outcome of that failure (`fallback` or `none`): a turn that failed was not partly recovered |
| `fallback` | `onError: fallback` served the configured answer |
| `none` | Nothing recovered it: the failure ended the model phase |

Same-model retries are recorded only for the task's **own model calls** (the
chat calls of the plain, tool-loop and re-ask paths, including those of a HITL
revision). Not recorded: retries of side calls made on the same thread — a
cascade judge, a summarisation, a tool-response truncation, an MCP call, a
sub-agent conversation started from a tool (which records its own) — and the
circuit breaker's refusals (the model was never called). The streaming path's
own retries of an empty timed-out or failed stream are recorded like any other
same-model retry (`TIMEOUT` for a timeout, the provider error's class
otherwise).

### Origins

Classified per turn from what the turn stored — the conversation's user id and
the step's `context:*` entries; the first match wins:

| Origin | Recognised by |
| --- | --- |
| `agent-test` | an [agent test](agent-testing.md) conversation (`agent-test:` user) |
| `group-member` | the reserved `groupConversationId` context of a [group](group-conversations.md) member turn |
| `delegated` | the reserved `delegationDepth` context (`converse_with_agent`, sub-agents) |
| `schedule` | the `schedule` / `scheduleMaxCostPerFire` context, or the scheduler's own user |
| `a2a` | the `a2aTaskId` context, or an `a2a:` user |
| `email` | an `email:` / `email-anon:` user, or the `emailIntegration` context |
| `slack` | a `slack:` user, or the `slack` context |
| `webhook` | a `webhook:` user, or the `webhookIntegration` context |
| `v1` | the [OpenAI-compatible API](open-webui-integration.md) (`channel=openai`, or its `channelIntent`) |
| `mcp` | the `mcp` context |
| `api` | anything else — the Chat UI, the Manager, plain REST |

The Chat UI and the Manager use the same REST API and are both `api`; the
stateless and stateful `/v1` modes are both `v1`. A conversation's start turn can
carry less than its later turns (a `new`-strategy schedule passes no context
when it starts the conversation), so a conversation's origin is its first row
that is not `api`. Three origins cannot be claimed by a client: `agent-test`
rests on a user id no request may use, `group-member` and `delegated` on context
keys the engine strips from client input. A client imitating the others can only
mislabel its own conversation — origin is a reporting dimension, never a
permission.

## Where it is stored

| Datastore | Collection / table |
| --- | --- |
| MongoDB | `healthturns`, `_id` `<conversationId>\|<executionId>`, indexed by `(agentId, day)`, `(conversationId, stepIndex)`, `userId`, `day`, `discardedAt` |
| PostgreSQL | `health_turns`, primary key `(conversation_id, execution_id)`, indexed by `(agent_id, day)`, `user_id`, `day`, `discarded_at_ms` (markers); created on first use |

## Configuration

| Property | Default | Description |
| --- | --- | --- |
| `eddi.health.enabled` | `true` | Off: no turn is recorded. Retention still runs |
| `eddi.health.queue-capacity` | `10000` | Rows the health worker may have waiting; past it a row is dropped (`eddi_health_dropped_total`), never waited for |
| `eddi.health.facts-retention` | `90d` | How long turn rows are kept, in whole UTC days |

## Retention

A daily sweep (`health-turn-retention`, on every node — it is one idempotent
`delete where day < cutoff`, so no coordination is needed) deletes the rows of
days older than `eddi.health.facts-retention`. It runs even with
`eddi.health.enabled=false`. With the defaults, turn rows are kept for less time
than conversations (90 against 365 days), so a conversation removed by its own
retention has no rows left. That holds only while `eddi.health.facts-retention`
is the shorter of the two: the conversation-retention sweep does not delete
health rows — only an explicit conversation delete does — so with a longer
setting a conversation's rows outlive it until their own retention takes them.
A user's [erasure](#gdpr) removes them whatever either setting says. Daily
aggregates (H2b) will be kept longer and hold no personal data.

## GDPR

Turn rows are personal data about a user's use of an agent:

- **Erasure** (`DELETE /admin/gdpr/{userId}`) deletes every row of the user — the
  participant `agentHealthTurns`. Group member conversations are owned by the
  discussion's user and are covered by the same erasure.
- **Export** includes the user's rows (newest first, at most 10,000) under
  `agentHealthTurns.turns`.
- **Deleting a conversation** deletes its rows (best effort — a failure is
  logged and the rows expire with the retention).

See [GDPR & CCPA compliance](gdpr-compliance.md).

## What is not collected

- No input, output, prompt or property **text** — only ids, counts, classes and
  action names.
- No per-agent Prometheus series: the counters above carry no agent id.
- Nothing is collected *inside* the turn: a slow or failing health store never
  slows or fails a conversation.

## Metrics

```text
eddi_health_recorded_total   # Turn rows written
eddi_health_dropped_total    # Turn records lost; tag: reason (queue_full|store_error|shutdown)
eddi_health_queue_depth      # Gauge: records waiting for the health worker
```

`eddi_health_dropped_total` should stay at zero. See [Metrics](metrics.md#agent-health-metrics).
