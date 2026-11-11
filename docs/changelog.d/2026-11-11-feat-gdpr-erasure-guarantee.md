## 🔒 feat(gdpr): a hard erasure guarantee for asynchronously written rows (2026-11-11)

**Repo:** EDDI (`feat/gdpr-erasure-guarantee`)

### What changed and why

Alert events are written from a queue, after the turn or rating that produced them.
A row queued before a GDPR erasure — or before a permanent conversation delete —
could reach the store after the deletion ran. Alerts guarded against that with a
node-local, 15-minute fence plus redaction: best effort. The maintainer asked for a
guarantee, so an erasure now reports `complete` only when no guarded row of the user
exists **and none can be written later**; otherwise a step fails and a re-run
finishes it.

The mechanism lives in the GDPR engine (`engine/gdpr/erasure`) so the stacked Agent
Health PR adopts it unchanged:

- **Durable tombstones** (`IErasureTombstoneStore`, MongoDB `erasuretombstones` /
  PostgreSQL `erasure_tombstones`): the user as HMAC-SHA256 of the SHA-256 the
  cluster stop carries, under a per-deployment salt the store creates on first use
  (insert-if-absent), and each of the user's conversations by id. Written before any
  stop or delete (failure: step `erasureTombstone`), kept `eddi.gdpr.tombstone-retention`
  (400 d). No read cache.
- **Guarded writes** (`ErasureGuard.beforeWrite` / `afterWrite`): refuse a row of an
  erased conversation, or of an erased user captured before the erasure
  (`eddi.gdpr.tombstone-clock-skew`, 5 m); remove a row written while the tombstone
  went up, or record a durable **pending removal** (`erasurependingremovals` /
  `erasure_pending_removals`) retried every five minutes. Fail closed on unreadable
  tombstones.
- **Drain barrier** (`AsyncErasureWriter.awaitDrained`, built on `DrainBarrier`): after
  the stop, every node waits until the work queued before the tombstones has run
  (`eddi.gdpr.drain-timeout`, 30 s). Locally a timeout fails `asyncWriteDrain`; peers
  drain inside `gdpr-stop` and must answer `drained: true`, or `clusterStop` fails —
  including a node of an older release.
- **Verify** (`ErasureGuard.verifyUserErased`): the writer's participant requires no
  row and no pending removal left; a re-run retries the pending removals first.
- **Permanent conversation delete**: `ConversationPurgeService.purge` tombstones the
  conversation, drains every node through the new `erasure-drain` cluster RPC, blanks
  and verifies the conversation's alert values — before deleting anything else. A
  failure is a `ResourceStoreException` (the REST delete and the agent-test cleanup
  already report it) and keeps the conversation for the retry.
- **Alerts adopt it** (`AlertErasureWriter`, `AlertEngine`, `AlertDispatcher`,
  `AlertGdprParticipant`); `AlertErasureFence` is removed — the tombstones and the
  barrier cover everything it did, without its 15-minute expiry.
- Metrics `eddi_gdpr_guard_*` with a "GDPR Erasure Guarantee" row on the Full Metrics
  dashboard.

**Files:** [`ErasureGuard.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/erasure/ErasureGuard.java),
[`AsyncErasureWriter.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/erasure/AsyncErasureWriter.java),
[`GdprComplianceService.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java),
[`ClusterControlHandlers.java`](../../src/main/java/ai/labs/eddi/engine/internal/ClusterControlHandlers.java),
[`ConversationPurgeService.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationPurgeService.java);
docs: [GDPR](../gdpr-compliance.md#the-erasure-guarantee-for-asynchronously-written-data),
[Alerts](../alerts.md), [Clustering](../clustering.md), [Configuration](../configuration-reference.md),
[Metrics](../metrics.md).

### What's next

Agent Health (stacked PR) registers an `AsyncErasureWriter` for its turn/outcome rows
and calls `beforeWrite`/`afterWrite`/`verifyUserErased` exactly as the GDPR guide's
"For a new async writer" paragraph lists.

```decision-log
| 2026-11-11 | Async per-user writes are guarded by durable tombstones + a drain barrier + verification; an erasure that cannot ensure "none can be written later" fails a step | Alert events queued before an erasure could land after it; the node-local fence was best effort | Keeping the fence with a longer TTL; deleting by a later sweep only; blocking erasure until queues empty without tombstones |
| 2026-11-11 | User tombstones are time-scoped (content captured up to `tombstone-clock-skew` after the erasure is refused), conversation tombstones are absolute | A reused user id must be able to produce new data; a deleted conversation never comes back | Absolute user tombstones for 400 days |
| 2026-11-11 | Tombstone user key = HMAC(per-deployment salt, SHA-256(userId)) | A plain SHA-256 of an e-mail is reversible by dictionary; nodes only receive the SHA-256 | The audit-keyed pseudonym (its key can rotate, which would make tombstones unreachable) |
| 2026-11-11 | No cache on tombstone reads | A cached "not erased" could miss a tombstone written before a barrier completed | A TTL cache invalidated by broadcast |
| 2026-11-11 | Retention-driven conversation deletion runs no barrier | It only deletes conversations ended and untouched for at least the retention period; nothing of theirs can be queued, and their alert events age out on their own retention | Running the cluster barrier per retained conversation |
```
