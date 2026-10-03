## Cluster mode, review of the shared-state and delivery branches (2026-10-03)

**Repo:** EDDI · **Branch:** `review/p11b` (on `feat/nats-cluster-deploy`)

An independent review of `feat/nats-cluster-state` and `feat/nats-cluster-deploy`. Each fix
below belongs to the branch named in its commit body.

### What changed

- **Helm and Kustomize (deploy):**
  - The in-chart NATS user could not publish to `$O.*.>`, so the exported-archive object store
    never worked on Kubernetes (a permission violation in the NATS log, the archive silently
    kept on one node).
  - NATS probes were inverted: liveness was the strict `/healthz` and readiness the weakest
    check, so a rolling update could restart the next node before the first had caught up its
    R3 streams. Startup is now strict, readiness is `js-server-only`, liveness
    `js-enabled-only` (chart and `k8s/overlays/nats`).
  - With `networkPolicy.enabled` and `nats.externalUrl` every replica was cut off from NATS (the
    HTTPS rule excludes private ranges): the policy now opens the URL's port, narrowable with
    `networkPolicy.natsEgressTo`.
  - `eddi.updateStrategy` accepts the Deployment's own strategy object as well as a string, so a
    values file written for the other shape no longer renders `type: map[type:Recreate]`.
- **Shared state (state):** a `WorkflowFactory` build that dies with an `Error` no longer leaves
  an incomplete future in the cache that blocks every later caller; two concurrent downloads of
  one exported archive no longer write the same partial file; an unrecorded (`autoDeploy=false`)
  redeploy of a version that was recorded once is no longer undeployed by the reconciliation
  sweep; the HITL recovery sweep forgets conversations that left IN_PROGRESS.
- **Docs:** NATS as a trust boundary, the nonce degraded behaviour as it actually is, moving
  from one node to a cluster and back, and the residual limits the review found (audit chain
  gap after a node crash, cache staleness bounds, the undeploy convergence window).

### Design decisions

- Docs describe the audit gap instead of reserving ranges: the design rejects ranges, and the
  window is the audit flush interval.
- Cache staleness bounds are documented, not shortened: prompt snippets have no event and rely
  on their 5-minute TTL.

### Merge notes for the open PRs

- `eddi.updateStrategy` (#938) is an object there and a string here; the chart now accepts both,
  but #938 also documents `RollingUpdate` with in-memory messaging as an opt-in, which this
  branch refuses.
- `helm/eddi/Chart.yaml` and `EXPECTED_CHART_VERSION` conflict with #938 and #951 (both 2.4.0).
- `AuditLedgerService`: #952 changes `pseudonymiseIfErased` to take a flag and redact content;
  a user erased on another node (`gdpr.user-erased`, by hash) is pseudonymised here but must
  also be redacted there. #952 still takes the `Instance<Connection>` constructor parameter.
- #953 (`CachedA2ATaskStore`) composes with the KV-backed `A2A_TASKS`/`A2A_CONTEXTS` caches
  (`A2ATaskRecord` round-trips through Jackson); `A2A_STATES` becomes unused, and the task
  record carries the user's and the agent's text into the KV bucket for the task TTL, which
  the GDPR erasure does not purge.
