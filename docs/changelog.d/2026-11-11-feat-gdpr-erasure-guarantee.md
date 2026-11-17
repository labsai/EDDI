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

### Review fixes

- **Primary reads (MongoDB).** The tombstone, pending-removal and meta collections read
  the primary with majority read concern and write with majority; the alert store's
  verification counts and the new `holdsPersonalContent` read-back do the same, and so
  does the rating read alert work uses to detect an erased rating.
- **Tombstone key outside the database** when the vault is configured (HKDF subkey of
  the vault's KEK-wrapped deployment key, `VaultSecretProvider.deploymentSubkey`); a
  stored check value makes a changed or lost key fail closed (ERROR,
  `eddi_gdpr_guard_key_mismatch_total`) instead of silently not matching.
- **Delivery out of the barrier** (`AlertDeliveryStage`): the barrier waits for the
  guarded write only; the send re-checks the guard first.
- **Rating excerpts** carry a keyed rater subject and are blanked and verified with
  the rater; they survive a conversation delete with their rating (documented).
- The database's write time also counts for the user tombstone's cut-off; the clock
  assumption, every residual limit and the old-coordinator rolling-upgrade case are
  documented; a peer warns about a stop from a node without the guarantee.
- Agent-test purges skip the guard; the drain shares one deadline across writers; one
  `Mac` per thread; PostgreSQL DDL tolerates a concurrent `CREATE`.
- **Archive edit:** the link to the removed `AlertErasureFence.java` in
  `docs/changelog/2026-10.md` became a code span. Archives are otherwise never edited;
  this one had to change because its link target was deleted and
  `DocumentationLinksTest` fails on a dead link.

### Still open

- The MongoDB primary/majority-read behaviour is covered by an integration test (MongoErasureTombstoneStoreIT) that needs Docker and was not run locally; CI is the check.
- Nodes of an older release do not write tombstones: an erasure they coordinate is not guarded (a peer logs a warning); upgrade every node, then re-run it.
- The residual limits (clock assumption, rows written by nodes that were offline during an erasure) are documented in the GDPR guide, not closed.

### What's next

Agent Health (stacked PR) registers an `AsyncErasureWriter` for its turn/outcome rows
and calls `beforeWrite`/`afterWrite`/`verifyUserErased` exactly as the GDPR guide's
"For a new async writer" paragraph lists.

```decision-log
| 2026-11-11 | Async per-user writes are guarded by durable tombstones + a drain barrier + verification; an erasure that cannot ensure "none can be written later" fails a step | Alert events queued before an erasure could land after it; the node-local fence was best effort | Keeping the fence with a longer TTL; deleting by a later sweep only; blocking erasure until queues empty without tombstones |
| 2026-11-11 | User tombstones are time-scoped (content captured up to `tombstone-clock-skew` after the erasure is refused), conversation tombstones are absolute | A reused user id must be able to produce new data; a deleted conversation never comes back | Absolute user tombstones for 400 days |
| 2026-11-11 | Tombstone user key = HMAC(key, SHA-256(userId)); the key is an HKDF subkey of the vault's KEK-wrapped deployment key when a vault is configured, else a DB-stored salt; a stored check value makes a changed key fail closed | A plain SHA-256 of an e-mail is reversible by dictionary; a key stored next to the tombstones only protects a dump of that table; a silently changed key would make every tombstone stop matching | The audit-keyed pseudonym (its key can rotate); a subkey of the KEK itself (changes on KEK rotation) |
| 2026-11-11 | No cache on tombstone reads | A cached "not erased" could miss a tombstone written before a barrier completed | A TTL cache invalidated by broadcast |
| 2026-11-17 | The drain barrier covers only the guarded write; first alert deliveries run on a separate stage that re-checks the guard before sending | A slow webhook or an alert storm would otherwise fail every erasure and conversation delete | Waiting for deliveries with a longer timeout |
| 2026-11-17 | Tombstone, pending-removal and verification reads on MongoDB go to the primary with majority concern | The client reads `nearest()`; a lagging secondary could answer "not erased" or "0 rows left" | Relying on the client default |
| 2026-11-17 | Rating comment excerpts carry a keyed rater subject and are erased with the rater, but survive a conversation delete | Ratings outlive their conversation and the excerpt follows the rating | Blanking excerpts on conversation delete |
| 2026-11-17 | Agent-test purges skip the tombstone and the cluster drain | Their user is synthetic; a drain per test conversation would tie every test run to the slowest node | Batching the drains |
| 2026-11-11 | Retention-driven conversation deletion runs no barrier | It only deletes conversations ended and untouched for at least the retention period; nothing of theirs can be queued, and their alert events age out on their own retention | Running the cluster barrier per retained conversation |
```
