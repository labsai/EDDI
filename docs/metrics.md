# Metrics & Monitoring

E.D.D.I exposes comprehensive metrics via [Micrometer](https://micrometer.io/) in Prometheus format, covering conversations, tool execution, caching, rate limiting, cost tracking, multi-agent group discussions, scheduled triggers, tenant quotas, audit integrity, and JVM internals.

## Quick Start — Grafana Dashboards

E.D.D.I ships three dashboards, all auto-provisioned into Grafana by
`docker-compose.monitoring.yml`:

| Dashboard | UID | File | Shape | Use it for |
|-----------|-----|------|-------|------------|
| **Operations Command Center** | `eddi-ops` | `eddi-operations-dashboard.json` | 58 panels, KPI strip + 9 rows | The front door. Is the platform healthy, and if not, roughly where. |
| **Full Metrics Reference** | `eddi-metrics-all` | `eddi-full-metrics-dashboard.json` | 166 panels, 22 rows | Every meter E.D.D.I registers. Go here when the number you need is not on the ops dashboard. |
| **EDDI Observability** | `eddi-observability` | `eddi-grafana-dashboard.json` | 16 panels, 6 rows | The original dashboard: Coordinator Health, Pipeline Tasks, Tool Execution, Vault & Security, NATS, HTTP & JVM. |

The Full Metrics Reference covers **every `eddi.*` meter the codebase
registers**, and that is a checked property rather than an aspiration:
`MetricsDashboardCoverageTest` scans the registration sites in `src/main/java`
and fails the build if any meter is queried by no panel. Add a meter without a panel and
`./mvnw test` tells you, naming the meter and the file that registers it. The same
test parses all three dashboards and fails on a query naming a series no meter
produces (a counter without `_total`, a timer percentile with no buckets), on a
duplicate panel id, on a pinned datasource uid or scrape job, and on a rate KPI
that would read "No data" on a quiet system.

All rows but the first are collapsed; open the subsystem you care about. Two
template variables scope every dashboard that has them (the Operations Command
Center and the Full Metrics Reference): the Prometheus **data source** and the
scrape **job**, which lists the jobs that have `process_uptime_seconds`.

**Quiet is 0, not "No data".** Panels over a counter that has never moved fall
back to `0` (`… or on() vector(0)`); ratios and latencies, which have no honest
value without traffic, say why they are empty instead ("no tool calls (1h)",
"no samples yet").

**Alerts.** `docker-compose.monitoring.yml` also loads the shipped alert rules
(`docs/monitoring/eddi-alerts.yml`); see [Prometheus Alerts](#prometheus-alerts).

### Enable Monitoring

```bash
# Docker Compose
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d

# Or via the install wizard
./install.sh --with-monitoring     # Linux / macOS
./install.ps1 -WithMonitoring      # Windows
```

| Service    | URL                        | Credentials    |
|------------|----------------------------|----------------|
| Grafana    | http://localhost:3000       | admin / `GRAFANA_ADMIN_PASSWORD` (default `admin`) |
| Prometheus | http://localhost:9090       | —              |
| Metrics    | http://localhost:7070/q/metrics | —          |

The Grafana login is `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` — `admin`/`admin` for a plain `docker compose up` (the port is published on `127.0.0.1` only), a generated password in `.env` when the installers set up the stack. Log in with it, then open **Dashboards → EDDI** — the provisioned folder holding all three. Grafana's built-in Home is still the landing page; anonymous access is not enabled.

### Dashboard Sections

| Row | Title | Key Panels |
|-----|-------|------------|
| **KPI Strip** | _(always visible)_ | EDDI up, Uptime, Agents Deployed, Active Conversations, Messages/sec, Tool Success % (1h), Cache Hit % (1h), 5xx Ratio, Tool Cost (last 1h) |
| **Row 1** | Platform Overview & HTTP Traffic | Request rate by status (2xx/4xx/5xx), latency mean and peak, CPU usage, top 10 slowest endpoints |
| **Row 2** | Conversations | Start/end/processing rate, processing duration mean and peak, active gauge, undo/redo, start vs load latency, dead-lettered turns, pending approvals and the oldest one's age |
| **Row 3** | Tool Execution Engine | Success vs failure rate, per-tool execution duration, cached/rate-limited breakdown, per-tool call counts |
| **Row 4** | Tool Cache Performance | Hit rate %, hits vs misses, cache size, get/put duration |
| **Row 5** | Rate Limiting & Cost | Allowed vs denied, denied by tool, total cost, budget exceeded events, cost accumulation, cost by tool |
| **Row 6** | Multi-Agent Group Discussions | Started vs failed, failure rate gauge, discussion duration |
| **Row 7** | Scheduled Triggers | Poll/fire/failed, fire duration, claim conflicts, dead-lettered |
| **Row 8** | Tenant Quotas & Audit | Quota allowed vs denied, denied by type, audit entries dropped, tenant usage, audit queue depth vs capacity |
| **Row 9** | JVM & Infrastructure | Heap/non-heap memory, threads, GC, MongoDB pool, PostgreSQL Agroal pool, NATS messaging, turn executor load, Quarkus worker pool |

> **Database-agnostic**: Row 9 includes panels for both MongoDB (`mongodb_driver_pool_*`) and PostgreSQL (`agroal_*`). Whichever backend is active shows data; the other shows "No data". Both used to be empty on every backend: EDDI builds its own MongoDB client, which `quarkus.mongodb.metrics.enabled` never reached, and Agroal's meters were off. A pool listener on that client and `quarkus.datasource.metrics.enabled=true` now publish them.

### Full Metrics Reference — rows

`Overview` (open by default) · `LLM — Per-call latency, tokens and errors` ·
`Conversations` · `Coordinator & Lifecycle Pipeline` ·
`Tool Execution Engine` · `Tool Cache` · `Tool Rate Limiting` ·
`LLM — Model Cascade & Streaming` · `Guardrails, Counterweights & Masking` ·
`Human-in-the-Loop` · `Group Conversations & Standing Teams` · `Scheduling` ·
`Persistent Memory — Dream & Summarization` ·
`Integrations — MCP, A2A Identity, OpenAI-compatible API` ·
`Capability Registry & Connections` · `Secrets Vault` · `Tenancy, Quotas & Audit` ·
`Platform Operator` · `NATS JetStream` · `Backup — Export, Import & Sync` ·
`RAG Ingestion` · `Outbound HTTP` ·
`Runtime context (Quarkus / JVM built-ins)`

---

## Naming: what the exposition actually looks like

All metrics are served at `/q/metrics`. Micrometer mostly uses **dot notation** in
source (`eddi.tool.cache.hits`) and Prometheus renders it with underscores. The
rules below are worth knowing exactly, because guessing wrong yields a panel that
is silently empty rather than an error. They were verified against Micrometer
1.17.0 with `micrometer-registry-prometheus-simpleclient`, the registry Quarkus
pulls in.

| Meter | Source name | Prometheus series |
|-------|-------------|-------------------|
| Counter | `eddi.tool.calls` | `eddi_tool_calls_total` |
| Counter, name already ends in `_count` | `eddi.vault.errors.count` | `eddi_vault_errors_count_total` |
| Counter, name already ends in `_total` | `eddi_group_cost_ceiling_hit_total` | `eddi_group_cost_ceiling_hit_total` — **not** doubled |
| Timer | `eddi.pipeline.task.duration` | `_seconds_count`, `_seconds_sum`, `_seconds_max` |
| Distribution summary | `eddi.llm.cascade.confidence` | `_count`, `_sum`, `_max` |
| Gauge | `eddi_agents_deployed` | `eddi_agents_deployed` |
| Tag key with a dot | `.tag("task.id", …)` | label `task_id` |

Tag keys that are already camelCase stay camelCase — `connection.resolve.time`
carries `authType`, not `auth_type`. Only dots are rewritten.

### Timers do not publish percentiles

Every E.D.D.I timer exposes `_seconds_sum`, `_seconds_count` and `_seconds_max`.
Only **two** — `eddi.pipeline.task.duration` and `eddi.llm.request.duration` —
call `publishPercentileHistogram()`, so they are the only ones with a
`_seconds_bucket` series and therefore the only ones where `histogram_quantile()`
returns anything.

Quarkus' own `http.server.requests` publishes no buckets either, deliberately:
it is tagged by `uri`, `method`, `status` and `outcome`, and a percentile
histogram adds about seventy series to each of those combinations. The
dashboards chart HTTP latency as mean and peak instead.

```promql
# Mean over the interval — works for every timer
sum(rate(eddi_tool_execution_duration_seconds_sum[5m]))
  / clamp_min(sum(rate(eddi_tool_execution_duration_seconds_count[5m])), 1e-9)

# Peak — the decaying max, not p100 of the window
max(eddi_tool_execution_duration_seconds_max)

# A true quantile — ONLY for eddi_pipeline_task_duration and eddi_llm_request_duration
histogram_quantile(0.99, sum by (le) (rate(eddi_pipeline_task_duration_seconds_bucket[5m])))
```

`histogram_quantile` over any other EDDI timer returns an empty result, which
Grafana renders as "No data" — indistinguishable from an idle system. If you want
percentiles on a timer, add `.publishPercentileHistogram()` at its registration
site first; it is not free (one series per bucket per tag combination).

### One name, one tag shape

A `PrometheusMeterRegistry` keeps only the **first** tag-key shape registered
under a given metric name and silently drops every later one — no exception, no
log line. Registering `foo` untagged and then `foo{tenant,type}` means the tagged
series never reaches `/q/metrics` at all. (Re-verified for this page against
`micrometer-registry-prometheus-simpleclient` 1.17.1, the registry in the 6.5.0
image, both directly and behind the composite registry Quarkus wraps it in:
the second registration returns normally and the scrape shows only the first
shape. Micrometer's newer `micrometer-registry-prometheus` reports the conflict
differently, so re-check if Quarkus switches registries.)

So a metric must be registered with the same tag keys at every call site. Where
you want both a total and a breakdown, tag everything and aggregate at query time
with `sum(rate(...))` — do not add a second untagged counter under the same name.
`TenantQuotaService` had exactly this bug; `TenantQuotaServiceTest`
(`PrometheusExpositionTests`) now pins the exposition against a real scrape,
because a `SimpleMeterRegistry` tolerates the collision and will not catch it.

---

## Metrics Reference

### Conversation Metrics

```text
eddi_conversation_start_count_total         # Conversations started
eddi_conversation_end_count_total           # Conversations ended
eddi_conversation_processing_count_total    # Messages processed
eddi_conversation_load_count_total          # Conversations loaded from DB
eddi_conversation_undo_count_total          # Undo operations
eddi_conversation_redo_count_total          # Redo operations
eddi_conversation_agent_version_switch_count_total  # Conversations moved to another compatible version of their agent
eddi_conversation_generation_upgrade_count_total  # Conversations moved onto the current compatibility generation by upgradeconversations
eddi_conversation_store_conflict_count_total  # Writes refused: another writer changed the conversation first
eddi_turn_idempotency_total                  # Keyed (Idempotency-Key) duplicate requests; tag: outcome (replayed|waited|wait_timeout)
eddi_processing_conversation_count          # Currently active (gauge)
eddi_conversation_abandoned_running         # Turns the agent-timeout watchdog gave up on that are still running (gauge, normally 0)

eddi_conversation_start_duration_seconds    # Start latency (timer)
eddi_conversation_end_duration_seconds      # End latency (timer)
eddi_conversation_load_duration_seconds     # Load latency (timer)
eddi_conversation_processing_duration_seconds  # Processing latency (timer)
eddi_conversation_undo_duration_seconds     # Undo latency (timer)
eddi_conversation_redo_duration_seconds     # Redo latency (timer)
```

### Tool Execution Metrics

```text
eddi_tool_execution_success_total           # Successful executions
eddi_tool_execution_failure_total           # Failed executions
eddi_tool_execution_cached_total            # Cache-served executions
eddi_tool_execution_ratelimited_total       # Rate-limited executions
eddi_tool_execution_timeout_total           # Executions abandoned at their per-tool timeout
eddi_tool_execution_abandoned               # Timed-out workers still running, nobody waiting (gauge)
eddi_tool_repeat_refused_total             # Repeated identical failing calls refused without running (tags: source, reason)
eddi_tool_execution_duration_seconds        # Execution duration (timer)
```

All execution metrics support a `tool` label for per-tool breakdown:
```promql
rate(eddi_tool_execution_success_total{tool="weather"}[5m])
```

**Which call was slow — tracing.** The duration timer says a tool is slow on
average; a trace says which call, in which turn, and where the time went. With
tracing on (`QUARKUS_OTEL_SDK_DISABLED=false` plus an OTLP endpoint) every tool
call is an `execute_tool <tool>` span with `gen_ai.tool.name`,
`gen_ai.tool.call.id`, `eddi.tool.source` and, when it never reached the tool,
`eddi.tool.cache_hit=true` or `error.type=rate_limited`/`timeout`. While it runs,
EDDI sends that span's W3C `traceparent` to the httpcall, MCP (header and
`params._meta`), A2A or built-in-tool endpoint it calls, so a traced downstream
service shows up as its child. Switch the propagation off with
`eddi.tracing.propagate-outbound=false`. Backends (Jaeger, Tempo, Langfuse, Phoenix)
and the propagation rules are in the
[monitoring guide](monitoring/monitoring-guide.md#sending-traces-to-langfuse--arize-phoenix--jaeger--tempo).

### Tool Cache Metrics

```text
eddi_tool_cache_hits_total                  # Cache hits
eddi_tool_cache_misses_total                # Cache misses
eddi_tool_cache_puts_by_tool_total{tool}    # Cache puts (per tool; there is no untagged variant)
eddi_tool_cache_hits_by_tool_total{tool}    # Cache hits, per tool
eddi_tool_cache_misses_by_tool_total{tool}  # Cache misses, per tool
eddi_tool_cache_get_duration_seconds        # Get latency (timer)
eddi_tool_cache_put_duration_seconds        # Put latency (timer)
eddi_tool_cache_size                        # Current entries (gauge)
eddi_tool_cache_bypassed_total              # Calls that skipped the cache (per tool)
```

> `eddi_tool_cache_bypassed_total` counts tool calls where caching was enabled
> but no identity could be derived to scope the entry to, so the cache was
> skipped on both the read and the write side. A sustained non-zero rate means
> tool calls are running without a user id **or** a conversation id and are
> paying full tool cost every time — investigate the caller rather than widening
> the cache scope.

### Rate Limiting Metrics

```text
eddi_tool_ratelimit_allowed_total{tool="..."}  # Allowed calls (per tool)
eddi_tool_ratelimit_denied_total{tool="..."}   # Denied calls (per tool)
eddi_tool_ratelimit_remaining                  # Remaining capacity (gauge)
```

Per-tool and aggregate queries:
```promql
# Per-tool denied rate
rate(eddi_tool_ratelimit_denied_total{tool="weather"}[5m])

# Aggregate allowed rate across all tools
sum(rate(eddi_tool_ratelimit_allowed_total[5m]))
```

### Cost Tracking Metrics

```text
eddi_tool_calls_total                       # Total tool calls
eddi_tool_costs_accrued                     # Cumulative cost in USD since start (gauge)
eddi_tool_budget_exceeded_total             # Budget exceeded events
```

Per-tool breakdown — note the `_total`, which the exposition adds to every
counter. `eddi_tool_calls{tool="weather"}` without it matches no series and
returns an empty result rather than an error:

```text
eddi_tool_calls_total{tool="weather"}       # Calls per tool
eddi_tool_costs_total{tool="weather"}       # Cost per tool
```

> **The total-cost gauge was renamed to `eddi_tool_costs_accrued`.** It used to
> be registered as `eddi.tool.costs.total`, which the exposition renders as
> `eddi_tool_costs_total` — the same name as the `tool`-tagged counter above.
> The Prometheus registry keeps only the first meter registered under a name
> (see [One name, one tag shape](#one-name-one-tag-shape)), so one of the two
> never reached the scrape — the registration returns normally and nothing is
> logged; re-verified on the 6.5.0 registry. `eddi_tool_costs_total` now always means the per-tool counter; take the
> all-tools total from the gauge, or as `sum(eddi_tool_costs_total)`. A dashboard
> or alert written against the old gauge must switch to `eddi_tool_costs_accrued`.

### Group Discussion Metrics

```text
eddi_group_discussion_count_total           # Discussions started
eddi_group_discussion_failure_count_total   # Discussions failed
eddi_group_discussion_duration_seconds      # Duration (timer)
eddi_group_cost_dollars                     # Cumulative discussion cost (gauge)
eddi_group_cost_ceiling_hit_total           # maxCostPerDiscussion reached
eddi_group_facilitator_moves_total{move,outcome}  # Facilitator moves
eddi_group_member_pause_skipped_count_total # Member turns skipped instead of paused
eddi_group_continue_count_total             # Continuation rounds
eddi_group_followup_count_total             # Member follow-ups
eddi_group_close_count_total                # Conversations closed to further rounds
eddi_group_task_reworks_total               # Tasks VERIFY sent back for rework (maxReworks)
eddi_group_verify_unparseable_total         # VERIFY verdicts that could not be parsed (tasks auto-verified)
```

### Standing Team (Cadence) Metrics

```text
eddi_team_cadence_runs_started_total        # Cadence fires that started a discussion
eddi_team_cadence_runs_skipped_total        # Fires skipped (run still in flight, empty backlog, claim lost)
eddi_team_cadence_writebacks_total          # Completed runs written back to the backlog
eddi_team_cadence_claims_reclaimed_total    # Stale claims reclaimed after claim-ttl
```

### Scheduled Trigger Metrics

```text
eddi_schedule_poll_count_total              # Poll cycles
eddi_schedule_fire_count_total              # Schedules fired
eddi_schedule_fire_failed_total             # Fire failures
eddi_schedule_fire_skipped_total            # Fires the coordinator dropped without running the turn
eddi_schedule_fire_timedout_total           # Fires interrupted at eddi.schedule.fire-timeout (recorded FAILED, retried)
eddi_schedule_claim_conflict_total          # Claim conflicts (multi-instance)
eddi_schedule_fire_deadlettered_total       # Dead-lettered schedules
eddi_schedule_fire_duration_seconds         # Fire latency (timer)
eddi_schedule_firelog_pruned_total          # Fire-log rows removed by the retention sweep
```

`eddi_schedule_fire_skipped_total` is neither a success nor a failure. The
coordinator dropped the turn without consuming the input — the conversation was
already IN_PROGRESS or AWAITING_HUMAN — so the schedule is re-armed at its next
cadence with `failCount` untouched, and it will never dead-letter on skips
alone. A skip rate that stays high is therefore the one scheduling problem the
failure and dead-letter counters cannot show you: a
`conversationStrategy=persistent` heartbeat aimed at a conversation that is
never free (a human is chatting in it, or it is parked on a HITL approval) has
its message dropped every single cycle while every other metric stays green.

`eddi_schedule_firelog_pruned_total` counts rows, not sweeps. Flat while the
fire-log table keeps growing means either retention is switched off
(`eddi.schedule.fire-log-retention=0`) or the sweep is throwing — the poller
logs that failure at ERROR.

### Self-continuation Metrics

Turns an agent starts itself with the reserved `CONTINUE_CONVERSATION` action
(see [Long jobs](long-jobs.md)). Charted in the **Scheduling** row of the Full
Metrics Reference, since every continuation is delivered as a one-shot schedule.

```text
eddi_continuation_scheduled_total           # Continuations armed after a committed turn
eddi_continuation_run_total                 # Continuation turns that ran
eddi_continuation_refused_total             # Continuations ignored or stopped; tag: reason
```

`reason` is one of `disabled` (the agent sets no `maxContinuations`), `limit`,
`cost_ceiling`, `not_settled` (the asking turn failed, ended or paused),
`group_member`, `schedule_failed`, and — decided when the schedule fires —
`ended`, `awaiting_human`, `gone` (deleted or erased), `restricted` (GDPR Art.
18), `undeployed`, `interrupted`, `duplicate` and `owner_mismatch` (the schedule's
user does not own the conversation — a schedule the engine did not mint; alert on
any). Every reason except
`undeployed` ends the chain; an `undeployed` fire is retried with the schedule
backoff and dead-lettered like any failed fire. A steady `disabled` rate means a
rule emits the action on an agent that never opted in. `duplicate` counts
re-delivered fires that found their turn already ran — the at-least-once
delivery working as designed, not an error.

### Agent Test Metrics

```text
eddi_agent_tests_runs_total{status}         # Finished agent test runs, by PASSED / FAILED / ERROR
eddi_agent_tests_turns_total                # Turns the runs executed (each a real test-environment turn)
eddi_agent_tests_run_duration_seconds       # Wall time of a run (timer)
```

A run drives real conversations in the `test` environment, so its model and
tool cost also appear in the conversation and cost metrics. See
[Agent Testing](agent-testing.md).

### Tenant Quota Metrics

```text
eddi_tenant_quota_allowed_total             # Slot acquisitions granted (untagged)
eddi_tenant_quota_denied_total{tenant,type} # Quota denials, always tagged
eddi_tenant_quota_unavailable_total{tenant,type} # Refusals caused by the quota store failing, not by a limit
eddi_tenant_usage_conversations_total       # Conversation usage (per tenant)
eddi_tenant_usage_api_calls_total           # API call usage (per tenant)
eddi_tenant_usage_cost_total                # Metered USD charged to the monthly cost budget (per tenant)
eddi_tenant_cost_metering_total{outcome}    # Settle-time cost charges: recorded / over_budget / unavailable / error
eddi_llm_spend_refused_total{reason}        # Paid LLM calls skipped by the spend checks — a task's model call, a re-ask, a cascade escalation, a guardrail reprompt, a `classifier` or `judge` guardrail call (tenant_budget / tenant_accounting_unavailable / conversation_ceiling / tenant_check_error — the budget check threw, a defect). Tool calls are counted only when the tool loop stops on the conversation ceiling (conversation_ceiling) or the tenant budget check throws (tenant_check_error); a tool call refused because the tenant budget is spent or its accounting is unavailable returns an error to the model without incrementing it
```

`eddi_tenant_usage_cost_total` moves whenever a settled turn (or a Dream run) with
**priced** spend is charged to an enabled tenant quota — see
[Tenant Quotas → The monthly cost budget](tenant-quotas.md#the-monthly-cost-budget).
An unpriced model meters $0, so a flat line on a busy deployment usually means
the models carry no `inputPricePer1M` / `outputPricePer1M`. A rising
`outcome="unavailable"` means turns spent money the counter could not record at
the time — the quota store was down when they settled. The amount is carried on the
conversation (`audit:pending_cost`) and charged at its next settle.

Denials carry `tenant` and `type` (`conversation` / `api_call` / `agent` / `cost`);
aggregate at query time rather than expecting an untagged total:

```promql
# One tenant, one limit type
rate(eddi_tenant_quota_denied_total{type="cost", tenant="acme"}[5m])

# All denials
sum(rate(eddi_tenant_quota_denied_total[5m]))
```

> **Upgrade note.** Before this was fixed, the denial counter was *also*
> registered untagged, which — per [One name, one tag
> shape](#one-name-one-tag-shape) — meant the tagged series never reached
> `/q/metrics` and the per-tenant breakdown above did not work at all. After
> upgrading, Prometheus keeps the old label-less samples for its retention window,
> so breakdown queries should filter them out with `{tenant!=""}`. The shipped
> dashboard already does.

`eddi_tenant_quota_allowed_total` counts **slot acquisitions only**. The read-only
gates (`checkAgentQuota`, `checkCostBudget`) deliberately do not touch it, so
`allowed / (allowed + denied)` is not a true accept rate.

`eddi_tenant_quota_unavailable_total` is the *other* reason a request is refused:
the quota store could not answer at all — a driver failure in whichever store is
configured (a `MongoException` in `MongoTenantQuotaStore`, which is the default
backend, or a `SQLException` in `PostgresTenantQuotaStore`) — so the turn is
denied for safety without any limit having been reached. It covers **both** store
calls a gate makes: reading the tenant's configuration and incrementing the
counter. The read runs first, so on a full outage it is the one that fails —
which is why a failing read used to exit as an opaque `500` on MongoDB and to
bypass enforcement silently on PostgreSQL, while only a partial outage (reads up,
writes down) ever reached the counter. It used to be counted on `eddi_tenant_quota_denied_total`
and answered `429` with `Retry-After: 60`, so a database outage looked exactly
like a tenant burning through its allowance on the very graph you would use to
decide whether to raise a limit. It now answers `503`
(`quota_accounting_unavailable`) and carries the same `tenant` / `type` tags, so
the two can sit side by side:

```promql
# Infrastructure, not allowance
sum(rate(eddi_tenant_quota_unavailable_total[5m])) by (tenant)
```

### Coordinator Metrics

```text
eddi_coordinator_active_conversations       # Conversations with a live queue (gauge)
eddi_coordinator_queue_depth                # Total queued messages across all conversations (gauge)
eddi_coordinator_total_processed_total      # Messages processed since start
eddi_coordinator_total_dead_lettered_total  # Turns dead-lettered (failed after they started, or could not be scheduled)
eddi_coordinator_dead_letters               # Dead letters retained right now (gauge; in cluster mode the shared stream's count)
eddi_coordinator_dead_lettered_total        # The same count under the name the shipped alert rules use (both modes)
eddi_coordinator_dead_letters_retained      # The same gauge under the name the shipped dashboards use (both modes)
eddi_runtime_executor_active                # Conversation work running; tag: pool (turn|nested|managed)
eddi_runtime_executor_queued                # Conversation work waiting for a thread; tag: pool
eddi_runtime_executor_max_threads           # Worker pool size (quarkus.thread-pool.max-threads); tag: pool
eddi_turn_rejected_total                    # Turns refused before they were queued; tag: reason (in_flight_limit|conversation_queue = 503 capacity_exceeded, caller_timed_out = 408)
eddi_turn_skipped_total                     # Queued turns dropped unrun; tag: reason (caller_timed_out)
eddi_conversation_max_in_flight_turns       # The node's resolved eddi.conversation.max-in-flight-turns (gauge; heap-derived with auto)
eddi_datastore_pool_wait_seconds            # Wait for a pooled MongoDB connection, per checkout (timer; PostgreSQL: agroal_blocking_time_*)
eddi_datastore_pool_wait_timeouts_total     # Checkouts that gave up waiting for a connection
```

`queue_depth` rising while `total_processed` flattens is the signature of a
backlog: work is arriving faster than it drains. Both coordinators register the
same five meters; `dead_letters > 0` means a turn failed and is waiting for an
operator (list, replay or discard it — see [Coordinator Admin](coordinator-admin.md)).

`dead_lettered_total` counts turns that failed after they had started (alert
`EddiConversationTurnsDeadLettered`); it keeps counting through the
`eddi.coordinator.max-dead-letters` cap and through a purge, while
`dead_letters_retained` is what `GET /administration/coordinator/dead-letters`
lists. Both coordinators register them; in cluster mode the gauge is the shared
dead-letter stream's count, and the console at `/administration/cluster` lists it.

`queue_depth` counts turns waiting behind an earlier turn of the **same**
conversation. The executor gauges count work by where it runs: `pool="turn"` is
the coordinators' turns and `pool="nested"` their pipelines, both on virtual
threads; `pool="managed"` is the bounded worker pool, which now carries deploys,
retention sweeps and the parser endpoint, not turns. `queued{pool="managed"}`
above zero for minutes means that pool is saturated (alert
`EddiExecutorSaturated`). Quarkus' `worker_pool_*` meters do not see this work —
they count only what is dispatched through Vert.x.

Turns are bounded by `eddi.conversation.max-in-flight-turns` instead of by
threads: past it a turn is refused with 503 `capacity_exceeded` and counted in
`eddi_turn_rejected_total{reason="in_flight_limit"}` (alert
`EddiTurnCapacityReached`). A turn holds its place until its task has ended,
persistence included, and a new conversation's start turn counts too, so the
limit is reached slightly before `eddi_processing_conversation_count` shows it:
that gauge drops as soon as the client has its answer.

A refusal at the limit happens before the turn reads anything: the REST and SSE
entry points check capacity before the ownership check and the conversation
load, and a new conversation's start is checked before the access check, the
GDPR restriction read and the agent lookup. A node at its limit spends no
database round trips on turns it turns away, which matters most in a burst,
when every refused client comes back after `Retry-After`. A conversation with more than
`eddi.conversation.max-queued-turns-per-conversation` turns waiting refuses the
next one with `reason="conversation_queue"`. Under load the MongoDB pool is
where a turn's time goes; `eddi_datastore_pool_wait_seconds` shows how long
operations wait for a connection, and `eddi_datastore_pool_wait_timeouts_total`
how many gave up at the effective pool-wait limit: `waitQueueTimeoutMS` when the
connection string sets it, otherwise `eddi.mongodb.max-wait-time-ms` (10 s by
default; `0` leaves the driver's own 120 s).

`reason="caller_timed_out"` counts REST turns whose caller had already given up:
`systemRuntime.agentTimeoutInSeconds` had passed since the request arrived. In
`eddi_turn_rejected_total` the request had used up that time before it reached
the engine, and was answered `408` without queueing a turn; in
`eddi_turn_skipped_total` the turn was queued and dropped when its turn came
(`eddi.conversation.skip-turns-of-timed-out-callers`). Either one rising means
the node takes longer to start a turn than its callers wait: it is overloaded,
so add nodes or shed load earlier with a lower `max-in-flight-turns`.

### Pipeline Metrics

```text
eddi_pipeline_task_duration_seconds         # Per-task latency; tags: task.id, task.type
eddi_pipeline_task_errors_total             # Per-task failures; tags: task.id, task.type, error.type
```

`eddi_pipeline_task_duration` is one of the two EDDI meters publishing histogram
buckets (with `eddi_llm_request_duration`), so `histogram_quantile` gives a real
percentile for it. See [Timers do not publish percentiles](#timers-do-not-publish-percentiles).

### LLM Call Metrics

Emitted by `LlmTelemetryListener` for **every** LLM call, on every provider and on
both the synchronous and streaming paths. Until these existed, the only LLM meters
were the cascade ones below, which `LlmTask` reaches solely under
`if (cascadeActive)` — so an agent naming a single model, which is almost every
agent, produced no LLM latency, token or error signal at all.

```text
eddi_llm_request_duration_seconds           # Provider call latency (timer); tags: provider, model, outcome (success|error)
eddi_llm_tokens_total                       # Tokens consumed; tags: provider, model, type (input|output)
eddi_llm_request_errors_total               # Failed calls; tags: provider, model, error (exception simple name)
```

**Tag values are bounded.** `provider` is langchain4j's provider name (`OPEN_AI`,
`ANTHROPIC`, …) or, where langchain4j has no name for the provider and reports
`OTHER` (Jlama and HuggingFace, which used to share `provider="OTHER"`), EDDI's own
model type (`jlama`, `huggingface`). A self-hosted `openai-compatible` endpoint
reports its `serverName` (`vllm`, `llama-server`, …; default `openai-compatible`)
instead of `OPEN_AI`, bounded to 32 characters of `a-z 0-9 . _ -`. `model` keeps its own
series for the first 100 distinct model names a node sees, cut to 128 characters;
every name after that is tagged `model="other"`. A model name can be templated from
a property, and a property can be user input, so an uncapped tag was an unbounded
number of series. Spans keep the real name. A failed call's span carries the error
class and a secret-redacted, 256-character excerpt of the provider's message —
never the raw text or the stack trace, which can echo the request.

**These count attempts, not turns.** `AgentExecutionHelper.executeWithRetry`
re-enters the model on a retryable failure, and each entry dispatches the
listeners again. That is the right granularity for latency — you want the
distribution of actual provider calls — but it means `eddi_llm_request_errors_total`
counts failed *attempts*, and a turn that succeeded on its second try contributes
one error and one success.

**The duration timer publishes percentile buckets.** It is registered with
`publishPercentileHistogram()`, so a Prometheus scrape carries
`eddi_llm_request_duration_seconds_bucket` alongside `_count` and `_sum`, and the
p95 panel on the bundled dashboard works without any extra registry
configuration. A Micrometer timer publishes no buckets by default, and a
`histogram_quantile` query over a series that does not exist renders as an empty
panel — which reads as "no LLM traffic" rather than "this was never published".
The cost is one series per bucket per `provider`/`model`/`outcome`; the tag set is
bounded the same way `eddi_pipeline_task_duration_seconds` is, which makes the
same trade.

**Do not add these to the cascade meters.** A cascading task reports through both:
once here per step, and once through `eddi_llm_cascade_*` tagged by step. They
measure the same calls from different angles.

**`error` is the exception's simple name**, which is what separates a rate limit
from a timeout from a bad request on a dashboard. A wall-clock timeout configured
via the `timeout` parameter reports as `ChatTimeoutException`.

A matching span, `gen_ai.client.inference`, carries the OpenTelemetry GenAI
attributes (`gen_ai.provider.name`, `gen_ai.request.model`,
`gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`). Those conventions are
still Development-status upstream, so the span also carries
`eddi.semconv.schema_version` recording which revision the names came from. Spans
only reach a collector when OpenTelemetry is enabled — it is off by default, see
`quarkus.otel.sdk.disabled` in `application.properties`. The meters above are
always recorded.

### Model Cascade Metrics

Full guide: [model-cascade.md](model-cascade.md).

```text
eddi_llm_cascade_executions_total           # Cascade runs started; tag: agentMode
eddi_llm_cascade_escalations_total          # Moves to a costlier step; tag: reason (low_confidence|timeout|<error type>)
eddi_llm_cascade_accepted_step_total        # Which step answered; tag: step
eddi_llm_cascade_step_errors_total          # Per-step failures; tags: provider, type
eddi_llm_failure_total                      # Failed model calls by failure class: each failed cascade step, and each failed single-model task (a cascade's overall failure is not counted twice); tags: class (TRANSIENT, RATE_LIMITED, QUOTA_EXHAUSTED, AUTH, ...), model (the resolved name, not a ${vars:...} template)
eddi_llm_cascade_ceiling_exceeded_total     # Run cut short; tag: kind (cost|duration|spend — the conversation ceiling or tenant budget refused the next step)
eddi_llm_turn_deadline_exceeded_total       # A layer stopped because the turn deadline left no room; tag: stage (attempt|sleep|attempt_timeout|cascade|tool|httpcall|httpcall_retry)
eddi_llm_cancelled_total                    # An attempt / cascade step abandoned on timeout; tag: scope (attempt|cascade_step)
eddi_llm_cascade_tokens_total               # Tokens consumed; tag: provider
eddi_llm_cascade_cost_total                 # Dollars spent; tag: provider
eddi_llm_cascade_step_latency_seconds       # Per-step latency (timer); tag: provider
eddi_llm_cascade_confidence                 # Confidence scores (distribution summary — `_count`/`_sum`/`_max`, no `_seconds`)
```

**`accepted_step` is the metric that says whether cascading is working.** Mass at
`step="0"` means the cheap model is carrying the load, which is the entire point.
Mass at the last step means every turn pays for the cheap attempt *and* the
expensive one.

### LLM Circuit Breaker Metrics

Full guide: [langchain.md](langchain.md#circuit-breaker-skip-a-model-that-keeps-failing). Only recorded for tasks that set `circuitBreaker.enabled`.

```text
eddi_llm_circuit_total                      # State changes of a model's circuit; tags: state (open|half_open|closed), class (INVALID_OUTPUT|BAD_REQUEST|MODEL_NOT_FOUND|AUTH|QUOTA_EXHAUSTED — what opened it; none on a close)
eddi_llm_circuit_skipped_total              # Turns that did not call the model because its circuit was open; tag: class
eddi_llm_circuit_open                       # Gauge: circuits open or half-open on this node right now
```

Every `state="open"` is also one ERROR log line (`LLM circuit OPEN …`) naming the agent, version, provider, model, the class and EDDI's reason (never model output). Alert on `increase(eddi_llm_circuit_total{state="open"}[5m]) > 0`, or on `eddi_llm_circuit_open > 0` for a standing condition. The breakers are per node and in memory: with several nodes, each opens on its own traffic.

### Streaming Metrics

```text
eddi_llm_streaming_downgraded_total         # Fell back to a single chunk; tag: reason
eddi_llm_output_total                       # convertToObject reply parse outcome; tag: outcome (valid|repaired|invalid|empty|schema_mismatch)
eddi_llm_recovery_total                     # Recovery actions of the LLM task; tags: action, outcome, trigger
                                            #   action=fallback, outcome=served,  trigger=onError|validation   (a turn answered with the configured fallback)
                                            #   action=retry,    outcome=recovered|still_invalid|failed|skipped_budget|skipped_no_time|skipped_cost|skipped_spend|skipped_breaker,
                                            #                    trigger=empty|truncated|content_filter|invalid_json|context_too_long   (a same-model re-ask)
                                            #   action=escalate, outcome=invalid_output|timeout|error|retryable_error|low_confidence, trigger=<reply problem or failure class>   (a cascade step handed over)
                                            #   action=repair,   outcome=recovered, trigger=invalid_json       (a reply parsed only after fence stripping / extraction)
                                            #   action=circuit_skip, outcome=skipped, trigger=<class>          (a model with an open circuit was not called)
                                            # every action also writes one INFO line, "LLM recovery conversationId=... action=..." (see llm-resilience.md)
eddi_llm_streaming_no_partials_total        # Provider streamed, but emitted no partial tokens
eddi_llm_stream_timeouts_total              # EDDI abandoned a stream at its own backstop; tag: path (legacy|tool_loop)
eddi_llm_tool_context_evictions_total       # Exchanges dropped to fit the tool-context budget; tag: outcome (within_budget|still_over_budget)
```

`still_over_budget` means eviction ran and the context *remains* too large — the
turn proceeds degraded. A persistent non-zero rate is a configuration problem,
not a blip.

### Attachment Metrics

Full guide: [attachments-guide.md](attachments-guide.md).

```text
eddi_attachment_forwarded_total             # Attachments converted to LLM content
eddi_attachment_reinlined_total             # Extracted text stitched back into history
eddi_attachment_errors_total                # Drops, cap-skips and capability gates
eddi_attachments_quota_rejected_total       # Uploads refused by a quota; tag: scope (conversation, user)
```

### Outbound HTTP Metrics

Requests through the outbound HTTP facade: the built-in tools, the RAG crawler, OAuth token requests, attachment downloads, A2A peers and Slack (profile `default`), and remote agent sync (profile `internal-peer`). `httpcalls` and the LLM providers use their own clients and report nothing here until they migrate. Settings: [configuration-reference.md](configuration-reference.md#outbound-http-client).

```text
eddi_http_outbound_requests_total           # Exchanges, a redirect chain counted once; tags: profile, method, status (2xx..5xx|none), outcome (success|blocked|timeout|error)
eddi_http_outbound_duration_seconds         # Exchange time, to the end of a buffered body or the headers of a streamed one; tags: profile, outcome
eddi_http_outbound_blocked_total            # Refused by policy; tags: profile, reason (ssrf|cap|redirect)
eddi_http_outbound_bytes_total              # Response body bytes read into memory or a file; tag: profile
```

Target hosts are never a tag: most targets are chosen by a user or a model, and a tag per URL would be unbounded. A rising `reason="ssrf"` rate means something keeps steering tools at internal addresses.

### HITL Metrics

Full guide: [hitl.md](hitl.md).

```text
eddi_hitl_pause_count_total                 # Turn-level pauses; tag: surface
eddi_hitl_resume_count_total                # Turn-level resumes; tag: surface
eddi_hitl_timeout_count_total               # Approvals that expired unanswered
eddi_hitl_tool_pause_count_total            # Per-tool-call pauses
eddi_hitl_tool_resume_count_total           # Per-tool-call resumes; tag: verdict
eddi_hitl_tool_guard_count_total            # Guard evaluations; tag: guard
eddi_hitl_rule_matched_total                # Approval rules that matched; tag: match
eddi_hitl_revision_total                    # Rejected rule pauses under onRejection: REVISE; tag: outcome
eddi_mcp_hitl_pending_listed_total          # Pending approvals listed via MCP; tag: surface
eddi_mcp_hitl_decision_total                # Decisions via MCP; tags: surface, verdict
eddi_mcp_hitl_cancelled_total               # Cancellations via MCP; tag: surface
```

```text
eddi_hitl_pending                           # Conversations waiting for a human right now (gauge)
eddi_hitl_pending_oldest_age_seconds        # How long the oldest of them has waited (gauge)
```

The two gauges read the backlog from the conversation store every
`eddi.hitl.metrics.refresh-interval` (default 60 s) — a bounded, projected read
of at most 1000 rows, never on the scrape thread. Every replica reports the
store-wide number, so aggregate with `max`, not `sum`; past 1000 pending
approvals the count saturates, while the oldest age stays exact (the store
lists the oldest pauses first, so the cap drops the newest). They read `NaN` until the first refresh and
after a failed one. Pauses minus resumes, the old way of estimating the backlog,
reset with every restart and never saw pauses taken before it; alert on
`eddi_hitl_pending_oldest_age_seconds` instead (`EddiApprovalWaitingLong`).

**Email notifications** ([hitl.md → Email notifications](hitl.md#email-notifications)):

```text
eddi_email_outbound_total                   # Notification mails by outcome; tags: kind (notify), outcome (sent|retry|failed|suppressed|not_allowed|rate_limited)
eddi_email_ledger_pending                   # Email ledger rows not yet sent, failed or suppressed (gauge)
```

`outcome=retry` counts a deferred attempt that will be retried with backoff; `failed` a row given up on (a 5xx
refusal, or the last of `eddi.email.outbound.max-attempts`); `suppressed` a notification not sent because its pause
was decided first; `not_allowed` a recipient outside `eddi.mail.notify.allowed-recipient-domains`; `rate_limited` a
recipient over `eddi.mail.notify.max-per-recipient-per-hour` (a sustained rise means something keeps an agent pausing). The gauge is read store-wide on every ledger sweep (`eddi.email.ledger.sweep-interval`, default
30 s), so aggregate with `max`; it reads `NaN` until the first sweep and after a failed one. No tag carries an
address, an agent or a conversation.

**Mail sender authentication** ([security.md → Mail Sender Authentication](security.md#mail-sender-authentication--dkim-and-dmarc)):

```text
eddi_email_auth_total                       # Verdicts of DKIM + DMARC alignment; tag: result (aligned_pass|pass_unaligned|fail|none)
eddi_email_dns_lookups_total                # TXT lookups for DKIM keys and DMARC records; tags: result (found|not_found|tempfail|budget_exhausted|oversize|invalid_name), cached (true|false)
```

`eddi_email_auth_total` counts the verdicts for all mail the selected instances processed, so it shows whether your
correspondents can be authenticated at all: if most mail is `none`, it comes from senders that do not sign (only an aligned pass is authenticated); a rise in `fail` is
tampered, forwarded or list mail, or forged mail — **or a DNS problem**: a key lookup that fails temporarily or runs
out of budget (`tempfail` / `budget_exhausted` below) is counted as `fail` too when no signature passes. If a signature
passes, DMARC alignment determines the verdict: an aligned signature yields `aligned_pass` (never temporary), and an
unaligned signature yields `pass_unaligned`. A caller can retry a temporary verdict, so a `fail`
rise that comes with a rise in `tempfail` points at DNS, and one that comes with `budget_exhausted` at the lookup
limit, rather than at the senders. An unexpected error inside the verifier is counted as `fail` as well (the mail is
treated as unauthenticated, not retried) and logs the WARN `Sender authentication failed internally`, so a `fail` rise
with neither DNS signal is worth checking against that log before blaming the senders. No tag carries a domain or an
address. For
`eddi_email_dns_lookups_total`, `cached=true` is an answer served from the cache (no query sent); a sustained
`tempfail` means TXT lookups are failing temporarily, of which an unreachable resolver (`eddi.email.dns.servers`) is
one possible cause; `budget_exhausted` is a local limit, not a DNS failure: a message needed more DKIM key and DMARC
record lookups than `eddi.email.dns.max-lookups-per-message` allows, and the lookups past it were not sent.

### Platform Operator Metrics

```text
eddi_operator_write_approval_total          # Gated operator writes; tag: decision
eddi_operator_gate_verified                 # Write gate is verified and active (gauge, 1|0)
eddi_operator_gate_last_verified_timestamp_seconds # When this process last received a verified report (epoch s; 0 = never)
eddi_operator_canary_total                  # Canary probes of the write gate
eddi_operator_canary_duration_seconds       # Canary probe latency
```

`eddi_operator_gate_verified` dropping to `0` means the Platform Operator's
human-approval gate is no longer proven — treat it as a security alert. It also
reads `0` before any report has arrived (a deployment that never activated the
operator), so the shipped rule `EddiOperatorGateRegressed` fires on the
transition — verified by the running process, unverified now — not on the value.
`eddi_operator_gate_last_verified_timestamp_seconds` records when this process last
received a verified report (0 until it has). Both gauges restart at `0` on every
boot, so a restart alone is not a regression, while a regression minutes after a
restart still pages. Both gauges exist from startup.

### Prompt & Guardrail Metrics

```text
eddi_snippets_cache_hits_total              # Prompt-snippet cache hits
eddi_snippets_cache_misses_total            # Prompt-snippet cache misses
eddi_counterweight_activation_count_total   # Counterweight activations; tag: level (normal|cautious|strict|unknown)
eddi_counterweight_strict_downgraded_total  # strict downgraded because the model could not honour it
eddi_identity_masking_applied_total         # Identity-masking passes applied
eddi_guardrail_toolresult_count_total       # Tool results inspected by the tool-result guardrail; tags: action, source (a null source reads as `unknown`)
eddi_guardrail_triggered_total              # An LLM task's input/output guardrail fired; tags: phase (input|output), type, action (block|redact|reprompt|warn)
eddi_guardrail_classifier_duration_seconds  # Timer: one `classifier` guardrail evaluation (outcome=spend_refused makes no provider call); tags: provider (a registered provider type, or unknown|unsupported), outcome (safe|unsafe|unparseable|unavailable|timeout|spend_refused)
eddi_guardrail_judge_duration_seconds       # Timer: one `judge` guardrail evaluation (outcome=spend_refused makes no provider call); tags: provider (a registered provider type, or unknown|unsupported), outcome (pass|fail|unparseable|unavailable|timeout|spend_refused)
```

`eddi_guardrail_triggered_total` counts findings, not evaluations: a guardrail that
passes the text records nothing. Unknown guardrail types are skipped before they are
counted, so `type` only ever carries a registered guardrail. See
[Input and Output Guardrails](langchain.md#input-and-output-guardrails).

### Conversation Rating Metrics

```text
eddi_conversation_ratings_total             # Ratings people gave; tags: kind (conversation|message), scale (THUMBS|FIVE_STAR), bucket (low|neutral|high), agentId
eddi_conversation_ratings_normalized        # Distribution summary of the normalized score (0..1); tags: agentId, kind (_count, _sum, _max)
eddi_conversation_ratings_withdrawn_total   # Ratings withdrawn by the person who gave them; tag: kind
```

The counter measures rating *events*: a person who changes a rating counts twice.
The current state — one rating per conversation and per reply — is what
`GET /ratings/summary` reports. `bucket` is `low` below a normalized 0.4 (one or
two stars, thumbs down), `neutral` below 0.6 (three stars) and `high` otherwise.
See [Conversation Ratings](conversation-ratings.md).

### Alert Metrics

```text
eddi_alerts_fired_total             # Alerts recorded; tag: source (RATING|CONVERSATION_ERROR|OUTPUT_MATCH|digest)
eddi_alerts_deduplicated_total      # Alerts folded into an earlier one of the same rule, agent and conversation in the same window
eddi_alerts_throttled_total         # Alerts held back past the rule's cap (for its digest, or the inbox only)
eddi_alerts_deliveries_total        # Delivery attempts; tags: type (slack|webhook), outcome (success|failure|gave_up)
eddi_alerts_digests_total           # Digests created (see eddi_alerts_deliveries_total for send outcomes)
eddi_alerts_dropped_total           # Alert work dropped because the alert queue was full; tag: reason (queue_full)
eddi_alerts_queue_depth             # Gauge: work waiting for the alert worker
```

No tag carries an agent id (a rule over every agent would mint a series per agent).
`eddi_alerts_dropped_total` should stay at zero; anything else means the alert
worker cannot keep up — see [Alerts](alerts.md#operating-alerts). A Grafana alert
rule on `outcome="gave_up"` is in the
[Monitoring Guide](monitoring/monitoring-guide.md#alerting-on-alert-delivery).

### Agent Identity & Signing Metrics

```text
eddi_agent_identity_sign_count_total        # Agent configurations signed
eddi_agent_identity_verify_success_total    # Signature verifications that passed
eddi_agent_identity_verify_fail_total       # Signature verifications that failed
eddi_agent_nonce_replay_rejected_total      # Requests rejected as replays
eddi_agent_nonce_freshness_rejected_total   # Requests rejected as stale
eddi_agent_nonce_clockskew_rejected_total   # Requests rejected for clock skew
```

Any sustained `verify_fail` or `replay_rejected` rate is an attack signal or a
misconfigured peer — never routine.

### Capability Registry Metrics

Full guide: [capability-match-guide.md](capability-match-guide.md).

```text
eddi_capability_query_count_total           # Capability lookups
eddi_capability_query_time_seconds          # Lookup latency (timer)
eddi_capability_miss_count_total            # Lookups matching no agent (untagged)
eddi_capability_strategy_applied_total      # Resolution strategy used; tag: strategy
```

`miss_count` is deliberately **untagged**: the skill string is caller-supplied
(REST, A2A discovery, templated rules, LLM tools), so a `skill` label would be
unbounded. The rate says how often a capability is missing; which one is in the
WARN log line `No capability match`. (The Full dashboard's "Top missing skills"
panel grouped by a `skill` label that does not exist and was always empty; it
is now "Capability misses (1h)".)

### Secrets Vault Metrics

Full guide: [secrets-vault.md](secrets-vault.md).

```text
eddi_vault_resolve_count_total              # Secret resolutions
eddi_vault_store_count_total                # Secrets written
eddi_vault_rotate_count_total               # Key rotations
eddi_vault_grant_update_count_total         # allowedAgents edited without the value (PUT .../grant)
eddi_vault_delete_count_total               # Secrets deleted
eddi_vault_errors_count_total               # Vault operation failures (store unreachable, decryption failed) — not "no such key"
eddi_vault_reseal_failures_total            # Sealed rows a DEK rotation could not open and skipped; tag: participant
eddi_vault_cache_hits_total                 # Resolved-secret cache hits
eddi_vault_cache_misses_total               # Resolved-secret cache misses
eddi_vault_resolve_errors_total             # Resolution failures seen by SecretResolver
eddi_vault_resolve_duration_seconds         # Resolution latency, provider side (timer)
eddi_vault_resolve_time_seconds             # Resolution latency, resolver side (timer)
eddi_vault_store_duration_seconds           # Write latency (timer)
```

### MCP & Integration Metrics

```text
eddi_mcp_discovery_total                    # Remote tool discoveries; tag: outcome
eddi_mcp_tool_schema_governed_total         # Remote tool parameter schemas changed before prompting; tag: outcome (redacted|truncated|dropped|dropped_server_cap)
eddi_mcp_response_truncation_count_total    # Tool responses truncated to fit the context
eddi_mcp_conversation_access_denied_total   # MCP conversation access refused; tag: tool
eddi_openai_requests_total                  # OpenAI-compatible API requests; tags: mode, outcome
eddi_openai_request_duration_seconds        # Request latency (timer)
eddi_openai_conversations_created_total     # Conversations created via the /v1 adapter
eddi_openai_conversations_rolled_over_total # /v1 conversations ended at max-conversation-steps
eddi_openai_chat_keys_total                 # Stateful /v1 requests by chat-key source; tag: source
eddi_a2a_tool_schema_governed_total         # Peer tool parameter schemas changed before prompting; tag: outcome (as the MCP counter)
eddi_a2a_requests_total                     # A2A JSON-RPC calls; tags: method (send|stream|get|cancel|..., disabled when A2A is off), dialect (1.0|0.3|legacy|none), outcome (ok|error|invalid|busy)
eddi_a2a_tasks_total                        # A2A tasks by the state their turn settled in; tag: state
eddi_a2a_in_flight                          # A2A turns holding an in-flight slot (gauge; cap: eddi.a2a.max-concurrent-requests)
eddi_caller_identity_resolution_total       # Caller-identity resolutions; tags: outcome, reference
eddi_channel_observe_decisions_total        # Observe-mode reply decisions; tags: reason, type
eddi_webhook_events_total                   # Inbound signed-webhook events; tag: outcome (accepted|duplicate|in_progress|refused_capacity|refused_<http status>)
```

`eddi_channel_observe_decisions_total` is one sample per message an observer
saw. `reason` is the gate that settled it: `MATCHED` (it replied), `NO_TRIGGER`
(the message was not for it), `COOLDOWN` / `DAILY_RESPONSE_CAP` /
`DAILY_COST_CAP` (it wanted to and was stopped), or `CONTENTION` (it could not
book the reply because concurrent events kept winning the compare-and-set --
a load signal, not a configuration one). `type` is the observer's
target type — `AGENT` today, since observe mode is refused on anything else —
not the channel platform. A rising throttle share with a flat `MATCHED` share is
an observer whose triggers are too broad for its budget.

### Backup, Export & Sync Metrics

```text
eddi_backup_export_count_total                    # Agent exports attempted
eddi_backup_export_failure_count_total            # Exports that failed
eddi_backup_import_count_total                    # Archive imports attempted
eddi_backup_import_failure_count_total            # Imports that failed
eddi_backup_upgrade_count_total                   # Upgrade/sync runs attempted (ZIP upgrade, /sync, /sync/batch)
eddi_backup_upgrade_failure_count_total           # Upgrade/sync runs that failed outright
eddi_backup_upgrade_resource_updated_count_total  # Resources updated in place by a sync
eddi_backup_upgrade_resource_created_count_total  # Resources created in the target by a sync
eddi_backup_upgrade_resource_skipped_count_total  # Matched resources whose content was already identical
eddi_backup_upgrade_resource_failure_count_total  # Resources a sync could not process (the 207 body lists them)
```

> The four `resource` counters explain a successful no-op. An all-skipped run is
> answered with `200 OK`: source and target already agree, nothing was written and
> no agent version was burned. (`201 Created` means something *was* written,
> `207 Multi-Status` that part of it failed — see `IRestImportService`.) A
> `created` line where `updated` is expected means the matcher is not joining
> source and target extensions and every sync is duplicating the configuration
> tree.

### Session & Listing Metrics

```text
eddi_session_checkpoint_count_total         # Conversation memory checkpoints written
eddi_conversations_listing_owner_scan_exhausted_total  # Conversation listing gave up scanning for an owner
eddi_conversations_listing_orphaned_descriptors_total  # Descriptors a listing skipped because their conversation memory is gone
```

### Audit Ledger Metrics

```text
eddi_audit_entries_dropped_total            # Audit entries dropped (compliance-critical)
eddi_audit_sequence_collisions_total        # Chain positions allocated by another replica
eddi_audit_queue_depth                      # Entries waiting to be written to the audit store (gauge)
eddi_audit_queue_capacity                   # eddi.audit.max-queue-size (gauge)
```

The drop counter moves only once the queue is already full. Depth against
capacity is the warning before it — `EddiAuditQueueFilling` fires at half.

`eddi_audit_sequence_collisions_total` is non-zero only on a multi-replica deployment
without conversation affinity, where two nodes allocate the same per-conversation chain
positions and `/auditstore/verify` then grades those conversations `BROKEN`. See
[Chain sequences and multi-replica deployments](audit-ledger.md#chain-sequences-and-multi-replica-deployments).

### Database Log Retention Metrics

```text
eddi_logs_db_retention_deleted_total        # Persisted log entries the retention sweep deleted
```

Stays at zero while `eddi.logs.db-retention-days` is off (the default). See
[log-administration.md](log-administration.md#retention).

### Dream (Background Memory Consolidation) Metrics

```text
eddi_dream_users_processed_total            # Users a dream cycle worked through
eddi_dream_entries_pruned_total             # Stale memory entries removed
eddi_dream_entries_summarized_total         # Entries folded into a consolidation
eddi_dream_contradictions_found_total       # Same-key/different-value pairs flagged
eddi_dream_cycles_failed_total              # Dream cycles that aborted
eddi_dream_summarization_failed_total       # LLM summarization steps that failed
eddi_dream_conflicts_skipped_total          # Writes skipped because the memory changed during the cycle
eddi_dream_duration_seconds                 # Dream cycle duration (timer)
```

Read the two failure counters together. A summarization failure the service classifies as
transient — a socket timeout, a connection refusal, a rate-limit message — is skipped and the
cycle carries on, so `eddi_dream_summarization_failed_total` rises while
`eddi_dream_cycles_failed_total` stays flat. **Every** other failure aborts consolidation for
that cycle and increments both.

So both counters rising together narrows the cause to a non-transient one; it does not identify
it. Missing credentials are the common case — a provider 401 lands here, and stale pruning keeps
working, so the cycle looks partly healthy — but so does a bad model name, a rejected request or
a provider outage that does not present as a timeout. Confirm from the ERROR line the cycle
logs: it names the provider, the model and the configured parameter *keys*, which is enough to
tell a missing `apiKey` from a wrong `llmModel` without exposing the value. If credentials are
the cause, set `userMemoryConfig.dream.parameters` — see [user-memory.md](user-memory.md).

`eddi_dream_conflicts_skipped_total` counts the prunes, in-place overwrites and deletes a cycle
did **not** make because the memory was written after the cycle read it — every Dream write is a
compare-and-set on `updatedAt`, so the user's newer fact wins. Occasional increments are normal
on active users; a steady rise means cycles keep racing live conversations, and scheduling them at
a quieter hour helps.

### Conversation Summarization Metrics

```text
eddi_summarization_calls_total              # Rolling-summary generations attempted
eddi_summarization_errors_total             # Rolling-summary generations that failed
eddi_summarization_duration_seconds         # Summarization duration (timer)
```

### RAG Ingestion Metrics

```text
eddi_ingestion_segments_stored_total        # Chunks embedded and written by source ingestion
eddi_ingestion_errors_total                 # Pages that failed to fetch, convert or embed
eddi_ingestion_files_stored_total           # Files accepted onto an upload source
eddi_ingestion_files_rejected_total         # Files an upload source refused, with a reason
eddi_ingestion_files_deleted_total          # Files removed from an upload source
```

The first two carry `knowledgeBase` (the RAG configuration's name) and `source` (the
ingestion source's name). Unchanged pages are not counted, so a scheduled re-crawl of a
static site stores nothing.

The three `files` counters carry `source` alone and belong to sources of type `upload`.
Rejections are the one worth alerting on: a rate that climbs means operators are being
turned away, and the reason is in the response rather than in the metric — a file that
is too large, a format nothing can read, or a source that has reached its own limit.

### Connection Resolution Metrics

```text
eddi_connection_resolve_count_total         # Connection resolutions; tags: authType, binding, outcome
eddi_connection_resolve_time_seconds        # Connection resolution duration (timer); tags: authType, binding
eddi_connection_grant_missing_count_total   # Resolutions refused for a missing grant; tag: binding
eddi_connection_oauth_authorize_count_total # Per-user OAuth link flows started; tags: outcome (issued), authType
eddi_connection_oauth_callback_count_total  # OAuth callback outcomes; tags: outcome (success, bad_state, binding_mismatch, provider_error, exchange_failed), authType
eddi_connection_token_refresh_count_total   # Token endpoint outcomes; tag: outcome (success, minted, invalid_grant, transient)
eddi_connection_token_refresh_claim_count_total # Refresh lease protocol; tag: outcome (claimed, awaited, lease_released, lease_expired)
```

### Deployed Agents

```text
eddi_agents_deployed                        # Currently deployed agents (gauge)
```

### Cluster Metrics

> Registered only when `eddi.messaging.type=nats` — an in-memory deployment
> exposes none of them. Charted on the **EDDI Cluster** dashboard
> (`docs/monitoring/eddi-cluster-dashboard.json`). The `eddi_nats_*` meters of
> the former build-profile coordinator no longer exist.

```text
eddi_cluster_nats_connected                 # 1 while this node is connected to NATS (gauge)
eddi_cluster_degraded                       # 1 while degraded: NATS unreachable for longer than the grace (gauge)
eddi_cluster_nats_reconnects_total          # Reconnects of this node
eddi_cluster_members                        # Members in the presence bucket (gauge)
eddi_cluster_lease_acquire_seconds          # Lease acquisition {outcome=acquired|timeout|degraded|degraded_reject, contended} (timer)
eddi_cluster_lease_held                     # Leases this node holds (gauge)
eddi_cluster_lease_lost_total               # Leases lost while a turn ran {reason=taken_over|force_released}
eddi_cluster_lease_takeover_total           # Leases taken over from a dead or restarted holder
eddi_cluster_lease_release_conflicts_total  # Releases that found the lease already taken over
eddi_cluster_fence_rejected_total           # Turn writes refused by the database fence (a zombie write stopped)
eddi_cluster_kv_seconds                     # KV calls {bucket, op, outcome} (timer)
eddi_cluster_kv_cas_conflicts_total         # Lost compare-and-sets {bucket}
eddi_cluster_events_published_total         # Cluster events published {type}
eddi_cluster_events_consumed_total          # Cluster events handled from other nodes {type}
eddi_cluster_events_publish_failed_total    # Events that went to the outbox instead
eddi_cluster_events_outbox                  # Events waiting in the outbox (gauge)
eddi_cluster_events_resync_total            # Full local cache flushes after possibly missed events
eddi_cluster_events_resync_coalesced_total  # Resync requests that joined one already queued (N nodes asking at once flush each node once more, not N times)
eddi_cluster_rpc_seconds                    # Node RPCs {op, outcome} (timer)
eddi_cluster_degraded_decisions_total       # Degraded-mode decisions {area, action}
eddi_cluster_ratelimit_decisions_total      # Cluster-wide tool rate-limit decisions {scope, outcome=allowed|denied|degraded}
eddi_cluster_ratelimit_cas_retries_total    # Lost compare-and-sets on a shared rate-limit bucket (retried)
eddi_cluster_audit_sequence_total           # Audit chain positions {outcome=allocated|unsequenced|conflict}
eddi_agent_nonce_unavailable_total          # Signed envelopes refused: the shared nonce store was unreachable (fail closed)
eddi_cluster_admin_actions_total            # Recovery actions from the cluster console {action, outcome}
```

Alert on `eddi_cluster_degraded == 1` for 5 minutes, any increase of
`eddi_cluster_fence_rejected_total`, a rising
`eddi_cluster_lease_acquire_seconds_count{outcome="timeout"}` and
`eddi_coordinator_dead_letters > 0` — the rules ship in
`docs/monitoring/eddi-cluster-alerts.yml`. See [Clustering](clustering.md).

### JVM & HTTP Server (auto-exposed)

Standard Micrometer metrics for Quarkus:

```text
jvm_memory_used_bytes{area="heap|nonheap"}
jvm_memory_committed_bytes{area="heap|nonheap"}
jvm_memory_max_bytes{area="heap"}
jvm_threads_live_threads
jvm_threads_daemon_threads
jvm_threads_peak_threads
jvm_gc_pause_seconds{action="..."}
process_uptime_seconds
process_start_time_seconds
process_cpu_usage
system_cpu_usage
jvm_memory_usage_after_gc{area="heap",pool="long-lived"}   # live data after GC / max; EddiHeapAfterGc*
worker_pool_ratio{pool_name}                               # Quarkus worker pools, Vert.x-dispatched work only
http_server_requests_seconds{method,uri,status,outcome}    # _count, _sum, _max — no _bucket
```

`jvm_info` is exposed as `jvm_info_total` (a counter of 1); the Full dashboard's
`$job` variable used to be sourced from `jvm_info` and found nothing.

`uri` is the endpoint's template (`/agentstore/agents/{id}`), so it has one value per endpoint, not per resource. Static files would otherwise get one value each, which is ~800 for the Manager's hashed chunks alone. Instead they fold into one value per directory: `/assets/{file}`, `/fonts/{file}`, `/scripts/{file}` and `/img/{file}`. Unknown paths share `NOT_FOUND`. Micrometer registers at most `quarkus.micrometer.binder.http-server.max-uri-tags` (500) values. A URI first seen after that has no request metrics, and the log says "Reached the maximum number … of URI tags" once.

### Database Connection Pool (auto-exposed)

**MongoDB** (when `eddi.datastore.type=mongodb`), from Micrometer's
`MongoMetricsConnectionPoolListener` on the client EDDI builds itself; tags
`cluster_id`, `server_address`:
```text
mongodb_driver_pool_size
mongodb_driver_pool_checkedout
mongodb_driver_pool_waitqueuesize
```

**PostgreSQL / Agroal** (when `eddi.datastore.type=postgres`), from
`quarkus.datasource.metrics.enabled=true`:
```text
agroal_active_count
agroal_available_count
agroal_awaiting_count
agroal_max_used_count
```

---

## Prometheus Alerts

E.D.D.I ships its alert rules: [`docs/monitoring/eddi-alerts.yml`](monitoring/eddi-alerts.yml),
25 rules in nine groups. They are loaded

- by `docker-compose.monitoring.yml` (mounted and listed under `rule_files`),
- by the Kubernetes monitoring component (`k8s/overlays/monitoring`, a generated
  `prometheus-rules` ConfigMap), and
- by the Helm chart as a Prometheus Operator `PrometheusRule`
  (`--set prometheusRule.enabled=true`, with `serviceMonitor.enabled=true` for the
  scrape).

They cover availability (`EddiDown`, `EddiMetricsAbsent`, `EddiRestartLoop`),
the 5xx ratio, the coordinator backlog and dead letters, pipeline task errors,
executor saturation, LLM error ratio and p95 latency, the tool failure ratio, the
vault, the Platform Operator gate and canary, audit drops, queue fill and chain
collisions, quota-store failures, schedule and NATS dead letters, and heap after
GC. Every rule has a runbook in
[monitoring-guide.md → Alert runbooks](monitoring/monitoring-guide.md#alert-runbooks),
linked from its `runbook_url` annotation; `promtool test rules` cases in
`docs/monitoring/eddi-alerts.test.yml` make each essential alert fire on synthetic
series and stay quiet on a healthy one.

The thresholds are starting points for a single replica with modest traffic, and
every ratio alert also requires a minimum number of events. Tune them in the YAML.
The rules over generic series (`up`, `http_*`, `jvm_*`, `process_*`) select on
`job="eddi"`; every shipped scrape configuration uses that job name.

---

## REST API Endpoints

EDDI also exposes tool metrics via REST:

All of them live under `/llm/tools` (`RestToolHistory`):

```bash
# Tool history for one conversation
GET    /llm/tools/history/{conversationId}

# Result cache
GET    /llm/tools/cache/stats
GET    /llm/tools/cache/ttl/{toolName}
DELETE /llm/tools/cache

# Rate limiting
GET    /llm/tools/ratelimit/{toolName}
POST   /llm/tools/ratelimit/{toolName}/reset

# Cost tracking
GET    /llm/tools/costs
GET    /llm/tools/costs/conversation/{conversationId}
GET    /llm/tools/costs/tool/{toolName}
POST   /llm/tools/costs/reset
```

> The pre-v6 prefix `/langchain/tools` still answers, because
> `LegacyPathRewriteFilter` rewrites it to `/llm/tools`. That is a compatibility
> shim for existing clients, not a second spelling to write in new code — it can
> be withdrawn, and it makes stale documentation look correct while it lives.

---

## Monitoring Best Practices

### Key Metrics to Watch

| Metric | Target | Why |
|--------|--------|-----|
| Cache Hit Rate | > 70% | Below this, tool calls are mostly un-cached → higher latency & cost |
| Tool Success Rate | > 95% | Dropping below indicates tool integration issues |
| Mean tool latency | < 2s | Conversation responsiveness depends on tool speed |
| Cost Per Request | < $0.001 | Runaway costs indicate misconfigured tools or abuse |
| Audit Drops | = 0 | Any non-zero value is a compliance incident |
| Error Rate (HTTP 5xx) | < 1% | Proxy for overall platform health |

### Key PromQL Queries

**Cache Hit Rate:**
```promql
sum(rate(eddi_tool_cache_hits_total[5m])) /
  (sum(rate(eddi_tool_cache_hits_total[5m])) +
   sum(rate(eddi_tool_cache_misses_total[5m])))
```

**Tool Success Rate:**
```promql
sum(rate(eddi_tool_execution_success_total[5m])) /
  (sum(rate(eddi_tool_execution_success_total[5m])) +
   sum(rate(eddi_tool_execution_failure_total[5m])))
```

**Mean Conversation Processing Latency:**
```promql
sum(rate(eddi_conversation_processing_duration_seconds_sum[5m]))
  / clamp_min(sum(rate(eddi_conversation_processing_duration_seconds_count[5m])), 1e-9)
```

> **There is no P95 for this timer.** See [Timers do not publish
> percentiles](#timers-do-not-publish-percentiles) — `eddi_conversation_processing_duration`
> exposes no `_bucket` series, so `histogram_quantile` over it returns nothing at
> all. Use the mean above, or `max(eddi_conversation_processing_duration_seconds_max)`
> for the peak.

**P99 Pipeline Task Duration** (the one timer that *does* have buckets):
```promql
histogram_quantile(0.99,
  sum by (le) (rate(eddi_pipeline_task_duration_seconds_bucket[5m])))
```

**Cost in the last hour:**
```promql
sum(increase(eddi_tool_costs_total[1h]))
```

> `rate()` would be dollars per **second** — 3600 times too low for a figure
> labelled per hour. The Operations dashboard's "Cost / hr" KPI had exactly that
> bug.

---

## Additional Resources

- **[LLM Integration Guide](langchain.md)** — Full LangChain and agent documentation
- **[Audit Ledger](audit-ledger.md)** — Audit compliance and dropped entry monitoring
- **[Security](security.md)** — Authentication and RBAC configuration
- **[Kubernetes](kubernetes.md)** — Production deployment with monitoring overlay
- **[Prometheus Documentation](https://prometheus.io/docs/)** — Prometheus setup
- **[Grafana Documentation](https://grafana.com/docs/)** — Dashboard creation
- **[Micrometer Documentation](https://micrometer.io/docs)** — Metrics framework
