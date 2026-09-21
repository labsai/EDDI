## 📈 docs(metrics): chart and document `eddi_conversation_store_conflict_count` (2026-09-19)

**Repo:** EDDI (`fix/conversation-turn-concurrency`, follow-up in PR #791)

The revision-guard commit registered `eddi_conversation_store_conflict_count` but did not add it to
`docs/metrics.md` or `docs/monitoring/eddi-full-metrics-dashboard.json`, so `MetricsDashboardCoverageTest`
failed in CI (`everyRegisteredMeterIsCharted` and `everyRegisteredMeterIsDocumented`). The counter now sits
beside undo/redo in the conversation-operations panel (legend "store conflict") and in the Conversation
Metrics reference. A non-zero rate means turn, undo/redo or resume writes that were refused because another
writer changed the conversation first. Before the revision guard, those were silent data loss.

Review follow-up in the same PR: `PostgresConversationMemoryStoreUnitTest` now stubs the existence probe
with its own statement and result set, and verifies the store closes the probe's result set. Before, it
reused the shared update mocks, which a code-quality bot reported as a possible `ResultSet` leak.

---

## ⚡ perf(conversation): append the turn's steps instead of rewriting the document (2026-09-18)

**Repo:** EDDI (`fix/conversation-turn-concurrency`, follows the revision-guard commit)

Appending one step used to rewrite the whole conversation document. On a production 5.x
database conversations average **410 KB**, so every turn sent 410 KB over the wire,
rewrote the document and put the whole document in the oplog — to add a few kilobytes.
The same whole-document cost is what made the 6.x startup migration take 24 minutes on
that database.

It is also what forced the previous commit to *report* a lost update rather than repair
it: a full-document replace has no safe retry, because re-applying it is the overwrite
the revision guard exists to refuse.

### What changed

- **`IConversationMemory.getPersistedStepCount()`** — how many steps the document held
  when this memory was loaded. Set on load by `convertConversationMemorySnapshot` (only
  when the stored steps and outputs agree in count), carried into the snapshot by
  `convertConversationMemory`, and refreshed by `ConversationStepRunner` after every
  successful write.
- **Both stores take an append path when the write is a pure append** — the count is
  known, steps and outputs are still in step, and the count grew.
  - MongoDB: `$set` of every field the snapshot emitted, `$unset` of every
    `ConversationMemorySnapshot.TOP_LEVEL_KEYS` entry it did not, `$pushEach` of the new
    steps and outputs, `$inc` of `_rev` — all under the same revision filter.
  - PostgreSQL: `(body - 'conversationSteps' - 'conversationOutputs') || jsonb_build_object(…)`
    so the arrays are concatenated server-side.
- **A conflict on the append path is retried — but only over another append.** Every
  full-document write (insert, replace, conditional replace: undo, redo, rerun, a HITL
  pause or resume commit) now stamps a second marker, `_histRev`, with the revision it
  creates; an append leaves it alone. So `_histRev <= loadedRevision` holds exactly when
  every write since this turn loaded was an append, and only then is pushing our tail after
  the winner's correct. Each attempt's filter also requires a state a say turn may complete
  over (not `ENDED`, `AWAITING_HUMAN` or `IN_PROGRESS`). When those hold, the retry
  (up to `MAX_APPEND_ATTEMPTS` = 5) re-applies the same push and both turns survive in
  commit order — the "reload and re-apply" option the previous commit deferred. When they
  do not, the append throws `ConcurrentConversationModificationException` and the say path
  reports it exactly as commit 1 does.
- **A merged append leaves the memory marked stale.** After a retry the document also holds
  the winner's step, which the live memory does not; the snapshot stays on the loaded
  revision with an unknown step baseline, so a second write from that memory is refused
  instead of erasing the winner's step.
- **Only the tail is encoded.** Both stores serialize the snapshot with the two arrays
  swapped for their new tails (Mongo) or emptied (Postgres body), so the stored history is
  neither re-serialized nor re-sent.
- **Everything that is not a pure append keeps the full-document write**: a rerun (it
  re-executes the current step without starting a new one, so the count does not grow), an
  undo or a redo (`ConversationMemory.undoLastStep`/`redoLastStep` drop the baseline
  explicitly — a redo grows the count by one and would otherwise look like a fresh turn),
  and a document whose steps and outputs had drifted (the replace also repairs the drift).

### Decisions

- **The `$set` field list is derived from the encoded snapshot, never hand-written.** A
  maintained list would silently stop persisting the next field somebody adds — a worse
  bug than the one being fixed. Everything the codec emits is `$set`; the complement of
  `TOP_LEVEL_KEYS` is `$unset`, which is what makes the non-array part of the document
  come out exactly as `replaceOne` would have left it.
- **`TOP_LEVEL_KEYS` exists because the serialization omits nulls** (`NON_NULL`) while
  `$set` merges. Without the `$unset` half, a field the turn cleared would keep its stale
  value — and every fresh turn after a resolved pause clears the HITL bookmark via
  `Conversation.clearStaleToolPauseState`. It is derived by reflection over the declared
  instance fields, and `ConversationMemorySnapshotTopLevelKeysTest` fails if a future
  `@JsonProperty` rename is not mapped, because under-inclusion is the dangerous direction
  (over-inclusion is a no-op `$unset`).
- **Postgres gets the append for the merge, not for the bytes.** MVCC rewrites the row
  either way; what matters there is that a retry concatenates instead of replacing.
- **Cross-instance.** The guard and the retry preconditions live in one filtered
  `updateOne` / `UPDATE … WHERE`, evaluated by the database, so they hold between pods.
  This matters more than it first appears, and an earlier draft of this commit got it
  wrong: it retried unconditionally and claimed identical behaviour on one pod or ten.
  Within one pod a say turn's persist runs before the coordinator releases the next turn,
  so the only concurrent writers are the uncoordinated REST paths (undo, redo, end). Across
  pods a HITL pause committed on pod 1 can land between pod 2's load and its persist; an
  unconditional retry would then `$set` READY and `$unset` the bookmark, erasing a pending
  approval whose timeout stays armed. The `_histRev` and state preconditions are what make
  the retry safe in both settings. They also close a pre-existing cross-pod hole:
  `endConversation`'s narrow `setConversationState(ENDED)` does not bump the revision, so
  an in-flight turn elsewhere used to resurrect the conversation as READY.
- **What leaving the prefix untouched rests on — a convention, not a type.** Earlier steps
  are handed out as `IConversationStep` (no `storeData`), but the `IData` they return has
  setters, and their outputs — like every entry of `getConversationOutputs()` — are
  mutable maps. No production code mutates a prior step today (the readers were checked:
  InputParserTask, MemoryItemConverter, the behavior-rule matchers, PropertySetterTask,
  ConversationHistoryBuilder, ConversationSummarizer, ContextualToolsProvider;
  LifecycleManager's strict-write handling touches the current step only). A future caller
  that did would have its change dropped on the next append, which is why this is written
  down in `ConversationMemoryStore.isPureAppend`.

### Known residual

The `$set` fields — `conversationProperties`, `conversationState`,
`pendingLongTermWrites`, the HITL bookmark — remain last-writer-wins on a retry, so two
genuinely concurrent turns can still have the later one's property map win. That is the
pre-existing semantics and unchanged for sequential turns; before this branch such a turn
lost its whole step *and* its properties. Field-level property merging (dotted `$set`
paths) would close it and is deliberately out of scope here.

Not tested: retry exhaustion (`MAX_APPEND_ATTEMPTS`) needs five interleaved writers
between one attempt's filter and its write, which the store offers no seam to force.

Pre-existing and out of scope: startup migrations (`MigrationManager`,
`V6RenameMigration.migrateEnvironments`) replace raw documents without touching `_rev` or
`_histRev`, so during a rolling upgrade they can revert a concurrent append on another pod.
The Postgres append falls back to `IN_PROGRESS` for a null state where the full-row path
throws — an inconsistency copied from the existing conditional store.

A second, separate improvement is also out of scope: `ConversationService.say` returns
HTTP 200 from inside the pipeline callable (`Conversation.runStep`'s
`outputProvider.renderOutput`) while the persist happens afterwards, and it loads the
memory on the REST thread before `conversationCoordinator.submitInOrder`. That is what
makes the window wide enough for a strictly sequential client to hit. The append makes it
harmless rather than closing it.

### Files

- `src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryStore.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java`
- `src/main/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshot.java`,
  `IConversationMemory.java`, `ConversationMemory.java`, `ConversationMemoryUtilities.java`
- `src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java`
- `src/test/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshotTopLevelKeysTest.java` (new)
- `src/test/java/ai/labs/eddi/datastore/mongo/MongoConversationTurnConcurrencyTest.java` —
  plus: an append does not merge over a pause, an undo, an undo hidden by a later append,
  or an `ENDED` state committed after its load; a merged append refuses a second write
- `src/test/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStoreTest.java` —
  the same pause/undo/ended cases, the conditional store's revision guard at SQL level, and
  an append on a row deleted mid-turn

### Mutation check

Forcing `isPureAppend` to false fails 3 MongoDB tests — `overlappingTurnsBothSurvive` and
`threeOverlappingTurnsAllSurvive` raise `ConcurrentConversationModificationException`, and
`appendDoesNotRewriteThePrefix` finds its out-of-band sentinel erased — and 1 Postgres test
(`overlappingTurnsBothSurvive`). Removing the retry preconditions (filter and re-check) on
both backends fails 7 tests: the four Mongo rewrite/ended cases and the three Postgres ones.
Replacing the merged-append invalidation with a normal stamp fails
`mergedAppendInvalidatesTheMemory`.

---

## 🐛 fix(conversation): optimistic concurrency on the conversation document (2026-09-18)

**Repo:** EDDI (`fix/conversation-turn-concurrency`)

A turn's reply could be handed to the client with HTTP 200 and `conversationState: READY`
and the turn then be discarded — no error, no 409, nothing in the log. Measured on a live
6.4.0 instance: three turns posted to `POST /agents/managed/{intent}/{userId}` back to back,
each awaiting its own 200, produced a conversation holding the greeting, turn 1 and turn 3.
Turn 2 was absent from MongoDB entirely. A 3 second gap between turns stored all four outputs.

### The mechanism, as verified in this branch

Two things compose:

1. **The reply leaves before the turn is durable.** `Conversation.runStep` calls
   `outputProvider.renderOutput(memory)` in its `finally` block, and that is the lambda
   `ConversationService.say` passes to `agent.continueConversation` — so
   `responseHandler.onComplete` (HTTP 200) fires from *inside* the pipeline callable, while the
   persist happens afterwards in the runtime's `onComplete`
   (`ConversationStepRunner.runGuardedConversationStep`). A client that waits for every 200
   can therefore still post the next turn before the previous one has been written.
2. **The next turn's load is not serialized, and the write replaced the whole document.**
   `ConversationService.say` calls `loadConversationMemory` on the REST thread, *before*
   `conversationCoordinator.submitInOrder`. So turn N+1 can load a snapshot that predates
   turn N's store, append its own step, and write the whole document back via
   `replaceOne({_id}, snapshot)` — matching on the id alone. Last writer won, silently.

`MongoConversationTurnConcurrencyTest` reproduces both shapes against a real MongoDB
(Testcontainers) through the production conversion path, and `threeSequentialTurnsKeepEveryTurn`
reproduces the reporter's exact symptom: stored inputs `[greeting, turn 1, turn 3]`.

### What changed

- **`ConversationMemorySnapshot._rev`** — a monotonic revision on the conversation document.
  A loaded snapshot carries the revision it was loaded at; `IConversationMemory.getRevision()`
  carries it across the turn (set by `convertConversationMemorySnapshot`, read back by
  `convertConversationMemory`).
- **Both snapshot-store methods are now revision-guarded.**
  `ConversationMemoryStore.storeConversationMemorySnapshot` filters on
  `{_id, _rev: loaded}` and stamps `_rev: loaded + 1`; a zero-match throws the new
  `ConcurrentConversationModificationException` (a `ResourceStoreException` subtype).
  `storeConversationMemorySnapshotIfState` — the undo/redo and HITL-resume path — now filters
  on the revision **in addition to** the conversation state, and returns `false` on a miss
  (which the REST layer already maps to 409).
- **`PostgresConversationMemoryStore` keeps parity** via
  `WHERE id = ? AND COALESCE((data->>'_rev')::bigint, 0) = ?`.
- **A zero-match is disambiguated with one point-read** so the two causes get different
  answers: the conversation is gone (deleted mid-turn — a plain `ResourceStoreException`,
  nothing to retry against) versus present at another revision (a conflict a retry from a
  fresh load can still resolve).
- **The say path reports the conflict loudly**: `ConversationStepRunner.reportStoreConflict`
  increments `eddi_conversation_store_conflict_count` and logs ERROR naming the revision the
  turn was built on.
- **A conditional-store miss is diagnosed, not assumed.** `storeConversationMemorySnapshotIfState`
  now misses for two reasons, and its callers (say-path pause commit, HITL resume, undo, redo)
  were written when there was one ("a concurrent end/cancel moved the state"). On a miss,
  `ConversationStepRunner.diagnoseConditionalStoreMiss` reads the stored state once: if it still
  equals the expected state, the revision failed, so it counts the conflict and logs it at WARN.
  The callers' own messages now say "state or revision". Without this, a revision conflict on
  those paths would have been logged as a benign handover and never counted — silent again.

### Decisions

- **Detect and report, do not merge — yet.** By the time the persist runs, the reply is already
  with the caller, so neither a 409 nor a re-execution is available on the say path. A "reload
  and re-apply" is only safe once re-applying means *appending* rather than replacing, which is
  the follow-up commit. This commit's job is to convert a silent loss into an attributable one:
  a dedicated exception, an ERROR line and a metric.
- **A conflict does NOT flip the conversation to ERROR.** `logConversationError` would, but the
  document on disk belongs to a writer that succeeded; breaking a healthy conversation because
  this turn lost the race trades one wrong outcome for another.
- **Cross-instance by construction.** The guard is a filtered `updateOne`/`UPDATE … WHERE`
  evaluated by MongoDB or PostgreSQL, not a JVM lock. It holds between pods, between the say
  path and the undo/redo path, and between EDDI and any other writer of the collection — which
  is the point: `InMemoryConversationCoordinator` only serializes turns within one pod, and the
  load happens outside even that.
- **Narrow field updates deliberately do not bump `_rev`.** `setConversationState`,
  `compareAndSetState` and `clearHitlBookmark` are already arbitrated by the conversation-state
  CAS; bumping there would turn existing intentional handovers (a watchdog parking a turn as
  `EXECUTION_INTERRUPTED` while that turn is completing) into write conflicts.
- **Legacy documents upgrade without a migration.** A document with no `_rev` reads as
  `UNVERSIONED_REVISION` and its filter is `{_rev: 0} OR {_rev: {$exists: false}}` — MongoDB's
  `{_rev: 0}` does not match a missing field, so without the `$exists` half every pre-upgrade
  conversation would have been bricked on its next turn.
  `legacyDocumentWithoutRevisionUpgrades` covers it.

### Files

- `src/main/java/ai/labs/eddi/engine/memory/ConcurrentConversationModificationException.java` (new)
- `src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryStore.java`
- `src/main/java/ai/labs/eddi/engine/memory/IConversationMemoryStore.java`
- `src/main/java/ai/labs/eddi/engine/memory/ConversationMemory.java`,
  `IConversationMemory.java`, `ConversationMemoryUtilities.java`
- `src/main/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshot.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java`
- `src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java`,
  `ConversationService.java`
- `src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java` (log wording)
- `src/test/java/ai/labs/eddi/datastore/mongo/MongoConversationTurnConcurrencyTest.java` (new)
- `src/test/java/ai/labs/eddi/engine/internal/ConversationServiceStoreConflictTest.java` (new) —
  the say-path conflict is counted and does not flip the conversation to ERROR; a
  conditional-store revision miss is counted, a state miss is not
- `src/test/java/ai/labs/eddi/engine/memory/ConversationMemoryStoreTest.java`,
  `ConversationMemoryStoreResilienceTest.java`,
  `src/test/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStoreUnitTest.java`

### Mutation check

Disabling the revision half of `revisionFilter` (returning the id filter alone) fails 4 tests,
including both invariant reproductions — `overlappingTurnIsNotSilentlyLost` reports
`[greeting, turn B]` and `threeSequentialTurnsKeepEveryTurn` reports
`[greeting, turn 1, turn 3]`. Removing the conflict-counter increments fails 2 of the 3
`ConversationServiceStoreConflictTest` tests.

### What's next

The write amplification is untouched: a turn still rewrites the whole document to append one
step (410 KB average on a production 5.x database). The follow-up commit replaces that with
`$push` of the new steps, which also turns a conflict into a retryable merge instead of a
reported loss.

---
