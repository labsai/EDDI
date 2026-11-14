# Agent Health

Agent Health answers the questions usage counts cannot: *is this agent doing its
job, is it getting better or worse, and why?* It is built in phases. This page
describes what ships today — the **turn facts** every view is computed from,
the **daily rollup**, **change markers**, and the **REST and MCP views** over
them — and marks what is still coming.

| Phase | What it adds | Status |
| --- | --- | --- |
| H2a — Turn facts | One small row per turn execution, recorded after the fact | **Available** |
| H2b — Series & compare | Daily rollups, trends, version-to-version compare, change markers, REST/MCP | **Available** |
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
| MongoDB | `healthturns`, `_id` `<conversationId>\|<executionId>`, indexed by `(agentId, day)`, `(conversationId, stepIndex)`, `userId`, `day`, `discardedAt`, `updatedAt` |
| PostgreSQL | `health_turns`, primary key `(conversation_id, execution_id)`, indexed by `(agent_id, day)`, `user_id`, `day`, `discarded_at_ms` (markers), `updated_at_ms`; created on first use |

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
A change that alters how stored turns are counted (outcome mappings, later
phases) marks a whole range dirty through `HealthDirtyDays.markRange`.

| Datastore | Daily rows | Dirty days | Change markers |
| --- | --- | --- | --- |
| MongoDB | `healthdaily` (`_id` `agent\|version\|origin\|day`, counters in a sub-document) | `healthdirty` | `healthmarkers` |
| PostgreSQL | `health_daily` (primary key `(agent_id, agent_version, origin, day)`, counters as `JSONB`) | `health_dirty` | `health_markers` |

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
back to the daily rows' turns. `status` is null for every agent until health profiles exist, so
a `status=` filter matches none. `sort=attention` puts first the agents whose key
measures (`agentFailureRate`, `apdex`, `costPerSession`) got **significantly**
worse — by the largest such change, absolute for rates, relative for the rest —
then the worst band among them, then traffic, then name. A **platform incident**
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

No view carries conversation content, a conversation id or a user id.

## Measures

`n` is the sample a value rests on; below a measure's minimum sample the band is
`GREY` with a hint ("Fewer than 30 turns in this period"), never green or red on
a handful of turns. Bands come from default thresholds (the `support` preset's
intent); health profiles will override them per agent.

| Section | Measure | Definition | Kind, default band |
| --- | --- | --- | --- |
| Reliability | `agentFailureRate` | Failed turns the agent is accountable for ÷ turns (see attribution below) | Scored, **critical**: green ≤ 1 %, red ≥ 5 %, min 30 turns |
| | `platformFailureRate` | Turns touched by a provider or platform failure — a platform-caused failure, or any failed attempt of class `TRANSIENT`, `RATE_LIMITED` or `TIMEOUT`, **recovered or not** — ÷ turns; `breakdown` lists failed attempts per class | Informational |
| | `apdex` | (satisfied + tolerating ÷ 2) ÷ timed turns, with satisfied ≤ T and tolerating ≤ 4T (`eddi.health.apdex-t`, 4 s). The label names T ("Response time (Apdex, T = 4 s)"). A period whose days were rolled up with different T values is `null` with a hint rather than a mix. A paused turn is not a latency sample | Scored: green ≥ 0.85, red ≤ 0.70 |
| | `turnDurationP95` | The 95th percentile of turn duration, **approximate**: interpolated inside fixed buckets (100 ms … 5 min) | Informational |
| | `fallbackRate` | Turns that served the configured fallback ÷ turns | Scored: green ≤ 2 %, red ≥ 10 % |
| Effectiveness | `resolutionRate` (with the four-part `breakdown`), `escalationRate`, `abandonmentRate`, `deflectionRate`, `outcomeCoverage` | `value: null`, "Available once conversation outcomes are recorded" (H3); the section's `coverage` is `null` | — |
| Quality | `ratingScore` | The Wilson lower bound of the average normalised conversation rating — what the ratings support with 95 % confidence, so three five-star ratings do not outrank three hundred four-star ones | Scored: green ≥ 0.7, red ≤ 0.5, min 10 ratings |
| | `ratingResponseRate` | Conversation ratings ÷ sessions | Informational |
| | `replyThumbsUp` | Thumbs-up reply ratings ÷ reply ratings | Scored: green ≥ 80 %, red ≤ 60 %, min 10 |
| | `guardrailTripRate` | Guardrail trips per turn (can exceed 1) | Informational |
| Efficiency | `costPerSession` | Spend of every row (undone executions included, so it matches the conversations' cost) ÷ sessions | Informational until budgets (H4) |
| | `costPerResolvedSession` | `null` until outcomes (H3) | — |
| | `tokensPerTurn` | Tokens ÷ turns | Informational |
| Adoption | `sessions`, `engagedSessions`, `distinctUsers` | Counted from the turn rows: distinct conversations (a session is a conversation until H3), those engaged (a person's turn after the agent's first output, judged on the period's turns), distinct user ids | Informational |
| | `returningUserShare` | Users of the period who also had a turn in the 30 days before it (clipped to the turn-row retention) ÷ distinct users. Never called significant: the look-back of a baseline near the retention is shorter. No daily series | Informational |
| | `turnsPerSession` | Turns ÷ sessions | Informational |
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
Sessions, engagement and users are counted in the database from the turn rows,
because distinct counts cannot be added up across days; a period older than the
turn-row retention (90 days) has none (`hint` says so). In a series grouped by
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

## MCP

Three read-only tools mirror the endpoints, with the same rules for the MCP
caller: `read_agent_health`, `list_agent_health` and
`compare_agent_versions_health` (tier `eddi-admin`, `eddi-editor`,
`eddi-viewer`). See [MCP Server](mcp-server.md#health-tools-3).

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
A user's [erasure](#gdpr) removes them whatever either setting says. The same
sweep deletes daily rows and change markers older than
`eddi.health.daily-retention` (730 days); those hold no personal data.

The same daily job then runs the **orphan sweep**: it reads the distinct
conversations of the rows (and discard markers) captured within
`eddi.health.orphan-sweep-window` (48 hours), asks the conversation store which
of them still exist — one projected read per 500 ids — and deletes the rows of
those that do not, counted (in rows) as
`eddi_health_dropped_total{reason="orphaned"}`. Only a definite "does not
exist" deletes: a batch the conversation store cannot answer keeps its rows,
with a WARN, and the next day asks again. An orphan is deleted only when its
rows name an agent: should its days be unreadable at delete time, that agent's
retained days are re-rolled instead. Rows that name no agent are kept for the
next sweep (a WARN with their count) rather than deleted without a way to
correct the daily rows. At most
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

- No input, output, prompt or property **text** — only ids, counts, classes and
  action names.
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
```

`eddi_health_dropped_total` should stay at zero for `queue_full`, `store_error`
and `shutdown`. `erased` and `orphaned` are rows withheld or removed because
their user was erased or their conversation deleted; a rising `orphaned` (rows
the daily sweep removed) means the worker's own clean-up had failed. See
[Metrics](metrics.md#agent-health-metrics).
