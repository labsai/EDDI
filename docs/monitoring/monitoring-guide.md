# EDDI Monitoring Guide

> **Enterprise observability for EDDI** — metrics, distributed tracing, alerting, and dashboards.

## Quick Start

```bash
# Start EDDI with full monitoring stack. The overlay has no default Grafana
# admin password and refuses to start without one.
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d

# Access points:
#   EDDI API:        http://localhost:7070
#   Prometheus:      http://localhost:9090
#   Grafana:         http://localhost:3000  (admin / GRAFANA_ADMIN_PASSWORD)
# Every port above except EDDI's is published on 127.0.0.1 only.
#   Jaeger UI:       http://localhost:16686
#   EDDI Metrics:    http://localhost:7070/q/metrics
#   EDDI Health:     http://localhost:7070/q/health
```

## Scraping with authentication on

`/q/metrics` requires authentication (`eddi.metrics.http-policy`, default
`authenticated`), because the exposition describes the deployment — agent ids,
traffic, error rates, providers. So once OIDC is on
(`quarkus.oidc.tenant-enabled=true` — `install.sh --full`, the compose auth
overlay, the Kubernetes auth component, `eddi.oidc.enabled` in Helm) an anonymous
Prometheus scrape gets **401** and the `eddi` target shows DOWN. Pick one:

**1. Give Prometheus a token (recommended).** Prometheus can run the OAuth2
client-credentials flow itself and refresh the token as it expires. Create a
confidential client for it in the `eddi` realm:

- *Client authentication* **on**, *Service accounts roles* **on**, every other
  flow off;
- a protocol mapper of type *Audience* that includes `eddi-backend` in the access
  token — EDDI refuses tokens without that audience;
- the `openid` client scope (a default scope in the shipped realm) — EDDI calls
  userinfo on every request, and Keycloak answers it only for `openid` tokens.

No realm role is needed: `/q/metrics` asks for an authenticated caller, nothing
more. Then add to the `eddi` job in `prometheus.yml`:

```yaml
    oauth2:
      client_id: eddi-metrics
      client_secret_file: /etc/prometheus/secrets/client-secret
      token_url: http://keycloak:8080/realms/eddi/protocol/openid-connect/token
      scopes: [openid]
```

and mount the client secret at that path. (The token's issuer is whatever
Keycloak stamps — `KC_HOSTNAME` — and EDDI checks it against
`quarkus.oidc.token.issuer`, so it validates the same way the Manager's tokens do.)

**2. Open `/q/metrics` where it cannot be reached from outside.**
`EDDI_METRICS_HTTP_POLICY=permit` (Helm: `eddi.metrics.httpPolicy=permit`) lets
anyone who can reach the path read it. That is reasonable when only Prometheus can:
EDDI's port is not published beyond the host, or a NetworkPolicy admits only
Prometheus, **and** no ingress route forwards `/q/*` (the Helm chart's ingress
routes every path by default). Otherwise it publishes the exposition to whoever
can reach EDDI.

## Architecture

```text
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
| `eddi_tool_execution_failure` | Counter | Failed tool executions (by tool name) |
| `eddi_tool_execution_ratelimited` | Counter | Rate-limited tool executions |
| `eddi_tool_ratelimit_allowed` | Counter | Allowed rate limit checks (by tool) |
| `eddi_tool_ratelimit_denied` | Counter | Denied rate limit checks (by tool) |
| `eddi_tool_ratelimit_remaining` | Gauge | Remaining rate limit budget |
| `eddi_tool_cache_hits` | Counter | Tool cache hits, aggregate (untagged) |
| `eddi_tool_cache_misses` | Counter | Tool cache misses, aggregate (untagged) |
| `eddi_tool_cache_hits_by_tool` | Counter | Same, tagged by `tool` — a separate meter, not a dimension of the above |
| `eddi_tool_cache_misses_by_tool` | Counter | Same, tagged by `tool` |
| `eddi_tool_cache_puts_by_tool` | Counter | Tool cache writes, tagged by `tool`. There is no untagged `..._puts` meter |
| `eddi_tool_cache_size` | Gauge | Current cache entry count |
| `eddi_tool_cache_get_duration` | Timer | Cache lookup latency |
| `eddi_tool_cache_put_duration` | Timer | Cache write latency |
| `eddi_tool_cache_bypassed` | Counter | Tool calls that skipped the cache because no identity was available to scope the entry to |

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
| `eddi_vault_grant_update_count` | Counter | Grant edits (`allowedAgents` changed without the value) |
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

```text
Trace: POST /agentstore/agents/{agentId}/conversations/{convId}
  └── eddi.pipeline.task [eddi.task.id=ai.labs.behavior, eddi.task.type=behavior_rules]
  └── eddi.pipeline.task [eddi.task.id=ai.labs.property, eddi.task.type=properties]
  └── eddi.pipeline.task [eddi.task.id=ai.labs.llm, eddi.task.type=langchain]
      └── HTTP POST https://api.openai.com/v1/chat/completions (auto)
      └── MongoDB find conversations (auto)
  └── eddi.pipeline.task [eddi.task.id=ai.labs.output, eddi.task.type=output]
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
        expr: increase(eddi_nats_dead_letter_count_total[10m]) > 0
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

## Grafana Dashboards

`docker-compose.monitoring.yml` provisions all three automatically. To load one by
hand: Grafana → Dashboards → Import → upload the JSON → pick your Prometheus data
source.

| File | Dashboard | Shape |
|------|-----------|-------|
| `eddi-operations-dashboard.json` | Operations Command Center (`eddi-ops`) | KPI strip + 9 rows, 51 panels |
| `eddi-full-metrics-dashboard.json` | Full Metrics Reference (`eddi-metrics-all`) | 19 subsystem rows, 138 panels — **every** meter EDDI registers, enforced by `MetricsDashboardCoverageTest` |
| `eddi-grafana-dashboard.json` | EDDI Observability (`eddi-observability`) | The original dashboard, 6 groups: Coordinator Health, Pipeline Tasks, Tool Execution, Vault & Security, NATS, HTTP & JVM |

Start at **Operations Command Center** — it answers "is the platform healthy".
Drop into **Full Metrics Reference** when the number you need is not there; it is
generated from the metric registration sites in the source, so every meter has a
panel. All its rows but `Overview` are collapsed, and a `data source` + `job`
template variable pair scopes the whole thing.

> **Percentile panels.** Only `eddi_pipeline_task_duration` publishes histogram
> buckets, so it is the only EDDI timer where `histogram_quantile()` works. Every
> other timer is charted as mean (`_seconds_sum / _seconds_count`) and peak
> (`_seconds_max`). See [Naming: what the exposition actually looks
> like](../metrics.md#naming-what-the-exposition-actually-looks-like).

## Production Checklist

- [ ] Set `QUARKUS_OTEL_SDK_DISABLED=false` and `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` to your trace collector
- [ ] Configure Prometheus to scrape `/q/metrics` (see `prometheus.yml`) — with OIDC on it needs a token; see [Scraping with authentication on](#scraping-with-authentication-on)
- [ ] Import Grafana dashboard and configure alert notification channels
- [ ] **Keep `GRAFANA_ADMIN_PASSWORD` secret** — the compose overlay has no default for it; a Grafana volume created before that change still has `admin`/`admin` until you change it (re-running the installer does)
- [ ] **Restrict Jaeger UI access** — Jaeger 2.x has no built-in auth; put it behind a reverse proxy or restrict to internal network
- [ ] Set appropriate retention policies (Prometheus: 15d, Jaeger: 7d recommended)
- [ ] Secure `/q/metrics` and `/q/health` endpoints if exposed externally
- [ ] Review `eddi.coordinator.max-active-conversations` for your deployment scale
- [ ] Review [Privacy Note](#privacy-note-gdpr--hipaa) if exporting traces to third-party backends
