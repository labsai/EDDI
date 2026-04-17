# EDDI Monitoring Guide

> **Enterprise observability for EDDI** — metrics, distributed tracing, alerting, and dashboards.

## Quick Start

```bash
# Start EDDI with full monitoring stack
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d

# Access points:
#   EDDI API:        http://localhost:7070
#   Prometheus:      http://localhost:9090
#   Grafana:         http://localhost:3000  (admin/admin)
#   Jaeger UI:       http://localhost:16686
#   EDDI Metrics:    http://localhost:7070/q/metrics
#   EDDI Health:     http://localhost:7070/q/health
```

## Architecture

```
┌─────────────┐     OTLP (gRPC :4317)     ┌──────────┐
│    EDDI      │ ──────────────────────── → │  Jaeger  │ ← Trace visualization
│  (Quarkus)   │                           └──────────┘
│              │     Prometheus scrape      ┌────────────┐
│  /q/metrics  │ ← ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  │ Prometheus │
└─────────────┘                            └─────┬──────┘
                                                 │ Data source
                                           ┌─────▼──────┐
                                           │  Grafana    │ ← Dashboards & Alerts
                                           └────────────┘
```

**EDDI emits two types of telemetry:**

| Type | Protocol | Backend | What's captured |
|------|----------|---------|-----------------|
| **Traces** | OTLP (gRPC) | Jaeger / Tempo / Datadog | REST requests, pipeline tasks, HTTP calls, MongoDB ops |
| **Metrics** | Prometheus scrape | Prometheus → Grafana | Counters, gauges, histograms for all EDDI subsystems |

## Metrics Reference

### Pipeline & Coordinator

| Metric | Type | Description |
|--------|------|-------------|
| `eddi_pipeline_task_duration` | Timer | Per-task execution time (tagged by `task.id`, `task.type`) |
| `eddi_pipeline_task_errors` | Counter | Task failures (tagged by `task.id`, `task.type`) |
| `eddi_coordinator_active_conversations` | Gauge | Number of active conversation queues |
| `eddi_coordinator_queue_depth` | Gauge | Total queued callables across all conversations |
| `eddi_coordinator_total_processed` | Counter | Monotonic count of completed conversation tasks |

### Tool Execution

| Metric | Type | Description |
|--------|------|-------------|
| `eddi_tool_execution_parallel` | Counter | Parallel tool execution batches |
| `eddi_tool_execution_failure` | Counter | Failed tool executions (by tool name) |
| `eddi_tool_execution_ratelimited` | Counter | Rate-limited tool executions |
| `eddi_tool_ratelimit_allowed` | Counter | Allowed rate limit checks (by tool) |
| `eddi_tool_ratelimit_denied` | Counter | Denied rate limit checks (by tool) |
| `eddi_tool_ratelimit_remaining` | Gauge | Remaining rate limit budget |
| `eddi_tool_cache_hits` | Counter | Tool cache hits (global + per-tool) |
| `eddi_tool_cache_misses` | Counter | Tool cache misses (global + per-tool) |
| `eddi_tool_cache_puts` | Counter | Tool cache put operations |
| `eddi_tool_cache_size` | Gauge | Current cache entry count |
| `eddi_tool_cache_get_duration` | Timer | Cache lookup latency |
| `eddi_tool_cache_put_duration` | Timer | Cache write latency |

### Secrets Vault

| Metric | Type | Description |
|--------|------|-------------|
| `eddi_vault_resolve_count` | Counter | Secret resolution attempts |
| `eddi_vault_resolve_duration` | Timer | Secret resolution latency |
| `eddi_vault_resolve_errors` | Counter | Failed secret resolutions |
| `eddi_vault_store_count` | Counter | Secret store operations |
| `eddi_vault_store_duration` | Timer | Secret store latency |
| `eddi_vault_delete_count` | Counter | Secret deletions |
| `eddi_vault_rotate_count` | Counter | Secret rotations |
| `eddi_vault_errors_count` | Counter | General vault errors |
| `eddi_vault_cache_hits` | Counter | Vault cache hits |
| `eddi_vault_cache_misses` | Counter | Vault cache misses |
| `eddi_vault_resolve_time` | Timer | End-to-end resolve time (incl. cache) |

### NATS (when `eddi.messaging.type=nats`)

| Metric | Type | Description |
|--------|------|-------------|
| `eddi_nats_publish_count` | Counter | Messages published to NATS |
| `eddi_nats_publish_duration` | Timer | Publish latency |
| `eddi_nats_consume_count` | Counter | Messages consumed from NATS |
| `eddi_nats_consume_duration` | Timer | Consume/processing latency |
| `eddi_nats_dead_letter_count` | Counter | Messages sent to dead-letter stream |

## Distributed Tracing

### What's Traced Automatically

Quarkus OpenTelemetry auto-instruments:
- **JAX-RS REST endpoints** — every inbound HTTP request
- **Vert.x HTTP client** — outbound API calls (httpcalls, LLM providers)
- **MongoDB operations** — database queries and writes

### Custom Pipeline Spans

EDDI adds manual spans in `LifecycleManager` for each pipeline task:

```
Trace: POST /agentstore/agents/{agentId}/conversations/{convId}
  └── eddi.pipeline.task [task.id=ai.labs.behavior, task.type=behaviorRules]
  └── eddi.pipeline.task [task.id=ai.labs.property, task.type=propertySetter]
  └── eddi.pipeline.task [task.id=ai.labs.llm, task.type=langchain]
      └── HTTP POST https://api.openai.com/v1/chat/completions (auto)
      └── MongoDB find conversations (auto)
  └── eddi.pipeline.task [task.id=ai.labs.output, task.type=outputGeneration]
```

**Span attributes:**

| Attribute | Example | Description |
|-----------|---------|-------------|
| `eddi.task.id` | `ai.labs.llm` | Task identifier |
| `eddi.task.type` | `langchain` | Task type (config file name) |
| `eddi.task.index` | `4` | Position in pipeline |
| `eddi.conversation.id` | `abc-123` | Conversation identifier |
| `eddi.agent.id` | `agent-xyz` | Agent identifier |

### Configuration

```properties
# application.properties — OTel is disabled by default
quarkus.otel.service.name=eddi
quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
quarkus.otel.sdk.disabled=true

# Enable via env var when a collector is available:
#   QUARKUS_OTEL_SDK_DISABLED=false
#   QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
# docker-compose.monitoring.yml sets these automatically.
```

**Switching backends:** EDDI uses standard OTLP protocol. To switch from Jaeger to Grafana Tempo, Datadog, or Honeycomb, just change the endpoint URL — no code changes needed.

### Privacy Note (GDPR / HIPAA)

> ⚠️ **Trace spans include `eddi.conversation.id` and `eddi.agent.id`.** If traces are exported to a third-party backend (Datadog, Honeycomb, Grafana Cloud), these identifiers leave your security boundary. While they are opaque IDs (not PII themselves), they can be correlated to user sessions.
>
> For regulated environments:
> - Ensure your trace backend is covered by appropriate DPAs (Data Processing Agreements)
> - Consider restricting trace export to self-hosted backends (Jaeger, Tempo) only
> - Review Quarkus [OTel resource attributes](https://quarkus.io/guides/opentelemetry) for additional data that may be auto-attached

## Alerting Rules

Add these to your Prometheus alerting configuration:

```yaml
groups:
  - name: eddi
    rules:
      - alert: EddiToolRateLimitDenied
        expr: rate(eddi_tool_ratelimit_denied_total[5m]) > 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Tool rate limit denials detected"
          description: "Tool {{ $labels.tool }} is being rate-limited at {{ $value }}/s"

      - alert: EddiCoordinatorQueueBacklog
        expr: eddi_coordinator_queue_depth > 100
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Conversation queue backlog detected"
          description: "{{ $value }} tasks queued across all conversations"

      - alert: EddiCoordinatorNearCapacity
        expr: eddi_coordinator_active_conversations > 9000
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Coordinator approaching capacity limit (10,000)"
          description: "{{ $value }} active conversations (90% of limit)"

      - alert: EddiVaultResolveErrors
        expr: rate(eddi_vault_resolve_errors_total[5m]) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Vault secret resolution failures"
          description: "Secret resolution errors at {{ $value }}/s — LLM calls may fail"

      - alert: EddiDeadLetterAccumulation
        expr: eddi_nats_dead_letter_count > 0
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Dead-lettered conversation tasks detected"
          description: "{{ $value }} tasks have been dead-lettered after exhausting retries"

      - alert: EddiHighToolFailureRate
        expr: rate(eddi_tool_execution_failure_total[5m]) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High tool execution failure rate"
          description: "Tool {{ $labels.tool }} failing at {{ $value }}/s"
```

## Grafana Dashboard

Import `eddi-grafana-dashboard.json` from this directory:

1. Open Grafana → Dashboards → Import
2. Upload `eddi-grafana-dashboard.json`
3. Select your Prometheus data source
4. Dashboard loads with 5 row groups:
   - **Coordinator Health** — active conversations, queue depth, total processed
   - **Tool Execution** — rate limits, cache efficiency, failures
   - **Vault & Security** — resolve latency, error rate, cache hits
   - **NATS Messaging** — publish/consume throughput (when enabled)
   - **HTTP & JVM** — request rate, latency percentiles, heap usage (Quarkus built-in)

## Production Checklist

- [ ] Set `QUARKUS_OTEL_SDK_DISABLED=false` and `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` to your trace collector
- [ ] Configure Prometheus to scrape `/q/metrics` (see `prometheus.yml`)
- [ ] Import Grafana dashboard and configure alert notification channels
- [ ] **Change `GF_SECURITY_ADMIN_PASSWORD`** before exposing Grafana publicly (default: `admin/admin`)
- [ ] **Restrict Jaeger UI access** — Jaeger 2.x has no built-in auth; put it behind a reverse proxy or restrict to internal network
- [ ] Set appropriate retention policies (Prometheus: 15d, Jaeger: 7d recommended)
- [ ] Secure `/q/metrics` and `/q/health` endpoints if exposed externally
- [ ] Review `eddi.coordinator.max-active-conversations` for your deployment scale
- [ ] Review [Privacy Note](#privacy-note-gdpr--hipaa) if exporting traces to third-party backends
