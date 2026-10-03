## 🐛 fix(monitoring): shipped and loaded alert rules, no structurally empty panels, the blind spots instrumented (2026-10-03)

**Repo:** EDDI (`fix/observability`) — REVIEW-2026-10-02 §3.3 and §4.9.

### What changed and why

The 6.5.0 review evaluated all 360 panel queries of the three dashboards against a live Prometheus and found no alert rule shipped or loaded anywhere, about eight panels that could never show data, ratio KPIs that read "No data" on a healthy system, and duplicate panel ids. On a stock 6.5.0 after a 170-check harness run, **92 of 227 panels were empty**. On this branch, after the same harness plus steady LLM traffic, **14 of 240 are**, none of them structurally: they are latencies of features the run never exercised (cascade, Dream, summarisation, `/v1`, connections, the operator canary, NATS) and top-N tables with nothing in the window, each saying so through `noValue`, plus the Agroal panel on a MongoDB deployment.

- **Alert rules.** [`docs/monitoring/eddi-alerts.yml`](../monitoring/eddi-alerts.yml): 25 rules in nine groups. They cover `up`/absent/restart loop, the 5xx ratio, the coordinator backlog and capacity, dead-lettered turns, pipeline task errors, executor saturation, the LLM error ratio and p95, the tool failure ratio, the vault, audit drops/queue fill/collisions, quota-store failures, schedule and NATS dead letters, and heap after GC.
  - The operator gate rule fires on a verified → unverified transition. The gauge reads 0 on a deployment that never activated the operator, so alerting on the value would page every fresh install.
  - Every rule has a runbook under [Alert runbooks](../monitoring/monitoring-guide.md#alert-runbooks).
  - Loaded by the compose stack (mounted and listed under `rule_files`; both installers download the file), by the Kubernetes component, and by Helm as an opt-in `PrometheusRule`.
  - [`eddi-alerts.test.yml`](../monitoring/eddi-alerts.test.yml) holds `promtool test rules` cases, which CI runs.
- **Dashboards.**
  - HTTP latency is mean/peak: a histogram would multiply each of up to `max-uri-tags` series by about seventy, and #933 is busy bounding that number.
  - The DB pool panels now have data. EDDI builds its own MongoDB client, so `quarkus.mongodb.metrics.enabled` never applied: a Micrometer pool listener was added in `PersistenceModule`, and `quarkus.datasource.metrics.enabled=true` turns on the Agroal meters.
  - The NATS dead-letter query reads `_total`.
  - Ops "Cost / hr" is `increase([1h])`; `rate()` was dollars per second.
  - Tool-success and cache-hit KPIs count a never-registered failure series as zero.
  - "Top missing skills" grouped by a label that does not exist and is now "Capability misses (1h)". The counter is untagged on purpose: the skill string is caller-supplied.
  - Ops gained `$datasource`/`$job` and an `up` KPI. Counter panels fall back to `or on() vector(0)`, cumulative counters are shown as increases, and ratios/latencies say why they are empty.
  - Full: duplicate ids renumbered, the LLM row moved below Overview, `$job` sourced from `process_uptime_seconds`. The old source, `jvm_info`, is scraped as `jvm_info_total`.
- **New meters.**
  - `eddi_coordinator_dead_lettered_total` and `eddi_coordinator_dead_letters_retained`.
  - `eddi_audit_queue_depth` and `eddi_audit_queue_capacity`.
  - `eddi_hitl_pending` and `eddi_hitl_pending_oldest_age_seconds`. `HitlPendingMetrics` reads them from the store on a timer, never on the scrape thread. They read `NaN` when unknown and come with the new property `eddi.hitl.metrics.refresh-interval`.
  - `eddi_runtime_executor_{active,queued,max_threads}{pool}`. `BaseRuntime` counts work queued for and running on the worker pool, which Quarkus' `worker_pool_*` meters do not see.
- **Vault errors.** `VaultSecretProvider` no longer counts "no such key" on `eddi_vault_errors_count_total`. `AgentSigningService` probes for a signing key on every signing attempt, so the counter rose by about one per turn on a healthy deployment: 925 of 927 resolves in the harness run. An alert on it could only ever fire.
- **Kubernetes / Helm** (chart **2.4.0**).
  - The monitoring component loads the rules, gives the Grafana datasource uid `prometheus`, and provisions all three dashboards from generated ConfigMaps.
  - Helm has opt-in `serviceMonitor.*` (relabels the job to `eddi`) and `prometheusRule.*`. They are not under `monitoring.*`, which chart 2.0.0 removed.
  - The new `eddi.tracing.otlpEndpoint` turns tracing on and, with `networkPolicy.enabled`, opens OTLP egress (`networkPolicy.otlpEgressTo`).
  - `scripts/sync-monitoring-assets.py` writes the copies Helm and kustomize can read.
- **Tests and CI.**
  - `MetricsDashboardCoverageTest` now parses every dashboard. It checks every query against the series a scrape can contain (`MonitoringSeries`), panel-id uniqueness, the `$datasource`/`$job` variables, and rate KPIs that can go blank. It also finds meters registered through a name constant: `eddi.tool.costs.accrued` was invisible to it.
  - New `AlertRulesTest`.
  - `DeploymentManifestsTest` covers the component, the chart objects and copy drift.
  - CI's Deployment Manifests job runs promtool and the copy check, and `docs/monitoring/**` now triggers the tests that read it.
- **Docs.**
  - Panel and row counts (the docs said 138/19, the dashboard had 160/22; it now has 166/22).
  - The traced operations: no MongoDB spans and no raw LLM HTTP spans; LLM calls appear as `gen_ai.client.inference`.
  - `COLLECTOR_OTLP_ENABLED` is removed from the compose file. It is a Jaeger 1.x switch, and Jaeger 2.7 received traces without it.
  - Two timers publish buckets, not one.
  - The cost-per-hour PromQL is fixed.
  - The new settings are in the [configuration reference](../configuration-reference.md#metrics--tracing).

- **Eager counters for one-shot alerts.** A counter created by its first increment is first scraped at 1, so `increase()` never saw the first canary failure or the first quota-store outage. `OperatorMetricsService` is now `@Startup` and registers every canary outcome at 0, and `TenantQuotaService` registers `eddi.tenant.quota.unavailable` at 0 for the default tenant. The monitoring guide states the remaining limitation.
- **Review fixes.** `EddiOperatorGateRegressed` no longer pages after a restart. The review's first fix (6h lookback plus 6h of uptime) also hid a real regression during a process's first six hours. The rule now reads the new `eddi_operator_gate_last_verified_timestamp_seconds`: the epoch of the last verified report received by the running process, 0 after boot. Ops "Top 10 Slowest Endpoints" divides summed rates. `BaseRuntime` releases the queued count when `submit` throws an `Error`. The Kubernetes docs and Helm values say to list a cross-namespace Prometheus in `networkPolicy.allowedIngressNamespaces`.

- **Pending approvals are listed oldest first** (PR review). Both stores applied the listing limit to an arbitrary order, so past 1000 pending approvals the oldest could be dropped. That made `eddi_hitl_pending_oldest_age_seconds` under-report and could let the retention sweep miss the approvals it exists to expire. MongoDB reads the dated pauses ascending, then fills the remaining room with undated ones. PostgreSQL orders by the pause time normalised from either JSON representation, with undated and malformed values last. The inbox listings now come back in that order too.

### Design decisions

- **"Silently drops" a second tag shape is accurate and was kept.** The review said the registry throws. Registering `foo` and then `foo{tenant}` on `micrometer-registry-prometheus-simpleclient` 1.17.1, the registry in the 6.5.0 image, returns normally both directly and behind a `CompositeMeterRegistry`. Quarkus registers no `onMeterRegistrationFailed` listener. The page now says which registry was verified.
- **No HTTP histogram.** Mean and peak need no new series. A percentile alert on HTTP latency would have needed the buckets, so the latency alert is on LLM calls, which already publish them.
- **The pending-approval gauge reads the store** rather than computing pauses minus resumes, which resets with every restart. It is capped at 1000 rows, and every replica reports the store-wide number, so the dashboards use `max`.

### Platform Operator

No change. Nothing the operator calls changed: there is no REST or MCP change, and `/q/metrics` and Prometheus are outside its allow-list by design.

### Expected conflicts

- `helm/eddi/Chart.yaml` and `EXPECTED_CHART_VERSION`: P11 (`feat/nats-cluster-deploy`) bumps the chart too.
- `docs/metrics.md`: #933 adds two lines.
- `application.properties`: #933 edits the `# Micrometer` block. This branch adds its properties elsewhere on purpose.

### Follow-ups

- `AgentSigningService` resolves a missing signing key from the store on every signing attempt, uncached.
- Under the PostgreSQL profile a MongoDB client is still created, against `mongodb:27017`; the new pool gauge shows it.

```decision-log
| 2026-10-03 | HTTP latency panels chart mean and peak; http.server.requests publishes no histogram | The P50/P95/P99 panels queried _bucket series nobody publishes | A percentile histogram on every uri-tagged series (cardinality x ~70), and SLO buckets (still x ~10) |
| 2026-10-03 | eddi_vault_errors_count_total no longer counts "no such key" | Agent signing probes a missing key every turn, so the counter could not be alerted on | Tagging the counter by kind (a second tag shape on an existing meter is dropped by the registry) |
```

```regression-note
| 2026-10-03 | Dashboards queried series that do not exist (`eddi_nats_dead_letter_count`, `http_server_requests_seconds_bucket`, a `skill` label, `jvm_info`) and read "No data" forever | The coverage test only substring-matched the Full dashboard against registered meters; nothing checked queries against what a scrape contains | Every dashboard parsed; every query's series checked against registered meters plus a verified built-in list | (this branch) |
```
