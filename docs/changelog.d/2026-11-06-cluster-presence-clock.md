## 🐛 fix(cluster): presence age on this node's clock; drain key and duplicate cleanup (2026-11-06)

**Repo:** EDDI (`fix/cluster-presence-clock`)

### What changed and why

**Presence ages are measured on this node's own clock (OPS-1).** The presence watch stamped each
record with the NATS server's write time and expired it against this node's wall clock after the
30 s bucket TTL. A node whose clock ran about 20 s ahead therefore dropped every member between two
rewrites, and `members()`, `currentMembers()` and the RPC peer list came back empty. Four staleness
checks compared another node's `updatedAt` with this node's clock against two presence intervals
(20 s): the drain `LAST_NODE` guard, the drained node's own check of a drain request over RPC, the
overview's `STALE` card, and the watcher's `node.stale` entry. Skew above about 10 s was enough to
refuse a legitimate drain or to report a live node as stale.

`ClusterPresence` now records, for each record, the revision it saw and when it saw that revision on
its monotonic clock. Expiry uses that time, and the new `heartbeatAgeMillis(nodeId)` reports it.
The four checks use that age. A record that was already in the bucket when this node started looking
(the watch's initial values, the first bucket read, or a read more than one TTL after the previous
one) was written at an unknown moment, so it has no age until it is rewritten. The drain guards count
such a node as not serving, so they refuse rather than drain the last live node. The overview shows
it `LIVE` with the age its own timestamp gives, and the watcher does not report it. `updatedAt`
stays in the record and in the overview as `lastHeartbeat`, for display only. `node.stale` is still
keyed by `updatedAt`, so every node's report of one stale record collapses into one entry.

**The drain key of a decision being applied survives the node's own key sync (OPS-9).** `drain()`
opens `gate.drain`, writes `drain.<node>` and then asks the node over RPC. If the node's scheduled
`syncOwnDrainKey` ran in between, it saw a key for a node that was not draining yet and deleted it.
The node then refused the request as "not recorded", and the administrator got `409 NODE_FAILED`. The
sync now reads the key's revision first, then skips the delete while the drain gate is held, and
deletes only at that revision. A key written after the read is either covered by a gate the check
sees or carries a newer revision, and the conditional delete then fails. A leftover key from a drain
that a restart ended is still removed on the next sync.

**Dead code (OPS-18).** `ClusterPresence.duplicateDetail()` had no caller; the same detail is in the
ERROR log. `KvLeaseManager.snapshot(prefix, max)` listed `max * 2 + 16` entries before filtering by
prefix, so a real prefix could miss matching leases. Its only production caller passed `""`, so it is
now `snapshot(max)`. `heartbeatAll()` is kept: nine tests use it.

**Tests (OPS-20, the presence part).** Skewed-clock tests for presence expiry and heartbeat age, the
drain guards, the overview and the watcher. The tests of the drain-key sync include one where the
key is rewritten between the sync's read and its delete.

**Files:** [`ClusterPresence.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterPresence.java),
[`ClusterAdminService.java`](../../src/main/java/ai/labs/eddi/engine/cluster/admin/ClusterAdminService.java),
[`ClusterWatcher.java`](../../src/main/java/ai/labs/eddi/engine/cluster/admin/ClusterWatcher.java),
[`KvLeaseManager.java`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java),
[`coordinator-admin.md`](../coordinator-admin.md)

```decision-log
| 2026-11-06 | Presence age is the time since this node saw the record's revision change, on its monotonic clock; a record found already written has no age until rewritten | A writer's `updatedAt` or the server's write time compared with this node's wall clock misjudged every skewed node: members vanished, drains were refused, live nodes reported stale | Keep wall-clock ages with a skew allowance (only moves the threshold); date a first-seen record as fresh (a dead node looks live to the drain guard for up to one TTL after a reconnect) |
```

```regression-note
| 2026-11-06 | A drain could fail with `NODE_FAILED` ("the drain of this node was not recorded") | The target node's scheduled drain-key sync deleted the key the deciding node had just written under the drain gate | Sync reads the key's revision, skips while the gate is held, and deletes only at that revision | fix/cluster-presence-clock |
```
