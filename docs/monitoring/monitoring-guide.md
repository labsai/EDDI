# EDDI Monitoring Guide

> **Enterprise observability for EDDI** — metrics, distributed tracing, alerting, and dashboards.

## Quick Start

```bash
# Start EDDI with full monitoring stack
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d

# Access points:
#   EDDI API:        http://localhost:7070
#   Prometheus:      http://localhost:9090
#   Grafana:         http://localhost:3000  (admin/admin unless GRAFANA_ADMIN_PASSWORD is set)
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
| **Traces** | OTLP (gRPC) | Jaeger / Tempo / Datadog | REST requests, pipeline tasks, LLM provider calls (`gen_ai.client.inference`) |
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
| `eddi_coordinator_dead_lettered` | Counter | Turns the in-memory coordinator dead-lettered (cumulative) |
| `eddi_coordinator_dead_letters_retained` | Gauge | Dead letters held for inspection right now |
| `eddi_runtime_executor_queued` / `_active` | Gauge | Conversation work waiting for / running on a worker thread (tag `pool`) |

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

### What's Traced

Quarkus OpenTelemetry instruments **inbound HTTP**: every REST request is a
server span named after its route template (`POST /agents/{conversationId}`).
Outbound calls made through Quarkus' own REST client appear as client spans.

Not traced: **database operations** (EDDI builds its own MongoDB client, with no
tracing listener, and the PostgreSQL datastore has no JDBC instrumentation) and
the **raw HTTP of LLM provider calls** — langchain4j uses the JDK HTTP client,
which OpenTelemetry does not instrument. LLM calls are covered by EDDI's own
`gen_ai.client.inference` span instead (below).

### EDDI's Own Spans

`LifecycleManager` adds a span per pipeline task, and `LlmTelemetryListener` one
per LLM provider call, following the OpenTelemetry GenAI conventions:

```text
Trace: POST /agents/{conversationId}
  └── eddi.pipeline.task [eddi.task.id=ai.labs.behavior, eddi.task.type=behavior_rules]
  └── eddi.pipeline.task [eddi.task.id=ai.labs.property, eddi.task.type=properties]
  └── eddi.pipeline.task [eddi.task.id=ai.labs.llm, eddi.task.type=langchain]
      └── gen_ai.client.inference [gen_ai.provider.name=openai, gen_ai.request.model=…,
                                   gen_ai.usage.input_tokens, gen_ai.usage.output_tokens]
  └── eddi.pipeline.task [eddi.task.id=ai.labs.output, eddi.task.type=output]
```

A failed LLM call sets the span's status to ERROR with `error.type`; the provider's
error text is redacted and capped, never copied raw (it can echo the prompt).

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

**Helm:** set `eddi.tracing.otlpEndpoint` (e.g. `http://otel-collector.observability:4317`).
The chart turns the SDK on, and with `networkPolicy.enabled` also opens
`eddi.tracing.otlpPort` (default 4317) to `networkPolicy.otlpEgressTo` — the
default-deny policy otherwise drops every span bound for an in-cluster collector.
**Kustomize:** the production NetworkPolicy carries the same rule, commented out;
uncomment and narrow it when you turn tracing on.

**Jaeger 2.x** receives OTLP on 4317/4318 out of the box. `COLLECTOR_OTLP_ENABLED`
is a Jaeger 1.x setting and does nothing on the 2.x image the compose stack runs.

**Switching backends:** EDDI uses standard OTLP protocol. To switch from Jaeger to Grafana Tempo, Datadog, or Honeycomb, just change the endpoint URL — no code changes needed.

### Privacy Note (GDPR / HIPAA)

> ⚠️ **Trace spans include `eddi.conversation.id` and `eddi.agent.id`.** If traces are exported to a third-party backend (Datadog, Honeycomb, Grafana Cloud), these identifiers leave your security boundary. While they are opaque IDs (not PII themselves), they can be correlated to user sessions.
>
> For regulated environments:
> - Ensure your trace backend is covered by appropriate DPAs (Data Processing Agreements)
> - Consider restricting trace export to self-hosted backends (Jaeger, Tempo) only
> - Review Quarkus [OTel resource attributes](https://quarkus.io/guides/opentelemetry) for additional data that may be auto-attached

## Alerting Rules

The rules ship with EDDI: [`eddi-alerts.yml`](eddi-alerts.yml) (25 rules, nine
groups). Where they are loaded:

| Delivery path | How |
|---|---|
| `docker-compose.monitoring.yml` | Mounted at `/etc/prometheus/eddi-alerts.yml` and listed under `rule_files` in [`prometheus.yml`](prometheus.yml). Prometheus' **Alerts** page shows them |
| Kubernetes component (`k8s/overlays/monitoring`) | A generated `prometheus-rules` ConfigMap, mounted at `/etc/prometheus-rules` |
| Helm chart | `--set prometheusRule.enabled=true` renders a `PrometheusRule`, `--set serviceMonitor.enabled=true` a `ServiceMonitor`. Both need the Prometheus Operator's CRDs; add `prometheusRule.labels.release=<your kube-prometheus-stack release>` if your Prometheus selects rules by label |

Prometheus **evaluates** the rules on its own; to be **notified**, point it at an
Alertmanager (`alerting: alertmanagers:` in `prometheus.yml`) and route the
`severity` label (`critical`, `warning`, `info`).

Conventions:

- The rules over generic series (`up`, `http_*`, `jvm_*`, `process_*`) select on
  `job="eddi"`. The compose and Kubernetes scrape configs use that job name, and
  the Helm `ServiceMonitor` relabels to it. Keep it if you write your own.
- Every ratio alert also needs a minimum number of events, so one failure on an
  idle system pages nobody.
- Thresholds are starting points for one replica with modest traffic. Edit the
  YAML; the copies in `helm/eddi/files/` and `k8s/overlays/monitoring/` are
  regenerated with `python scripts/sync-monitoring-assets.py` (the build fails when
  they drift).
- Validate a change with `promtool check rules docs/monitoring/eddi-alerts.yml` and
  `promtool test rules docs/monitoring/eddi-alerts.test.yml` (CI runs both).

## Alert runbooks

### EddiDown

`up{job="eddi"} == 0` for 2 minutes: Prometheus cannot scrape the target. Check, in
order: is the process running (`docker compose ps`, `kubectl get pods`); does
`curl -s -o /dev/null -w '%{http_code}' http://<eddi>:7070/q/metrics` answer 200 — a
**401** means authentication is on and the scrape has no token (see
[Scraping with authentication on](#scraping-with-authentication-on)); is
`/q/health/ready` UP. A target that answers but is slow to scrape shows as down at
the 10 s scrape timeout.

### EddiMetricsAbsent

There is no `up{job="eddi"}` series at all. Service discovery found no EDDI pod
(Kubernetes: the pod lost its `prometheus.io/scrape` annotation, or the
ServiceMonitor's selector does not match the Service), or the scrape job has a
different name — the rules expect `eddi`.

### EddiRestartLoop

The process started three or more times in 30 minutes. `kubectl describe pod`
shows `OOMKilled` or a failing liveness probe; the previous container's log
(`kubectl logs --previous`, `docker compose logs --since 30m eddi`) shows why it
stopped. A boot that fails on the vault key or the datastore exits early on every
attempt.

### EddiHighHttp5xxRatio

More than 5% of requests answered 5xx for 10 minutes. The **Top 10 Slowest
Endpoints** table and `sum by (uri, status) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))`
show which route; `/administration/logs?level=ERROR` the cause. A burst on
`/agents/{conversationId}` usually follows an LLM or datastore outage — check the
LLM and pipeline alerts first.

### EddiCoordinatorQueueBacklog

More than 50 turns waiting behind earlier turns of the same conversations, for 5
minutes. Turns are slower than users send them. `GET /administration/coordinator/status`
lists queue depths per conversation; one conversation with a deep queue is a stuck
turn (the agent timeout ends it), many shallow ones a slow LLM or tool —
compare `EddiLlmLatencyHigh` and the tool panels.

### EddiCoordinatorNearCapacity

More than 9000 active conversation queues. The in-memory coordinator refuses NEW
conversations at `eddi.coordinator.max-active-conversations` (default 10000);
existing ones keep working. A climbing count that never falls is a leak worth a
bug report; steady high load needs the cap raised (each entry is small).

### EddiConversationTurnsDeadLettered

A turn failed after it had started and was dead-lettered by the in-memory
coordinator. `GET /administration/coordinator/dead-letters` lists them with the
error; the conversation itself stays usable. Replay or discard them there once the
cause is fixed.

### EddiPipelineTaskErrors

More than 10% of one task type's executions threw, for 10 minutes. The Full
dashboard's **Task errors by task and error type** table names the task id and
exception; `/administration/logs` has the stack trace. Usually a broken agent
configuration (template, HTTP call target) or an unreachable dependency.

### EddiExecutorSaturated

At least one conversation turn has been waiting for a worker thread for 5 minutes:
every thread of the pool is busy. Find what holds them — long LLM calls, slow tools,
a blocking HTTP call — on the **Turn executor load** panel and in a thread dump
(`jcmd <pid> Thread.print`). Raise `quarkus.thread-pool.max-threads` only once the
holders are understood; more threads also mean more concurrent load on the LLM
provider and the datastore.

### EddiApprovalWaitingLong

The oldest conversation paused for a human has waited more than 24 hours
(`info`). Expected under `WAIT_INDEFINITELY`; otherwise nobody works the approval
inbox (`GET /agents/pending-approvals`, the Manager's approvals view). Set
`eddi.hitl.pending.max-age` to auto-cancel abandoned approvals.

### EddiLlmErrorRatio

More than 10% of calls to one LLM provider failed for 5 minutes.
`eddi_llm_request_errors_total` names the exception (Full dashboard, **LLM error
rate by exception**): authentication errors point at the API key or vault secret,
rate-limit errors at the provider quota, timeouts at provider latency. A model
cascade can mask a failing first step — check `eddi_llm_cascade_step_errors_total`.

### EddiLlmLatencyHigh

The p95 of one provider's call duration stayed above 30 seconds for 15 minutes.
Check the provider's status page and the token rate (long outputs are slow
outputs). Turns wait for these calls, so the coordinator backlog and executor
saturation alerts tend to follow.

### EddiToolFailureRatio

More than 25% of one tool's executions failed for 10 minutes (at least 5
failures). Failures include rate-limit refusals (`eddi_tool_execution_ratelimited_total`)
and timeouts (`eddi_tool_execution_timeout_total`); the remainder are the tool's
own errors, logged as `Tool '<name>' failed`. An HTTP tool failing on every call
usually has a moved endpoint or an expired credential.

### EddiVaultErrors

The vault provider failed to store, read, rotate or delete a secret. Check the
datastore (the vault lives there) and the master key: a changed
`EDDI_VAULT_MASTER_KEY` makes every existing secret undecryptable. See
[secrets-vault.md](../secrets-vault.md).

### EddiVaultResolveErrors

Secret references (`${eddivault:…}`) keep failing to resolve: a deleted secret, an
agent not in the secret's `allowedAgents`, or a key the vault cannot decrypt. The
agent that needs it fails its LLM or HTTP calls with the same error.

### EddiOperatorGateRegressed

The Platform Operator's write-approval gate read back as sound within the last 6 hours
and does not now (an EDDI restart within that window silences the alert: the gauge
starts at 0 on every boot). Until it is re-verified the operator's write tools may run without
human approval. Treat it as a security incident: open the Manager's Platform
Operator page, which re-reads the gate from the live agent document and says why
it is unverified, and re-provision the operator.

### EddiOperatorCanaryFailing

The Manager's synthetic write canary reported a failure in the last hour: an
operator write did not behave as the gate requires. Investigate as for
EddiOperatorGateRegressed.

### EddiAuditEntriesDropped

The audit queue was full and entries were dropped. A dropped entry is
unrecoverable and invisible to `/auditstore/verify`. The audit store is not keeping
up: check the datastore's health and write latency, then raise
`eddi.audit.max-queue-size` if the store is healthy and merely bursty. See
[audit-ledger.md](../audit-ledger.md).

### EddiAuditQueueFilling

The audit queue is more than half full for 5 minutes — the warning before
EddiAuditEntriesDropped. Same causes and remedies.

### EddiAuditSequenceCollisions

Another replica allocated chain positions for a conversation this one is writing.
`/auditstore/verify` grades those conversations `BROKEN`. Run one replica, or route
every conversation to one replica. See
[audit-ledger.md → Chain sequences and multi-replica deployments](../audit-ledger.md#chain-sequences-and-multi-replica-deployments).

### EddiQuotaStoreUnavailable

Tenant quota checks failed closed: requests were refused because the quota store
could not be read, not because a limit was reached. Users see quota errors with
quota to spare. Check the datastore.

### EddiScheduleFiresDeadLettered

A schedule exhausted its retries. `GET /schedulestore/schedules/{id}/fires` shows the
failed fire and its error; `POST /schedulestore/schedules/{id}/retry`
fires it again once the cause is fixed. See [scheduling.md](../scheduling.md).

### EddiNatsDeadLetters

The NATS coordinator moved conversation tasks to the dead-letter stream after
exhausting their retries. Inspect them through the coordinator admin API
(`/administration/coordinator/dead-letters`) and the NATS stream.

### EddiHeapAfterGcHigh

More than 85% of the maximum heap is still live after garbage collection, for 10
minutes. The next burst ends in long GC pauses or an OutOfMemoryError. Raise the
container memory limit (the JVM sizes its heap from it), or find the growth: the
coordinator and cache gauges, a heap histogram (`jcmd <pid> GC.class_histogram`).

### EddiHeapAfterGcCritical

More than 95% live after GC for 5 minutes: an OutOfMemoryError is imminent. Same as
EddiHeapAfterGcHigh, urgently.

## Grafana Dashboards

`docker-compose.monitoring.yml` provisions all three automatically. To load one by
hand: Grafana → Dashboards → Import → upload the JSON → pick your Prometheus data
source.

| File | Dashboard | Shape |
|------|-----------|-------|
| `eddi-operations-dashboard.json` | Operations Command Center (`eddi-ops`) | KPI strip + 9 rows, 58 panels |
| `eddi-full-metrics-dashboard.json` | Full Metrics Reference (`eddi-metrics-all`) | 22 rows, 166 panels — **every** meter EDDI registers, enforced by `MetricsDashboardCoverageTest` |
| `eddi-grafana-dashboard.json` | EDDI Observability (`eddi-observability`) | The original dashboard, 6 groups: Coordinator Health, Pipeline Tasks, Tool Execution, Vault & Security, NATS, HTTP & JVM |

Start at **Operations Command Center** — it answers "is the platform healthy".
Drop into **Full Metrics Reference** when the number you need is not there; every
meter the code registers is queried by one of its panels, which
`MetricsDashboardCoverageTest` enforces. All its rows but `Overview` are collapsed.
Both carry a `data source` + `job` template variable pair, so they work against any
Prometheus datasource and scrape job name.

The Kubernetes monitoring component provisions all three dashboards into the same
"EDDI" folder (generated ConfigMaps, projected into Grafana's dashboard directory),
and its Prometheus datasource has the fixed uid `prometheus`.

> **Percentile panels.** Only `eddi_pipeline_task_duration` and
> `eddi_llm_request_duration` publish histogram buckets, so they are the only EDDI
> timers where `histogram_quantile()` works. Every other timer — and Quarkus'
> `http_server_requests` — is charted as mean (`_seconds_sum / _seconds_count`) and
> peak (`_seconds_max`). See [Naming: what the exposition actually looks
> like](../metrics.md#naming-what-the-exposition-actually-looks-like).

## Production Checklist

- [ ] Set `QUARKUS_OTEL_SDK_DISABLED=false` and `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` to your trace collector
- [ ] Configure Prometheus to scrape `/q/metrics` (see `prometheus.yml`) — with OIDC on it needs a token; see [Scraping with authentication on](#scraping-with-authentication-on)
- [ ] Import the Grafana dashboards (provisioned automatically by the compose and Kubernetes stacks) and point Prometheus at an Alertmanager so the shipped rules notify someone
- [ ] **Set `GRAFANA_ADMIN_PASSWORD`** before exposing Grafana beyond `127.0.0.1` (compose default: `admin/admin`; the installers generate one, and re-running one moves an existing `admin`/`admin` volume to it)
- [ ] **Restrict Jaeger UI access** — Jaeger 2.x has no built-in auth; put it behind a reverse proxy or restrict to internal network
- [ ] Set appropriate retention policies (Prometheus: 15d, Jaeger: 7d recommended)
- [ ] Secure `/q/metrics` and `/q/health` endpoints if exposed externally
- [ ] Review `eddi.coordinator.max-active-conversations` for your deployment scale
- [ ] Review [Privacy Note](#privacy-note-gdpr--hipaa) if exporting traces to third-party backends
