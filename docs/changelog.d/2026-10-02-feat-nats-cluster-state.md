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
  harness through a round-robin load balancer).
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
