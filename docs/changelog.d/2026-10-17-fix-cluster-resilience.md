## 🐛 fix(cluster): leases that survive a busy node, turns that stop when they lose them, one turn per idempotency key across nodes (2026-10-17)

**Repo:** EDDI · **Branch:** `fix/cluster-resilience`

### What changed and why

An assessment of cluster mode (NATS leases with fencing, event bus, degraded mode) found
the design sound and its failure scenarios tested. It also found a handful of places where
a node under strain, or a NATS that hangs instead of refusing, does worse than the
[clustering page](../clustering.md) promises. Per-turn database work is
#1055's and node sizing is #1053's; neither is touched here.

- **Lease heartbeats get a thread of their own** ([`NatsConnectionManager`](../../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java),
  [`KvLeaseManager`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java)).
  They shared a 2-thread scheduler with presence, the console's pollers, the HITL recovery
  sweep (a database scan) and every on-connect hook (up to 10,000 synchronous outbox
  publishes, bucket provisioning, the stale-lease sweep). Right after NATS came back,
  exactly when it is slowest, those hooks could hold both threads past the 20 s lease
  TTL and a live node lost every lease at once. Now the leases run on
  `leaseScheduler()` (one thread, nothing else on it) and the on-connect hooks on two
  threads of their own. A heartbeat pass also renews the held leases side by side on the
  virtual-thread I/O executor instead of one after another, so a NATS that answers in
  2 s per call no longer stretches a pass over hundreds of leases past the TTL; a lease
  whose renewal is still in flight is skipped, never renewed twice at once.
- **A drained node hands its leader lease over** (`KvLeaseManager.setDraining`). It
  stopped running the leader's work (`tryAcquireKey` answers empty while draining) but
  kept the `leader.hitl-recovery` lease alive through its heartbeat, so no node ran HITL
  crash recovery for as long as the drain lasted — contrary to the method's own Javadoc.
- **Shutdown releases leases side by side, within a bound** (`releaseAll`): up to three
  NATS calls per lease, sequentially, could outlast the termination grace against a slow
  NATS. A lease it misses still expires by TTL.
- **A cancelled turn stops its tool loop** ([`ToolLoopRunner`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java)).
  Lease loss, `/cancel`, a GDPR stop and the watchdog all raise the turn's cancel flag, but
  the loop only checked the thread's interrupt flag, so an agent loop ran all its remaining
  rounds — more model calls and side-effectful tools — for a turn whose result is
  discarded. It now checks the flag before every model call and every tool. The call
  already in flight finishes; interrupting it would also interrupt the persistence and
  dead-lettering that follow on the same thread.
- **Bounded NATS waits on the turn's thread.** A failed turn's dead letter retried its
  publish ten times; against a NATS that keeps the connection and answers nothing, each
  attempt ran into the 2 s request timeout — about 24 s on the failing turn's thread. The
  retry ([`JetStreamDeadLetterStore`](../../src/main/java/ai/labs/eddi/engine/cluster/JetStreamDeadLetterStore.java))
  is now bounded by about 2 s and skipped when the client is disconnected; the entry stays
  in the node-local ring and is forwarded later. Audit position allocation
  ([`AuditClusterSupport`](../../src/main/java/ai/labs/eddi/engine/cluster/AuditClusterSupport.java))
  stops asking NATS for one request timeout after a failure, so a turn's audit entries cost
  one timeout, not one each (they are recorded unsequenced meanwhile, as they already were
  on failure).
- **Dead-letter stream size cap**: new `eddi.coordinator.dead-letter.max-bytes` (1 GiB).
  The stream was bounded by age only, on the NATS volume the leases share. When full it
  refuses new entries (`DiscardPolicy.New`) rather than dropping the oldest an operator may
  still replay.
- **Event bus** ([`JetStreamEventBus`](../../src/main/java/ai/labs/eddi/engine/cluster/events/JetStreamEventBus.java)):
  the outbox is also flushed every 2 s while connected (an event whose async publish failed
  without a disconnect waited for a reconnect that might never come), at most one flush at
  a time; and resyncs coalesce — requests that arrive while one is queued join it, so N
  nodes asking for a resync after a long outage flush each node once more, not N times.
  Each flush is a wave of cache misses against the shared database.
- **`Idempotency-Key` holds across nodes** ([`ConversationService`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
  [`TurnIdempotencyService`](../../src/main/java/ai/labs/eddi/engine/internal/TurnIdempotencyService.java)).
  A duplicate that reached another node while the first request ran there missed that
  node's in-flight registry, became an owner, queued for the lease and, once it held it,
  ran the turn a second time over the newer document. The turn builder now re-checks the
  key when it rebuilds over a superseded document: under the lease, the check and the run
  are one step cluster-wide. The duplicate is answered as skipped (409 + `Retry-After`) and
  its retry is served the stored answer.
- **Autoscaling** (Helm chart 2.8.0 → **2.9.0**): `autoscaling.extraMetrics` and
  `autoscaling.behavior` are rendered as given, and each resource target may be `null`. A
  node waiting on LLMs runs hundreds of turns at little CPU, so a CPU-only HPA may never
  scale up; the docs show how to scale on in-flight turns
  (`eddi_runtime_executor_active{pool="turn"}`) through a custom-metrics adapter. The chart
  refuses an HPA with no metric at all.
- **Docs**: [Clustering](../clustering.md) gains database-connection sizing (a stock
  PostgreSQL's `max_connections=100` is exhausted by a third replica at the default pool of
  50; PgBouncer only in session mode, because EDDI takes session-level advisory locks), an
  Autoscaling section, a "NATS hangs" failure mode, and the behaviour above;
  [Kubernetes](../kubernetes.md) and the [configuration reference](../configuration-reference.md)
  list the new values. WS5 of `planning/throughput-optimization-plan.md` is marked
  superseded by cluster mode. The Platform Operator's prompt learns the dead-letter cap and
  that a drain hands over leadership (operator revision bump).

### Tests

- `KvLeaseManagerTest`: renewals side by side (6 leases at 300 ms each in well under the
  sequential 1.8 s), a lease with a renewal in flight is skipped, `releaseAll` side by side,
  a drain releases the leader lease but keeps conversation leases and the heartbeat does not
  take it back.
- `JetStreamDeadLetterStoreTest`: a hanging NATS holds the turn about 2 s, not 7+; a
  disconnected node does not retry or provision; the stream config carries the size cap and
  `DiscardPolicy.New`.
- `AuditClusterSupportTest`: after an outage, five more entries cost no NATS call; allocation
  resumes after the pause.
- `JetStreamEventBusTest`: five resync requests during a flush cause exactly one more flush;
  an outbox entry is flushed by the sampler without a reconnect.
- `ConversationServiceIdempotencyTest`: a duplicate queued on "another node" does not run
  again once the document holds its key; a queued turn with a different key still runs.
- `ToolLoopRunnerToolPolicyTest`: a turn cancelled during a tool runs neither the rest of the
  batch nor another model call.
- `DeploymentManifestsTest`: the HPA template's extra metrics, behavior, optional targets
  and no-metric refusal; chart version 2.9.0.

### Not done here

- Distributing one group discussion's member turns across nodes (today the whole discussion
  runs on the node that started it) — a design change, not a fix.
- A multi-node throughput benchmark — in progress separately (`scripts/loadtest`, 1→8
  nodes).
