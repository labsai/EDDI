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

### Design decisions

- Execution stays on the receiving node (SSE, caller identity, live group discussions and
  HITL are unchanged); only mutual exclusion moved to NATS. Routing turns to an owner node
  was rejected — it would have meant serializing caller identities and every response channel.
- In-memory replay keeps its coordinator contract (remove the entry) so its tests stay
  untouched; the admin REST replay is what became honest — it rebuilds the turn from the
  captured input and refuses (409, entry kept) an entry without one.
