## 🐛 fix(cluster): leases stop trusting themselves, survive lost replies, and cost fewer round trips (2026-11-03)

**Repo:** EDDI (`fix/cluster-lease-validity`) — workstream A of the cluster production-readiness review (`planning/cluster-production-readiness-review.md`, findings A1, A2, A11, D1–D5, D7, and the lease part of C12).

### What changed and why

**A1 — a holder cut off from NATS stops itself.** A renewal that could not reach NATS used to be ignored forever (`KvLeaseManagerTest.heartbeatIoFailureKeepsLease` asserted it), so a partitioned holder kept running — LLM calls, tools, the unfenced user-memory upsert, a 200 to its client — after another node had taken the conversation over at `lease.ttl`. A lease is now trusted for `ttl − 2 × heartbeat` (10 s by default) after the last renewal NATS *acknowledged*, counted from when that renewal was *sent*, so the server-side TTL always ends later. Past that `LeaseHandle.isLost()` is true at once and the lost callbacks fire (from the tick every quarter heartbeat, or from `isLost()` itself, on the I/O executor). The test is rewritten to assert the new contract. Contract for the turn side (WS-B): check `isLost()` before the user-memory upsert and before delivering the reply.

**A2 — a write applied on the server whose reply was lost.** Every lease value now carries a lease id (`lid`) unique within the process. A renewal that conflicts re-reads the key from the stream leader: if it still holds this lease (same node, boot and `lid`), an earlier renewal landed although its reply timed out — the lease is kept at the revision found (`eddi_cluster_lease_adopted_total{op="renew"}`) instead of cancelling a healthy turn. An acquire that finds its own `lid` adopts the lease its timed-out create wrote; a lease of this process that nobody here holds (an acquire that gave up) is deleted instead of blocking the conversation for the TTL — this node included. `release()` of a lost handle now still deletes the key when it is provably still this lease.

**A11 — waiter markers and peek.** A granted waiter deletes its `w.<key>` marker by compare-and-set at the revision it wrote, so another node's marker survives; a waiter that times out, is drained or shut down removes its own. New `IConversationLeaseManager.peekStrict` / `peekKeyStrict` throw `ClusterUnavailableException` instead of reading an outage as "no lease" (for `ClusterHitlRecovery.eligible`, owned by WS-B).

**D1 — fewer round trips.** Acquire reads the key's last record (`ISharedKv.latest`, which also returns a delete marker's revision) and writes at that revision: 2 round trips instead of jnats' 3 for a `create` on a deleted key. Release is one guarded delete that returns the marker's revision (`deleteReturningRevision`); the unconditional waiter-marker read and the broadcast are gone. An uncontended turn's lease went from about 6 to 3 round trips; a waiter woken by a release acquires with 1.

**D2 — contention.** The coordinator hands a healthy lease straight to the next turn of the same conversation queued on this node (`IConversationLeaseManager.handOver`: no I/O, refused when less than a heartbeat of validity is left, when another node is known to wait, or after 16 handoffs in a row). The waiter poll is capped at 500 ms (250–750 ms jittered; it was 2.5–7.5 s at the default TTL).

**D3 — reopen storm.** `NatsSharedKv` reopens a bucket handle once, under a lock, for all the callers that failed on it; it never drops a handle younger than 1 s or on a full outgoing queue / closed connection, and a failed open is not retried for 250 ms.

**D4 — heartbeats at scale.** At most 64 renewals in flight, least recently confirmed first; each lease is renewed when 3/4 of a heartbeat has passed since its last renewal, from a tick every quarter heartbeat, so thousands of leases no longer go out in one burst.

**D5 — stale-lease sweep.** Runs once per process (leases of an earlier boot cannot appear after it started), with one watch instead of a read per key — no more whole-bucket scans on every node at every reconnect.

**D7 — release notifications.** A waiter tells the holder's node that it waits (`<root>.lease.to.<nodeId>`, core NATS, repeated with each poll); the holder addresses the release only to those nodes, with the delete marker's revision. The cluster-wide broadcast is kept only for an administrative force-release.

**C12 (lease part).** `eddi_cluster_lease_acquire_seconds` (every outcome × contended), `eddi_cluster_lease_lost_total` (every reason), `eddi_cluster_lease_adopted_total` and `eddi_cluster_degraded_decisions_total{area="turns"}` are registered at construction, so `increase()` sees the first occurrence on each pod.

### Rolling upgrade: why old and new nodes keep mutual exclusion

- **Acquisition stays create-if-absent.** The new acquire is a compare-and-set at the revision of the key's last record, which succeeds only while the key holds no live value — the same server-side check jnats' `create` makes. An old node's `create` and a new node's write race on the one stream leader; exactly one wins. `LeaseProtocolIT.earlierVersionInteroperates` runs both against real NATS.
- **The delete marker is jnats' own.** `deleteReturningRevision` publishes the `KV-Operation: DEL` marker with `Nats-Expected-Last-Subject-Sequence`, exactly as jnats' `delete(key, revision)` does, only keeping the acknowledgement's sequence; old nodes read it as a deleted key.
- **The value only gains a field.** `lid` is ignored by old nodes, which read `node`, `boot` and `since` as before. A new node treats a value without `lid` as somebody else's lease, so it never adopts or deletes an old node's lease as its own.
- **Takeover rules are unchanged** (presence, boot id, three unchanged heartbeats); renewals and releases stay compare-and-set on the revision.
- **Notifications are hints.** Old nodes still broadcast on `lease.released.<key>`, and new nodes still listen there. New nodes no longer broadcast a plain release, so an old node waiting for a lease a new node releases falls back to its poll (`ttl/4`, 5 s by default) until it is upgraded, and a new holder does not see an old waiter's marker when it decides to yield or hand off — fairness toward old-version waiters is weaker for the length of the upgrade. Neither affects mutual exclusion or the fence.

### Tests

`KvLeaseManagerValidityTest` (A1, A2, A11), `KvLeaseManagerScaleTest` (D1, D2, D4, D5, D7, C12), `NatsSharedKvReopenTest` (D3), two handoff tests in `ClusterConversationCoordinatorTest`, and `LeaseProtocolIT` against a real NATS: the delete-marker round trip, interoperation with an earlier version's jnats `create`/`delete`, a renewal and a create applied on the server whose replies are reported as timeouts, and addressed release notifications with no broadcast. Rewritten: `KvLeaseManagerTest.heartbeatIoFailureKeepsLease` (now `…KeepsLeaseOnlyUntilItsValidityRunsOut`).

**Files:** [`KvLeaseManager.java`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java), [`NatsLeaseManager.java`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/NatsLeaseManager.java), [`IConversationLeaseManager.java`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/IConversationLeaseManager.java), [`ISharedKv.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ISharedKv.java), [`NatsSharedKv.java`](../../src/main/java/ai/labs/eddi/engine/cluster/NatsSharedKv.java), [`InMemorySharedKv.java`](../../src/main/java/ai/labs/eddi/engine/cluster/InMemorySharedKv.java), [`ClusterConversationCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/ClusterConversationCoordinator.java); docs: [`clustering.md`](../clustering.md), [`configuration-reference.md`](../configuration-reference.md), [`metrics.md`](../metrics.md).

### Not done here

- The turn side of A1 (checking `isLost()` before the user-memory upsert and the reply) and the switch of `ClusterHitlRecovery` to `peekStrict` belong to WS-B.
- D2's "load memory after the lease is granted" and the reference ingress configs' consistent hashing are outside this workstream.
- Sharding `LEASES` by hash prefix (D3–D5 plan item) is not attempted.

```decision-log
| 2026-11-03 | A lease is trusted for ttl − 2×heartbeat after the last acknowledged renewal, counted from its send time; past that isLost() is true and callbacks fire | A holder cut off from NATS ran on past the TTL (A1) | Keeping the lease until a conflict is seen (the old behaviour); counting from the reply's arrival (the server's TTL starts earlier) |
| 2026-11-03 | Lease values carry a per-process lease id; conflicts are re-read and adopted when the id matches | Applied-but-timed-out writes read as takeovers (A2) | Matching on node+boot only — a second lease of the same process under the same key could be mistaken for this one |
| 2026-11-03 | Release notifications are addressed to the nodes that said they wait; no broadcast for plain releases | Every node received every release (D7) | Keeping the broadcast during the upgrade — old waiters poll instead, a hint is never needed for exclusion |
```
