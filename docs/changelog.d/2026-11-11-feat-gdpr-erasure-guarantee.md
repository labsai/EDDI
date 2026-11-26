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

### Merge-ready round (2026-11-25)

Merged `main` (the only conflict: the generated Full Metrics dashboard, where `main`'s
Organizations row took panel id 459 — the GDPR row is now 463, with 464 and 465), then
closed what the PR and its review still listed:

- **Nothing of any writer is overlooked.** A conversation delete counts the
  conversation's pending removals of *every* writer, and a user erasure ends with
  `asyncWritePending` over every writer's removals of the user — a removal recorded by
  a writer no longer deployed keeps both failed. User erasure also deletes and
  verifies every writer's rows of the user's conversations (`asyncWriteConversations`,
  `ErasureGuard.eraseConversationRows`), so a row that names a conversation but not the
  user is covered; a failure keeps the snapshots for the re-run. A conversation the
  user started after step 00 listed them is tombstoned before its rows go.
- **Fair retries.** A pending removal that fails again is deferred (`nextAttemptAt` /
  `next_attempt_at`), and the maintenance pass takes the due ones, longest-due first:
  a full batch of removals that keep failing no longer starves the rest. A
  store-backed gauge, `eddi_gdpr_guard_pending_backlog`, is the backlog; the counters
  are documented as activity.
- **A changed key reaches running nodes.** The cached tombstone key is re-checked
  against the stored check value on every use (a plain read) and dropped when it no
  longer matches. The key-reset recovery now quiesces the writers and clears the
  excerpts stored under old-key subjects first; `adopt-master-key` says when it reset
  the key the tombstone key is derived from.
- **An unguarded erasure is reported.** A `gdpr-stop` without the tombstone marker
  (an older coordinator) is answered with an error, which every release's coordinator
  records as an incomplete cluster stop.
- **Every permanent delete is guarded.** `ConversationPurgeService` refuses a
  permanent delete without a guard (m7), and the retention sweep runs one batched
  pass, `ErasureGuard.eraseConversations` (one tombstone write, one drain), before it
  deletes, keeping what it could not erase.
- **Conversation deletes blank rating excerpts** and their rater subject too, in both
  stores (`redactConversationContent` / `countConversationContent`).
- **Stale presence (m4)** is documented: a crashed node fails erasures and permanent
  deletes for up to 3 × `eddi.cluster.presence.interval`.
- Docs: re-run the erasure after a database recovery (residual limit 2), the
  adopter's contract (plain user id allowed, scheduled jobs through a direct
  executor), dashboard axes (rates, a count axis for timeouts and the backlog, an
  empty drain panel says why).
- Tests: `AgentHealthAdoptionTest` drives an Agent-Health-shaped writer
  (`HealthShapedWriter`, public API only, its own package) through the real cascade
  and the delete path — queued and in-flight writes, an unreachable node, a failing
  store, a full queue. The tombstone store contract also runs on the in-memory double.
  Every new check was mutation-tested.

### Review round (2026-11-26)

CodeRabbit's full review of the merge-ready round:

- **The drain is confirmed node by node.** `IClusterRpc.expectedPeerIds()` and
  `callAllAwaiting(op, request, wait, nodes)`: the conversation-delete drain
  (`ErasureGuard.clusterVerdict`) and the user erasure's `gdpr-stop` require every
  node read before the scatter to answer, and wait for those nodes, not for a count.
  A node that joined meanwhile used to fill a missing node's slot and end the wait,
  so an erasure could go on while that node still had a write queued.
- **An unconfirmed drain keeps the snapshots.** When the local drain times out or a
  node does not confirm it, the user erasure keeps the conversation snapshots
  (`conversations` fails). A late write that names only a conversation and cannot
  remove itself leaves a pending removal the user's last check does not count; the
  re-run finds it through those conversations.
- **Conversation deletes clear a subject left without an excerpt.** A rating alert
  whose excerpt was blanked when its rating was deleted kept the keyed rater
  subject, which neither store's conversation delete matched or counted.
- **The tombstone write time only moves later** (PostgreSQL): `written_at` keeps the
  later of the stored value and `now()`. `now()` is the transaction start, so an
  earlier write that committed last could move the cutoff back.
- **Linearizable point reads** (MongoDB, replica set or mongos): the tombstone and
  key-check reads by `_id` use linearizable read concern with a 5 s `maxTime`, so a
  replaced primary that has not noticed cannot answer from before a tombstone. A
  standalone server refuses linearizable reads and has no second primary; it keeps
  majority reads. Each such read now waits for a majority acknowledgement.
- **A too-short tombstone retention fails the start.** `eddi.gdpr.tombstone-retention`
  must be at least one day plus twice `eddi.gdpr.drain-timeout` plus
  `eddi.gdpr.tombstone-clock-skew`. A tombstone that expired while its erasure was
  still running let a late write through.
- Docs: alert delivery does not occupy the drain barrier, but the redaction still
  waits for a send that holds a claim; executors that drop tasks without throwing
  (`DiscardPolicy`, `DiscardOldestPolicy`) are unsupported by `DrainBarrier`; a
  running node whose key-check row was deleted writes its own check back, which is
  why the reset stops every node first. `DrainBarrierTest` no longer leaves a worker
  blocked when an assertion fails.

### Second review round (2026-11-26)

- **Tombstones hold through the erasure, and their retention counts from its
  completion.** At the end of a conversation batch (`eraseConversationRows`) and of
  the user's last check (`verifyNoPendingRemovals`) every tombstone must still be
  valid; one that expired while the erasure ran fails it for a re-run, and the ones
  that held are written again (expiry and, for the user, the cutoff move to the
  completion) — within the hold check's one-minute margin, or, like a renewal that
  fails, it fails the conversation or the user's last check, since a late renewal
  cannot confirm there was no gap. The retention floor stays as a sanity bound.
- **Writers carry the guard's admission.** `beforeWrite` returns an
  `ErasureGuard.Admission` (`allowed()`), and `afterWrite(name, rowId, admission,
  remover)` checks and records against the user key and conversation it resolved.
  A key-check read failing after the write no longer leaves a pending removal
  without its user scope, and the post-write check saves the key-check read.
  `AlertEngine`, the `HealthShapedWriter` adoption test and the "For a new async
  writer" contract use the new shape.
- **A conversation started during a user erasure is drained again.** Its tombstone
  (step 3b) postdates the stop's drain, so a row naming only that conversation could
  still be queued; the erasure drains every node again before counting its rows,
  only when there is such a conversation, and keeps the snapshots if that drain
  fails.
- **Indexes for the conversation-content lookup** (`idx_alert_events_conversation`,
  `idx_alertevent_conversation`): the value-only partial index could not serve the
  predicate that now includes excerpts and subjects.
- **Tombstone write times only move later on MongoDB too** (an update pipeline with
  `$max` over `$$NOW`), and PostgreSQL takes `clock_timestamp()` so a wait for the
  row lock is inside the cutoff.
- A negative `eddi.gdpr.tombstone-clock-skew` (or drain timeout) fails the start;
  `pendingRemovals` returns nothing for a limit of 0 or less in every store; docs: an
  alert storm's evaluation backlog can still hold the barrier, and `afterWrite`
  returning `true` means "withhold the row", not proof of removal or of a recorded
  retry. Declined as documented residual limits: an erasure that stalls past its
  tombstones' retention (a later chunk, two concurrent erasures of one conversation,
  an expired tombstone deleted during a Postgres insert's conflict wait). A slow store
  that makes a hold chunk late fails the erasure safely; time-budget chunking is a
  possible follow-up.

### Still open

- The residual limits (clock assumption, rows written by a node cut off from NATS)
  are documented in the GDPR guide, not closed: closing the cut-off case would need a
  write-intent or an atomic conditional insert in every writer's store.

### What's next

Agent Health (#1386) replaces `HealthErasureFence` with an `AsyncErasureWriter` per
row kind, as the GDPR guide's "For a new async writer" list says and
`HealthShapedWriter` shows: `DrainBarrier.execute`/`awaitDrained`, `beforeWrite`/
`afterWrite` around every write, `verifyUserErased` in its participants, and
`removeRow`, `eraseConversation`, `countForConversation`.

```decision-log
| 2026-11-11 | Async per-user writes are guarded by durable tombstones + a drain barrier + verification; an erasure that cannot ensure "none can be written later" fails a step | Alert events queued before an erasure could land after it; the node-local fence was best effort | Keeping the fence with a longer TTL; deleting by a later sweep only; blocking erasure until queues empty without tombstones |
| 2026-11-11 | User tombstones are time-scoped (content captured up to `tombstone-clock-skew` after the erasure is refused), conversation tombstones are absolute | A reused user id must be able to produce new data; a deleted conversation never comes back | Absolute user tombstones for 400 days |
| 2026-11-11 | Tombstone user key = HMAC(key, SHA-256(userId)); the key is an HKDF subkey of the vault's KEK-wrapped deployment key when a vault is configured, else a DB-stored salt; a stored check value makes a changed key fail closed | A plain SHA-256 of an e-mail is reversible by dictionary; a key stored next to the tombstones only protects a dump of that table; a silently changed key would make every tombstone stop matching | The audit-keyed pseudonym (its key can rotate); a subkey of the KEK itself (changes on KEK rotation) |
| 2026-11-11 | No cache on tombstone reads | A cached "not erased" could miss a tombstone written before a barrier completed | A TTL cache invalidated by broadcast |
| 2026-11-17 | The drain barrier covers only the guarded write; first alert deliveries run on a separate stage that re-checks the guard before sending | A slow webhook or an alert storm would otherwise fail every erasure and conversation delete | Waiting for deliveries with a longer timeout |
| 2026-11-17 | Tombstone, pending-removal and verification reads on MongoDB go to the primary with majority concern | The client reads `nearest()`; a lagging secondary could answer "not erased" or "0 rows left" | Relying on the client default |
| 2026-11-17 | Rating comment excerpts carry a keyed rater subject and are erased with the rater | The rater's erasure must find them after the ratings are gone, without storing the user id | Storing the plain rater id |
| 2026-11-25 | A conversation delete blanks rating comment excerpts of the conversation too (superseding "they survive it") | The write side already refuses an excerpt for a tombstoned conversation, so the delete reporting success while one remains contradicted it; blanking an alert's copy costs nothing the rating does not keep | Keeping excerpts with their rating and exempting them from the conversation count |
| 2026-11-17 | Agent-test purges skip the tombstone and the cluster drain | Their user is synthetic; a drain per test conversation would tie every test run to the slowest node | Batching the drains |
| 2026-11-25 | The retention sweep gives the guarantee in one batched pass (one tombstone write, one drain) and keeps what it could not erase (superseding "runs no barrier") | Orphaned snapshots have no age, so "nothing can be queued" did not cover them; a batch costs one drain per sweep | One guarded erasure per conversation (a cluster scatter each); keeping the exemption |
| 2026-11-25 | A running node re-checks its cached tombstone key against the stored check value on every use, with a plain read | A node that kept an old key would hash users the others no longer match; an insert-if-absent there would undo an operator's reset | Caching for a TTL; requiring a restart only |
| 2026-11-26 | Cluster drains and `gdpr-stop` are confirmed by node id: every node read before the scatter must answer `drained=true` | A count let a node that joined meanwhile stand in for one that never answered, which may still hold a queued write | Comparing counts; ignoring late joiners entirely |
| 2026-11-26 | MongoDB tombstone and key-check point reads are linearizable on a replica set or mongos, majority on a standalone server | Majority reads do not make a deposed primary confirm it is still primary; a standalone server refuses linearizable reads and has only one primary | Majority everywhere; linearizable everywhere (every guarded write would fail closed on a standalone server) |
| 2026-11-26 | `eddi.gdpr.tombstone-retention` below 1 day + 2 × drain timeout + clock skew fails the start | A tombstone must outlive the erasure that writes it; the delete phase has no fixed bound, so the day is a margin | Keeping the tombstone alive until the erasure returns; no check |
| 2026-11-26 | A tombstone must still be valid when its erasure's verification ends, and is then written again so its retention (and the user cutoff) counts from the completion | A tombstone that expired mid-erasure could admit a write the verification did not see; a fixed retention floor cannot bound an erasure's length | Only the retention floor; keeping tombstones without expiry until the erasure returns |
| 2026-11-26 | `beforeWrite` returns an `Admission` that `afterWrite` takes, carrying the resolved user key | A failed key-check read after the write lost the pending removal's user scope | Re-resolving the key after the write; passing the raw user id |
| 2026-11-25 | Pending removals are checked across every writer, and a failed one is deferred rather than retried first | A removal of a writer no longer deployed must not be overlooked; a stuck batch must not starve the rest | Per-writer counts only; a larger batch |
```
