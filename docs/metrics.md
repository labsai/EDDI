# Metrics & Monitoring

E.D.D.I exposes comprehensive metrics via [Micrometer](https://micrometer.io/) in Prometheus format, covering conversations, tool execution, caching, rate limiting, cost tracking, multi-agent group discussions, scheduled triggers, tenant quotas, audit integrity, and JVM internals.

## Quick Start — Grafana Dashboards

E.D.D.I ships three dashboards, all auto-provisioned into Grafana by
`docker-compose.monitoring.yml`:

| Dashboard | UID | File | Shape | Use it for |
|-----------|-----|------|-------|------------|
| **Operations Command Center** | `eddi-ops` | `eddi-operations-dashboard.json` | 51 panels, KPI strip + 9 rows | The front door. Is the platform healthy, and if not, roughly where. |
| **Full Metrics Reference** | `eddi-metrics-all` | `eddi-full-metrics-dashboard.json` | 138 panels, 19 subsystem rows | Every meter E.D.D.I registers. Go here when the number you need is not on the ops dashboard. |
| **EDDI Observability** | `eddi-observability` | `eddi-grafana-dashboard.json` | 16 panels, 6 rows | The original dashboard: Coordinator Health, Pipeline Tasks, Tool Execution, Vault & Security, NATS, HTTP & JVM. |

The Full Metrics Reference covers **every `eddi.*` meter the codebase
registers**, and that is a checked property rather than an aspiration:
`MetricsDashboardCoverageTest` scans the registration sites in `src/main/java`
and fails the build if any meter has no panel. Add a meter without a panel and
`./mvnw test` tells you, naming the meter and the file that registers it.

All rows but the first are collapsed; open the subsystem you care about. Two
template variables scope everything: the Prometheus **data source** and the
scrape **job**.

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
| Grafana    | http://localhost:3000       | admin / admin  |
| Prometheus | http://localhost:9090       | —              |
| Metrics    | http://localhost:7070/q/metrics | —          |

The dashboard appears automatically as the Grafana home page. Anonymous viewer access is enabled by default.

### Dashboard Sections

| Row | Title | Key Panels |
|-----|-------|------------|
| **KPI Strip** | _(always visible)_ | Uptime, Agents Deployed, Active Conversations, Messages/sec, Tool Success %, Cache Hit %, Error Rate, Cost/hr |
| **Row 1** | Platform Overview & HTTP Traffic | Request rate by status (2xx/4xx/5xx), latency P50/P95/P99, CPU usage, top 10 slowest endpoints |
| **Row 2** | Conversations | Start/end/processing rate, processing duration percentiles, active gauge, undo/redo, start vs load latency |
| **Row 3** | Tool Execution Engine | Success vs failure rate, per-tool execution duration, cached/rate-limited breakdown, per-tool call counts |
| **Row 4** | Tool Cache Performance | Hit rate %, hits vs misses, cache size, get/put duration |
| **Row 5** | Rate Limiting & Cost | Allowed vs denied, denied by tool, total cost, budget exceeded events, cost accumulation, cost by tool |
| **Row 6** | Multi-Agent Group Discussions | Started vs failed, failure rate gauge, discussion duration |
| **Row 7** | Scheduled Triggers | Poll/fire/failed, fire duration, claim conflicts, dead-lettered |
| **Row 8** | Tenant Quotas & Audit | Quota allowed vs denied, denied by type, audit entries dropped, tenant usage |
| **Row 9** | JVM & Infrastructure | Heap/non-heap memory, threads, GC, MongoDB pool, PostgreSQL Agroal pool, NATS messaging |

> **Database-agnostic**: Row 9 includes panels for both MongoDB (`mongodb_driver_pool_*`) and PostgreSQL (`agroal_*`). Whichever backend is active shows data; the other gracefully shows "No data".

### Full Metrics Reference — rows

`Overview` (open by default) · `Conversations` · `Coordinator & Lifecycle Pipeline` ·
`Tool Execution Engine` · `Tool Cache` · `Tool Rate Limiting` ·
`LLM — Model Cascade & Streaming` · `Guardrails, Counterweights & Masking` ·
`Human-in-the-Loop` · `Group Conversations & Standing Teams` · `Scheduling` ·
`Persistent Memory — Dream & Summarization` ·
`Integrations — MCP, A2A Identity, OpenAI-compatible API` ·
`Capability Registry & Connections` · `Secrets Vault` · `Tenancy, Quotas & Audit` ·
`Platform Operator` · `NATS JetStream` · `Runtime context (Quarkus / JVM built-ins)`

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
Only **one** — `eddi.pipeline.task.duration` — calls
`publishPercentileHistogram()`, so it is the only one with a `_seconds_bucket`
series and therefore the only one where `histogram_quantile()` returns anything.

```promql
# Mean over the interval — works for every timer
sum(rate(eddi_tool_execution_duration_seconds_sum[5m]))
  / clamp_min(sum(rate(eddi_tool_execution_duration_seconds_count[5m])), 1e-9)

# Peak — the decaying max, not p100 of the window
max(eddi_tool_execution_duration_seconds_max)

# A true quantile — ONLY for eddi_pipeline_task_duration
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
series never reaches `/q/metrics` at all.

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
eddi_processing_conversation_count          # Currently active (gauge)

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
eddi_tool_execution_duration_seconds        # Execution duration (timer)
```

All execution metrics support a `tool` label for per-tool breakdown:
```promql
rate(eddi_tool_execution_success_total{tool="weather"}[5m])
```

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
eddi_tool_costs_total                       # Total cumulative cost (gauge)
eddi_tool_budget_exceeded_total             # Budget exceeded events
```

Per-tool breakdown:
```text
eddi_tool_calls{tool="weather"}             # Calls per tool
eddi_tool_costs{tool="weather"}             # Cost per tool
```

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
eddi_schedule_claim_conflict_total          # Claim conflicts (multi-instance)
eddi_schedule_fire_deadlettered_total       # Dead-lettered schedules
eddi_schedule_fire_duration_seconds         # Fire latency (timer)
```

### Tenant Quota Metrics

```text
eddi_tenant_quota_allowed_total             # Slot acquisitions granted (untagged)
eddi_tenant_quota_denied_total{tenant,type} # Quota denials, always tagged
eddi_tenant_usage_conversations_total       # Conversation usage (per tenant)
eddi_tenant_usage_api_calls_total           # API call usage (per tenant)
eddi_tenant_usage_cost_total                # Cost usage (per tenant)
```

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

### Coordinator Metrics

```text
eddi_coordinator_active_conversations       # Conversations with a live queue (gauge)
eddi_coordinator_queue_depth                # Total queued messages across all conversations (gauge)
eddi_coordinator_total_processed_total      # Messages processed since start
```

`queue_depth` rising while `total_processed` flattens is the signature of a
backlog: work is arriving faster than it drains.

### Pipeline Metrics

```text
eddi_pipeline_task_duration_seconds         # Per-task latency; tags: task.id, task.type
eddi_pipeline_task_errors_total             # Per-task failures; tags: task.id, task.type, error.type
```

`eddi_pipeline_task_duration` is the **only** EDDI meter publishing histogram
buckets, so it is the only one where `histogram_quantile` gives a real
percentile. See [Timers do not publish percentiles](#timers-do-not-publish-percentiles).

### Model Cascade Metrics

Full guide: [model-cascade.md](model-cascade.md).

```text
eddi_llm_cascade_executions_total           # Cascade runs started; tag: agentMode
eddi_llm_cascade_escalations_total          # Moves to a costlier step; tag: reason (low_confidence|timeout|<error type>)
eddi_llm_cascade_accepted_step_total        # Which step answered; tag: step
eddi_llm_cascade_step_errors_total          # Per-step failures; tags: provider, type
eddi_llm_cascade_ceiling_exceeded_total     # Run cut short; tag: kind (cost|duration)
eddi_llm_cascade_tokens_total               # Tokens consumed; tag: provider
eddi_llm_cascade_cost_total                 # Dollars spent; tag: provider
eddi_llm_cascade_step_latency_seconds       # Per-step latency (timer); tag: provider
eddi_llm_cascade_confidence                 # Confidence scores (timer used as a distribution)
```

**`accepted_step` is the metric that says whether cascading is working.** Mass at
`step="0"` means the cheap model is carrying the load, which is the entire point.
Mass at the last step means every turn pays for the cheap attempt *and* the
expensive one.

### Streaming Metrics

```text
eddi_llm_streaming_downgraded_total         # Fell back to a single chunk; tag: reason
eddi_llm_streaming_no_partials_total        # Provider streamed, but emitted no partial tokens
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
```

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
eddi_mcp_hitl_pending_listed_total          # Pending approvals listed via MCP; tag: surface
eddi_mcp_hitl_decision_total                # Decisions via MCP; tags: surface, verdict
eddi_mcp_hitl_cancelled_total               # Cancellations via MCP; tag: surface
```

Pauses without matching resumes are approvals nobody answered. Alert on
`eddi_hitl_pause_count_total - eddi_hitl_resume_count_total` growing without
bound, not on either alone.

### Platform Operator Metrics

```text
eddi_operator_write_approval_total          # Gated operator writes; tag: decision
eddi_operator_gate_verified                 # Write gate is verified and active (gauge, 1|0)
eddi_operator_canary_total                  # Canary probes of the write gate
eddi_operator_canary_duration_seconds       # Canary probe latency
```

`eddi_operator_gate_verified` dropping to `0` means the Platform Operator's
human-approval gate is no longer proven — treat it as a security alert.

### Prompt & Guardrail Metrics

```text
eddi_snippets_cache_hits_total              # Prompt-snippet cache hits
eddi_snippets_cache_misses_total            # Prompt-snippet cache misses
eddi_counterweight_activation_count_total   # Counterweight activations; tag: level (normal|cautious|strict|unknown)
eddi_counterweight_strict_downgraded_total  # strict downgraded because the model could not honour it
eddi_identity_masking_applied_total         # Identity-masking passes applied
```

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
eddi_capability_miss_count_total            # Lookups matching no agent; tag: skill
eddi_capability_strategy_applied_total      # Resolution strategy used; tag: strategy
```

`miss_count` tagged by `skill` names exactly which capability your agents cannot
serve — the most directly actionable metric in this list.

### Secrets Vault Metrics

Full guide: [secrets-vault.md](secrets-vault.md).

```text
eddi_vault_resolve_count_total              # Secret resolutions
eddi_vault_store_count_total                # Secrets written
eddi_vault_rotate_count_total               # Key rotations
eddi_vault_delete_count_total               # Secrets deleted
eddi_vault_errors_count_total               # Vault operation failures
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
eddi_mcp_response_truncation_count_total    # Tool responses truncated to fit the context
eddi_mcp_conversation_access_denied_total   # MCP conversation access refused; tag: tool
eddi_openai_requests_total                  # OpenAI-compatible API requests; tags: mode, outcome
eddi_openai_request_duration_seconds        # Request latency (timer)
eddi_openai_conversations_created_total     # Conversations created via the /v1 adapter
eddi_caller_identity_resolution_total       # Caller-identity resolutions; tags: outcome, reference
```

### Session & Listing Metrics

```text
eddi_session_checkpoint_count_total         # Conversation memory checkpoints written
eddi_conversations_listing_owner_scan_exhausted_total  # Conversation listing gave up scanning for an owner
```

### Audit Ledger Metrics

```text
eddi_audit_entries_dropped_total            # Audit entries dropped (compliance-critical)
```

### Deployed Agents

```text
eddi_agents_deployed                        # Currently deployed agents (gauge)
```

### NATS Messaging Metrics

> Only active when using the NATS messaging profile. Shows nothing under in-memory messaging.

```text
eddi_nats_publish_count_total               # Messages published
eddi_nats_consume_count_total               # Messages consumed
eddi_nats_dead_letter_count_total           # Dead letters
eddi_nats_publish_duration_seconds          # Publish latency (timer)
eddi_nats_consume_duration_seconds          # Consume latency (timer)
```

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
process_cpu_usage
system_cpu_usage
http_server_requests_seconds{method,uri,status}
```

### Database Connection Pool (auto-exposed)

**MongoDB** (when `eddi.datastore.type=mongo`):
```text
mongodb_driver_pool_size
mongodb_driver_pool_checkedout
mongodb_driver_pool_waitqueuesize
```

**PostgreSQL / Agroal** (when `eddi.datastore.type=postgres`):
```text
agroal_active_count
agroal_available_count
agroal_awaiting_count
agroal_max_used_count
```

---

## Prometheus Alerts

### Sample Alert Rules

```yaml
groups:
  - name: eddi_alerts
    rules:
      # Critical
      - alert: ToolSystemDown
        expr: rate(eddi_tool_execution_success_total[5m]) == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "No successful tool executions in 2 minutes"

      - alert: BudgetExceeded
        expr: eddi_tool_costs_total > 10
        labels:
          severity: critical
        annotations:
          summary: "Total tool costs exceeded $10"

      - alert: AuditEntriesDropped
        expr: eddi_audit_entries_dropped_total > 0
        labels:
          severity: critical
        annotations:
          summary: "Audit entries are being dropped — compliance risk"

      # Warning
      - alert: HighToolFailureRate
        expr: >
          rate(eddi_tool_execution_failure_total[5m]) /
          (rate(eddi_tool_execution_success_total[5m]) +
           rate(eddi_tool_execution_failure_total[5m])) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Tool failure rate above 5%"

      - alert: CacheDegraded
        expr: >
          sum(rate(eddi_tool_cache_hits_total[5m])) /
          (sum(rate(eddi_tool_cache_hits_total[5m])) +
           sum(rate(eddi_tool_cache_misses_total[5m]))) < 0.5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Cache hit rate below 50%"

      - alert: HighRateLimitDenials
        expr: rate(eddi_tool_ratelimit_denied_total[5m]) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High rate limit denials: {{ $value }}/sec"

      - alert: ScheduleDeadLetters
        expr: eddi_schedule_fire_deadlettered_total > 0
        labels:
          severity: warning
        annotations:
          summary: "Dead-lettered schedules detected"
```

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
| P95 Latency | < 2s | Conversation responsiveness depends on tool speed |
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

**Cost Per Hour:**
```promql
rate(eddi_tool_costs_total[1h])
```

---

## Additional Resources

- **[LLM Integration Guide](langchain.md)** — Full LangChain and agent documentation
- **[Audit Ledger](audit-ledger.md)** — Audit compliance and dropped entry monitoring
- **[Security](security.md)** — Authentication and RBAC configuration
- **[Kubernetes](kubernetes.md)** — Production deployment with monitoring overlay
- **[Prometheus Documentation](https://prometheus.io/docs/)** — Prometheus setup
- **[Grafana Documentation](https://grafana.com/docs/)** — Dashboard creation
- **[Micrometer Documentation](https://micrometer.io/docs)** — Metrics framework
