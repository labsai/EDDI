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
