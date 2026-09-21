## 🚧 fix(ingestion): a reaped run can no longer write over the run that replaced it (2026-09-21)

**Repo:** EDDI (`feat/ingestion-state-store`)

### Why

Review of PR #785 found a write-after-reap hole in the ingestion state store, and left it
open because no fix fitted in that PR's shape. Worker A holds the `RUNNING` run row for a
source, stalls past the stale threshold, and `reapStaleRuns` fails its run. Worker B claims
a replacement run. A then wakes up and calls `recordIngested` / `recordSeen` /
`recordUnreachable` with a run id that is dead, mutating document rows that now belong to B.

The severity was originally judged bounded — a late `recordIngested` sets `missedRuns = 0`
and `tombstoned = false`, so it preserves a document rather than losing one. Writing the
contract cases turned up two worse paths that the "it only keeps data" reading misses:

- **Silent, permanent loss of a page.** Tombstoning deletes a document's vectors. A stale
  `recordIngested` lifts the tombstone *and* restores the hash those deleted vectors
  matched, so `hasChanged` reports "unchanged" on every later run and the page is never
  embedded again. It is gone from retrieval for good, with nothing in any log.
- **Vector deletion of a live page.** `recordUnreachable` writes only the run marker, which
  looks harmless until you follow it: stamping that marker is exactly how a document escapes
  the owning run's miss count. A zombie overwriting it hands a page the live run has just
  seen back to `tombstoneMissing`.

### What changed

A fencing token both backends apply **in the same statement as the document write**, because
the alternatives re-introduce backend divergence: MongoDB cannot join collections in an
update, and multi-document transactions need a replica set while EDDI supports standalone
MongoDB (`docker-compose.yml` ships `mongo:7` standalone). A PostgreSQL-only
`AND EXISTS (SELECT 1 FROM rag_ingestion_runs …)` fence would leave the two stores behaving
differently — the exact drift `IngestionStateStoreContract` exists to prevent.

Ownership is denormalized onto the document row instead:

- `fencing_run_id` / `fencingRunId` — the run that owns the row. `startRun` takes ownership
  of the source's rows when it claims a run; `reapStaleRuns` releases it (to `NULL`) when it
  actually reaps something, so a reaped worker is fenced from that moment rather than only
  once a replacement claims.
- `fencing_generation` / `fencingGeneration`, paired with a new `generation` on the run row —
  the claim's sequence number, which orders the ownership stamps themselves. Without it, a
  stamp delayed past its own run's reaping could land after the replacement's and take the
  source back.
- Every `record*` and `tombstoneMissing` call puts its `runId` into the update's own filter.
  **No signature changed and the caller makes no extra round trip** — the `runId` the pipeline
  already passes *is* the fencing token. PostgreSQL uses `ON CONFLICT … DO UPDATE … WHERE
  fencing_run_id = EXCLUDED.fencing_run_id`; MongoDB puts the field in the upsert filter and
  reads the resulting `(sourceId, documentId)` duplicate-key error as the same answer.

A fenced write is a no-op logged at DEBUG, not an exception: the reaper already decided the
run is dead and `finishRun` logs once that its result was discarded, so failing every document
of a doomed crawl would only add noise to a result that is thrown away.

**What the fence does not cover,** stated in the interface Javadoc rather than glossed: a
document the superseded run is the first ever to see has no row to own, so its insert still
lands. That is the benign direction, and the owning run's `tombstoneMissing` reconciles it
away over the following runs.

### Design decisions

- **Ownership stamped at claim, not propagated lazily.** Comparing a per-document generation
  on the write alone only fences rows the *replacement* run has already touched — a zombie
  would still be free to write every row the live run had not reached yet, which is most of
  them early in a crawl. Taking ownership of the source's rows at claim time costs one bulk
  update per run and is the only shape that fences the whole source.
- **`fencing_run_id` is separate from `last_run_id`.** They mean different things:
  `last_run_id` is the run that last *wrote* the row and drives the miss count; ownership is
  about who is *allowed* to write. Overloading one field would have made `recordUnreachable`
  grant itself the write permission it is being checked for.
- **`ALTER TABLE … ADD COLUMN IF NOT EXISTS` alongside the `CREATE TABLE`.** `CREATE TABLE IF
  NOT EXISTS` is a no-op against a database an earlier build of this branch already created,
  which would have left the fence silently un-enforceable there.

### Verified

`MongoIngestionStateStoreTest` and `PostgresIngestionStateStoreTest` — 38 cases each, both
green. Non-vacuity checked by reverting `src/main` and re-running: **6 of the 7 new cases fail
identically on both backends** (the seventh, `fencingIsScopedPerSource`, is the guard against
over-fencing and must pass either way).

### Follow-up for the downstream stack

`InMemoryIngestionStateStore` (test double, added in #787) implements the same contract, so it
will need the same fence when this branch merges forward into #787 / #789 / #790. Nothing in
`IngestionPipeline` needs to change — preview mode never calls `record*`, and a reserved run id
comes from `startRun` like any other.

### Files

- `src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java`
- `src/main/java/ai/labs/eddi/modules/ingestion/mongo/MongoIngestionStateStore.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresIngestionStateStore.java`
- `src/test/java/ai/labs/eddi/modules/ingestion/IngestionStateStoreContract.java`

---

### Review follow-ups (2026-09-21)

- **The reaper released ownership for the whole source, not for the runs it reaped.**
  `reapStaleRuns` fails the stale runs and clears `fencing_run_id` in two writes, not one. Once the
  first commits, the partial unique index on the source is free: a replacement run can claim the
  source and stamp every document with its own id before the second one runs, and a source-wide
  release then wipes the live run's fence. Every `recordSeen`, `recordIngested`,
  `recordUnreachable` and `tombstoneMissing` of that run silently matches nothing while it carries
  on crawling and embedding, so it finishes looking healthy having recorded no document at all, and
  the whole source is re-fetched and re-embedded on the next run. PostgreSQL now reaps with
  `UPDATE ... RETURNING run_id` and releases `WHERE fencing_run_id = ANY (?)`; MongoDB claims each
  stale run with `findOneAndUpdate` — the ids have to come back *with* the write, not from a read
  after it — and releases with `in(fencingRunId, reapedIds)`.
- **`startRun`'s insert was the one operation still outside `translating(...)`.** It handled
  `MongoWriteException` and let everything else out, so a connection failure, a timeout or a
  step-down during the insert escaped as a raw `MongoException` while PostgreSQL answered the same
  outage with `IngestionStateStoreException` — the backend-dependent exception contract the helper
  was added to remove. The duplicate-key case is now handled inside the supplier, so it still
  returns `Optional.empty()`, and everything else falls through to the helper.

The contract gained `reapingDoesNotReleaseAnotherRunsOwnership` and one hook,
`forceDocumentOwner`, implemented per backend. The hook is needed because the interleave cannot be
produced through the public interface: the partial unique index means a stale `RUNNING` run and its
replacement can never both exist, so there is no sequence of `startRun` calls that leaves a document
owned by a run other than the one about to be reaped.

### Still open

Review also found that `recordIngested` inserts a newly discovered document with a
`fencing_run_id` but no `fencing_generation`, and `takeOwnership` matches
`fencing_generation IS NULL` (the arm that exists for rows predating the fencing columns). A
`takeOwnership` delayed past its own run's reaping can therefore land on a row the replacement run
inserted and take that one document back. The scoped release above does not close it. Every fix
needs the claiming run's generation at insert time, which `recordIngested` is not given: a scalar
subquery works on PostgreSQL and has no MongoDB equivalent, a per-call lookup is symmetric but adds
a read per ingested document, and carrying the generation through `startRun`'s return type is the
cleanest but changes `IIngestionStateStore`, which two further open PRs are built on. Left for that
decision rather than picked unilaterally here.
