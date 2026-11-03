## 🔒 fix(cluster): NATS TLS, failing loudly, replication, manifests and alerts (2026-11-03)

**Repo:** EDDI (`fix/cluster-ops-security`) — workstream D of the cluster production-readiness review (`planning/cluster-production-readiness-review.md`, findings C1, C2, C3, C5, C7–C15 and the scheduler starvation of D6).

### What changed and why

- **C1 — TLS host names.** EDDI's hostname-verifying SSL context is now built for every `tls://` or `wss://` server, not only with `eddi.nats.tls.enabled=true`. The chart's external path never set that flag, so jnats fell back to the JVM default context, which accepts any CA-signed certificate for any host: a man in the middle received the shared NATS password. `opentls://` (trust anything) fails the boot. [`NatsConnectionManager.java`](../../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java), [`ClusterConfig.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterConfig.java)
- **C2 — fail loudly.** A node that never connected since boot, or whose connection fails for a reason retrying cannot heal (authentication, TLS, client options), now reports not-ready (`eddi.cluster.readiness.require-first-connect`, `eddi.cluster.readiness.fail-on-misconfiguration`, both default `true`) instead of serving every turn unfenced while green. Every NATS error is classified and counted in `eddi_cluster_nats_errors_total{cause}` (all causes registered at 0); misconfigurations log at ERROR, the rest at WARN, rate-limited; `eddi_cluster_nats_misconfigured` is a gauge. Trust stores may be PEM (a cert-manager `ca.crt`), and the chart mounts the NATS CA — the in-chart TLS Secret's, or `nats.externalCaSecretName` — into EDDI as its trust store; the in-chart URL is `tls://` with `nats.tls.enabled`. [`ClusterHealthCheck.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterHealthCheck.java)
- **C5 — dead connections.** `eddi.nats.ping-interval` (5 s) and `eddi.nats.max-pings-out` (2): a server lost without a TCP reset is noticed in about 10–15 s instead of about 4 minutes.
- **C3 — replication.** The chart renders `EDDI_NATS_REPLICAS` for an external NATS too (`nats.externalReplicas`, default 3); EDDI warns at connect when R1 meets three or more servers, or the replica count exceeds the servers it knows.
- **C7 — rollouts.** Readiness still flips at SIGTERM, but turns are admitted for the readiness grace (3 s) before the gate closes; the chart (`eddi.preStopSleepSeconds`, 5) and the Kustomize `nats` component add a `preStop` sleep, counted in the termination-grace check. [`GracefulShutdownService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/GracefulShutdownService.java)
- **C8 — Kustomize NATS.** The `nats` component now authenticates clients and routes (credentials from a `nats-auth` Secret the operator creates — **existing users must create it before applying**), restricts EDDI's user, sets `max_file_store` with headroom, requires one NATS pod per node, spreads zones, sets `GOMEMLIMIT` and `EDDI_CLUSTER_DEGRADED_TURNS=reject`. TLS remains the Helm chart's.
- **C9 — storage.** `<prefix>_ARCHIVES` gets `eddi.cluster.archives.max-bytes` (1 GiB; an existing bucket is capped on first use); the in-chart `max_file_store` is 80 % of the volume (`nats.storage.maxFileStore` overrides). [`ClusterArchiveStore.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterArchiveStore.java)
- **C10 — NATS StatefulSet.** `nats:2.11.17-alpine` pinned by digest on every delivery path (chart, Kustomize, compose; `ThirdPartyImagePinsTest` enforces it), required anti-affinity (`nats.antiAffinity`) plus zone spread, `nats.goMemLimit`, an optional `prometheus-nats-exporter` sidecar with a ServiceMonitor (`nats.metrics.enabled`), and a second user for password rotation without downtime (`nats.auth.secondaryUsername`/`secondaryPassword`).
- **C11 / C4 (chart side) — isolation and least privilege.** The in-chart and Kustomize NATS deny EDDI's user `$JS.API.STREAM.DELETE.>`, `$JS.API.SERVER.>`, `$JS.API.META.>` and `$JS.API.ACCOUNT.PURGE.>` (checked against a real NATS 2.11.17: KV, streams, purge and object store still work; a stream delete is refused). `eddi.nats.inbox-prefix` (default jnats' `_INBOX`) and the opt-in `nats.isolation.enabled` put a deployment in its own NATS account with its own inbox prefix — opt-in because it moves the buckets into a new account.
- **C12 — alerts.** `eddi-cluster-alerts.yml` gains NATS misconfiguration, NATS errors by cause, member loss, lease takeovers, an event-outbox backlog and, from the NATS exporter, JetStream storage, a full dead-letter stream and slow consumers; it has promtool tests (`eddi-cluster-alerts.test.yml`, run in CI) and the chart's PrometheusRule includes it in cluster mode.
- **C13 — ingress.** With `ingress.className: nginx` the chart disables buffering and sets a 300 s read timeout (SSE), and in cluster mode adds an `<release>-mcp` Ingress hashing `/mcp` on the client address (`ingress.nginxDefaults`, `ingress.readTimeoutSeconds`).
- **C14 — HPA.** `autoscaling.targetMemoryUtilizationPercentage` defaults to `null`: a JVM keeps its heap, so the memory target ratcheted the HPA to `maxReplicas`.
- **C15 — configuration.** Boot refuses non-positive durations, two authentication methods at once and a malformed inbox prefix; warnings for an acquire timeout below the lease TTL or at or above the agent timeout, plaintext credentials and `verify-hostname=false`.
- **D6 — scheduler.** The cluster scheduler's two platform threads now only keep time; every job runs on a virtual thread and never overlaps itself ([`OffloadingScheduler.java`](../../src/main/java/ai/labs/eddi/engine/cluster/OffloadingScheduler.java)), so jobs blocked on NATS or the database can no longer delay presence past its 30 s TTL.
- **Docs:** [Clustering](../clustering.md) (degraded mode, failure modes, permissions, TLS, alerts), [Kubernetes](../kubernetes.md), [Configuration reference](../configuration-reference.md), [Metrics](../metrics.md), the monitoring guide; the Platform Operator explains a not-ready node (revision bump). Helm chart **2.14.0**.

### Design decisions

- Readiness DOWN for a misconfiguration takes every node with the same bad credential out of the load balancer. That is the intended trade: loud instead of silently unfenced. Both switches can be turned off.
- The code default of `degraded.turns` stays `local`; the chart exposes `eddi.cluster.degradedTurns` and the docs and the Kustomize component recommend `reject`.
- Account isolation and a custom inbox prefix are opt-in: switching an existing release would strand its dead letters and archives in the old account.

### What's next

- Per-node NKey/JWT users need an external NATS with account management; the chart stays with password users.
- Signed RPC/events (C4) and the receiving-side last-node guard are workstreams C and B.

```decision-log
| 2026-11-03 | NATS auth/TLS failures and "never connected since boot" fail readiness by default | Review C2: such nodes served every turn unfenced while green | Capping unfenced time in the coordinator (owned by another workstream); keeping readiness UP |
| 2026-11-03 | Cluster scheduler jobs run on virtual threads behind a timer-only pool | Review D6: two blocked jobs starved presence past its TTL | A dedicated presence thread only (presence is owned by another workstream) |
```
