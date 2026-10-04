## Cluster mode — shared state, invalidation and cross-node control (2026-10-02)

**Repo:** EDDI · **Branch:** `feat/nats-cluster-state` (stacked on `feat/nats-cluster`)

The second half of horizontal scaling: everything a replica used to keep to itself — caches
of deployed workflows, secrets, rate limits, replay nonces, tool pages — is now either
shared through NATS JetStream KV or invalidated on every node when it changes, and the
operations that must reach the node running a turn (cancel, GDPR stop, HITL, undeploy)
do. With `eddi.messaging.type=in-memory` none of it is wired: every path below falls back
to the single-node behaviour it had before.

### What changed

- **Caches** — [`CacheFactory`](../../src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java)
  decides centrally: state caches (A2A tasks, replay nonces, paginated tool responses, Slack
  dedup and channel locks) become [`SharedKvCache`](../../src/main/java/ai/labs/eddi/engine/caching/SharedKvCache.java)s
  backed by KV buckets with TTLs; derived caches (trigger, user-conversation, conversation
  state, GDPR restriction) become [`ClusterInvalidatingCache`](../../src/main/java/ai/labs/eddi/engine/caching/ClusterInvalidatingCache.java)s
  that publish `cache.evict` / `cache.clear` events keyed by a hash, never the raw key.
- **Config propagation** — `WorkflowFactory` now builds each workflow once per key outside
  its lock (no double build, failures not cached, the `WorkflowId.equals` NPE gone) and
  announces evictions; `SecretResolver`, `GlobalVariableResolver`, `ConnectionRegistry` and
  the `ChatModelRegistry` (plus a max-age) drop entries when another node changes them.
  Undeploy on one node stops the others within seconds, with a two-sweep reconciliation
  against the store as the safety net for a missed event. A request that reaches a node
  for an agent another node has only just deployed no longer answers 404: the node deploys
  it on demand and waits, bounded, for it to become ready (found by the shared live
  harness through a round-robin load balancer). A deploy with `autoDeploy=false` — which
  is deliberately not recorded — is announced too, so every node serves it, and an exported
  agent archive is copied to a JetStream object store
  ([`ClusterArchiveStore`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterArchiveStore.java)),
  so its download works through any node.
- **Rate limits and costs** — `ToolRateLimiter` keeps its global and per-tool buckets in KV
  ([`KvRateLimitBackend`](../../src/main/java/ai/labs/eddi/modules/llm/tools/KvRateLimitBackend.java),
  CAS token bucket); `ToolCostTracker` sums budgets cluster-wide. While NATS is down both
  degrade to the local limit and count the decision.
- **Replay nonces fail closed** — `NonceCacheService` rejects a signed envelope it cannot
  check against the shared bucket (`eddi.agent.nonce.unavailable`).
- **Audit ledger** — sequence numbers come from one CAS counter per conversation
  ([`AuditClusterSupport`](../../src/main/java/ai/labs/eddi/engine/cluster/AuditClusterSupport.java)),
  so replicas no longer collide; the dead `Instance<Connection>` is gone and the collision
  WARN prints its node id instead of a literal `{2}`.
- **Cross-node control** — [`ClusterControlHandlers`](../../src/main/java/ai/labs/eddi/engine/internal/ClusterControlHandlers.java)
  answers `conversation-cancel`, `group-control`, `gdpr-stop` and `coordinator-status`
  requests; cancel, end-conversation, group cancel and GDPR erasure reach the node that
  runs the work, and erasure purges the user's dead letters.
- **HITL crash recovery** — in cluster mode the startup sweep is replaced by a leader-run
  sweep ([`ClusterHitlRecovery`](../../src/main/java/ai/labs/eddi/engine/hitl/ClusterHitlRecovery.java))
  that only touches a conversation nobody holds a lease on and that it has seen unchanged
  for a minimum age, so a rolling restart cannot "recover" a turn another node is running.
- **Coordinator admin** — `GET /administration/coordinator/status?scope=cluster` gathers
  every node; dead letters page with `limit`/`after`; replay rebuilds the turn from its
  captured input (409 and the entry kept when there is none).
- **Slack** — event deduplication is one atomic `putIfAbsent` on the shared cache.

### Design decisions

- One generic `cache.evict` / `cache.clear` event instead of one event type per cache: the
  factory is the only place that knows which caches are derived, so no cache can be
  forgotten when a new one is added.
- HITL takeover is a leader sweep, not inline recovery in the turn runner: a `say` turn that
  crashed leaves the document `READY`, and recovering inline would have broken resumes
  queued behind it.
- Not fixed here, documented in [`clustering.md`](../clustering.md): tool-response pages are
  not bound to their conversation (the tool has no conversation context; the id is a random
  UUID), and Slack follow-ups to a group discussion running on another node are not routed.

### Fixed after the PR #957 review

- **Erasure marks the user on every node before the audit scrub.** `AuditLedgerService` now
  takes part in the cluster `gdpr-stop`, so each node that answers marks the erased user
  before the erasing node pseudonymises the stored rows. Before, a late entry from another
  node kept the raw id unless the `gdpr.user-erased` event happened to arrive first.
- **Shared caches:** one `SharedKvCache` wrapper per local cache, so every caller sees which
  keys live on this node only. A `putIfAbsent` that wins the bucket clears an old
  local-only marker. A lost race still reports the key as present when the stored value
  cannot be read, which is the fail-closed answer for replay nonces.
- **Archive bucket:** a bucket deleted after the first check is created again on the next
  export or download, instead of failing every download through another node.
- **Group lease:** a discussion that cannot take its group lease logs it. A cancel forwarded
  to a holder that answers "not running here" now falls back to the database path instead
  of reporting `false`.
- **Deployment sweep:** a local undeploy forgets the version's record history, so an
  unrecorded redeploy whose event overtook the undeploy is not undeployed again.
