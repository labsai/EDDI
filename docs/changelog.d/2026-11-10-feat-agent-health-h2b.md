## ✨ feat(health): Agent Health series, version compare, change markers and the portfolio — REST, MCP, operator (2026-11-10)

**Repo:** EDDI (`feat/agent-health-h2b`, on top of `feat/agent-health`)

### What changed and why

Phase H2b of [`planning/agent-health-plan.md`](../../planning/agent-health-plan.md): the turn facts of H2a become
views an agent's maintainers can read. Documented in [`docs/agent-health.md`](../agent-health.md); the wire shapes
are [`planning/agent-health-api.md`](../../planning/agent-health-api.md), implemented as written.

- **Daily rollup.** Every turn-row write notes the day the row is *stored* on as dirty (a resumed turn keeps its
  first day; an undo marks the discarded executions' days), flushed every 5 s to `healthdirty` / `health_dirty`
  so a busy agent's entry is not a hot document. The `health-rollup` system schedule (heartbeat,
  `eddi.health.rollup-interval` = 5m, routed by `ScheduleFireExecutor` like `alerts-maintenance`, reserved over
  REST) recomputes each dirty agent-day from its turn rows with `HealthTurnAggregator` and **replaces** its rows in
  `healthdaily` / `health_daily`, clearing the entry only if its token is unchanged — so a write during the
  recompute keeps the day dirty. Days older than the turn retention are dropped from the queue, never recomputed
  (that would wipe rows nothing can rebuild). Daily rows hold no personal data and are kept
  `eddi.health.daily-retention` (730d). `HealthDirtyDays.markRange` is the entry point for later mapping changes.
- **Aggregates** gained the agent/platform failure split, platform-affected turns, token turns, fixed duration
  buckets (approximate p95) and Apdex counts (`eddi.health.apdex-t`, 4s).
- **Change markers** (`healthmarkers` / `health_markers`, append-only, because `DeploymentInfo` has no
  timestamps): written on a deploy that reached READY, on global-variable writes and on snippet saves; plus a
  derived `VERSION_FIRST_TURN` from the daily rows. Marker writes never fail the change.
- **Measures** (`HealthMeasures`, pure): reliability, efficiency, adoption, quality from ratings, effectiveness as
  `value: null` until outcomes (H3), business empty; Wilson intervals, significance against the previous period,
  bands from `HealthDefaults`, `GREY` below the minimum sample. `status` stays null until H4.
- **REST**: `GET /agents/{agentId}/health`, `/health/series`, `/health/compare`, `GET /health/agents`.
  **MCP**: `read_agent_health`, `list_agent_health`, `compare_agent_versions_health` (tier admin/editor/viewer).
- **Platform Operator**: the five reads (including the existing `GET /agents/{agentId}/usage`) are allow-listed
  and a self-contained prompt block teaches how to answer "why is agent X worse?"; revision bumped.
- **Metrics**: `eddi.health.rollup.{duration,days,rows,failures,pending,lag}` and a dashboard row.

### Design decisions

- **People and sessions are counted from the turn rows, in the database** — distinct users and engaged sessions
  cannot be added up across days, and a per-day user-id set in the daily rows would put personal data into rows
  kept two years. So adoption reaches back as far as the turn rows (90 days) and says so beyond; reliability and
  efficiency come from the daily rows and reach two years.
- **Agent vs. platform** (`HealthAttribution`): platform classes are `TRANSIENT`, `RATE_LIMITED`, `TIMEOUT`. A
  failed turn is the platform's when interrupted, stopped by the tenant budget, or ended by unrecovered failures
  that are all of a platform class; everything else (pipeline errors, its own deadline, cost ceiling, auth/quota
  of its key, bad request, context too long, unknown model) is the agent's. The platform rate counts recovered
  failures too.
- **Significance**: pooled two-proportion z-test for rates, Welch z with the exact variance for Apdex, the
  conditional Poisson test for counts; both periods must reach the minimum sample. Percentiles and averages
  without stored variance are never called significant.
- **p95 is approximate** (interpolated in fixed buckets) and labelled so.
- **Portfolio "attention"** until H4: significant worsening of the key measures first, then worst band, then
  traffic. Platform incidents: a failure class hitting ≥ 3 of the listed agents on the same day (≥ 10 turns,
  ≥ 3 failed attempts, ≥ 5 % of turns), consecutive days merged — over the caller's viewable agents only.
- **Permissions** as plan §6.4: VIEW on the agent for everything; the quality section follows the ratings rule
  exactly (admin/editor, EDIT unless seeing everything) and is `restricted` otherwise.
- **Preset auto-selection**: an agent with interactive traffic (or none) is `support`, else `task`, which picks
  the default origins.

### What's next

H3 (sessions and outcomes) fills the effectiveness section and `costPerResolvedSession`; H4 adds profiles,
presets, status and reasons.

## 🐛 fix(health): H2b review — no lost dirty marks, bounded scans, like-for-like significance (2026-11-10)

**Repo:** EDDI (`feat/agent-health-h2b`, after merging the updated `feat/agent-health`)

### What changed and why

- **No lost dirty marks.** `HealthDirtyDays.note` adds inside `ConcurrentHashMap.compute` and the flush takes a set out with
  `remove`, so a day can no longer land in a set the flush already took (a concurrency test proves every noted day is
  marked). The recorder flushes after its worker drained at shutdown, and each rollup pass first **self-heals**: it
  re-marks the (agent, day)s of turn rows written since a durable watermark (`healthrollupstate` / `health_rollup_state`),
  so a node killed before its flush leaves no day stale.
- **Erasures and deletes re-roll.** A GDPR erasure, a conversation delete, the orphan sweep and the recorder's
  post-write cleanup read the (agent, day)s of the rows they delete and mark them dirty.
- **Bounded reads.** The views cache what they read for `eddi.health.cache-ttl` (60 s), per query and agent, never per
  caller; the portfolio counts every agent's traffic of the window and the baseline in one pass
  (`IHealthTurnStore.portfolioSessions`) instead of four.
- **Like-for-like statistics.** A count measure's baseline is scaled to the current period's exposure (today counts its
  elapsed part), and its significance uses the Poisson test at unequal exposure — no "significant drop" every morning.
  The returning-user look-back is a fixed 30 days per window, and the share is never called significant.
- **Rollup robustness.** A failing day is deferred to the back of the queue and dropped after five attempts; the clear of a
  day past the retention is guarded. The backlog gauges read the shared queue when scraped (cached 10 s), so every node
  reports the cluster's backlog. A changed `eddi.health.rollup-interval` updates the schedule at startup.
- **`dataThrough`** ignores days marked less than one rollup interval ago.
- **Markers** are tenant-scoped (a global variable's tenant; agents see `default` and their space's tenant), coalesced per
  kind, tenant and hour, and read newest first with separate limits for the agent's own and deployment-wide ones.
- **Apdex** names its T in the label; a period mixing T values is GREY with a hint.
- **Distinct users** count every user id of the rows in both stores (a conversation with two users counts both).
- Contract notes in `planning/agent-health-api.md` §12 (count baselines, Apdex label, cursor, markers); docs updated.

## 🐛 fix(health): H2b review follow-ups — marker ids, deletion marks, first turns, portfolio retention (2026-11-10)

**Repo:** EDDI (`feat/agent-health-h2b`)

- Coalesced marker ids are `g-` plus 40 hex characters of a SHA-256 of kind, tenant and hour: fixed length, so a long
  tenant id no longer overflows the Postgres id column.
- Deletion marks (erasure, conversation delete, orphan sweep, the recorder's post-write cleanup) are written to the dirty
  store at once (`HealthDirtyDays.markDeleted`), falling back to the debounced queue only when that write fails: the
  self-heal pass cannot find deleted rows.
- The days of a conversation's rows are read best effort; a failed lookup never skips the delete.
- A version's first turn (markers, compare windows) ignores `agent-test` rows unless they are asked for.
- Portfolio: sessions are `null` with a hint when the period reaches past the turn-row retention, and `sessionsDelta`
  when the baseline does; `sort=traffic` falls back to daily-row turns.

## 🐛 fix(health): deleted rows' days come with the delete; unknown days re-roll the agent (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h2b`)

- `IHealthTurnStore.deleteConversationReturningDays` deletes a conversation's rows and answers their (agent, day)s:
  PostgreSQL `DELETE ... RETURNING agent_id, day`; MongoDB reads the pairs just before the delete and deletes even when
  that read fails (`HealthDeletedRows.agentDays` is then `null`).
- Unknown days (`HealthDirtyDays.markDeletedRows`): every retained day of the conversation's agent is marked dirty, with
  a WARN. The purge reads the agent from the conversation before deleting its snapshot; the recorder uses the row's agent.
- Orphan sweep: the candidate read returns each conversation's agent ids; an orphan whose rows name no agent is kept for
  the next sweep (WARN with the count) rather than deleted blind.
- Deletion marks retry the write once before falling back to the in-memory queue, and WARN when they do. The residual
  (both attempts fail, then the node stops before the next successful flush) is documented: those days keep the deleted
  counts until re-rolled for another reason or `eddi.health.daily-retention`.

## 🐛 fix(health): a GDPR erasure re-rolls the user's days even when they cannot be read (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h2b`)

- `IHealthTurnStore.deleteByUserIdReturningDays` deletes a user's rows and returns their (agent, day)s
  (PostgreSQL `DELETE ... WHERE user_id = ? RETURNING agent_id, day`; MongoDB reads the pairs, then `deleteMany`, and
  returns `null` days if that read fails). `HealthTurnGdprParticipant.erase` uses it instead of a separate lookup.
- Unknown days: every retained day of the user's agents is re-rolled (`HealthDirtyDays.markErasedRows`). The agents
  come from the conversation store. `HealthErasureFence` notes them at step 0, before the cascade deletes the
  conversations; this is best effort and never fails the step, and the noted agents expire with the fence. If nothing
  was noted, the participant reads the conversation store again.
- Nothing known: a WARN, and the daily rows keep the counts until `eddi.health.daily-retention`. This is not a failed
  step, which would hold back the participants that run last although the personal data is gone.

## 🐛 fix(health): self-heal finds discards of old rows; the re-roll limit of old daily rows is stated (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h2b`)

- An undo that discards an existing row from an earlier day now sets that row's discard time once, when it flips
  (`discardedAt` / `discarded_at_ms`, the field markers already use). `updatedAt`, the write-ordering guard, is
  untouched. The self-heal read (`forEachAgentDayUpdatedSince`) matches rows written **or** discarded in its window,
  so a lost in-memory dirty mark for that day is recovered. PostgreSQL has a new partial index
  `idx_health_turns_discarded_rows` (`discarded_at_ms` where `day IS NOT NULL`); the column already existed. MongoDB
  sets the field with a pipeline update and uses its existing sparse index.
- Docs (`agent-health.md`, `gdpr-compliance.md`): daily rows older than `eddi.health.facts-retention` can no longer
  be recomputed, so an erased user's or deleted conversation's contribution stays in those aggregates until
  `eddi.health.daily-retention`. Those rows hold no user id or content.

## 🐛 fix(health): a purge that knows neither agent nor days keeps the rows for the orphan sweep (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h2b`)

- `IHealthTurnStore.deleteConversationIfDaysKnown` deletes nothing, and returns `null`, when the days cannot be read.
  That happens only on MongoDB; PostgreSQL's `DELETE ... RETURNING` always has them.
  `ConversationPurgeService.deleteHealthTurns` uses it when the conversation's agent lookup failed. Without either,
  the rows are kept with a WARN. The next orphan sweep, whose candidate read names the agent, deletes them and
  re-rolls that agent's retained days. Kept rows outside `eddi.health.orphan-sweep-window` expire with
  `eddi.health.facts-retention`.
- Docs and the `HealthDirtyDays` comment now name the in-memory retry window as the one remaining limit: both writes
  of a deletion mark fail, then the node stops before the next successful flush.

## 🐛 fix(health): no rollup day is dropped or skipped by the self-heal (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h2b`)

- A day whose rollup keeps failing stays in the dirty queue, retried every pass (a WARN past five failures), instead
  of being dropped after five. A historical day may have no later write to mark it again, and the self-heal is
  already past its rows, so a dropped day kept stale counts until `eddi.health.daily-retention`.
- The self-heal no longer moves its watermark past pairs it did not mark. A window with more than 10,000 (agent, day)
  pairs is halved until one fits (down to one second), and the watermark moves only to the end of that part; the
  rest is the next pass's.
- A turn row that reaches the store more than 30 seconds (half the self-heal grace) after its capture time marks its
  day in the dirty store at once (`HealthDirtyDays.markLateWrite`, with the deletion marks' retry and fallback),
  not on the five-second flush. The self-heal finds rows by capture time and may already be past such a row, so a
  mark lost with the node could not be found again.
