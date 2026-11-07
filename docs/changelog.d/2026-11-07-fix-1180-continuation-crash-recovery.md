## 🐛 fix(continuation): recover a decided-to-continue turn lost to a crash before arming (2026-11-07)

**Repo:** EDDI (`fix/1180-continuation-crash-recovery`) — resolves [#1180](https://github.com/labsai/EDDI/issues/1180)

### What changed and why

A turn that emits `CONTINUE_CONVERSATION` decides before its store and arms the
one-shot schedule after the commit. A node that died between the two — or a
schedule write that kept failing after its three retries — left a committed
`continuation:next` with no schedule, and the job silently stopped.
[`long-jobs.md`](../long-jobs.md) documented this as the one window not
recovered automatically. It now is, with an **outbox record plus a recovery
sweep** on the existing schedule machinery:

- **The outbox record.** `decide` leaves a `PendingContinuation` (number, the
  agent's `maxContinuations` and `continuationInput`, environment, `decidedAt`)
  on the memory, and the snapshot conversion writes it as the top-level
  `pendingContinuation` of the stored conversation — in the same document write
  as the step, on the append path and the full-replace path of both stores. It
  is derived from the current step on every write, so the next committed turn
  replaces or removes it, and a memory loaded without it rebuilds it from the
  agent's policy.
- **Deterministic schedule ids.** A continuation's schedule id is a name-based
  UUID of conversation and number, written through the new
  `IScheduleStore.createScheduleIfAbsent` (MongoDB unique `_id`, PostgreSQL
  `ON CONFLICT (id) DO NOTHING`). The turn's own arm, its retries and any number
  of sweeping nodes write one row between them. This also stops the arm's retry
  from writing a duplicate row when an attempt reported a failure but landed.
- **The sweep** (`ContinuationRecoverySweep`, `@Scheduled`, every node, under a
  `leader.continuation-recovery` lease that only saves reads) reads records
  older than a grace period, oldest first and bounded, from a sparse
  (MongoDB) / partial expression (PostgreSQL) index over
  `pendingContinuation.decidedAt` that holds only unhandled records.
  `ConversationContinuations.recover` arms a missing schedule, leaves an
  existing one alone, never revives a conversation that is not READY, and
  withdraws its own row if the record was removed or replaced meanwhile.
- **Handled, not removed.** A sweep only *marks* a record handled (drops
  `decidedAt`, which takes it out of the index); only a cancel, an end or a newer
  turn removes or replaces it. That is what lets a sweep tell "the job was
  stopped" from "another sweep was faster" — the first design cleared the record
  on both and a concurrent sweep would have withdrawn the winner's row.
- **Cancel and end** (`disarm`) remove the record before deleting the
  schedules, so every interleaving with a sweep ends without a live row. A
  cancel of a job whose arm was lost answers `200 CANCELLED`, as for any job
  between two turns. `decide` now also refuses a turn already cancelled
  (`interrupted`) so its decision never reaches the store.
- **Indexes are built without blocking writes**: PostgreSQL `CREATE INDEX
  CONCURRENTLY` on a background thread under an advisory lock (INVALID builds
  dropped and rebuilt, like the audit ledger's late indexes), MongoDB on a
  background thread; both start with the first sweep. Until the index is valid the sweep
  reads nothing, so it never scans the collection.
- **Configuration:** `eddi.continuation.recovery.enabled` (`true`),
  `.interval` (`60s`), `.grace-period` (`60s`), `.batch-size` (`100`) — in
  [`configuration-reference.md`](../configuration-reference.md).
- **Metric:** `eddi_continuation_recovered_total`, on the Full Metrics
  dashboard's continuation panel (k8s copy re-synced) and in
  [`metrics.md`](../metrics.md).
- **Platform Operator:** the prompt no longer tells admins that a missing
  schedule after `continuation:next` means a stopped job; it explains the sweep
  (operator revision bumped).

### Tests

- `ConversationContinuationsTest` — new `Outbox` and `Recover` groups: the record
  is written, round-trips the snapshot, follows the current step and is dropped
  by the next one; derived ids; cancel paths clear the record; `recover` arms a
  missing schedule once, leaves an armed one alone, never revives ENDED / paused,
  withdraws when superseded, never withdraws a row it did not write; two
  concurrent sweeps write one row and both keep it.
- `ContinuationRecoverySweepTest` — grace period, batch bound, lease, failures.
- `ConversationContinuationIT` (MongoDB and PostgreSQL) — a crash between commit
  and arm is recovered exactly once and the job runs to its cap; an armed
  continuation is untouched; an ended conversation (narrow end and API end) is
  not revived; a cancel of a lost arm stops the job; four concurrent sweeps
  against the real stores write one schedule. The record is read back through
  the real store loaders.

**Files:** [`ConversationContinuations.java`](../../src/main/java/ai/labs/eddi/engine/continuation/ConversationContinuations.java),
[`ContinuationRecoverySweep.java`](../../src/main/java/ai/labs/eddi/engine/continuation/ContinuationRecoverySweep.java),
[`PendingContinuation.java`](../../src/main/java/ai/labs/eddi/engine/memory/model/PendingContinuation.java),
[`ConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryStore.java),
[`PostgresConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java),
[`MongoScheduleStore.java`](../../src/main/java/ai/labs/eddi/engine/schedule/mongo/MongoScheduleStore.java),
[`PostgresScheduleStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresScheduleStore.java),
[`long-jobs.md`](../long-jobs.md)

```decision-log
| 2026-11-07 | Continuation crash recovery is an outbox record on the conversation document plus a bounded, indexed sweep, with deterministic schedule ids for idempotency | #1180: a crash between commit and arm stopped the job | A single store transaction across conversation and schedule (no cross-collection transaction on MongoDB standalone; two stores); scanning steps for continuation:next (unbounded); clearing the record from the sweep (a concurrent sweep would withdraw the winner's row) |
```
