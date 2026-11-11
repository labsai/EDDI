## 📝 docs(planning): Agent Health plan (2026-11-10)

**Repo:** EDDI (`feat/agent-health`)

### What changed and why

[`planning/agent-health-plan.md`](../../planning/agent-health-plan.md) proposes **Agent Health**: a per-agent
status with reasons built from turn facts, conversation outcomes, ratings, cost and reliability, plus business
outcome events from other systems matched by the agent's own references (order id, ticket id). It surveys how
Intercom Fin, Zendesk, Agentforce, Langfuse, Gainsight, Datadog SLOs and Apdex measure this, lists what EDDI
already records, and phases the work H0–H7.

The plan was revised twice before implementation: a self-review against the code and an independent review.
Both are recorded in its §0 with the evidence for each change.

### Design decisions

- **Status with reasons, not a single score** — a weighted index exists only within a preset.
- **Outcomes are evidence-labelled** — confirmed, agent-closed and silence-assumed resolutions are always
  shown split.
- **Health is derived and not billing-grade** — it never changes conversations; quotas and cost ceilings stay
  the authoritative counters.
- The eight product decisions settled with the maintainer are in the plan's §9.

## ✨ feat(health): Agent Health turn facts — one row per turn execution, recovered LLM failures on the step (2026-11-10)

**Repo:** EDDI (`feat/agent-health`)

### What changed and why

Phase H0 + H2a of the [Agent Health plan](../../planning/agent-health-plan.md). H0 settled the open items
from the code (the plan's §9 now has an "H0 decisions" table); H2a records the **turn facts** every later
health view is computed from. Documented in [`agent-health.md`](../agent-health.md).

- **One row per turn execution** — `healthturns` (MongoDB) / `health_turns` (PostgreSQL), keyed by
  `(conversationId, executionId)` (`stepIndex` is stored on the row, not part of its key) and written by a
  guarded **replace**, never `$inc`. It holds agent,
  version (and the version moved from), user, origin, UTC day, the step's cost / discarded cost / tokens
  (the same `audit:*` keys the ledger records), duration, `ErrorKind`, pause, every failed LLM attempt with
  its recovery, fallback, tool calls and failures, guardrail trips, user input, output, a question-mark
  heuristic and the actions. No text.
- **`executionId`** — `health:execution_id`, minted by `Conversation` at the end of a step's first
  execution (beside `recordAgentVersion`, so step data keeps its positions). A HITL resume or rerun keeps
  it and replaces its row; a say after an undo gets a new one, and the undone executions are listed on the
  new step (`health:discarded_executions`) and marked `discarded`. Spend stays equal to `conversationCost`.
- **Collection** — `HealthTurnRecorder` on its **own** single-thread executor with a bounded queue
  (`eddi.health.queue-capacity`, default 10,000): it copies the values on the turn's thread and never blocks
  or throws. Hooked into `ConversationStepRunner.waitForExecutionFinishOrTimeout` (every say, rerun and
  resume — clean, failed and paused) and the inline `CONVERSATION_START` turn in `startConversation`, which
  never reached that hook. The alert path is unchanged; `eddi.alerts.enabled=false` does not stop health.
- **Recovered LLM failures** — `health:llm_failures` on the step: `{class, model, recovery}` with
  `retry` / `cascade` / `fallback` / `none`. Recorded by the cascade's existing `recordFailure`, the LLM
  task's model-phase `catch` (`LlmFailureRecords.settle`) and, for same-model retries, a thread-bound
  `RetryObserver` the retry loop notifies (carried onto cascade step threads). Recovery behaviour unchanged.
- **Origin** — classified per turn from the stored user id and the step's own context
  (`HealthOrigins`): `agent-test`, `group-member`, `delegated`, `schedule`, `a2a`, `email`, `slack`,
  `webhook`, `v1`, `mcp`, `api`.
- **GDPR** — `HealthTurnGdprParticipant` (`agentHealthTurns`) erases by user id and exports the rows;
  `ConversationPurgeService` deletes a deleted conversation's rows (best effort).
- **Retention** — `eddi.health.facts-retention=90d`, a daily idempotent `@Scheduled` sweep
  (`HealthTurnRetention`; why not a system job is in its Javadoc).
- **Metrics** — `eddi_health_recorded_total`, `eddi_health_dropped_total{reason=queue_full|store_error}`,
  `eddi_health_queue_depth`; on the Full Metrics dashboard and in [`metrics.md`](../metrics.md).
- `HealthTurnAggregator` adds rows up per (agent, version, origin, day) — store-agnostic, for the H2b rollup.

**Files:** new package [`engine/health`](../../src/main/java/ai/labs/eddi/engine/health/) (model, store
interface, Mongo and Postgres stores, recorder, factory, origins, execution ids, aggregator, GDPR,
retention, settings, metrics); [`LlmFailureRecords.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmFailureRecords.java),
[`RetryObserver.java`](../../src/main/java/ai/labs/eddi/configs/shared/RetryObserver.java); small hooks in
`Conversation`, `ConversationStepRunner`, `ConversationService`, `CascadingModelExecutor`, `LlmTask`,
`RetryConfiguration`, `ConversationPurgeService`, `DataStoreProducers`, `MemoryKeys`; docs
[`agent-health.md`](../agent-health.md), `configuration-reference.md`, `metrics.md`, `gdpr-compliance.md`,
`SUMMARY.md`, the Full Metrics dashboard.

### Design decisions

- **Origin per turn, not stored once at start** — start paths pass contexts the integrations chose (A2A and
  MCP none), so a stored-at-start value needed nine call-site changes; classification over stored step data
  is deterministic and recomputable. A conversation's origin (H3) is its first non-`api` row.
- **Execution id at the end of the step** — writing at the start would shift the positions detailed
  snapshots are read by.
- **Discard marks come from the new step**, collected where the redo cache is dropped — the same point
  `audit:discarded_cost` arises — so no undo hook in `ConversationService` was needed.
- **Retry capture is thread-bound** because the retry loop is shared with MCP and knows no conversation; with
  nothing bound it does nothing.

### Review fixes (same entry, before merge)

- **Tool outcomes** — `ToolLoopRunner` marks a `tool_result` trace entry `failed` (ran and failed, as
  `ToolOutcomeClassifier` reads it) or `notExecuted` (never ran). Rows count `toolCalls` (ran),
  `toolFailures` (ran and failed) and `toolRefusals` (never ran — quotas, budgets, the deadline, approval
  caps, credential-reference checks); `tool_call` entries are no longer counted.
- **Unsettled turns** — an abandoned wait (timeout, interrupted wait), a cancelled, interrupted or
  deadline turn is inserted only, never replaces a row and discards nothing (a cancelled say after an
  undo used to mark the surviving undone execution discarded for good). A step not stamped yet gets a
  fresh id per capture; `legacy-<n>` is only for a step that finished an execution before Agent Health.
- **Write rules** — identity `(conversationId, executionId)`; `day` and `timestamp` first-recorded (a
  resume after midnight stays on its day), a new `updatedAt` orders writes (a stale one is ignored), and
  `discarded` is sticky with a marker when the discard arrives before the row. MongoDB uses one
  pipeline update; PostgreSQL `COALESCE`, `OR` and a `WHERE` guard on the conflict update.
- **Retry attribution** — retries are reported only inside the task's own model calls
  (`RetryObserver.modelCall`, opened by `AgentExecutionHelper`); judge, summarisation, truncation, MCP
  and sub-agent retries are not attributed to the model. `reviseAnswer` (HITL REVISE) observes retries
  like `execute`.
- **Cascade** — a cascade that ends in a failure settles every entry it recorded to that failure's
  outcome (`none` / `fallback`), so a failed turn is not "partly recovered".
- **Integration tests** — `MongoHealthTurnStoreIT` and `PostgresHealthTurnStoreIT` run a shared
  `HealthTurnStoreContract` (replace, first-recorded day, stale writes, insert-only, sticky discard and
  early markers, streaming, every delete, idempotent DDL).
- Smaller: the recorder starts eagerly (gauge registered at boot), a row offered after shutdown counts
  `reason="shutdown"`, the Mongo stream closes its cursor, Postgres binds `LocalDate` directly.

**Review fixes (PR #1386).** A same-model retry is now settled when its retry loop ends:
`RetryConfiguration.executeWithRetry` opens a `RetryObserver.loop()` on every exit path, and a loop's retries
are `retry` only when a later attempt succeeded — a call whose attempts all failed no longer counted as
recovered; its retries take the outcome of the failure that ended it (`cascade`, `fallback` or `none`, via the
cascade's record and `LlmFailureRecords.settle`). `StreamingLegacyChatExecutor` reports its own retries the
same way (`TIMEOUT` for an empty timeout, the classifier's class for a provider error). `toolRefusals` also
counts the refusals written instead of a `tool_result` (`tool_repeat_refused`, `tool_call_capped`,
`tool_not_activated`, `hitl_self_conversation`, `hitl_self_ungating`, `hitl_request_changed`,
`hitl_not_executed`, `hitl_rejected`). `MongoHealthTurnStore.upsert` retries the guarded upsert once on a
duplicate key, so the newer of two concurrent first writes is not dropped (contract test with eight concurrent
writers). [`agent-health.md`](../agent-health.md) now says a resumed turn changes its day's rollup, that a turn
keeps at most 20 failure entries, and that turn rows outlive conversations when `eddi.health.facts-retention` is
the longer setting (the conversation-retention sweep does not delete them; erasure does).

**Second review round (PR #1386).**
- **Queued rows no longer outlive an erasure or a conversation delete** (barring the documented limits: a node
  that missed the cluster stop, a check that cannot answer, a failed clean-up; such a row stays until
  retention, with a WARN). A row waiting on the health
  worker's queue could reach the store after a GDPR erasure or a purge had already deleted the user's or the
  conversation's rows.
  - `HealthErasureFence`, a `UserErasureParticipant` modelled on `AlertErasureFence`, is raised at erasure
    step 0 on every node through the existing `gdpr-stop` broadcast. While it is up (15 minutes) the worker
    writes none of the user's rows or discard markers; such drops are counted as
    `eddi_health_dropped_total{reason="erased"}`.
  - After each write the worker checks again. If the user is now fenced, or the conversation no longer exists
    (one primary-key read on the worker), it deletes the conversation's rows. Both deleters remove the
    conversation before its health rows, so this holds on whichever node queued the row.
- **Uncommitted turns are recorded unsettled.** The say and resume paths now tell the health hook whether the
  turn's snapshot was stored. A lost pause compare-and-store, a conflict, or a fenced or failed write is
  recorded unsettled: insert-only, no discards, never as paused.
- **Docs.** The row key is documented as `(conversationId, executionId)`.
- **A durable second chance for rows that land after a delete** (third round). If the worker's post-write
  check or clean-up failed, such a row used to stay until retention (90 days). The daily
  `health-turn-retention` job now also runs an **orphan sweep**:
  - It reads the distinct conversations of rows and discard markers written within
    `eddi.health.orphan-sweep-window` (48h), using new `updatedAt` indexes, and checks them against the
    conversation store in batches of 500.
  - It deletes the rows of conversations that no longer exist, counted as
    `eddi_health_dropped_total{reason="orphaned"}`. Erasure deletes the user's conversations too, so this
    covers erasures as well.
  - It never deletes on uncertainty, and it is capped by `eddi.health.orphan-sweep-max`.

  Also in this round:
  - A pause that a cancel converted after it was stored is no longer recorded as `paused`.
  - The worker WARNs (rate-limited) when the conversation store cannot answer its post-write check.

### What's next

H2b (rollups, series, version compare, change markers — the marker store the H0 table says must be
added) and H3 (outcomes) can start. A load test under `scripts/loadtest/` is still open from the H2a row.
