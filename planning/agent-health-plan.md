# Agent Health — from usage counts to an outcome-aware view of every agent

> **Status: proposal; the product decisions of §9 are settled, implementation not yet approved.** Written 2026-11-10 against `main` at `1e62b93dff`. Revised twice
> the same day: a self-review (§0.1) and an independent critical review (§0.2). Every "exists today"
> claim names the class or doc it was checked in; re-check before starting a phase.

## 0. Revision history

### 0.1 Self-review

| First draft said | Wrong because | Now |
| --- | --- | --- |
| Escalation comes from human takeover | Takeover is **design only** (`ConversationState` has no `HUMAN_CONTROL`); HITL approvals are not escalations | Escalation is explicit until takeover ships |
| Abandonment comes from idle-ending | Idle-ending is no outcome signal (see §0.2 for exactly when it runs) | Health has its *own* inactivity window; `endReason` `idle` / `agent-version-retired` is never evidence |
| An idle conversation is `ABANDONED` | A user who got the answer and left looks identical to one who gave up | `ENDED_QUIETLY` + evidence; outcome **coverage** is a measure |
| Per-agent hourly `$inc` buckets | Hot document; not recomputable; no drill-down | Per-turn facts + recomputable rollups (key refined in §0.2) |
| Thresholds in `AgentConfiguration` | Versioned: tuning would mint versions and split the statistics | Behaviour versioned; thresholds in an unversioned **health profile** |
| Weighted overall score as headline | Meaningless across agent kinds; gameable | **Status with reasons**; index only within a preset |
| Reliability gate on all failures | A provider outage turns every agent red | Platform vs. agent-attributable split (capture gap in §0.2) |
| Push + pull + aggregate KPI dashboards | Rebuilding Databox | Ref-matched outcome events and **export** first |
| Tenant in every key | Tenant context not built | Reserved field only |

### 0.2 Independent review (sub-agent, findings verified in code before accepting)

| Finding | Evidence | Change |
| --- | --- | --- |
| A per-turn ledger already exists and the plan ignored it | Audit ledger records `agentVersion`, `stepIndex`, `durationMs`, `cost`, `actions`, `timestamp` per task, on by default (`docs/audit-ledger.md`, `eddi.audit.enabled=true`) | §6.2 now says explicitly why health keeps its own small row and takes its numbers from the same `audit:*` step keys |
| A clean-turn hook already exists | `ConversationStepRunner.reportTurnOutcome` → `conversationService.reportCleanTurn(memory)`; `OutputMatchHook.snapshot` builds a `TurnSnapshot` | Extend that path; own executor and enable flag (alerts off must not kill health) |
| Recovered LLM failures leave no `FailureClass` on the step | `TurnOutcome` carries `ErrorKind`; `FailureClass` reaches memory only on `ERROR` (`TurnError`) or a served fallback (`llm:error:*`); retried/cascaded failures exist only as untagged Micrometer counters | New per-step failure record written by the recovery path — an `LlmTask`-side change H2 must own |
| "Idle-ending never runs" was half wrong | `AgentDeploymentManagement.manageAgentDeployments` (`@Scheduled(every="24h")`) ends idle conversations of **superseded** versions after `maximumLifeTimeOfIdleConversationsInDays=90` | Premise corrected; conclusion unchanged |
| HITL settles one step twice; say-after-undo reuses a step index | `TurnCostMeter` Javadoc; `undoLoaded`/`redoLoaded` | Fact row per **turn execution**, written by idempotent *replace* of step totals, not `$inc` |
| `(conversationId, day)` cannot roll up per version | Version following moves conversations mid-day | Version stored per turn row |
| `/v1` stateless, email, schedules, group members break "session" outcomes | `OpenAiConversationBridge` stateless starts a conversation per request; email keys per thread; persistent schedules add a step per fire; group members get their own conversations | **Channel/origin** dimension, **minimum-engagement** rule, per-channel windows; non-interactive origins excluded from effectiveness |
| `ERROR → FAILED` confuses a turn state with an outcome | `ERROR` is set per turn and the user can continue | Evaluated only at session close |
| `CONVERSATION_END` unused | Reserved action the agent emits when *it* closes the dialog | A default structural signal |
| "Service role via OIDC client credentials" does not exist inbound | All `client_credentials` code is outbound; inbound machine auth is `/v1` key, HMAC webhooks, A2A | Outcome ingest = new **target kind of the webhook channel integration**, bound to agent ids |
| External refs can be personal data and are not erasable by user | `IGdprParticipant.erase(userId)` finds rows by user id | `userId` on outcome rows; ref names validated; hashed match option; Art. 18 restriction honoured |
| Viewer-visible composite would leak ratings | Usage allows `eddi-viewer`; ratings deliberately exclude viewers (`docs/conversation-ratings.md`) | Each section gated by its own source's rule |
| H1 as a server endpoint is a throwaway façade | Its shape changes in H2 | H1 is Manager-only composition |
| v-to-v compare is the view owners trust first | — | Moved into the first phase that has facts |
| Burn rate on small agents alerts on 2 of 3 turns | — | Volume floor |

Rejected or softened: deriving health **directly** from the audit ledger (reasons in §6.2); making
presets fully deployment-editable templates in the first iteration (later; defaults ship as code with
profile overrides).

## 1. Why

Today an agent's maintainers can see *that* it is used — `GET /agents/{agentId}/usage` returns
`ConversationUsage(total, active, distinctUsers)` — and, scattered elsewhere, ratings, alerts, cost,
Prometheus metrics and golden-test runs. Nobody can answer:

1. *Is this agent doing its job?* — do people get what they came for, give up, or need a human?
2. *Is it getting better or worse, and why?* — since last week, since version 8
3. *Is it worth it?* — cost per successful conversation; effect on the numbers it exists to move
4. *Which of my agents needs me first?*

Two audiences, served without merging them: the **agent designer** (which version, which failure,
which conversations) and the **business owner** (outcomes and value, often without Manager access).

## 2. What others do

| Who | What they measure | Lesson |
| --- | --- | --- |
| **Intercom Fin** | Involvement rate; resolution rate *over involved conversations*; automation rate = involvement × resolution; CX score | State the denominator; define "involved" (the agent actually engaged) |
| **Zendesk AI agents** | "Automated resolution": no escalation, no further customer activity for a window, reportedly LLM-verified; the **billing** unit | A waiting window and a verified tier; silence alone is not success |
| **Salesforce Agentforce** | Deflection, escalation, abandonment, task-resolution; Health tab; alerts. Salesforce calls these *proxy* signals and moves to context-based outcome evaluation | Proxies first, judged outcomes later; operational health ≠ effectiveness |
| **Langfuse** | Scores as first-class rows with a source: user feedback, guardrails, LLM-as-judge (sampled, cost-estimated), human annotation; judge calibration | Source per row, never per aggregate; judges sampled, capped, calibrated |
| **Gainsight** | Weighted measures, thresholds, manual override, bands, alerts on *change*; warnings against unattended scores | Measures + bands + override + change alerts |
| **Datadog SLOs** | Error budget; burn rate with long+short windows | Reliability is a gate; burn rate needs a volume floor |
| **Apdex** | (satisfied + tolerating/2) / total, thresholds T, 4T | Explainable 0..1 for latency |
| **Databox / Geckoboard** | Raw timestamped points into datasets; pull connectors | If EDDI ingests, ingest raw. EDDI should not become this |

**The gap:** nobody joins *this conversation* to *the business event that followed* (order placed,
ticket reopened). EDDI owns the conversation and can carry the other system's own reference — that
join is the differentiator (§5).

## 3. What EDDI has — verified

| Signal | Where | Usable as-is? |
| --- | --- | --- |
| Usage counts | `ConversationMemoryStore.getConversationUsage` | Point-in-time; `distinct("userId")` materialised into a list — not for series or portfolios |
| Turn completion hook | `ConversationStepRunner.reportTurnOutcome`: `TurnOutcome(…, ErrorKind)` for bad turns, `reportCleanTurn(memory)` for clean ones (not `AWAITING_HUMAN`); `OutputMatchHook.snapshot` → `TurnSnapshot` (actions, output, properties, version, userId, time) → `AlertDispatcher` | Yes — extend; `AlertDispatcher` refuses work when alerts are disabled, so health needs its own executor |
| Per-task ledger | Audit ledger: version, `stepIndex`, `durationMs`, `cost`, actions, timestamp, prompts/responses; HMAC, write-once; `eddi.audit.enabled=true` | Same numbers health needs, in a compliance store — see §6.2 |
| Cost | `audit:cost` / `audit:discarded_cost` per step, summed by `ConversationMemoryUtilities#conversationCost`; `TurnCostMeter` meters HITL re-settles by delta; `ToolCostTracker` map is in-memory | Read step totals at completion |
| Failure classification | `ErrorKind` (`TURN_ERROR`, `TURN_INTERRUPTED`, `TURN_DEADLINE`, `COST_CEILING`, `TENANT_BUDGET`) on `TurnOutcome`; `FailureClass` only on `ERROR` (`TurnError`) and served fallbacks (`llm:error:*`) | ⚠ recovered failures not on the step |
| Ratings | `engine/ratings`, `/summary`, `ConversationRatingSaved`, `channel` field | Opt-in, sparse, selection-biased; viewers excluded |
| Alerts | `engine/alerts` — `RATING`, `CONVERSATION_ERROR`, `OUTPUT_MATCH`; outbox; REST only (no Manager page) | Add a `HEALTH` source |
| Golden suites | `docs/agent-testing.md` | Dated check |
| Version following | Implemented; conversations change version mid-way | ⚠ version per turn |
| Idle ending | Admin REST `endInactiveConversations`; daily `manageAgentDeployments` ends idle conversations of superseded versions after 90 d | Not outcome evidence |
| Conversation retention | `deleteEndedConversationsOnceOlderThanDays=365` | ⚠ health history must outlive it |
| Memory indexes | No time index; large documents | ⚠ no time series over memory |
| Webhook integrations | `WebhookSignatureVerifier`, `WebhookEventHandler` (replay cache, capacity caps, vault secret rules), `targets` routing, Manager editor | Host the outcome ingest as a target kind |
| `/v1` API | `OpenAiConversationBridge` stateless (conversation per request) and stateful | ⚠ origin dimension |
| Schedules | `ScheduleFireExecutor`; `ScheduleFireLog.cost` is **tool spend only** | Fire failures yes, cost no |
| Agent sync / export | `AgentConfiguration` exported whole (`SecretScrubber`); alert rules not synced | `healthConfig` travels; profiles do not |
| Deployments | `IDeploymentStore` (`DeploymentInfo`) | ⚠ check for timestamps before using as change markers |
| Platform Operator | `tool-scopes.ts` does not list `/agents/{id}/usage` today | Allow-list from the first phase |
| Takeover, tenant context | Not built | — |

## 4. The model

### 4.1 Status first, numbers underneath

Headline per agent: **status** — `Healthy` · `Watch` · `Needs attention` · `Not enough data` — plus
**top reasons** ("Resolution 41 % (−12 pp since v8 on 3 Nov)"). Rules:

- any **critical** measure red → `Needs attention`
- any measure amber, or a significant negative trend on a scored measure → `Watch`
- otherwise `Healthy`; too few samples on every scored measure → `Not enough data`

A weighted 0–100 **index** exists only within a preset. The portfolio sorts by *needs-me-first*
(severity, size of negative change, traffic). Every view shows measures, denominators, thresholds,
period and evidence source.

### 4.2 Denominators: origin and engagement

Every turn row and outcome carries an **origin**, classified per turn as settled in H0 (§9):
`api`, `v1`, `mcp`, `email`, `slack`, `webhook`, `schedule`, `a2a`, `group-member`, `delegated`,
`agent-test`. The Chat UI and the Manager both record `api`, and both `/v1` modes record `v1`. Neither
pair can be told apart server-side until a client hint exists. A conversation counts as **engaged** only
with ≥ 1 user turn after the agent's first output (the Intercom "involved" idea). Greeting-only and empty
sessions are counted but excluded from effectiveness denominators. By default, `api`, `v1`, `mcp`,
`email` and `slack` feed effectiveness, while `schedule`, `webhook`, `a2a`, `group-member` and
`delegated` feed the `task` view (the designer can include them). `agent-test` is always excluded unless
asked for explicitly.

### 4.3 Measures — scored, informational, checks

| Area | Measure | Kind | Notes |
| --- | --- | --- | --- |
| **Reliability** | Agent-attributable failure rate (pipeline errors, own timeouts, `BAD_REQUEST`, `CONTEXT_TOO_LONG`, `MODEL_NOT_FOUND`, `AUTH`, `QUOTA_EXHAUSTED`, cost ceiling) | Scored, critical | SLO + burn rate with volume floor |
| | Platform failure rate (`TRANSIENT`, `RATE_LIMITED`, provider `TIMEOUT`, incl. recovered) | Informational | Needs the per-step failure record (§6.3); grouped as a platform event when many agents are hit |
| | Turn duration (Apdex T/4T); fallback / cascade-exhausted rate | Scored | Fallbacks already visible via `llm:fallback:*` |
| **Effectiveness** | Resolution (confirmed + assumed, shown split), escalation, abandonment — over engaged interactive sessions | Scored | Only when **coverage** ≥ threshold (default 50 %) |
| | Outcome coverage | Scored for designers | Prerequisite of the above |
| **Quality** | Rating (Wilson bound of normalised score), reply thumbs, response rate | Scored when n ≥ min | Rating section obeys rating permissions |
| | Guardrail trips, output-match alerts | Informational | Direction ambiguous |
| | Golden suite on deployed version | Check | Pass/fail + age |
| **Efficiency** | Cost per engaged session, **cost per resolved session**, tokens per turn | Scored vs. budget | From step totals, not `ScheduleFireLog.cost` |
| **Adoption** | Sessions, distinct users, returning share, turns per session | Informational | Same-weekday trend; no baseline → grey |
| **Business** | Outcome events (§5) | Scored if mapped | |

Small *n*: Wilson intervals; period changes flagged only when meaningful; below minimum sample → grey.

### 4.4 Conversation outcomes

Taxonomy: `RESOLVED` · `ASSUMED_RESOLVED` · `ESCALATED` · `ABANDONED` · `FAILED` · `DEFLECTED` ·
`ENDED_QUIETLY` · `OPEN`. Evaluated per **session** (a conversation split at gaps longer than the
origin's window: chat 24 h, email 72 h, configurable; non-interactive origins not evaluated).

Sources, highest precedence first; each outcome row records its source (Langfuse lesson):

1. **External outcome event** (§5).
2. **Explicit**: an action mapped in `healthConfig.outcomes`, or the built-in tool
   `report_conversation_outcome` (`self_reported`, weak; once per session, last write wins, never
   overrides an external event).
3. **Structural**: the agent emitted `CONVERSATION_END` / `STOP_CONVERSATION` after a successful turn →
   `ASSUMED_RESOLVED` with source **`agent_closed`** unless mapped otherwise; the session's **last** turn
   failed and nothing followed → `FAILED`; human takeover → `ESCALATED` once takeover ships. `endReason`
   `idle` / `agent-version-retired` is never evidence.
4. **Inferred after the window**: last output an unanswered question → `ABANDONED`; last turn
   succeeded, no negative rating, no escalation → `ASSUMED_RESOLVED` with source **`silence`** (on by
   default, `assumeResolvedOnSilence: false` turns it off per agent); anything else → `ENDED_QUIETLY`.

The two assumed sources stay **distinguishable everywhere** (decision 3): the resolution measure is
always shown split in four parts — *confirmed* (external event, explicit agent action) · *self-reported*
(`report_conversation_outcome`, the LLM grading itself — weak, never counted as confirmed) · *agent
closed* · *silence* — so an owner sees at a glance how much of a 70 % resolution rate rests on users
simply going quiet or on the model's own word. Status
rules may weight them differently (default: `silence` counts, but a resolution rate resting more than
half on `silence` is flagged as a reason — "mostly assumed from silence").
5. **Judged** (H6, sampled).

Outcomes are provisional: a resumed session reopens to `OPEN`. Coverage is the feature's biggest risk
(§8) — the "map these actions" nudge and the `CONVERSATION_END` default are part of H3's definition of
done, not prose.

### 4.5 Where configuration lives

| What | Where | Why |
| --- | --- | --- |
| `outcomes`, `outcomeEvents`, `externalRefs`, `assumeResolvedOnSilence`, interactive-origin overrides | `AgentConfiguration.healthConfig` — **versioned**, travels in export/sync (like `ratingConfig`, `hitlConfig`) | Agent behaviour |
| Preset, thresholds, critical measures, budgets, SLOs, overrides | **Health profile** — unversioned admin resource keyed by agent | Tuning must not mint versions. Does **not** travel with sync/export (thresholds are per deployment) — documented; optional sync later |
| Windows, retention, judge defaults | `eddi.health.*` | Deployment defaults |

```json
"healthConfig": {
  "outcomes": { "order_confirmed": "RESOLVED", "handoff_human": "ESCALATED", "out_of_scope": "DEFLECTED" },
  "externalRefs": ["orderId", "ticketId"],
  "outcomeEvents": { "order.placed": "RESOLVED", "ticket.reopened": "FAILED" },
  "assumeResolvedOnSilence": true
}
```

```json
{ "agentId": "…", "preset": "support",
  "critical": ["agentFailureRate", "resolutionRate"],
  "thresholds": { "resolutionRate": { "green": 0.7, "red": 0.4 } },
  "budgets": { "costPerResolvedUsd": 0.5 },
  "slo": { "agentTurnSuccess": 0.99, "apdexTMs": 4000, "minTurnsForBurnAlert": 50 },
  "overrides": [ { "measure": "business", "state": "IGNORED", "reason": "CRM outage", "until": "2026-11-14" } ] }
```

Save-time validation: unknown measures, impossible thresholds, expired overrides; each `externalRefs`
entry must be a property some `property.json` in the agent's workflows sets (warn otherwise), and must
not match identifier patterns (`email`, `phone`, …) unless explicitly allowed.

### 4.6 Presets

`support` (default for chat), `sales`, `assistant`, `task` (default for agents driven only by
schedules, webhooks, A2A, groups: run failure rate, duration, cost per run, schedule fire failures).
Shipped as defaults; profile overrides any field.

### 4.7 Per version, with change markers — the hero view

Turns attributed to the version that ran them; outcomes to the version at session end. Charts carry
change markers (deployments, version switches, model/price changes, snippet and global-variable
edits). "v8 since rollout vs v7's last 14 days" ships in the first phase that has facts.

## 5. External signals

### 5.1 Order of value

1. **Outcome events matched by the agent's own reference** (`orderId`, `ticketId`, set by
   `property.json`), or by `conversationId`.
2. **Export** — `HEALTH` alerts, daily outcome/health JSON and CSV, optional agent-tagged metrics.
3. **Aggregate KPI import** — correlation only, last, thin, maybe never.

### 5.2 Ingest — as a webhook integration target kind

Outcome events arrive through the existing **webhook channel integration** with a new target kind,
e.g. `targets: [{ "name": "crm-outcomes", "kind": "health-outcomes", "agentIds": ["…"] }]`. That
inherits the endpoint, HMAC verification, replay cache, delivery caps, vault secret rules, CloudEvents
and the Manager's integration editor — the actual "no second implementation". The binding to
`agentIds` bounds what a leaked secret can poison; every event records its integration id.

```json
{ "agentId": "…", "ref": { "orderId": "A-123" }, "event": "order.placed", "value": 49.90, "at": "2026-11-10T09:12:00Z" }
```

Caps per request and per agent; unmatched refs kept briefly (late events are normal), then counted and
dropped. A documented Keycloak service-account path for plain REST callers can follow; it does not
exist today and must not be assumed. Pull sources only after push proves out, as recipes.

### 5.3 Privacy of references

Refs may be identifiers. Outcome and event rows carry `userId` so `IGdprParticipant.erase(userId)` finds
them; refs can be stored and matched as salted hashes (vault salt) per agent; GDPR processing
restriction (Art. 18) suppresses ingest for restricted users, as ratings do.

### 5.4 Attribution honesty

Every number states its evidence: *by reference*, *by conversation id*, *agent action*,
*agent closed*, *silence*, *self-reported*, *inferred*, *judged*. Aggregate KPIs are never labelled as
caused.

## 6. Architecture

### 6.1 Not a lifecycle task

After the fact, off the existing turn-completion hook, never slowing or failing a turn. Rollups and
outcome evaluation are `ScheduleFireExecutor` system jobs with CAS claims (like `alerts-maintenance`).

### 6.2 Data

| Store | Key | Content |
| --- | --- | --- |
| `healthturns` / `health_turns` | `(conversationId, stepIndex, executionId)` | agentId, agentVersion, userId, origin, day, step **totals** (cost from `audit:cost`, tokens, duration), failure markers (ErrorKind, FailureClass list incl. recovered), fallback, tool calls/failures, guardrail trips, actions relevant to outcomes, last-output-was-question |
| `healthoutcomes` / `health_outcomes` | `(conversationId, sessionNo)` | outcome, provisional, source, evidence, refs (hashed or plain), userId, origin, engaged, version at end, events |
| `healthdaily` / `health_daily` | `(agentId, agentVersion, origin, day)` | aggregates — recomputed |
| `healthdirty` | `(agentId, day)` | days to re-roll |
| `healthprofiles` | agentId | §4.5 |

**Write semantics** (as settled in H0, §9). The turn row is keyed by `(conversationId, executionId)` and
written by a **guarded replace** of the step's totals, not `$inc`.
- `executionId` is stamped on the step at the **end** of its first execution and kept from then on. A
  HITL resume or a rerun (the same step, settled again) therefore replaces its own row. A say after an
  undo runs on a new step and gets a new id, and the undone executions are marked discarded, matching
  `audit:discarded_cost`.
- `day` and the first timestamp stay as first recorded.
- A write older than the stored row's `updatedAt` is ignored.
- `discarded` is sticky.
- An **abandoned or cancelled** turn (watchdog timeout, client cancel) is insert-only. It never replaces
  an existing row and never applies discards.

The write runs asynchronously on its own bounded executor, and drops are counted
(`eddi_health_dropped_total`). Health is not billing-grade: quotas and ceilings stay authoritative.

**Rollups.** Every writer (turn, outcome, event, mapping change) enqueues `(agentId, day)` in
`healthdirty`; the rollup job drains it and recomputes those days from turn and outcome rows. A
mapping change enqueues the retention window. Days older than turn-row retention cannot be re-rolled —
stated in the UI.

**Why not derive from the audit ledger** (considered, rejected as the *source*, kept as the *number
origin*): the ledger can be disabled (`eddi.audit.enabled=false` would silently kill health); its rows
are per task and content-heavy (compiled prompts, responses); it is an immutable, HMAC-chained
compliance store whose retention and redaction are set for compliance, not analytics; and it lacks
origin, engagement, recovered failure classes and outcomes. Health takes cost, tokens and duration
from the same step keys the ledger records, so both always agree.

**Retention bands.** Turn rows and outcomes 90 d (drill-down and re-roll work), conversations 365 d,
daily aggregates 2 y (trend only). GDPR: turn and outcome rows erased by `userId` and
`conversationId`; daily aggregates hold no personal data.

### 6.3 Collection

- Extend `reportCleanTurn` / `TurnOutcome` into one turn record (reuse `TurnSnapshot` fields) on a
  health executor with its own `eddi.health.enabled` flag.
- **New, owned by H2:** the LLM recovery path (`CascadingModelExecutor` / `LlmFallbackHandler`) records
  each failed attempt (`FailureClass`, model, recovered-by) on the step, so recovered failures are
  countable per agent.
- `ConversationRatingSaved` observer; golden-suite completion; deployment and version-switch markers
  (verify `DeploymentInfo` timestamps first).

### 6.4 Surfaces and permissions

- **REST**: `GET /agents/{id}/health`, `/health/series`, `/health/compare?from=7&to=8`,
  `GET /health/agents`, profile CRUD, export. Each section obeys its source's rule: usage/reliability/
  efficiency at VIEW (like `/usage`); ratings at admin/editor (viewers excluded, as today); alerts at
  EDIT; drill-down needs conversation read *and* conversation review enabled on that version.
- **Alerts**: `AlertSource.HEALTH` — status change, burn rate (long+short, volume floor), significant
  negative trend.
- **Manager**: Health tab (status, reasons, measures, sparklines, markers, version compare, coverage
  nudge); Health overview; profile editor (a new surface — alert rules have no Manager page to copy).
- **MCP + Platform Operator from the first phase that ships an endpoint** (and add the existing
  `/agents/{id}/usage` to the allow-list in H1); `operator-revision.json` bump; plugin skills check.
- **Docs + changelog fragment per phase.**

### 6.5 The judge (H6)

Sending transcripts to a model is a data-processing decision: opt-in per agent, only where
`conversationReview` is enabled, provider explicit, audit-ledger record, dollar cap, incremental.
Calibration against explicit outcomes and ratings shown beside judged rates. Shared with
`planning/evaluated-generation-plan.md` is the *model-call machinery* (registry, pricing, timeouts),
not the in-turn guardrail component.

## 7. Phases

| Phase | Scope | Value | Size |
| --- | --- | --- | --- |
| **H0 — Decisions** | Remaining open items of §9: turn-row key and `executionId`; origin list and engagement rule; change-marker sources | — | S |
| **H1 — Manager composition** | Health tab composed client-side from existing endpoints (usage, ratings summary where permitted, alert events where permitted, last golden run); Operator allow-list gets `/usage`. No new REST | Validates the page cheaply | S |
| **H2a — Turn facts** | `health_turns` (Mongo + Postgres), `executionId`, recovered-failure record in the LLM path, origin, GDPR participant, load test (`scripts/loadtest/`) | Foundation | M–L |
| **H2b — Series & compare** | Dirty-day rollups, `health_daily`, series, **version compare**, change markers, platform/agent split; REST + MCP + Operator | Trends, versions, "why" | M |
| **H3 — Outcomes** | Sessions, origin windows, engagement, explicit/structural/inferred outcomes, `CONVERSATION_END` default, `report_conversation_outcome`, coverage + mapping nudge | Effectiveness, honestly | M |
| **H4 — Status & action** | Profiles, presets, status rules, Wilson, portfolio, `HEALTH` alerts with volume floor, overrides | "Which agent needs me" | M |
| **H4b — Owner report** | Minimal branded, read-only health report for business owners without Manager access (white-label theming); decide sharing-link model and its permissions (expiry, revocation, which sections a link may show — never drill-down, ratings only if the creator may read them) | Serves the second audience | S–M |
| **H5 — Business join** | `externalRefs`, webhook target kind `health-outcomes`, ref hashing, Art. 18, export | Differentiator | M |
| **H6 — Verified** | Judge + calibration, golden gate, deploy/canary gate | Trust, safe rollouts | L |
| **H7 — Breadth** | Group health (replace client-side Workforce Insights), task depth, aggregate import, pull recipes, profile sync | — | M |

H2a blocks everything after it. H3 and H2b can run in parallel once H2a lands.

## 8. Risks

- **Coverage** (biggest): day one, chat agents show no outcome data and owners call the page empty.
  Mitigation inside H3: `CONVERSATION_END` and silence assumptions on by default (shown split), the
  mapping nudge — and the version compare and reliability views carry the page until outcomes fill in.
- **Assumed rates read as real** — the flip side of the defaults above. The split display and the
  "mostly assumed from silence" reason are mandatory, not optional UI polish.
- **Goodhart** — source breakdown always visible; judged calibration in H6.
- **Noisy denominators** — origin dimension and engagement rule.
- **Platform incidents** — recovered-failure capture, cross-agent grouping.
- **Write load** — one small async replace per turn; load-tested in H2a.
- **Privacy** — refs, judge, permissions per section.
- **Scope creep into BI** — export over import.

## 9. Decisions (settled 2026-11-10 with the maintainer)

| # | Question | Decision |
| --- | --- | --- |
| 1 | Headline | **Status with reasons**; a weighted index only within a preset |
| 2 | LLM self-reported outcome | **Yes** — `report_conversation_outcome`, opt-in per agent like other built-in tools, once per session, never overrides an external event, always labelled `self_reported` |
| 3 | Assumptions without explicit signals | **Both on by default**: agent-closed (`CONVERSATION_END` after a successful turn) *and* silence after a successful turn count as `ASSUMED_RESOLVED`, kept **distinguishable** as sources `agent_closed` / `silence`; `assumeResolvedOnSilence: false` turns silence off per agent |
| 4 | Inactivity windows | Chat **24 h**, email **72 h**, configurable per agent |
| 5 | Recovered LLM failures | **Record them on the step** (H2a) |
| 6 | External references | **Plain by default**, identifier-like names refused at save, rows erasable by user id; salted hashing **opt-in** per agent |
| 7 | Should health act? | **Alerts only**; no automatic rollback or pausing until the H6 gate exists |
| 8 | Report for non-Manager owners | **H4b**, right after status exists |

### H0 decisions (settled 2026-11-10 from the code, before H2a)

| Item | Decision | Evidence |
| --- | --- | --- |
| **`executionId`** | A random UUID under the internal step key `health:execution_id`, written by `Conversation` at the **end** of a step's first execution (the `finally` of `init` and `runStep`, beside `recordAgentVersion`) and only when absent. A HITL resume (`Conversation.resume`) and a rerun continue the same step and keep it, so their row replaces the paused/first one; a say after an undo runs on a new step and gets a new id. Written at the end, not the start, because detailed snapshots are read by position (`MemoryKeys.AGENT_VERSION` Javadoc). A step that finished an execution before H2a (it has `agent:version` but no id) uses `legacy-<stepIndex>`; a step a watchdog-abandoned turn has not stamped yet gets a fresh random id at capture time, so repeated timeouts at one step are separate rows | `ConversationMemory.startNextStep()` / `undoLastStep()` / `redoLastStep()`; `Conversation.resume` re-enters the current step; `ConversationStep.storeData` keeps the slot of an existing key |
| **Discarding undone executions** | Before `startNextStep()` clears the redo cache, `Conversation` collects the execution ids of the steps in it and stores them on the new step as `health:discarded_executions` (end of step, ≤ 100 ids). The recorder marks those rows `discarded` after writing the new one. Spend stays consistent with `conversationCost`: Σ `costUsd` over **all** rows = Σ (`costUsd` + `discardedCostUsd`) over the non-discarded rows | The same point where `audit:discarded_cost` arises (`ConversationMemory.startNextStep()`) |
| **Internal keys** | `health:*` keys are not in the simple snapshot (only `input:initial`, `actions*`, `output*`, `quickReplies*` pass `convertSimpleConversationMemory`); they show in `returnDetailed=true`, which is acceptable — they hold ids and failure classes, no content | `ConversationMemoryUtilities` |
| **Which turns** | Every turn through `ConversationStepRunner.reportTurnOutcome` (say, rerun, HITL resume, clean or not, including `AWAITING_HUMAN`) **plus** the `CONVERSATION_START` turn, which runs inline in `ConversationService.startConversation` and never reached that hook. A start turn that throws stores no conversation and is not recorded. A watchdog-abandoned turn is recorded once, at the timeout, with what its live step held then | `ConversationStepRunner.waitForExecutionFinishOrTimeout`, `ConversationService.startConversation` |
| **Origin** | Classified **per turn** from data the turn stored — the conversation's `userId` and the step's own `context:*` keys — first match wins: `agent-test` (`agent-test:` user) · `group-member` (reserved `groupConversationId`) · `delegated` (reserved `delegationDepth`; `converse_with_agent` / sub-agents) · `schedule` (`schedule` / `scheduleMaxCostPerFire` context, or the scheduler placeholder user) · `a2a` (`a2aTaskId`, `a2a:` user) · `email` (`email:` / `email-anon:` user, `emailIntegration`) · `slack` (`slack:` user, `slack` context) · `webhook` (`webhook:` user, `webhookIntegration`) · `v1` (`channel=openai`, or a `channelIntent` starting `channel:openai:`) · `mcp` (`mcp` context) · else `api`. Rejected: a value stored once at start. Every start path passes a context the integration chose (A2A and MCP pass none, a `new`-strategy schedule passes none without an allowance), so "set once at start" could not tell them apart without changing nine call sites and their tests; per-turn classification over stored data is deterministic and recomputable. Consequences: a `new`-strategy schedule conversation's start turn is `api` and its fire turns `schedule` — **a conversation's origin (H3) is its first non-`api` row**; `chat-ui` vs `manager` and `v1-stateless` vs `v1-stateful` are not distinguishable server-side today (both UIs use the same REST API; the stateless bridge passes the same `channelIntent`) and are `api` / `v1` until a client hint exists. Only `group-member` and `delegated` rest on reserved (client-stripped) keys; the others can be imitated by a client, which can only mislabel its own conversation | `ClientContextGuard.RESERVED_KEYS`; start contexts in `OpenAiConversationBridge`, `EmailInboundProcessor`, `WebhookEventHandler`, `ScheduleFireExecutor`, `MemberTurnExecutor`, `SlackEventHandler`, `A2ATaskHandler`, `McpConversationTools`; user-id prefixes `EmailUserIdentity`, `SlackUserIdentity`, `WebhookEventHandler.USER_PREFIX`, `AgentTestConversations` |
| **Engagement** | Each row carries `userInput` (the step's `input:initial` is non-blank and the turn is not a self-continuation — no `context:selfContinuation`) and `agentOutput` (the step rendered output text). A conversation (session in H3) is **engaged** iff some non-discarded row has `userInput` and a `stepIndex` greater than the smallest `stepIndex` of a row with `agentOutput`. A greeting followed by one user turn is engaged; a start with no greeting followed by one question and its answer is not (no user turn *after* the first output) | Turn rows alone suffice; no conversation read |
| **Change markers** | `DeploymentInfo` has **no timestamps**: Mongo's row stores `lastModified` of the last status write only (overwritten, not history), Postgres stores none; `DeploymentEvent` is in-process and carries no time. So H2b must **add** an append-only marker store, written on deploy `READY` (`RestAgentAdministration` / `DeploymentListener`) and on global-variable writes (`GlobalVariable` has no timestamp either). Usable as-is: agent and snippet **versions** carry `createdOn` / `lastModifiedOn` in their `ResourceDescriptor`; a conversation's version switch is on its step (`agent:switch`), and turn rows record `agentVersion` and `previousAgentVersion`, so "first turn on version N" is derivable from the facts | `DeploymentInfo`, `MongoDeploymentStorage.setDeploymentInfo`, `PostgresDeploymentStorage`, `ResourceDescriptor`, `GlobalVariable` |
| **Recovered LLM failures** | Recorded under `health:llm_failures` as `{class, model, recovery}` with recovery `retry` (a same-model retry followed, from `RetryConfiguration`), `cascade` (escalated, or an earlier step's answer served), `fallback` (`onError: fallback`) or `none` (the failure ended the model phase). Same-model retries reach the step through a thread-bound observer, because the retry loop has no memory, and only inside the task's own model calls (`AgentExecutionHelper`), so judge, summarisation, truncation and MCP retries are not attributed to the model. A cascade that ends in a failure settles all its entries to that failure's outcome. The streaming path's own retry is not recorded | `CascadingModelExecutor`, `RetryConfiguration.executeWithRetry`, `LlmTask` model-phase `catch` |
| **Write rules (review, 2026-11-10)** | Row identity `(conversationId, executionId)`; `day`/`timestamp` first-recorded; newest `updatedAt` wins; `discarded` sticky, with a marker when the discard arrives first; an unsettled turn (abandoned wait, cancelled, interrupted, deadline) is insert-only and discards nothing | Review of H2a: day drift on a resume across midnight, cross-node reordering, a cancelled say after an undo |
| **Duration** | Measured by the hook, from the start of its wait for the turn to the outcome; for the start turn, the whole `startConversation`. A resumed turn's row holds the resumed segment only (the human's wait excluded) | No step-level duration key exists; per-task `durationMs` lives only in the audit collector |

## 10. Deliberately not proposed

- A new lifecycle task, a TSDB, per-vendor KPI connectors in Java.
- Deriving health from the audit ledger (§6.2).
- A new inbound auth mechanism for ingest.
- Per-agent Prometheus series by default.
- Attribution by user id and time window.
- Health as input to billing or quotas.

## Sources

- Intercom — [Fin automation rate](https://www.intercom.com/help/en/articles/13533623-fin-ai-agent-automation-rate), [Fin performance metrics update](https://www.intercom.com/help/en/articles/15599377-update-to-fin-performance-metrics)
- Zendesk — [About automated resolutions for AI agents](https://support.zendesk.com/hc/en-us/articles/5352026794010-About-automated-resolutions-for-AI-agents), [outcome-based pricing](https://www.zendesk.com/newsroom/articles/zendesk-outcome-based-pricing/)
- Salesforce — [Trailhead: monitor agent metrics and scores](https://trailhead.salesforce.com/content/learn/modules/agent-analytics-and-monitoring/monitor-agent-metrics-and-scores), [Agentforce Observability](https://www.salesforce.com/agentforce/observability)
- Langfuse — [Evaluation concepts](https://langfuse.com/docs/evaluation/concepts), [Scores overview](https://langfuse.com/docs/evaluation/scores/overview)
- Gainsight — [Scorecards](https://www.gainsight.com/blog/scorecards-quantifying-customer-health/), [3 ways to structure a health score](https://www.gainsight.com/blog/3-ways-to-structure-your-customer-health-score-no-usage-data-required/); GitLab [health scoring handbook](https://handbook.gitlab.com/handbook/customer-success/product-usage-data/use-case-adoption/)
- Datadog — [SLO burn rate](https://docs.datadoghq.com/service_management/service_level_objectives/burn_rate/), [burn rate is a better error rate](https://www.datadoghq.com/blog/burn-rate-is-better-error-rate/)
- New Relic — [Apdex](https://docs.newrelic.com/docs/general/apdex)
- Databox — [send data via API](https://help.databox.com/send-data-to-databox-via-api); Geckoboard — [Datasets API](https://support.geckoboard.com/en/articles/10306899-how-to-use-the-datasets-api)
