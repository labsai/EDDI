## ✨ feat(health): Agent Health conversation outcomes — sessions, evidence, coverage, drill-down and the self-report tool (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h3`, on top of `feat/agent-health-h2b`)

### What changed and why

Phase H3 of [`planning/agent-health-plan.md`](../../planning/agent-health-plan.md): the effectiveness section stops
being empty. Documented in [`docs/agent-health.md`](../agent-health.md#conversation-outcomes) and
[`docs/agent-config-authoring.md`](../agent-config-authoring.md#healthconfig--how-agent-health-judges-conversations);
the wire shapes of [`planning/agent-health-api.md`](../../planning/agent-health-api.md) are implemented as written, with
the backend's answers in its new §13.

- **`healthConfig`** on `AgentConfiguration` (versioned, appended at the end of the class; the model is its own file
  `HealthConfig`): `outcomes` (action → `RESOLVED|ESCALATED|DEFLECTED|FAILED|ABANDONED`), `assumeResolvedOnSilence`
  (default `true`), `inactivityWindowHours` per origin (1–720), `selfReportTool` (default `false`). Save-time checks in
  `HealthConfigValidator` (unknown outcomes, reserved actions that start/pause/continue a conversation, bounds), wired
  beside `RatingConfigValidator`; `HealthConfigPolicy` reads it per agent version, cached, leniently. Export/import
  and sync carry it unchanged (`HealthConfigRoundTripTest` runs it through `SecretScrubber` and Jackson).
- **Sessions and outcomes** (`HealthSessionEvaluator`, pure): a conversation splits at gaps longer than its origin's
  window (`eddi.health.session-window` 24h, `eddi.health.email-session-window` 72h). Precedence: the last mapped
  action (`agent_action`) > the last `report_conversation_outcome` claim (`self_reported`) > `CONVERSATION_END` /
  `STOP_CONVERSATION` after a successful turn (`ASSUMED_RESOLVED` / `agent_closed`, final at once) > last turn failed
  (`FAILED` / `structural`) > after the window: a pending HITL approval or a negative rating (`ENDED_QUIETLY`), an open
  question (`ABANDONED` / `inferred`), else `ASSUMED_RESOLVED` / `silence` (or `ENDED_QUIETLY` with the assumption off).
  Inside the window a session is `OPEN` (or carries its explicit outcome `provisional`); a turn after the window starts
  a new session. Only interactive origins are judged; the others are counted with no outcome. `endReason` is never read.
- **Outcome store** `IHealthOutcomeStore` — MongoDB `healthoutcomes`, PostgreSQL `health_outcomes`, key
  `(conversationId, sessionNo)`; `sessionNo` is the session's first step index, so it is stable under retention.
- **The evaluation** (`HealthOutcomeEvaluator`) runs as the first step of every `health-rollup` pass (≤ 45 s), under
  the same schedule claim — no second system job or lease. An incremental feed over turn rows written since a watermark
  (`conversationsUpdatedBetween`, paged by each conversation's newest write so a pass can stop anywhere), starting at
  the retention on the first pass (backfill), plus sessions due because their window closed or a rating arrived
  (`ConversationRatingSaved` → `markDue`). Reads only turn rows and ratings. Outcomes are counted from their own rows, so
  no day is marked dirty.
- **`report_conversation_outcome`** (`ReportConversationOutcomeTool`, `@ConversationBound`): offered by
  `BuiltinToolsProvider` when the version has `selfReportTool` and the task has built-ins (and whitelists it, if it has a
  whitelist). Writes the outcome only — no reason text — to `health:self_reported_outcome`; `HealthTurnFactory` copies
  it into the new turn-row field `selfReportedOutcome` (Postgres column added with `ADD COLUMN IF NOT EXISTS`).
- **Measures**: `resolutionRate` with the four-part breakdown (`confirmed`, `selfReported`, `agentClosed`, `silence`,
  shares of `n` adding up to the rate), `escalationRate`, `abandonmentRate`, `deflectionRate`, `outcomeCoverage` —
  over engaged, closed sessions of judged origins; the rates are withheld (`value: null` + hint) below
  `eddi.health.min-outcome-coverage` (0.5). `costPerResolvedSession`. Adoption `sessions` / `engagedSessions` (and the
  portfolio's sessions) are real sessions now. Series and compare pick them up.
- **REST**: `GET /agents/{agentId}/health/sessions` (EDIT + conversation review on the version, else
  `403 {"reason": "FORBIDDEN" | "REVIEW_DISABLED"}`; no user ids) and `GET /agents/{agentId}/health/outcome-suggestions`
  (VIEW; name heuristics). **MCP**: `list_agent_health_sessions`, `suggest_outcome_mappings`; `read_agent_health`
  describes effectiveness.
- **GDPR / retention**: `HealthOutcomeGdprParticipant` (`agentHealthOutcomes`) erases and exports by user; a
  conversation delete removes its sessions; the evaluation honours the erasure fence; the retention sweep and the orphan
  sweep cover outcome rows.
- **Metrics**: `eddi.health.outcomes.sessions`, `eddi.health.outcomes.failures` (on the rollup panel of the Full
  dashboard).
- **Platform Operator**: the two GET endpoints allow-listed, the health prompt teaches sessions, evidence, coverage, the
  four-part split and the silence caveat; operator revision 62; the OpenAPI snapshot gains both operations.

### Design decisions

- **One job, not two**: the evaluation needs the same single-node guarantee and cadence as the rollup; a sibling job
  would add a schedule, a claim and a lease for nothing.
- **Outcomes counted from their own rows**, like sessions and users from turn rows, instead of a daily aggregate: the
  rows are small, indexed by `(agentId, day)`, and a recount needs no dirty-day bookkeeping.
- **Engagement against the conversation's first output**, not the session's: a person who comes back the next day and
  asks one question has replied to the agent.
- **No reason on the self-report tool**: a reason is model-written text about the conversation; health rows hold none.

### Not done

- The "mostly assumed from silence" status reason (H4) — the breakdown exposes the shares it needs.
- `healthConfig` interactive-origin overrides (plan §4.5); `externalRefs` / `outcomeEvents` are H5.

**Files:** [`HealthSessionEvaluator.java`](../../src/main/java/ai/labs/eddi/engine/health/HealthSessionEvaluator.java),
[`HealthOutcomeEvaluator.java`](../../src/main/java/ai/labs/eddi/engine/health/HealthOutcomeEvaluator.java),
[`IHealthOutcomeStore.java`](../../src/main/java/ai/labs/eddi/engine/health/IHealthOutcomeStore.java),
[`HealthConfig.java`](../../src/main/java/ai/labs/eddi/configs/agents/model/HealthConfig.java),
[`ReportConversationOutcomeTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/impl/ReportConversationOutcomeTool.java)
