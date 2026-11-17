## Cluster fencing survives a lease-bucket stream-leader failover (2026-11-17)

**Repo / branch:** labsai/EDDI, `fix/lease-fence-revision-regression`.

**What was wrong:** `MongoClusterE2EIT.streamLeaderFailoverUnderLoad` (and the Postgres variant) kept main's nightly Cluster E2E red with LOST turns, all in one conversation. In CI run 43971937053, a lease renewal under `KV_EDDI_LEASES` leader nats-2 was acknowledged at revision `…278633`. After the step-down to nats-3, new leases got revisions `…278348` and up. The old leader had acknowledged writes at sequences its successor never had, and the successor handed those sequences out again. A lease from the lost range raised the conversation's `_fence` to `…278765`. Every lease after it had a lower revision, so its `raiseFence` (`$max` / `GREATEST`) was a silent no-op and the turn ran anyway. Its write was then refused (`ConversationFencedException`) after the reply had gone out as 200: 118 turns were answered OK but never stored.

**What changed:**
- `IConversationMemoryStore.raiseFence` now returns the fence the document carries after the raise (Mongo: one `findOneAndUpdate`; Postgres: the conditional update, then a read only when it did not move). A new `liftFence(id, expected, lifted)` is a compare-and-set on `_fence`.
- `LeaseHandle` has two new methods. `confirmHeld()`, implemented in `KvLeaseManager.Held`, does a consistent read from the stream leader: is the key still this boot's lease with this lease id? `liftFence(long)` raises the handle's fence and never lowers it. Renewals and releases still compare-and-set on the KV revision.
- `ConversationStepRunner.fenceRaised`: when the raise answers a fence above the lease's own, the turn no longer runs blindly. If the bucket confirms the lease, the stored fence is lifted to `stored + 1` by CAS, the lease writes with it, and a WARN is logged. If the lease is not confirmed, or the CAS loses, the turn is answered busy (409 + `Retry-After`, input not consumed). A turn whose write cannot land is never started and answered 200.
- `docs/clustering.md`: the fencing row describes the lift.

**Design decisions:**
- The fix is safe because a confirmed-current lease rules out a newer holder: a holder after it could only have acquired after this lease was deleted. So a higher stored fence must come from the lost range. Lifting it above that range keeps the old range's holder fenced out, should it still be writing.
- The lift is a CAS from the exact value read. Two holders lifting from the same value cannot both win, and a raise that lands in between makes the lift miss, which is answered busy and retried.
- The normal path costs nothing extra: the confirmation read happens only after a raise that did not take.

**Tests:** `ConversationStepRunnerLeaseTest` replays the CI ordering (raise 278352 → stored 278765 → lift to 278766, and the next lease lifts to 278767), plus the unconfirmed lease, the lost CAS and the normal raise. `KvLeaseManagerTest` covers `confirmHeld` (held, taken over, released) and `liftFence`. The Mongo and Postgres store tests cover the raise answer and the lift CAS. The cluster E2E itself needs CI.
