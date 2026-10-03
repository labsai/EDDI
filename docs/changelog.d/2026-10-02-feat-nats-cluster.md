## Horizontal scaling on NATS JetStream — runtime cluster mode (2026-10-02)

**Repo:** EDDI · **Branch:** `feat/nats-cluster`

EDDI now scales horizontally from the one published image: `eddi.messaging.type=nats`
is read at runtime, and any number of replicas run behind a plain round-robin load
balancer, sharing one database and one NATS JetStream cluster. `in-memory` stays the
zero-dependency default and behaves exactly as before — no NATS class is instantiated,
no meter is registered and nothing about NATS is logged.

### What changed

- **Runtime selection** — [`ClusterProducers`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterProducers.java)
  picks the coordinator, lease manager, shared state, event bus and RPC by
  `eddi.messaging.type` (any other value fails the boot). The build-profile
  `NatsConversationCoordinator`, `NatsHealthCheck` and `NatsMetrics` are gone with their
  tests: the coordinator published to JetStream under the per-conversation lock (10/20/30 s
  stacking when NATS was slow), made every turn of a node booted without NATS fail, took
  readiness DOWN on any NATS blip, leaked its connection on a partial start, and its dead
  letters could not be listed, discarded or replayed.
- **One NATS connection** — [`NatsConnectionManager`](../../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java)
  connects asynchronously (boot never waits), fails every call fast while disconnected,
  supports user/password, token, creds and nkey auth and TLS/mTLS, and drains on shutdown
  without leaking a half-open connection.
- **Cluster-wide conversation leases with fencing** — a turn still runs on the node that
  received it, but only while it holds the conversation's lease
  ([`KvLeaseManager`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java),
  KV `create` + heartbeat + server-side TTL, release notifications, presence-based
  takeover, handoff fairness). The lease revision is a fencing token: both conversation
  stores refuse a write carrying an older token than the document has seen
  (`_fence`, [`ConversationFencedException`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationFencedException.java)),
  so a node that lost its lease cannot overwrite the turn that ran after it.
- **Coordinator** — the in-memory coordinator's queue logic moved verbatim into
  [`AbstractQueuedConversationCoordinator`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AbstractQueuedConversationCoordinator.java);
  [`ClusterConversationCoordinator`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/ClusterConversationCoordinator.java)
  acquires the lease asynchronously (no NATS I/O under the queue monitor), answers a turn
  that waited past `eddi.cluster.lease.acquire-timeout` with 409 + `Retry-After` (not a dead
  letter — its input was never consumed), and dead-letters failed turns to a JetStream
  stream every node can read, with the turn's input captured for replay. Both coordinators
  now register `eddi.coordinator.total_dead_lettered` and `eddi.coordinator.dead_letters`.

- **Rolling updates without failed turns** — the MongoDB client was closed by a JVM
  shutdown hook, which runs concurrently with Quarkus' shutdown: SIGTERM closed it the moment
  the graceful drain began, so every turn still running failed with "state should be: open"
  and the drain waited out its timeout for turns that could no longer finish (a single node
  was affected exactly the same way). It is now closed by the bean's `@PreDestroy`, after the
  drain. The drain also brackets the coordinator: `beginShutdown` answers the turns still
  waiting for a conversation lease with 409 + `Retry-After` at once, and `completeShutdown`
  releases the leases still held so those conversations move on without waiting out the TTL.
  Found by the live rolling-restart demo.
- **The node RPC and the event bus actually start** — both were `@Typed` to their own class,
  which hid them from `ClusterBootstrap`'s `Instance<ClusterStartable>`, so neither ever
  subscribed: cross-node calls answered "no responders" and no node consumed another's
  events. The unit tests call `startCluster()` themselves and could not see it;
  `ClusterStartableTypingTest` now checks every startable's bean types. Found by the live
  cross-node cancel demo.
- **Discarding a dead letter twice answers 404** — a replicated (R3) stream reports an
  already-deleted entry as 10043 ("sequence not found"), which surfaced as a 500.

### Design decisions

- Execution stays on the receiving node (SSE, caller identity, live group discussions and
  HITL are unchanged); only mutual exclusion moved to NATS. Routing turns to an owner node
  was rejected — it would have meant serializing caller identities and every response channel.
- In-memory replay keeps its coordinator contract (remove the entry) so its tests stay
  untouched. Every dead letter now carries the turn's input, which is what lets the admin
  replay in the follow-up `feat/nats-cluster-state` rebuild the turn instead of only
  dropping the entry.
- Shared state beyond leases and dead letters (caches, rate limits, nonces, audit
  sequences, cross-node cancel/GDPR/HITL) follows in `feat/nats-cluster-state`; Helm,
  Kubernetes, Compose and the demo in `feat/nats-cluster-deploy`.
