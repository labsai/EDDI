## fix(runtime): persist late agent-test charges and fence dead letters behind GDPR erasure (2026-11-01)

**Repo:** EDDI (`fix/runtime-late-charges-erasure`, stacked on `fix/review-engine-runtime`)

The two items the engine-runtime review PR deferred. Each has unit tests that fail without the fix.

### What changed and why

- **Late charges of an abandoned agent-test turn** (`AgentTestRunner`): the terminal record was built from the run's cost at the moment `run()` returned, so a turn that completed afterwards updated only the in-memory total and the reported spend stayed too low. A `LateChargeLedger` now tracks what the terminal record accounted for. Once that record is stored, every further difference (cost, newly seen models) is handed out exactly once and applied to the stored run as an adjustment, and an `AGENT_TEST_LATE_CHARGE` entry carrying just the added cost goes to the audit ledger. Charges that land between building the record and storing it are collected right after the write. A failed adjustment is handed out again with the next charge.
- **Dead letters vs GDPR erasure across nodes** (`ClusterConversationCoordinator`, new `ErasureTombstones`): a node that reconnects between the erasure's shared purge and the deletion of the conversation's snapshot still found the conversation existing and republished its erased dead letter. `purgeDeadLetters(conversationId)` now writes a marker to a shared `ERASED` bucket first; forwarding reads it before publishing (erased: discard), and again after the publish (erased meanwhile: purge the published copy). An unreadable marker defers the entry, as an unreadable conversation store already did.

### Design decisions

- The late-charge adjustment is a compare-and-swap loop on the terminal record's `heartbeatAt`, which no longer means anything once the run is terminal, so it doubles as the version counter. No new store method was needed. The alternative, holding finalisation for abandoned turns, would delay every timed-out run by up to the turn timeout plus the write-back wait.
- The adjustment does not recompute status, cases or `costCeilingReached`: it corrects the figure, not the verdict.
- The marker lives in the coordinator's purge, not in `GdprComplianceService`, so every caller of the purge gets it; if it cannot be written, the shared stream is purged anyway and the failure is reported to the cascade (step `deadLetters`).
- The marker's lifetime is configurable (`eddi.cluster.erasure-marker-ttl`, default `P7D`). It holds only the conversation id as key. Past it, the existing existence check takes over.

### Decision Log

```decision-log
| 2026-11-01 | Late agent-test charges adjust the stored terminal record (compare-and-swap on `heartbeatAt`) | A timed-out run would otherwise wait up to the turn timeout plus the write-back wait before it could report | Holding finalisation for abandoned turns |
| 2026-11-01 | Dead-letter forwarding is fenced by a shared erasure tombstone, checked before and after publishing | The snapshot outlives the shared purge, so "does the conversation exist" cannot see an erasure under way, and one check still races the purge | Relying on the existence check alone |
```
