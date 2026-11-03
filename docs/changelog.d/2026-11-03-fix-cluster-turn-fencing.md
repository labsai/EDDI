## 🐛 fix(cluster): fence first, never lower the fence, dead-letter what was refused (2026-11-03)

**Repo:** EDDI (`fix/cluster-turn-fencing`)

Workstream B of the cluster production-readiness review (`planning/cluster-production-readiness-review.md`):
the turn path, the conversation stores, HITL resume, dead-letter replay and the admin console.

### What changed and why

- **A3, fence first.** A turn under a cluster lease raised the conversation's `_fence` only *after*
  it had read the state, the revision, a reloaded snapshot and the recorded `Idempotency-Key`s, so an
  expired predecessor could still append in between: the successor then ran without that step, its
  properties overwrote the predecessor's, and a duplicate `Idempotency-Key` could run twice. A failed
  raise was only logged. Now the fence goes up before anything is read, and a turn that cannot raise
  it — or whose lease is already lost — does not run: 409 + `Retry-After`, input not consumed. The
  HITL resume does the same and restores the pause.
  `ConversationStepRunnerLeaseTest.raiseFailureDoesNotStopTheTurn` asserted the old behaviour and was
  replaced by `raiseFailureAnswersBusy`.
- **A4, the fence only grows.** A full-document write (Mongo `replaceOne`, Postgres `data = ?`)
  persisted the `_fence` it had loaded. A raise does not move `_rev`, so an unfenced writer that
  loaded before a raise passed the revision guard and put the old fence back, after which the holder
  the raise had shut out could write again. Postgres now builds `_fence` in SQL (`GREATEST` for a
  fenced write, the stored value for an unfenced one); Mongo refuses to write a lower fence and adopts
  the stored one. Documents without `_fence` stay writable. Undo and redo, which ran on the REST
  thread outside the lease, now run through the coordinator in cluster mode, under the lease, with
  its token on their write (single-node undo/redo are unchanged). A turn whose write lost the
  optimistic-concurrency race after its reply had gone out is dead-lettered in cluster mode
  (`reason: conflict`) instead of only logged.
- **A6.** A conditional write (pause commit, resume, undo, redo) refused by the fence returned
  `false`, which every caller read as "a concurrent writer moved the state": the turn was dropped
  without a dead letter. It now throws `ConversationFencedException`; the say path and the resume
  dead-letter it, undo/redo answer 409.
- **A5.** A HITL resume that lost its lease only raised the cancel flag, and nothing then moved the
  conversation off `IN_PROGRESS` until crash recovery minutes later. The pause is now restored at
  once with a fenced compare-and-set (new `IConversationMemoryStore.compareAndSetStateFenced`: only
  while no newer holder has started on the conversation) and the resume is dead-lettered; its caller
  is answered 409.
- **A1 (turn side).** The turn's memory reads `LeaseHandle.isLost()` itself
  (`IConversationMemory.setLeaseLostCheck`), so a lease that can no longer be trusted cancels the
  turn, skips the unfenced long-term user-memory write and answers the reply as not processed, even
  before the lease manager's lost-callback arrives. When `isLost()` turns true is the lease
  workstream's part (`fix/cluster-lease-validity`).
- **A7.** A replay deleted its dead letter as soon as the turn was *queued*, so a replayed turn that
  was then skipped, timed out on the lease or failed lost its input. The entry is now deleted only
  once the replayed turn commits (checked through its `Idempotency-Key`, `dead-letter-replay:<id>`,
  which every replay of one entry carries), the claim is held until then, and the outcome is on the
  activity timeline. The legacy `POST /administration/coordinator/dead-letters/{id}/replay` goes
  through the same path, so it now claims the entry and is audited with the calling admin.
- **A9.** A turn the runtime refused after its lease was granted was both dead-lettered and answered
  "retry". It is answered busy, and its dead letter is marked `answeredRetry` and cannot be replayed
  (`notReplayableReason: ANSWERED_RETRY`).
- **A11 (group lease).** A discussion whose `g.<id>` lease another live node holds no longer runs a
  second leg here; it stops without writing. Only when no holder can be read (NATS unreachable) does
  a discussion run without its lease. `ClusterHitlRecovery` no longer takes a lease read made while
  NATS is unreachable for "nobody holds it, recover now" (it checks the connection around the read
  until the lease workstream's `peekStrict` is wired in).
- **C4 (receiving side).** A drain request that arrives over RPC is checked again on the node it
  drains: the drain gate must be held, its own `drain.<node>` key written, and another live node must
  still take turns — so a request published straight onto the RPC subject, a broadcast included,
  drains nothing. What a node applies or refuses on another node's request (drain, undrain,
  reconcile, forward) is written to its audit ledger (actor `cluster-rpc`) and activity timeline.
- **D6 (console reads).** One scan of the lease bucket and one read of each dead-letter page is
  shared for 3 s by every console polling this node, and dropped by a force-release, replay, discard
  or forward.
- **C12 (part).** `eddi.cluster.fence.rejected` is registered at startup, so the first rejection on
  a pod is visible to `increase()`.
- **A8, A10 (docs).** `clustering.md` no longer claims "no loss" for degraded turns (conversation
  properties are last-writer-wins), nor that node-local dead letters survive a restart or the
  1000-entry cap.

### Design decisions

- Mongo cannot express `$max` inside a `replaceOne`, so the full write is filtered on "the stored
  fence is not above the one written" and, on a miss caused only by that, adopts the stored fence and
  tries again (at most three times). Only an unfenced write can miss this way.
- Undo/redo go through the coordinator only in cluster mode: that is where the fence exists; on one
  node the state-and-revision CAS already orders them.
- The node-local dead-letter ring (A10) is not persisted to disk here: it lives in the coordinator
  base class, and a durable journal would also have to honour GDPR erasure. The docs now say what it
  does; persisting it is left to the coordinator workstream.

**Files:** [`ConversationStepRunner.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java),
[`ConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
[`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java),
[`ConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryStore.java),
[`PostgresConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java),
[`ClusterAdminService.java`](../../src/main/java/ai/labs/eddi/engine/cluster/admin/ClusterAdminService.java),
[`RestCoordinatorAdmin.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestCoordinatorAdmin.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`clustering.md`](../clustering.md), [`coordinator-admin.md`](../coordinator-admin.md)
