# Metrics & Monitoring

E.D.D.I exposes comprehensive metrics via [Micrometer](https://micrometer.io/) in Prometheus format, covering conversations, tool execution, caching, rate limiting, cost tracking, multi-agent group discussions, scheduled triggers, tenant quotas, audit integrity, and JVM internals.

## Quick Start — Grafana Dashboard

E.D.D.I ships with a pre-built **Operations Command Center** dashboard (45 panels, 9 rows) that auto-provisions into Grafana.

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
| **Row 3** | Tool Execution Engine | Success vs failure rate, per-tool execution duration, parallel execution stats, cached/rate-limited breakdown |
| **Row 4** | Tool Cache Performance | Hit rate %, hits vs misses, cache size, get/put duration |
| **Row 5** | Rate Limiting & Cost | Allowed vs denied, denied by tool, total cost, budget exceeded events, cost accumulation, cost by tool |
| **Row 6** | Multi-Agent Group Discussions | Started vs failed, failure rate gauge, discussion duration |
| **Row 7** | Scheduled Triggers | Poll/fire/failed, fire duration, claim conflicts, dead-lettered |
| **Row 8** | Tenant Quotas & Audit | Quota allowed vs denied, denied by type, audit entries dropped, tenant usage |
| **Row 9** | JVM & Infrastructure | Heap/non-heap memory, threads, GC, MongoDB pool, PostgreSQL Agroal pool, NATS messaging |

> **Database-agnostic**: Row 9 includes panels for both MongoDB (`mongodb_driver_pool_*`) and PostgreSQL (`agroal_*`). Whichever backend is active shows data; the other gracefully shows "No data".

---

## Metrics Reference

All metrics are accessible at `/q/metrics`. Micrometer uses **dot notation** (e.g., `eddi.tool.cache.hits`); Prometheus automatically converts to **underscore notation** with `_total` suffix for counters (e.g., `eddi_tool_cache_hits_total`).

### Conversation Metrics

```
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

```
eddi_tool_execution_success_total           # Successful executions
eddi_tool_execution_failure_total           # Failed executions
eddi_tool_execution_cached_total            # Cache-served executions
eddi_tool_execution_ratelimited_total       # Rate-limited executions
eddi_tool_execution_duration_seconds        # Execution duration (timer)
eddi_tool_execution_parallel_total          # Parallel batches started
eddi_tool_execution_parallel_count_total    # Number of parallel tools
eddi_tool_execution_parallel_duration_seconds  # Parallel execution time (timer)
eddi_tool_execution_parallel_timeout_total  # Parallel timeouts
eddi_tool_execution_parallel_error_total    # Parallel errors
```

All execution metrics support a `tool` label for per-tool breakdown:
```promql
rate(eddi_tool_execution_success_total{tool="weather"}[5m])
```

### Tool Cache Metrics

```
eddi_tool_cache_hits_total                  # Cache hits
eddi_tool_cache_misses_total                # Cache misses
eddi_tool_cache_puts_total                  # Cache puts (per tool)
eddi_tool_cache_get_duration_seconds        # Get latency (timer)
eddi_tool_cache_put_duration_seconds        # Put latency (timer)
eddi_tool_cache_size                        # Current entries (gauge)
```

### Rate Limiting Metrics

```
eddi_tool_ratelimit_allowed_total           # Allowed calls
eddi_tool_ratelimit_denied_total            # Denied calls
eddi_tool_ratelimit_remaining               # Remaining capacity (gauge)
```

### Cost Tracking Metrics

```
eddi_tool_calls_total                       # Total tool calls
eddi_tool_costs_total                       # Total cumulative cost (gauge)
eddi_tool_budget_exceeded_total             # Budget exceeded events
```

Per-tool breakdown:
```
eddi_tool_calls{tool="weather"}             # Calls per tool
eddi_tool_costs{tool="weather"}             # Cost per tool
```

### Group Discussion Metrics

```
eddi_group_discussion_count_total           # Discussions started
eddi_group_discussion_failure_count_total   # Discussions failed
eddi_group_discussion_duration_seconds      # Duration (timer)
```

### Scheduled Trigger Metrics

```
eddi_schedule_poll_count_total              # Poll cycles
eddi_schedule_fire_count_total              # Schedules fired
eddi_schedule_fire_failed_total             # Fire failures
eddi_schedule_claim_conflict_total          # Claim conflicts (multi-instance)
eddi_schedule_fire_deadlettered_total       # Dead-lettered schedules
eddi_schedule_fire_duration_seconds         # Fire latency (timer)
```

### Tenant Quota Metrics

```
eddi_tenant_quota_allowed_total             # Quota checks passed
eddi_tenant_quota_denied_total              # Quota checks denied
eddi_tenant_usage_conversations_total       # Conversation usage (per tenant)
eddi_tenant_usage_api_calls_total           # API call usage (per tenant)
eddi_tenant_usage_cost_total                # Cost usage (per tenant)
```

Quota denied counters include `type` and `tenant` labels:
```promql
rate(eddi_tenant_quota_denied_total{type="cost", tenant="acme"}[5m])
```

### Audit Ledger Metrics

```
eddi_audit_entries_dropped_total            # Audit entries dropped (compliance-critical)
```

### Deployed Agents

```
eddi_agents_deployed                        # Currently deployed agents (gauge)
```

### NATS Messaging Metrics

> Only active when using the NATS messaging profile. Shows nothing under in-memory messaging.

```
eddi_nats_publish_count_total               # Messages published
eddi_nats_consume_count_total               # Messages consumed
eddi_nats_dead_letter_count_total           # Dead letters
eddi_nats_publish_duration_seconds          # Publish latency (timer)
eddi_nats_consume_duration_seconds          # Consume latency (timer)
```

### JVM & HTTP Server (auto-exposed)

Standard Micrometer metrics for Quarkus:

```
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
```
mongodb_driver_pool_size
mongodb_driver_pool_checkedout
mongodb_driver_pool_waitqueuesize
```

**PostgreSQL / Agroal** (when `eddi.datastore.type=postgres`):
```
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

```bash
# Cache stats
GET /langchain/tools/cache/stats

# Rate limit info
GET /langchain/tools/ratelimit/{toolName}

# Cost tracking
GET /langchain/tools/costs
GET /langchain/tools/costs/conversation/{conversationId}
GET /langchain/tools/costs/tool/{toolName}

# Tool history
GET /langchain/tools/history/{conversationId}
```

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

**P95 Conversation Processing Latency:**
```promql
histogram_quantile(0.95,
  sum(rate(eddi_conversation_processing_duration_seconds_bucket[5m])) by (le))
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
