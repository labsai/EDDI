# Agent Health

Agent Health answers the questions usage counts cannot: *is this agent doing its
job, is it getting better or worse, and why?* It is built in phases. This page
describes what ships today — the **turn facts** every view is computed from,
the **daily rollup**, **change markers**, **conversation outcomes** per session,
a **status with reasons** judged by each agent's **health profile**,
**`HEALTH` alerts**, **owner report links**, and the **REST and MCP views** over
them — and marks what is still coming.

| Phase | What it adds | Status |
| --- | --- | --- |
| H2a — Turn facts | One small row per turn execution, recorded after the fact | **Available** |
| H2b — Series & compare | Daily rollups, trends, version-to-version compare, change markers, REST/MCP | **Available** |
| H3 — Outcomes | Sessions, engagement, resolved / escalated / abandoned, coverage, drill-down | **Available** |
| H4 — Status | Health profiles, presets, status with reasons, `HEALTH` alerts | **Available** |
| H4b — Owner report | Read-only, branded report links for people without Manager access | **Available over REST** — the Manager's owner report page and link screens are not built yet |
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
  (H2b marks that day dirty). `updatedAt` is when the row was last **captured** (on the turn's thread, before it is queued), not when it reached the store;
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
| `lastOutputWasQuestion` | the output text, trimmed, ends with `?` (or `？`) | A cheap hint for outcome inference (`ABANDONED`). It misses questions without a question mark and cannot tell a rhetorical one |
| `actions` | the turn's actions, at most 32 — of more, the last 32, where the ending and mapped actions are | Outcomes mapped from actions |
| `selfReportedOutcome` | `health:self_reported_outcome`, written by [`report_conversation_outcome`](#the-self-report-tool) | The model's own claim — weak evidence |
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
| MongoDB | `healthturns`, `_id` `<conversationId>\|<executionId>`, indexed by `(agentId, day)`, `(conversationId, stepIndex)`, `userId`, `day`, `discardedAt`, `updatedAt` |
| PostgreSQL | `health_turns`, primary key `(conversation_id, execution_id)`, indexed by `(agent_id, day)`, `user_id`, `day`, `discarded_at_ms` (markers), `updated_at_ms`; created on first use |

The H4 stores sit beside them, each created on first use on PostgreSQL:

| Store | MongoDB | PostgreSQL | Holds |
| --- | --- | --- | --- |
| Health profiles | `healthprofiles`, `_id` the agent id | `health_profiles`, primary key `agent_id` | The profile as written (JSON text) and its last author |
| `HEALTH` alert states | `healthalertstate`, `_id` `<ruleId>\|<agentId>` | `health_alert_state`, primary key `(rule_id, agent_id)` | What each rule last saw of each agent: the status and the conditions that held |
| Owner report links | `healthreportlinks`, unique index on `tokenHash` | `health_report_links`, unique index on `token_hash` | Link id, agent, SHA-256 of the token, sections, period, creator, expiry — never the token |

## Daily rollup

The views read **daily rows** — one per (agent, version, origin, UTC day), the
turn rows of that key added up (`HealthTurnAggregator`) — rather than every turn.
They are recomputed, never incremented:

1. Every write of a turn row notes the **day the row is stored on** as dirty —
   for a resumed or rerun turn that is the day it was first recorded, not the
   day of the resume, and for an undo the days of the discarded executions. The
   notes are written to the `healthdirty` queue every five seconds, each
   (agent, day) once, so a busy agent does not turn its queue entry into a hot
   document. A node that stops flushes its notes after its health worker
   drained; a node that is killed loses at most a few seconds of notes, which
   the self-heal pass (step 2) recovers. An erasure, a conversation delete and
   the orphan sweep mark the days of the rows they delete, so the daily rows
   lose those counts at the next pass. A conversation's rows are deleted
   together with their days (PostgreSQL `DELETE … RETURNING`; on MongoDB the
   days are read just before the delete). When those days cannot be read, the
   delete still goes ahead and **every retained day** of the conversation's
   agent is marked instead, with a WARN — the agent comes from the
   conversation (purge), the row (the recorder's clean-up) or the sweep's
   candidate read. A purge that can read neither the agent nor the days
   (MongoDB only, since PostgreSQL returns the days with the delete) **keeps** the
   rows, with a WARN. The next orphan sweep then deletes them and marks their
   agent's days, because its candidate read names the agent. Kept rows
   captured within `eddi.health.orphan-sweep-window` are removed by that sweep;
   older ones expire with `eddi.health.facts-retention`. An erasure deletes the
   user's rows the same way; its fallback is the agents of the user's
   conversations, noted at the erasure's first step while those conversations
   still exist (see [GDPR](#gdpr)). Daily rows older than
   `eddi.health.facts-retention` cannot be recomputed at all (see below).
   These marks are written to the store at once, not on the five-second flush,
   because the self-heal cannot find a deleted row; a failed write is retried
   once. **The one remaining limit:** when both attempts fail (a WARN names the
   agent), the mark waits in memory for the next flush. If the node stops
   before a flush succeeds, the mark is lost, and the daily rows of those days
   keep the deleted rows' counts until the day is re-rolled for another reason
   (such as a later write of the agent on that day) or reaches
   `eddi.health.daily-retention`.
2. The `health-rollup` system schedule (a heartbeat, every
   `eddi.health.rollup-interval`, default 5 minutes; created at startup, run by
   one node at a time under the schedule store's claim) first **self-heals** —
   it re-marks dirty the (agent, day) pairs of turn rows written, or discarded
   by an undo, since its last pass. A discard leaves the row's write time
   alone, so the time a row was first discarded is stored with it for this
   (a watermark kept in `healthrollupstate` / `health_rollup_state`, one
   minute behind the clock, at most 10,000 pairs a pass; a window holding more
   is halved until one fits, and the watermark moves only past what was
   marked. A one-second window that still holds more than 10,000 pairs is not
   cut: all of its pairs are streamed and marked in chunks of 10,000, with a
   WARN, so the watermark never passes a pair it did not mark — even when many
   rows share one timestamp). A row that reaches the store more than 30 seconds
   after its capture time — which this scan may already be past, since it goes
   by capture time — is **written late**: the same write stores the time of the
   write with the row (`writtenAt` on MongoDB, `written_at_ms` on PostgreSQL),
   and the self-heal matches that time too; an undo in such a write is timed by
   the write as well. So a late row is found again like any other, by a time
   the watermark cannot have passed, and needs no dirty mark to survive: its
   days are noted for the five-second flush like an on-time row's, and a rollup
   that ran during the write, a node stopping right after it, or a dirty queue
   that refuses every mark only delay the recompute until the next pass after
   the write's time is a minute old. Only late rows carry the stamp — a later
   on-time write of the row removes it — so its index (sparse on MongoDB,
   partial on PostgreSQL) holds the rare late rows only, and an on-time write
   costs nothing extra. **What this rests on:** a row is found only if its
   write commits within the one-minute grace of the time the self-heal goes by
   — the capture for an on-time row, the start of the write for a late one —
   less the clock skew between nodes. A write that takes longer than that, on
   a node that then stops before its flush, can still be missed, for late and
   on-time rows alike. Then it drains the
   queue: for
   each entry it streams that agent-day's turn rows, **replaces** the day's
   daily rows with the result, and clears the entry **only if it was not marked
   again meanwhile** (a token per mark). A turn written while its day was being
   recomputed therefore keeps the day dirty for the next pass. A pass stops after
   90 seconds and leaves the rest for the next. A day whose rollup fails moves
   to the back of the queue, so it cannot block the days behind it. It is never
   dropped, because the day may have no later write to mark it again: it is
   retried every pass, with a WARN once it has failed five times.
3. A dirty day older than the turn-row retention (`eddi.health.facts-retention`)
   is dropped from the queue without recomputing: its turns are gone, and the
   daily rows it already has are kept.

The rollup is idempotent: recomputing a day from the same turn rows writes the
same rows, so a retried pass or a duplicated schedule does no harm. The
schedule is server-managed like `alerts-maintenance`: it cannot be created,
changed or deleted over REST or MCP (`SYSTEM_SCHEDULE`), only fired, and the
name `health-rollup` (and the `healthType` metadata key) is reserved — a user
schedule cannot take it. A changed `eddi.health.rollup-interval` updates the
schedule at the next start.

`dataThrough` in a report is the day before the earliest day of the agent that
has waited for the rollup longer than one rollup interval, else today: a day
marked a moment ago is waiting for the next pass, not behind. **Today's numbers
may lag by up to one rollup interval.** Daily rows are kept
`eddi.health.daily-retention` (730 days) and hold no personal data (no user or
conversation id); an erasure removes a user's counts from them at the next pass,
for days within `eddi.health.facts-retention` (90 days). **Older daily rows
cannot be recomputed**, because their turn rows are already gone. An erased
user's, or a deleted conversation's, contribution to those counts stays until
`eddi.health.daily-retention` removes the rows.

The views cache what they read from the stores for `eddi.health.cache-ttl`
(60 s; 0 switches it off), keyed by the full query and per agent, never per
caller — who may see what is decided before the cache is asked. Loads are
single-flight per key: when an entry is missing or expired, one caller runs the
query and every concurrent caller of the same query waits for that result, so a
Manager poll from several tabs or users cannot stampede the store. A failed or
empty (`null`) load is not cached — the callers waiting on it see that one
outcome, and the next caller retries. The portfolio's batch reads over many
agents are shared the same way between identical requests. The portfolio
counts every listed agent's traffic, now and in the baseline, in one pass over
the turn rows (indexed by `(agentId, day)`).
A change that alters how stored turns are counted (later phases) marks a whole
range dirty through `HealthDirtyDays.markRange`. An outcome mapping is not such
a change: it is versioned, and sessions are judged by the version they ended
on, so a new mapping applies to sessions that end on the new version.

| Datastore | Daily rows | Dirty days | Change markers |
| --- | --- | --- | --- |
| MongoDB | `healthdaily` (`_id` `agent\|version\|origin\|day`, counters in a sub-document) | `healthdirty` | `healthmarkers` |
| PostgreSQL | `health_daily` (primary key `(agent_id, agent_version, origin, day)`, counters as `JSONB`) | `health_dirty` | `health_markers` |

## Conversation outcomes

Effectiveness — *did people get what they came for?* — is judged per
**session**, not per turn and not per conversation, by the **outcome
evaluation**: the first step of every `health-rollup` pass, under the same
schedule claim (one node at a time, no job of its own). It reads only turn rows
and ratings, never conversation memory, and writes one row per session.

### Sessions

A conversation splits into sessions wherever a turn starts more than its
origin's **inactivity window** after the previous turn ended. A turn's times come
from its row: it started `durationMs` before its first capture (`timestamp`,
taken when the turn ends) and was last active at the later of that and its last
capture (`updatedAt`). A HITL resume keeps the first `timestamp`, so it counts
from the resume: the session stays open a full window after it, and a session
that had already closed while it waited for the approver reopens to `OPEN`.

| Origin | Window |
| --- | --- |
| `email` | 72 h (`eddi.health.email-session-window`) |
| every other origin | 24 h (`eddi.health.session-window`) |

An agent can override the window per origin in
[`healthConfig.inactivityWindowHours`](agent-config-authoring.md#healthconfig--how-agent-health-judges-conversations)
(1–720 hours); each session is split and closed by the window of the version it
ran on. A conversation's origin is its first turn row that is not `api` (see
[origins](#origins)); sessions judged before a later non-`api` row appeared keep
the origin they were judged with. `sessionNo` is the **step index the session
starts at** — `0` for the first session, the index of its first turn for a
later one. A session whose first turns the retention removed keeps its stored
key; one whose first turn an undo removed is re-keyed by its new first turn. Of
two rows of one step — an unsettled, insert-only capture (a cancelled or
deadline-stopped turn) next to the settled one — the settled row is the turn.

A session is **open** while its window lasts: a turn inside it continues the
session. Once the window has passed it is **closed**, and a turn that arrives
later starts a *new* session — the closed one keeps its outcome. A closed
session is judged again only when its own rows change (a HITL resume of its
last turn, an undo) or a rating arrives.

A session is **engaged** when a person's turn comes after the agent's first
output in the conversation (the [H0 rule](#what-a-turn-row-holds), with "first
output" taken over the whole conversation, so a person who comes back the next
day and asks one question is engaged). Greeting-only and empty sessions are
counted, never judged.

Only **interactive** origins are judged: `api`, `v1`, `mcp`, `email`, `slack`.
Sessions of `schedule`, `webhook`, `a2a`, `group-member`, `delegated` and
`agent-test` are counted (adoption) with no outcome.

### Outcomes and their evidence

Every session row records its outcome **and the evidence for it**; the highest
signal wins:

| # | Signal | Outcome | Evidence | When |
| --- | --- | --- | --- | --- |
| 1 | The last action of the session mapped in [`healthConfig.outcomes`](agent-config-authoring.md#healthconfig--how-agent-health-judges-conversations) | as mapped (`RESOLVED`, `ESCALATED`, `DEFLECTED`, `FAILED`, `ABANDONED`) | `agent_action` | at once, `provisional` until the window passes |
| 2 | The session's last [`report_conversation_outcome`](#the-self-report-tool) claim | as claimed | `self_reported` | at once, `provisional` |
| 3 | `CONVERSATION_END` or `STOP_CONVERSATION` on a last turn that did not fail | `ASSUMED_RESOLVED` | `agent_closed` | at once, final — the conversation is over |
| 4 | The last turn failed and nothing followed — a cancelled turn (`TURN_INTERRUPTED`) is not a failure | `FAILED` | `structural` | after the window |
| 5 | The last turn is still waiting on a HITL approver, or was cancelled | `ENDED_QUIETLY` | `inferred` | after the window |
| 6 | The last output asked a question | `ABANDONED` | `inferred` | after the window |
| 7 | A negative rating (a conversation rating given during the session, or a thumbs-down on one of its replies; normalised score below 0.5) | `ENDED_QUIETLY` | `inferred` | after the window |
| 8 | Quiet after a successful last turn | `ASSUMED_RESOLVED` | `silence` (default), or `ENDED_QUIETLY` with `assumeResolvedOnSilence: false` | after the window |

Without signal 1–3 an open session is `OPEN`. A mapped action on a turn that
also ended the conversation is final at once. The conversation's `endReason`
(`idle`, `agent-version-retired`) is never evidence — it is not even read.
External outcome events (`external_event`, `conversation_id_event`, H5) and a
sampled judge (`judged`, H6) will rank above signal 1; they are reserved values
today.

**Both assumptions are on by default and kept apart** (decision 3): agent closed
and silence each count as `ASSUMED_RESOLVED`, but under their own evidence, and
the resolution rate always shows how much of it rests on each.

### The self-report tool

`report_conversation_outcome` lets the agent's model say how the session went —
`RESOLVED`, `ESCALATED`, `DEFLECTED` or `FAILED`. It is **opt-in twice**: the
agent version sets `healthConfig.selfReportTool: true`, and the LLM task has
`enableBuiltInTools` (naming `report_conversation_outcome` when it uses a
`builtInToolsWhitelist`) — the gate of the user-memory tool. The claim is stored
on the step (`health:self_reported_outcome`) and reaches the turn row; the
session's **last** claim counts (a second call in the same turn replaces the
first, so a model calling it in a loop changes one value). It is weak evidence:
below a mapped action, never counted as *confirmed*, always shown as
`selfReported`. The tool takes **no reason**: a reason would be text the model
derived from the conversation, and health rows hold no conversation content.

### Coverage

`outcomeCoverage` is the share of closed, engaged, judged sessions whose outcome
is not `ENDED_QUIETLY`. While it is below `eddi.health.min-outcome-coverage`
(50 %), the rates — resolution, escalation, abandonment, deflection — are
`value: null` with a hint naming both numbers: a rate over the few sessions that
have an outcome would describe them, not the agent. Coverage itself is always
shown and never called significant. With the defaults (silence assumed) it is
usually high; the four-part resolution split shows what that rests on. To raise
real coverage, map the actions that end sessions —
[outcome suggestions](#rest-api) list them.

### Where outcomes are stored

| Datastore | Collection / table |
| --- | --- |
| MongoDB | `healthoutcomes`, `_id` `<conversationId>\|<sessionNo>`, indexed by `(agentId, day)`, `(conversationId, sessionNo)`, `userId`, `day`, `dueAt`, `evaluatedAt` |
| PostgreSQL | `health_outcomes`, primary key `(conversation_id, session_no)`, the same indexes; created on first use |

A row holds the agent and its version at the session's end, the user id (GDPR
only), origin, engaged, start and end, turns, spend, failed turns, outcome,
evidence, `provisional`, the end day (`day`, what a period counts by), when it
must be judged again (`dueAt`), and the action names of the session's last turn
(for mapping suggestions) — no text.

### The evaluation

Per pass (at most 45 s of it, then the daily-row drain runs):

1. **Due sessions first**, within half of that time, so a backfill never starves
   them — open sessions whose window has closed, and sessions a rating made due:
   a saved reply rating marks the session of its step, a saved conversation
   rating the session it was given in (the last one started before it), a
   withdrawn rating its session (a withdrawn or changed conversation rating
   every session of the conversation — a changed one counts at its new time, and
   the session it was in before is not known). The rating request never waits
   for this: the mark is handed to a worker thread. A mark that arrives while its
   session is being evaluated survives that evaluation's write, even when the
   session was already overdue: a mark always sets the session due at its own
   time. A session the evaluation creates did not exist to take a mark, so when
   such a new row is written final from ratings it read, the ratings are read
   once more after the write, and a change makes the row due. A mark that cannot
   be stored when it arrives — the worker's queue (1,000) is full, or the store
   fails — is deferred on that node (up to 10,000 conversations) and stored,
   marking every session of the conversation, after the worker's next mark or
   at the start of the node's next pass, before the due sessions are read; it
   stays deferred until that write succeeds (see [Metrics](#metrics)). Each due session is
   judged over its own steps only, up to the next stored session. A due session
   whose evaluation fails is made due again later — 15 minutes at the least,
   growing with the time since it was last judged, up to 6 hours — so sessions
   that keep failing cannot fill every pass's page of 1,000 and starve the rest.
2. **The feed** — conversations with turn rows captured up to a minute ago,
   paged by each conversation's newest capture (then its id), oldest first.
   Where it stands is a **cursor** of two such (capture, conversation) pairs
   (`healthrollupstate` document `outcomes` / `health_rollup_state` rows
   `outcomes` and `outcomes.replay`, written together), so a pass can stop
   anywhere — even inside a run of more than a page, or more than a pass, of
   conversations sharing one capture millisecond — and the next resumes right
   after the last conversation read. Each pass has two parts:
   - the **replay** re-reads what the feed already read, from
     **`eddi.health.outcome-feed-overlap`** (15 min) behind its watermark. Rows
     are ordered by *capture* time, set on the turn's node before the row waits
     on that node's health queue, so a row can reach the store after the
     watermark passed it; the replay catches it — the price is re-judging those
     conversations (the evaluation is idempotent). It gets at most half of the
     pass's time and conversations; what it does not reach it resumes next pass
     from its own position, instead of skipping it;
   - the **forward feed** then reads on from the watermark. While a replay is
     pending, the stored watermark stays within one overlap of it, so the replay
     never trails it by more than that; the replay's own position only moves
     on, so the feed always does too. The limit of this: while a replay is
     pending across passes (more conversations in the overlap than half a
     pass takes), a row that lands *behind* the replay's position is not
     re-read by that sweep, and the next sweep starts at its own watermark
     minus the overlap. Covering it would mean restarting every sweep where
     the last one began, which under the load that makes a sweep pending never
     moves on. Such a row is still judged when its session's window closes
     (the due evaluation reads the session's steps again) or with the
     conversation's next turn; only a row landing in a session already judged
     final, while a sweep is pending, waits for that.

   A conversation whose evaluation fails, in either part — any store it needs
   cannot be read: turn rows, outcomes, the agent version's `healthConfig`, the
   ratings — sets the replay back to its capture time, so every following pass
   retries it first, however far the forward feed has got, for at most an hour
   from its first failure, however old the turn (then it is given up with a
   WARN; its next change or due time judges it). The node that saw the failure
   also retries it directly at the start of each replay, so with several
   failures a later one is retried every pass too, not only once the replay —
   restarted at the earliest — gets that far. A conversation given up starts a
   new retry window when a later capture of it fails; a re-read of the same
   capture does not. Nothing is written for it in
   the meantime: a session is never judged under default settings, or as
   silence, because its configuration or ratings were unavailable. The first pass starts at the turn-row retention, so turns
   recorded before outcomes existed are judged too, up to 5,000 conversations
   per pass; while it catches up, every outcome-based measure (effectiveness,
   sessions, engaged sessions, turns and cost per session, rating response, cost
   per resolved session) carries the hint "Outcomes are still being evaluated
   (through <day>)", and the portfolio's `sessionsDelta` is `null`.
   `eddi_health_outcomes_lag_seconds` shows how far behind it is.

A conversation is read as a range of steps, oldest first, from its latest stored
session on, at most 10,000 turn rows: when a range is longer, its last session —
the one the rows read cut — is left as stored. Sessions are keyed by the step they
genuinely start at. Sessions no longer found in the range read (an undo removed a
session's first turn) are deleted, never one outside it; when every turn of the
range was undone, the previous session is judged again (an undo back into it).
Idempotent: judging the same rows again writes the same rows.

## Change markers

Charts carry markers of what changed, because a deployment record has no
timestamps and is overwritten. An append-only store (`healthmarkers`) is written
at the moment of the change; nothing in it ever fails the change itself:

| Kind | Written when | Concerns |
| --- | --- | --- |
| `DEPLOYMENT` | a deploy reached `READY` and actually deployed (re-deploying a version that was already up records nothing) — "v8 deployed (production)" | the agent |
| `GLOBAL_VARIABLE_CHANGE` | a global variable was written or deleted — "Global variables changed" | the agents of the variable's tenant: `default` concerns every agent, a space's tenant the agents of that space |
| `SNIPPET_CHANGE` | a prompt snippet was created, updated or deleted — "Prompt snippets changed" | every agent (snippets are not tenant-scoped) |
| `VERSION_FIRST_TURN` | derived, not stored: the first day a version has a daily row of a real origin — `agent-test` only when asked for, so a golden-suite run before going live does not count ("First turn on v8", at 00:00 UTC of that day). The version compare's windows use the same day | the agent |

The labels of global-variable and snippet markers do not name the variable or
snippet: those concern many agents, and a viewer of one agent's health has no
business reading the deployment's configuration keys. They are **coalesced**:
one marker per kind, tenant and UTC hour, so a script saving a thousand
variables leaves one marker, not a thousand. A series returns at most 200
markers, the newest: up to 150 of the agent's own and 50 deployment-wide ones,
read separately so neither can crowd the other out. Markers are kept as long as
the daily rows.

## REST API

The contract is [`planning/agent-health-api.md`](../planning/agent-health-api.md).
Every timestamp is ISO-8601 UTC and every day `YYYY-MM-DD` (UTC).

| Method | Path | Returns |
| --- | --- | --- |
| `GET` | `/agents/{agentId}/health` | The report: every section with its measures, the baseline, the golden-suite check |
| `GET` | `/agents/{agentId}/health/series` | One point per day per measure (`measure=` repeatable, 1–6), `groupBy=none\|version\|origin`, and the change markers of the period |
| `GET` | `/agents/{agentId}/health/compare` | `from=<version>&to=<version>[&days=14]`: both sides' sections and per-measure `deltas` |
| `GET` | `/health/agents` | The portfolio: every agent the caller may view (at most 5,000; `agentsTruncated: true` when there are more), `sort=attention\|name\|traffic`, `limit` (default 100, ≤ 500), `cursor` (`nextCursor`, `null` on the last page), plus `platformIncidents` |
| `GET` | `/agents/{agentId}/health/sessions` | The drill-down: one row per session — conversation id, `sessionNo`, start and end, version, origin, engaged, outcome, evidence, `provisional`, turns, cost, failed turns — newest first. Filters `outcome=` and `evidence=` (repeatable), `origin`, `version`, `period`/`from`/`to`; `limit` 1–100 (default 50), `cursor` |
| `GET` | `/agents/{agentId}/health/outcome-suggestions` | The actions that most often end engaged, closed sessions and are not mapped in the current version's `healthConfig.outcomes`, with `sessionsEndingWith`, a `suggested` outcome from the action's name (`*confirm*`, `*done*`, `*complete*`, `*booked*` → `RESOLVED`; `*handoff*`, `*human*`, `*escalat*` → `ESCALATED`; `*out_of_scope*`, `*not_supported*` → `DEFLECTED`; else none) and a `reason`. At most 20; reserved actions are left out. Never applies anything |
| `GET` | `/agents/{agentId}/health/profile` | The effective [health profile](#health-profiles-and-presets), `stored: false` when it is only the preset's defaults; `defaulted` names the fields that come from the preset, not the stored profile |
| `PUT` | `/agents/{agentId}/health/profile` | Stores a profile (EDIT); `200` with the effective profile, `400 {"violations": [{"path", "message"}]}` |
| `DELETE` | `/agents/{agentId}/health/profile` | Back to the preset (EDIT); `204` |
| `GET` | `/health/presets` | The four presets with their critical measures and thresholds |
| `POST` | `/agents/{agentId}/health/reports` | Creates an [owner report link](#owner-report-links) (EDIT); `201` with its token, shown once |
| `GET` | `/agents/{agentId}/health/reports` | The live links (EDIT) — never their tokens |
| `DELETE` | `/agents/{agentId}/health/reports/{id}` | Revokes a link (EDIT); `204`, `404` for an unknown one |
| `DELETE` | `/agents/{agentId}/health/reports` | Revokes every link of the agent (EDIT); `200 {"revoked": n}` |
| `GET` | `/health/reports/{token}` | **Unauthenticated**: the report a link shows |

**Query parameters.** `period=7d|30d|90d` (default `30d`; the portfolio `7d`)
ending today, or `from`/`to` (both, at most 90 days, not after today);
`version=<n>`; `origin=<id>` repeatable. Without `origin` the **preset's**
default applies: an agent with interactive traffic (or none) is `support` and
counts the interactive origins (`api`, `v1`, `mcp`, `email`, `slack`); an agent
driven only by schedules, webhooks, A2A, groups or delegation is `task` and
counts every origin but `agent-test`. `agent-test` is counted only when named.
A bad value is a `400` whose message names the parameter. A day without data is a
series point with `value: null, n: 0`. The baseline is the previous period of
equal length.

**Compare.** The newer of the two versions is measured since its first turn
(at most 90 days), the older one over its last `days` days before the newer took
over. A version without turns gets empty sections, not an error. Each side is
computed on its own; `deltas` lists every measure with a value on both sides,
with `significant` and `better` (null for a measure with no direction).

**Portfolio.** `sessions` is null (with a `hint`) when the period reaches past
the turn-row retention, and `sessionsDelta` when its baseline does — so the
90-day portfolio has sessions but no session change; `sort=traffic` then falls
back to the daily rows' turns. Every row has its `status` and `topReason` (the
first [reason](#status-and-reasons), without a marker), computed by the same rules
as the agent's report over the period's reliability, effectiveness and efficiency
measures; `status=` (repeatable) filters by it. `sort=attention` orders by the
status's severity (`NEEDS_ATTENTION`, `WATCH`, `HEALTHY`, then `NOT_ENOUGH_DATA`),
then by the largest **significant** change for the worse among the counted
measures (absolute for rates, relative for the rest), then traffic, then name.
The key measures are `agentFailureRate`, `apdex` and `costPerSession`. A **platform incident**
is a run of days on which one platform failure class hit at least 3 of the
listed agents at once (an agent counts when it had ≥ 10 turns that day, ≥ 3
failed attempts of the class and ≥ 5 % of its turns touched by the platform);
daily granularity, because that is what the rollup keeps.

### Who may read what

| Part | Rule |
| --- | --- |
| Every endpoint | Roles `eddi-admin`, `eddi-editor`, `eddi-viewer`, and **`VIEW` on the agent** — the rule of `GET /agents/{agentId}/usage`. `403` without it, `404` for an unknown agent |
| Quality section (ratings) | The [ratings rule](conversation-ratings.md#analyzing-and-triaging-the-people-running-the-agent) exactly: `eddi-admin` or `eddi-editor` (never `eddi-viewer`), and `EDIT` on the agent unless the caller sees every agent. Anyone else gets `{"restricted": true, "measures": []}`, and rating series points are `null` |
| Portfolio | Only agents the caller may `VIEW`; platform incidents are computed over those agents only |
| Session drill-down | The [conversation-review rule](workspaces.md): the caller **maintains** the agent (`EDIT`), else `403 {"reason": "FORBIDDEN"}`, and conversation review is enabled on the version asked for — without `version`, on the agent's current version — else `403 {"reason": "REVIEW_DISABLED"}`. Without `version` the page lists only sessions of versions that have review enabled (of the 100 newest). The version checked is the one of the session's **last** turn: a session that moved from a version without review to one with it is listed. The rows carry ids, counts and outcomes, no content; reading the conversation itself goes through the conversation endpoints, which apply the review rule to the conversation's own version |
| Outcome suggestions | `VIEW`, like the report |
| Health profile, presets | Read: `VIEW`, like the report. `PUT` and `DELETE`: roles `eddi-admin` or `eddi-editor` and `EDIT` on the agent |
| Owner report links | Create, list and revoke: `eddi-admin` or `eddi-editor` and `EDIT` on the agent. A link with the `quality` section only when the creator may read the agent's ratings (`403` otherwise). The public `GET /health/reports/{token}` needs no account — see [the security notes](#owner-report-links) |

The **status** never counts the quality section, so it is the same for a viewer
and an admin, and a viewer cannot infer a rating from it.

No view but the drill-down carries a conversation id, and none carries
conversation content or a user id.

## Measures

`n` is the sample a value rests on; below a measure's minimum sample the band is
`GREY` with a hint ("Fewer than 30 turns in this period"), never green or red on
a handful of turns. Bands, `critical` and `thresholds` come from the agent's
[health profile](#health-profiles-and-presets) — its preset unless a profile
says otherwise; the defaults below are the `support` preset's.

| Section | Measure | Definition | Kind, default band |
| --- | --- | --- | --- |
| Reliability | `agentFailureRate` | Failed turns the agent is accountable for ÷ turns (see attribution below) | Scored, **critical**: green ≤ 1 %, red ≥ 5 %, min 30 turns |
| | `platformFailureRate` | Turns touched by a provider or platform failure — a platform-caused failure, or any failed attempt of class `TRANSIENT`, `RATE_LIMITED` or `TIMEOUT`, **recovered or not** — ÷ turns; `breakdown` lists failed attempts per class | Informational |
| | `apdex` | (satisfied + tolerating ÷ 2) ÷ timed turns, with satisfied ≤ T and tolerating ≤ 4T (`eddi.health.apdex-t`, 4 s). The label names T ("Response time (Apdex, T = 4 s)"). A period whose days were rolled up with different T values is `null` with a hint rather than a mix. A paused turn is not a latency sample | Scored: green ≥ 0.85, red ≤ 0.70 |
| | `turnDurationP95` | The 95th percentile of turn duration, **approximate**: interpolated inside fixed buckets (100 ms … 5 min) | Informational |
| | `fallbackRate` | Turns that served the configured fallback ÷ turns | Scored: green ≤ 2 %, red ≥ 10 % |
| Effectiveness | `resolutionRate` | `RESOLVED` + `ASSUMED_RESOLVED` ÷ closed, engaged sessions of judged origins (`n`). `breakdown` always has four parts, each a share of `n` that add up to the rate: `confirmed` (mapped agent actions; external events in H5), `selfReported`, `agentClosed`, `silence`. A rate resting mostly on `silence` is an assumption, not a result | Scored, **critical**: green ≥ 70 %, red ≤ 40 %, min 30 sessions; `null` below the coverage minimum |
| | `escalationRate`, `abandonmentRate`, `deflectionRate` | `ESCALATED`, `ABANDONED`, `DEFLECTED` ÷ the same `n` | Scored (deflection informational): escalation green ≤ 15 %, red ≥ 30 %; abandonment green ≤ 20 %, red ≥ 35 % (support preset); `null` below the coverage minimum |
| | `outcomeCoverage` | Closed sessions with an outcome other than `ENDED_QUIETLY` ÷ `n`; also the section's `coverage` | Scored: green ≥ 80 %, red ≤ 50 %; never significant |
| Quality | `ratingScore` | The Wilson lower bound of the average normalised conversation rating — what the ratings support with 95 % confidence, so three five-star ratings do not outrank three hundred four-star ones | Scored: green ≥ 0.7, red ≤ 0.5, min 10 ratings |
| | `ratingResponseRate` | Conversation ratings ÷ sessions | Informational |
| | `replyThumbsUp` | Thumbs-up reply ratings ÷ reply ratings | Scored: green ≥ 80 %, red ≤ 60 %, min 10 |
| | `guardrailTripRate` | Guardrail trips per turn (can exceed 1) | Informational |
| Efficiency | `costPerSession` | Spend of every row (undone executions included, so it matches the conversations' cost) ÷ sessions | Informational; scored with `budgets.costPerSessionUsd` |
| | `costPerResolvedSession` | The period's spend (the selected origins) ÷ resolved sessions — what one resolution costs, failures included | Informational; scored with `budgets.costPerResolvedUsd` |
| | `tokensPerTurn` | Tokens ÷ turns | Informational; scored with `budgets.tokensPerTurn` |
| Adoption | `sessions`, `engagedSessions` | Sessions that **ended** in the period (open ones included), from the outcome rows; engaged as [defined above](#sessions) | Informational |
| | `distinctUsers` | Distinct user ids of the period's turn rows | Informational |
| | `returningUserShare` | Users of the period who also had a turn in the 30 days before it (clipped to the turn-row retention) ÷ distinct users. Never called significant: the look-back of a baseline near the retention is shorter. No daily series | Informational |
| | `turnsPerSession` | The sessions' turns ÷ sessions | Informational |
| Business | — | Empty until outcome events (H5) | — |

Ratings count production ratings only, unless `agent-test` is among the origins;
ratings have no origin, so the origin filter does not narrow them further.

**Agent or platform.** A failed turn is the **platform's** when it was
interrupted (`TURN_INTERRUPTED`: a lost lease, a cancel, a stop), stopped by the
tenant budget (`TENANT_BUDGET`), or ended (`TURN_ERROR`, `TURN_DEADLINE`) with
unrecovered provider failures that are all of a platform class (`TRANSIENT`,
`RATE_LIMITED`, `TIMEOUT`). Everything else is the **agent's**: a pipeline error,
its own deadline, its cost ceiling (`COST_CEILING`), and unrecovered failures of
`QUOTA_EXHAUSTED`, `AUTH`, `BAD_REQUEST`, `CONTEXT_TOO_LONG`, `MODEL_NOT_FOUND` or
`UNKNOWN` — its key, its prompt size, its model id (`HealthAttribution`).

**Where the numbers come from.** Reliability, efficiency and guardrails come from
the daily rows, so a baseline can reach back past the turn-row retention.
Users are counted in the database from the turn rows, because distinct counts
cannot be added up across days; sessions, engagement and every effectiveness
measure from the outcome rows, by the day each session ended. Both are kept for
the turn-row retention (90 days): a period older than that has none (`hint`
says so). In a series grouped by
version or origin, a conversation active on two versions in one day counts in
both.

### Statistics

- **Intervals** — the Wilson score interval at 95 % for every rate (it stays
  inside 0–1 and behaves at small `n` and at 0 % or 100 %); for Apdex a normal
  interval, whose variance is known exactly (each turn scores 1, ½ or 0).
- **`significant`** — a two-sided test at 95 % against the baseline, and only when
  both periods reach the minimum sample: rates by the pooled **two-proportion
  z-test**; Apdex by Welch's z-test with its exact variance; counts (sessions,
  engaged sessions, users) by the conditional **Poisson test** at the two
  periods' exposure — given `n = a + b`, the current count is binomial with
  `p = e / (1 + e)`, `e` the current period's exposure over the baseline's. A
  measure with no variance to test — a percentile from buckets, a cost or token
  average, the returning-user share — is never called significant.
- **`tested`** says which kind a measure is: `true` for every measure with a test
  (the rates, Apdex, the counts), `false` for `turnDurationP95`, `costPerSession`,
  `costPerResolvedSession`, `tokensPerTurn`, `turnsPerSession`,
  `guardrailTripRate`, `returningUserShare` and `outcomeCoverage`. It is a
  property of the measure, derived from how it is computed, not of the period's
  data — so a client can tell "no significant change" from "never tested".
- **Exposure.** A period that ends today is not over: its exposure is its full
  days plus the part of today that has passed. A count measure's `baseline` is
  the previous period's count **scaled to that exposure**, so a window read at
  09:00 is compared with 37.5 % of a baseline day, not a whole one, and does not
  show a "significant" drop every morning. Rates are unaffected.
- **`delta`** is `value − baseline` in the measure's unit (percentage points for
  rates); the portfolio's `sessionsDelta` is relative to the exposure-scaled
  baseline (−0.08 = −8 %).

### Checks

`goldenSuite` is the newest finished [agent test](agent-testing.md) run of the
version deployed to production (any version when none is deployed): `PASS` or
`FAIL`, `UNKNOWN` when no run finished (an `ERROR`, aborted or cancelled run
says nothing about the agent), `stale` when older than 14 days.

## Status and reasons

Every report has a `status` — the headline, read before any number — and the
`reasons` for it. The rules (`HealthStatusRules`):

| Status | When |
| --- | --- |
| `NOT_ENOUGH_DATA` | Every counted measure is `GREY` (too little data, or no threshold) |
| `NEEDS_ATTENTION` | A **critical** measure is `RED` |
| `WATCH` | Any counted measure is `AMBER` or `RED`, or got **significantly worse** than in the baseline by at least 2 percentage points (rates, scores) or 10 % (everything else) |
| `HEALTHY` | None of the above |

**Counted** are the scored measures (a [budget](#health-profiles-and-presets)
makes a cost measure scored) that no active override ignores. The **quality**
section (ratings) is never counted: who may read ratings differs per caller, and
a status that changed with the caller's permissions would leak them. The effect
floor keeps a statistically significant but tiny change of a busy agent from
raising a flag. The status describes the selection asked for — a `version=` or
`origin=` filter judges that slice.

A **reason** is `{measureId, severity, text, markerId}`, at most five, ordered by
severity (`RED`, `AMBER`, `INFO`), critical measures first, then by the size of
the change for the worse:

- a band — "Agent-caused failures 8 % (target ≤ 1 %)";
- a change for the worse — "Resolution 41 % (−12 pp since v8 on 3 Nov)", with
  the newest deployment or version start of the period as `markerId` (without
  one, "vs. the previous period"); the portfolio's `topReason` never names a
  marker (markers are read per agent);
- `INFO` "Resolution rests mostly on silence: 64 % of resolved sessions are
  assumed because people went quiet" whenever the `silence` part is **more than
  half** of the resolved sessions — also while the rate itself is withheld, and
  whatever the status (it does not change it): an assumed rate must never read as
  a measured one;
- `INFO` "Not enough data: …" with `NOT_ENOUGH_DATA`, from the first critical
  measure's hint.

Texts are English and short, for display as they are. No weighted 0–100 index
is computed: the contract has none, and a single number across different kinds
of agents is what the plan rejected as a headline.

## Health profiles and presets

How an agent's health is judged lives in its **health profile** — an admin
resource keyed by the agent id, **not versioned** (tuning a threshold must not
mint an agent version and split its statistics) and **not part of agent export
or sync** (thresholds belong to a deployment). Agent behaviour that decides
outcomes stays in the versioned `healthConfig`.

```json
{ "preset": "support",
  "critical": ["agentFailureRate", "resolutionRate"],
  "thresholds": { "resolutionRate": { "green": 0.7, "red": 0.4 } },
  "budgets": { "costPerResolvedUsd": 0.5 },
  "slo": { "agentTurnSuccess": 0.99, "apdexTMs": 4000, "minTurnsForBurnAlert": 50 },
  "overrides": [ { "measure": "business", "state": "IGNORED", "reason": "CRM outage", "until": "2026-11-14" } ] }
```

Every field is optional and falls back to the preset:

| Field | Meaning |
| --- | --- |
| `preset` | `support`, `sales`, `assistant` or `task`. Without one, the traffic decides: `task` for an agent driven only by schedules, webhooks, A2A, groups or delegation, else `support` |
| `critical` | The measures whose `RED` band makes the status `NEEDS_ATTENTION`; the preset's when absent, none when `[]`. Scored measures only (a cost measure once it has a budget), never quality |
| `thresholds` | `{measure: {green, red}}` over the preset's; the measure decides whether higher is better. Rates and scores between 0 and 1. Cost measures take a budget instead |
| `budgets` | `costPerResolvedUsd`, `costPerSessionUsd`, `tokensPerTurn`: each makes its measure scored, green up to the budget, red from 1.5× it |
| `slo.agentTurnSuccess` | The share of turns without an agent-caused failure (default 0.99, at least 0.5, below 1). Unless `thresholds.agentFailureRate` is given, it sets that measure's bands: green up to the error budget (`1 − agentTurnSuccess`), red from five times it. Also the budget of the [burn-rate alert](#health-alerts) |
| `slo.apdexTMs` | The agent's Apdex T (100 ms – 10 min); default `eddi.health.apdex-t`. Applied by the rollup: a change marks the agent's retained days for a recount, so for a while the period may mix T values (then Apdex is `null` with a hint) |
| `slo.minTurnsForBurnAlert` | The volume floor of a burn-rate alert, per window (default 50) |
| `overrides` | Up to 50 `{measure \| section, state: "IGNORED", reason, until}`: left out of the status through `until` (UTC day, inclusive). The measure is still shown, with the override in its hint. An expired override is ignored, not refused |

**`GET`** answers the **effective** profile — the preset with the stored profile
laid over it — and `stored: false` when nothing is stored, so a client that
writes back what it read keeps the meaning. **`PUT`** replaces the stored profile
with the body as sent; a field left out falls back to the preset again.
Validation reports every problem as `{path, message}` with a dotted path into the
body — `preset`, `critical[1]`, `thresholds.resolutionRate.red`,
`budgets.costPerResolvedUsd`, `slo.agentTurnSuccess`, `overrides[0].reason` — in
a `400 {"error": "bad_request", "message": …, "violations": […]}`. A save or a
delete leaves a `PROFILE_CHANGE` [marker](#change-markers) ("Health profile
changed"); the bands and the status follow at once (profiles are cached for 60 s
per node).

**Presets** (`GET /health/presets`):

| Preset | For | Critical | Thresholds besides the shared ones |
| --- | --- | --- | --- |
| `support` | A chat agent that solves people's problems (the default for interactive traffic) | `agentFailureRate`, `resolutionRate` | resolution green ≥ 70 % / red ≤ 40 %; escalation ≤ 15 % / ≥ 30 %; abandonment ≤ 20 % / ≥ 35 % |
| `sales` | An agent that converts | `agentFailureRate`, `abandonmentRate` | resolution ≥ 40 % / ≤ 15 %; escalation ≤ 25 % / ≥ 45 %; abandonment ≤ 30 % / ≥ 50 % |
| `assistant` | Open-ended help without one goal | `agentFailureRate` | Apdex ≥ 0.8 / ≤ 0.6; resolution ≥ 60 % / ≤ 30 %; abandonment ≤ 30 % / ≥ 50 %; coverage ≥ 60 % / ≤ 30 %; escalation not banded |
| `task` | Driven by schedules, webhooks, A2A or groups (the default when there is no interactive traffic) | `agentFailureRate` (the run failure rate) | failures ≤ 2 % / ≥ 10 %; Apdex ≥ 0.7 / ≤ 0.5; fallbacks ≤ 5 % / ≥ 20 %; no effectiveness bands — set a cost budget per run |

Shared by `support`, `sales` and `assistant`: agent-caused failures green ≤ 1 %,
red ≥ 5 %; Apdex ≥ 0.85 / ≤ 0.7; fallbacks ≤ 2 % / ≥ 10 %; outcome coverage
≥ 80 % / ≤ 50 %; rating score ≥ 0.7 / ≤ 0.5; thumbs up ≥ 80 % / ≤ 60 %.

## HEALTH alerts

An [alert rule](alerts.md#sources-and-conditions) with `"source": "HEALTH"` tells
people when an agent's health changes for the worse:

```json
{ "name": "Support health", "scope": { "agentIds": ["6740…"] }, "source": "HEALTH",
  "condition": { "healthTriggers": ["STATUS_CHANGE", "BURN_RATE", "CRITICAL_TREND"], "healthStatuses": ["NEEDS_ATTENTION"] },
  "delivery": [ { "type": "SLACK", "channelId": "C0123ABC" } ] }
```

| Trigger | Fires when |
| --- | --- |
| `STATUS_CHANGE` | The agent's status over the last 7 days (the portfolio's) entered one of `healthStatuses` — `WATCH`, `NEEDS_ATTENTION`, both by default. A rule that has not seen the agent yet reports a status in its list once, so a new rule says what already needs attention |
| `BURN_RATE` | The agent-caused failure rate burns the SLO's error budget (`1 − slo.agentTurnSuccess`) at least **2×** in the long window (the last 3 UTC days, today included) **and** in the short one (today), each with at least `slo.minTurnsForBurnAlert` turns. Whole days, because the rollup keeps daily rows; the volume floor keeps an agent with three turns from paging anyone over two failures |
| `CRITICAL_TREND` | A critical measure of the profile got significantly worse over the last 7 days against the 7 before, by at least the status rules' effect floor |

Rules are evaluated **after each rollup pass**, never in a turn, by
`HealthAlertEvaluator` under the rollup's schedule claim. Each condition fires
when it **starts** — what a rule last saw of an agent is kept per rule and agent
(`healthalertstate`) — not again while it lasts; states not refreshed for 30
days (a rule deleted or narrowed) are dropped. Alerts go through the existing
engine: deduplicated per rule, agent, condition and throttle window, throttled
and digested like every other source, delivered to the inbox, Slack or a
webhook. The alert's `match` carries `healthStatus`, `previousHealthStatus`,
`measureId` and the reasons' texts; never conversation content. **Health only
alerts** — it never rolls back, pauses or changes an agent (plan decision 7).

## Owner report links

A business owner without a Manager account gets a read-only, branded link to an
agent's health.

> **Not yet available in the Manager — REST only for now.** The owner report
> page at `/manage/health-report/<token>` and the screens to create, list and
> revoke links follow in a later release. Until then a link's URL leads to a
> Manager route that does not render it, and the report itself is the JSON of
> `GET /health/reports/{token}`.

```http
POST /agents/{agentId}/health/reports
{ "expiresInDays": 30, "sections": ["reliability", "effectiveness", "efficiency", "adoption"], "period": "30d" }

201 { "id": "r-…", "url": "https://eddi.example.com/manage/health-report/<token>",
      "path": "/manage/health-report/<token>", "expiresAt": "…", "sections": […], "period": "30d" }
```

- `expiresInDays` 1–90 (default 30); `sections` any of the six (default all but
  `quality`); `period` `7d`, `30d` (default) or `90d`, ending the day the link is
  read. `quality` only when the creator may read the agent's ratings. At most 20
  live links per agent.
- The URL is `eddi.self.base-url` when set, else the request's base, plus
  `/manage/health-report/<token>` — the route the Manager will render without
  sign-in (not built yet, see above); it reads the report from
  `GET /health/reports/{token}`.
- `GET …/health/reports` lists the live links — id, expiry, sections, period,
  creator — **never** a token; `DELETE …/health/reports/{id}` revokes one and
  `DELETE …/health/reports` all of them. Expired links are deleted by the rollup
  pass a day after they expire; an agent's links are revoked when the agent is
  deleted (soft or permanent).
- **A link shows only while its creator still holds `EDIT` on the agent.** Every
  read re-checks the creator's access as it is now — ownership, grants, shares,
  a move to another space — with the team list taken when the link was created.
  A link whose creator lost `EDIT` answers 404 like a revoked one (and shows
  again if the access comes back); one whose creator was erased (GDPR) answers
  404 for good. What cannot be re-checked without the creator's own sign-in —
  their roles (administrator, the rating roles behind `quality`) and leaving a
  team — is as it was at creation: **revoke a departing person's links** (`DELETE
  …/health/reports` per agent).
- The report is the [`GET /agents/{agentId}/health`](#rest-api) shape for the
  link's period, limited to its sections — with the **status and reasons judged
  over those sections alone** (a link without `reliability` does not reveal that
  reliability is red), the golden-suite check only with `reliability` — plus
  `expiresAt` and a `brand` (`productName`, `tagline`, `poweredBy`, `logoUrl`
  `/branding/logo`, `primaryColor`) from the
  [white-label settings](white-labeling.md). No free text: no measure carries a
  `hint` (an override's reason is an admin's note), and no reason a `markerId`.
  No drill-down, no conversation ids, no user ids, no series.

**Security.** `GET /health/reports/{token}` is the only unauthenticated health
endpoint: the `health-report` permit rule covers `GET`/`HEAD` of
`/health/reports/*` and nothing else (`/health/agents`, `/health/presets` and
everything under `/agents` stay authenticated).

- The token is 32 bytes from `SecureRandom` (256 bits), base64url. Only its
  SHA-256 is stored; a request is answered by looking the hash up in a unique
  index, so no token is compared character by character and a database dump
  holds no usable link. The token is in the creation response only.
- A malformed, unknown, expired or revoked token answers the same `404 {"error":
  "not_found"}`; a malformed one is answered before any rate limit or database
  lookup.
- Rate limits per node, arranged so that tokens leading nowhere never use up a
  real link's capacity: a token not found in the last 10 minutes is charged,
  **before** its database lookup, to 10 lookups a minute per client address and
  60 a minute in all — and gets the charge back when it turns out to be a live
  link. Only a found link is charged to its own 30 reads a minute and to 300 a
  minute for all found reports. Past any of them `429` with `Retry-After: 60`.
  The client address is the one Quarkus resolves: the peer, or the forwarded
  client only with `quarkus.http.proxy.proxy-address-forwarding` and the proxy in
  `quarkus.http.proxy.trusted-proxies` — a raw `X-Forwarded-For` is never read.
  Behind an unconfigured proxy every client shares one address, which only
  tightens the unknown-token limit. A scan can therefore delay the *first* read
  of a link on a node for a minute, never a link already being read.
- Every answer is `Cache-Control: no-store`, `Referrer-Policy: no-referrer` (the
  token is in the page's URL; it replaces the deployment-wide
  `strict-origin-when-cross-origin` rather than adding a second header) and
  `X-Robots-Tag: noindex`. No cookie, no CORS of its own — `quarkus.http.cors.*`
  applies as to any endpoint.
- **The token is in the URL path**, so whatever logs paths sees it. EDDI's own
  traces do not: `HealthReportSpanScrubber` rewrites `url.path`, `http.target`,
  `http.route`, `url.full` and `http.url` of a span to `/health/reports/{token}`
  as it starts. A reverse proxy's or load balancer's access log is outside EDDI —
  exclude `/health/reports/` from it or mask the segment there.
- Creating and revoking links, and saving or deleting a profile, are logged
  (`[HEALTH]`) and recorded in the [audit ledger](audit-ledger.md)
  (`HEALTH_REPORT_LINK_CREATED`, `HEALTH_REPORT_LINK_REVOKED`,
  `HEALTH_PROFILE_SAVED`, `HEALTH_PROFILE_DELETED`; actor, agent and link id —
  never a token or its hash). The Platform Operator may list links but neither create nor
  revoke them: a tool result is kept in its conversation and the audit ledger,
  where a token would outlive being shown once, and its write scope holds no
  `DELETE`.

## MCP

Eight tools mirror the endpoints, with the same rules for the MCP caller:
`read_agent_health`, `list_agent_health`, `compare_agent_versions_health`,
`list_agent_health_sessions` (the drill-down — the review rule applies to the MCP
caller exactly as over REST), `suggest_outcome_mappings`,
`read_agent_health_profile` and `list_health_presets` (tier `eddi-admin`,
`eddi-editor`, `eddi-viewer`), and `update_agent_health_profile` (tier
`eddi-admin`, `eddi-editor`, plus `EDIT` on the agent). Report links have no
tool. `update_agent_health_profile` replaces the stored profile: send what is
stored with your change, without the fields the read profile's `defaulted`
names. See [MCP Server](mcp-server.md#health-tools-8).

## Configuration

| Property | Default | Description |
| --- | --- | --- |
| `eddi.health.enabled` | `true` | Off: no turn is recorded. Retention still runs |
| `eddi.health.queue-capacity` | `10000` | Rows the health worker may have waiting; past it a row is dropped (`eddi_health_dropped_total`), never waited for |
| `eddi.health.facts-retention` | `90d` | How long turn rows are kept, in whole UTC days. Also the oldest day the rollup recomputes, and how far back sessions and users are counted |
| `eddi.health.orphan-sweep-window` | `48h` | How far back the daily orphan sweep looks, by the time each row was **captured** on the turn's thread (its `updatedAt`, set before the row is queued for the health worker): the conversations of those rows are checked, and the rows of those that no longer exist deleted |
| `eddi.health.orphan-sweep-max` | `50000` | The most conversations one orphan sweep checks; past it a WARN. The sweep keeps no cursor, so the excess is **not** guaranteed a later check: what leaves the window unchecked stays until `eddi.health.facts-retention` |
| `eddi.health.rollup-interval` | `5m` | How often the `health-rollup` job runs — how far today's numbers may lag. At least 15 s; read when the schedule is created |
| `eddi.health.daily-retention` | `730d` | How long daily rows and change markers are kept |
| `eddi.health.cache-ttl` | `60s` | How long the views cache what they read from the stores; `0` switches the cache off |
| `eddi.health.apdex-t` | `4s` | Apdex threshold T (satisfied ≤ T, tolerating ≤ 4T); applies to days rolled up after a change |
| `eddi.health.session-window` | `24h` | The inactivity window after which a session closes, for every origin but e-mail; `healthConfig.inactivityWindowHours` overrides it per agent and origin |
| `eddi.health.email-session-window` | `72h` | The same for e-mail conversations |
| `eddi.health.outcome-feed-overlap` | `15m` | How far behind its watermark the outcome evaluation re-reads conversations every pass, so a turn row that reached the store late is still judged (see [the evaluation](#the-evaluation)) |
| `eddi.health.min-outcome-coverage` | `0.5` | The outcome coverage below which the effectiveness rates are withheld (`value: null` with a hint) |

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
A user's [erasure](#gdpr) removes them whatever either setting says. Session
outcomes share the turn rows' retention: the same sweep deletes those that
ended before the cutoff. The same sweep deletes daily rows and change markers
older than `eddi.health.daily-retention` (730 days); those hold no personal
data.

The same daily job then runs the **orphan sweep**: it reads the distinct
conversations of the rows (and discard markers) captured within
`eddi.health.orphan-sweep-window` (48 hours) — and of the session outcomes
evaluated within it, since an evaluation that read a conversation's rows just
before its delete can write its sessions after it — asks the conversation store
which of them still exist — one projected read per 500 ids — and deletes the
rows (turns and sessions) of those that do not, counted (in rows) as
`eddi_health_dropped_total{reason="orphaned"}`. Only a definite "does not
exist" deletes: a batch the conversation store cannot answer keeps its rows,
with a WARN, and the next day asks again. The turn rows go first, and an
orphan's sessions are deleted only once they are gone. When its recent rows
name an agent, should its days be unreadable at delete time, that agent's
retained days are re-rolled instead. Without an agent — its recent rows name
none, or it was found by its sessions alone, with turn rows older than the
window that a failed purge left — its rows are deleted only if their days can
be read; otherwise rows and sessions are both kept for the next sweep (a WARN
with their count) rather than deleted without a way to correct the daily rows,
the sessions being the next sweep's way back to turn rows outside its window.
At most
`eddi.health.orphan-sweep-max` conversations are checked per run (a WARN when
there were more); the sweep keeps no cursor, so conversations past the cap are
not guaranteed a check before they leave the window, and their rows then stay
until retention. The window is measured by **capture** time — `updatedAt` is
set when the turn's thread captures the row, before it is queued — so a row
delayed long enough on a queue can be written after its conversation was
deleted yet already be outside the window; if its post-write clean-up also
failed, it stays until retention. Like the retention it runs on every node and
is idempotent.
It is the durable second chance for the [queued-row race](#gdpr) below.

## GDPR

Turn rows are personal data about a user's use of an agent:

- **Erasure** (`DELETE /admin/gdpr/{userId}`) deletes every row of the user — the
  participant `agentHealthTurns`. Group member conversations are owned by the
  discussion's user and are covered by the same erasure. The delete returns the
  (agent, day)s of the deleted rows, and those days are re-rolled without the
  user. If the days cannot be read, the rows are deleted all the same and every
  retained day of the user's agents is re-rolled instead. Those agents come from
  the conversation store: noted at the erasure's first step (the
  `agentHealthErasureFence` step reads them before the cascade deletes the
  conversations; a failed read never fails that step), else read again when the
  participant runs. If no agent is known either, a WARN is logged and the daily
  rows keep the user's counts until `eddi.health.daily-retention`. This is not
  reported as a failed step: the rows (the only personal data) are gone, and a
  failed step would hold back the participants that run last. Only days within
  `eddi.health.facts-retention` (90 days) can be re-rolled. On older days the
  user's contribution to the daily counts stays until
  `eddi.health.daily-retention` (730 days) removes those rows, which hold no
  user id or content.
- **Export** includes the user's rows (newest first, at most 10,000) under
  `agentHealthTurns.turns`.
- **Session outcomes** (`agentHealthOutcomes`) are erased and exported the same
  way — every session row of the user, newest first, at most 10,000 under
  `agentHealthOutcomes.sessions` (ids, counts, outcome, evidence, last actions;
  no text). The outcome evaluation writes nothing for a user an erasure is
  fencing, and deletes again what it wrote while the fence went up; outcomes
  are derived from turn rows, which the erasure deletes, so nothing re-creates
  them. A conversation delete removes its sessions too.
- **Health profiles and report links** (`agentHealthAdministration`) hold only the
  account name of whoever last saved a profile (`updatedBy`) or created a link
  (`createdBy`); erasure blanks it and keeps the profile (it belongs to the
  agent) and the link row — which, with nobody's authority behind it, answers
  404 from then on. The export lists the profiles the person saved last (agent,
  time) and the links they created (id, agent, sections, period, created,
  expiry) — never a token.
- **Deleting an agent** revokes its report links; a permanent delete also
  removes its health profile and its `HEALTH` alert states.
- **Deleting a conversation** deletes its rows (best effort — a failure is
  logged and the rows expire with the retention).
- **A row still queued is withheld or removed — best effort, not a guarantee.** Rows are written on the health
  worker's queue, so a turn finished just before an erasure or a delete can
  reach the store after it. The erasure's first step raises a fence for the user
  on every node (through the cluster stop in cluster mode); while it lasts (15
  minutes) the worker writes none of the user's rows or discard markers. After
  every write the worker checks again, and deletes the conversation's rows when
  the user is now fenced or the conversation no longer exists — both the erasure
  and a conversation delete remove the conversation before its rows, so one of
  the two sees the other on whichever node the row was queued — provided its
  check and delete succeed (the limits follow). Withheld
  and removed rows count as `eddi_health_dropped_total{reason="erased"}`. That
  second check costs one primary-key read of the conversation per recorded
  turn, on the worker. What it cannot cover: a node that did not confirm the
  erasure's cluster stop (the erasure then reports `clusterStop` as failed and
  must be re-run), a conversation store that cannot answer, or a cleanup delete
  that fails. Each logs a WARN, at most once a minute (the stores EDDI ships
  always answer the check; only a store without `getStateAndRevision` cannot).
  Such a row is **normally** removed by the next daily [orphan sweep](#retention),
  which finds its conversation gone and deletes it — the erasure deletes the
  user's conversations, so this covers a row left behind by an erasure (also
  one a node wrote without having confirmed the cluster stop) as well as by a
  conversation delete. The sweep's own limits: it looks only at rows captured
  within `eddi.health.orphan-sweep-window`, so if it cannot ask the
  conversation store, or cannot delete, for longer than that, the row is left
  to the retention (each failed or skipped run — also one with no conversation
  store to ask — logs a WARN); and a conversation delete
  whose own delete of older rows failed is not caught by it (that failure is
  logged by the delete, and those rows expire with the retention).
- **A turn whose snapshot was not stored** — a pause that lost its
  compare-and-store to a concurrent end or cancel, a write conflict, a fenced or
  failed write — is recorded unsettled: inserted only if absent, never as
  paused, discarding nothing.

See [GDPR & CCPA compliance](gdpr-compliance.md).

## What is not collected

- No input, output, prompt or property **text** — only ids, counts, classes,
  outcomes and action names. `report_conversation_outcome` takes no reason.
- No per-agent Prometheus series: the counters above carry no agent id.
- Nothing is collected *inside* the turn: a slow or failing health store never
  slows or fails a conversation.

## Metrics

```text
eddi_health_recorded_total   # Turn rows written
eddi_health_dropped_total    # Turn records lost; tag: reason (queue_full|store_error|shutdown|erased|orphaned)
eddi_health_queue_depth      # Gauge: records waiting for the health worker
eddi_health_rollup_duration_seconds  # Timer: one rollup pass
eddi_health_rollup_days_total        # Agent days recomputed
eddi_health_rollup_rows_total        # Daily rows written
eddi_health_rollup_failures_total    # Agent days whose rollup failed (retried)
eddi_health_rollup_pending           # Gauge: agent days waiting (the shared queue, cached 10 s)
eddi_health_rollup_lag_seconds       # Gauge: age of the oldest waiting agent day
eddi_health_outcomes_sessions_total  # Session outcome rows the outcome evaluation wrote (each re-evaluation counts)
eddi_health_outcomes_failures_total  # Conversation evaluations that failed (the feed retries them for up to an hour)
eddi_health_outcomes_lag_seconds     # Gauge: how far the outcome evaluation's watermark is behind the clock
eddi_health_outcomes_rating_overflow_total  # Rating changes the outcome hand-off queue had no room for; tag: result (deferred|dropped)
```

A rating saved or withdrawn marks its session for another look on a hand-off
thread (1,000 marks queued), so the rating request never waits. When that queue
is full, or the store fails to take the mark, the conversation is deferred
(`deferred`) and marked — every session of it — once the thread runs its next
mark or the node runs its next outcome pass; it leaves the deferred set only
once that mark is stored. Only past 10,000 deferred conversations is a mark
lost (`dropped`, with a WARN at most once a minute). Both should stay at zero.

`eddi_health_dropped_total` should stay at zero for `queue_full`, `store_error`
and `shutdown`. `erased` and `orphaned` are rows withheld or removed because
their user was erased or their conversation deleted; a rising `orphaned` (rows
the daily sweep removed) means the worker's own clean-up had failed. `HEALTH`
alerts are counted with the other alerts, as `eddi_alerts_fired_total{source="HEALTH"}`.
See [Metrics](metrics.md#agent-health-metrics).
