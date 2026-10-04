## Cluster mode — Helm, Kubernetes, Compose, docs and a live demo (2026-10-02)

**Repo:** EDDI · **Branch:** `feat/nats-cluster-deploy` (stacked on `feat/nats-cluster-state`)

The delivery half of horizontal scaling: every shipped way to run EDDI can now run it as a
cluster, and every one still refuses to run several in-memory replicas.

### What changed

- **Helm chart 2.5.0** — `eddi.messagingType=nats` unlocks `eddi.replicas > 1`, a real
  HorizontalPodAutoscaler and a `RollingUpdate`; each stays refused with in-memory messaging.
  `eddi.updateStrategy` defaults per mode (Recreate for in-memory, RollingUpdate with
  maxSurge 1 / maxUnavailable 0 for nats); `eddi.terminationGracePeriodSeconds` and
  `eddi.shutdownDrainTimeoutSeconds` default to 75 s / 65 s in cluster mode and the chart
  refuses a grace that does not exceed the drain. The PDB uses `minAvailable` once there is
  more than one replica. Pods get `EDDI_CLUSTER_NODE_ID` from their name and a topology spread.
  The in-chart NATS is a three-node routed JetStream cluster with a non-root, read-only
  container, anti-affinity, a PDB, user/password authentication limited to EDDI's subjects
  (password required, never defaulted) and optional TLS; `nats.externalUrl` points at your
  own. `nats.buildProfileImage` is gone — the published image selects the mode at runtime.
- **Kustomize** — `k8s/overlays/nats` is now the cluster-mode component (three NATS nodes,
  three EDDI replicas, rolling updates, node ids, drain), composed in
  `k8s/examples/postgres-ha`; the NetworkPolicies cover the route port.
- **Compose** — new standalone `docker-compose.cluster.yml` (three replicas, three NATS
  nodes with a required password, MongoDB, nginx round robin from `docker/cluster/nginx.conf`);
  `docker-compose.nats.yml` is documented as the one-replica overlay it is, on NATS 2.11.
- **Docs** — Kubernetes scaling section rewritten (plus the `eddi-secrets` volume name and the
  seeded realm roles, `eddi-viewer` included); architecture, coordinator admin (status
  `nodeId`/`cluster`, paging, honest replay, the status SSE), Slack, monitoring guide, audit
  ledger and GDPR pages updated for cluster mode; README; Prometheus rules in
  `docs/monitoring/eddi-cluster-alerts.yml`.
- **Demo** — [`scripts/cluster-demo/`](../../scripts/cluster-demo/README.md) builds three
  nodes of one build, three NATS nodes, MongoDB or PostgreSQL, nginx and a mock LLM in Docker
  and runs every failure scenario of [`clustering.md`](../clustering.md) with a verdict.
- **CI** — `manifest-lint` renders the cluster shapes and asserts that the chart still refuses
  multi-replica, autoscaled and rolling in-memory installs, an in-chart NATS without a
  password, both NATS sources at once and a drain longer than the grace.

### Design decisions

- `eddi.updateStrategy` reuses the key and null-fallback semantics of the build-hygiene PR
  (#938, chart 2.4.0), extended by the messaging-type default; this chart is 2.5.0 and
  expects that PR to merge first (a trivial conflict on `values.yaml` / `Chart.yaml`).
- The Kustomize component carries no NATS authentication (it would need a Secret the
  operator has to create first); it restricts both NATS ports with NetworkPolicies and points
  to the Helm chart for authentication and TLS.
