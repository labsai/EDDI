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

### Merging `main`: two designs for the same concern

`main` reworked tombstoning while this branch was open, and the two changes met
head-on. This branch made `tombstoneMissing` atomic -- `UPDATE ... RETURNING` on
PostgreSQL, `findOneAndUpdate` in a loop on MongoDB -- so two runs finishing together
could not both report the same document as newly gone. `main` split it instead, into
`bumpAndFindMissing` + `markTombstoned`, so a caller can delete the vectors *before*
marking: marking first is durable in the wrong order, because a crash between the two
leaves a document flagged gone while its chunks stay retrievable, and a tombstoned
document is never reported again.

**`main`'s split is kept, and this branch's fencing is grafted onto it.** The two
rationales are not symmetric: the durability ordering is a property nothing else
provides, whereas the atomicity the `RETURNING` clause bought is already delivered by
the fence. Only the owning run satisfies `fencing_run_id = ?`, so two runs cannot both
reach the same document to report it in the first place. Keeping both would have meant
choosing the weaker guarantee and losing the stronger one.

Three consequences, each of which is now pinned by a test:

- **The search is fenced, not just the bump.** Fencing the miss counter alone leaves a
  hole: the counter is shared, so a document another run has already bumped to the
  threshold still satisfies an unfenced search. A superseded run would hand its caller
  a list of documents to delete, and the caller deletes the vectors before anyone
  checks who owned them. Both statements now carry the predicate, in both backends.
  This was not covered -- removing the predicate from the PostgreSQL search left all 39
  contract cases green -- so `supersededRunReportsNothingMissing` was added to the
  shared contract, where all three implementations run it.
- **`tombstoneMissing` reports the transition it just performed.** `bumpAndFindMissing`
  reports its candidates *before* marking them and `DocumentState` is immutable, so the
  convenience default was handing back a list that still said `tombstoned=false`
  although marking had succeeded. A caller that believed it would re-report the same
  documents on the next run. `DocumentState.asTombstoned()` restamps them.
- **The in-memory double was fenced too.** `main` added
  `InMemoryIngestionStateStore` with no fencing, which would have left the double
  behaving differently from both backends on exactly the property this branch exists to
  add -- the failure the shared contract was written to prevent. It now takes
  ownership on `startRun`, fences all four document writes, and releases only the
  ownership a reap actually took. All three implementations run the same 39 cases.

The double deliberately reproduces one thing it could have quietly fixed: a document
row inserted for the first time lands with an owner and **no** generation, because that
is what both backends do. Stamping the claiming generation there is the subject of an
open review thread and is a sequencing decision, not a merge one -- it changes
`IIngestionStateStore`, which two further PRs are built on.

---

## 🗃️ feat(ingestion): document state and run history for RAG sources (2026-09-17)

**Repo:** EDDI (`feat/ingestion-state-store`)

### Why

Ingesting a source is a *reconciliation*, not an append: each run compares what the source offers now
against what the knowledge base holds, and decides per document whether to skip, re-embed, or conclude
it is gone. That needs durable state, and the shape of it is where the salvaged PR #529 went wrong —
quietly, because a corrupted knowledge base has no stack trace. `IIngestionStateStore` is that state,
designed so the draft's four failure modes are not expressible.

### The two rules the API enforces

- **A document is recorded only after its vectors are stored.** The draft's `shouldIngest(source, doc,
  content)` upserted the new hash while *deciding* whether to ingest, and embedding happened afterwards
  inside a `catch` that only logged. One 429 from the embedding provider therefore marked a page done
  forever: the hash matched on every later run, so it reported "unchanged" and was never embedded. Here
  `lookup` and `recordIngested` are separate calls and the Javadoc says which side of the embedding call
  each belongs on.
- **A document missing from one run is not a deleted document.** The draft marked everything not seen in
  the current run as stale, unconditionally — so a site outage, a network blip, or simply hitting
  `maxPages` flagged the remainder of the corpus. `tombstoneMissing` counts *consecutive* misses and only
  tombstones at a threshold, and callers are told not to call it for a failed run at all.

### What it stores

Per (source, document): content hash, ETag and Last-Modified for conditional fetching, first/last
ingested timestamps, last run id, consecutive miss count, tombstone flag. Per run: status, timings, the
seen/ingested/unchanged/failed/tombstoned counters, segments stored, cost in dollars, and the error —
so a failure is visible in the Manager rather than only in a log line.

`startRun` returns empty when a run is already in flight, enforced by a **partial unique index** on
`(source_id) WHERE status = 'RUNNING'` in both backends. This is what stops an operator clicking "run
now" five times from starting five concurrent crawls into one knowledge base, and because the database
enforces it, it holds across instances. `reapStaleRuns` releases a source whose run died with its
process.

`ContentHashes.sha256` is a static utility rather than an interface method: the draft had each backend
carry its own copy, two chances to drift, and a drift silently re-embeds an entire knowledge base.

### Tests

**One shared contract, run against both backends** — `IngestionStateStoreContract` is a JUnit interface
with 23 cases, implemented by `MongoIngestionStateStoreTest` and `PostgresIngestionStateStoreTest`
(Testcontainers). The draft's two stores had drifted apart — one overwrote the first-ingest timestamp on
every call while the other preserved it — and nothing failed, because each was only tested against
itself. A knowledge base that behaves differently depending on the operator's database choice is a
support problem with no error message.

Plus 7 cases for `ContentHashes`, including a pinned published SHA-256 vector: if the hash ever changes,
every deployed knowledge base re-embeds itself on the next run.

53 tests, all green on both backends. Mutation-checked: reverting `setOnInsert` to `set` and ignoring the
miss threshold fails three of them.

### Next

The crawler, then the source config plus the pipeline that ties fetch → convert → this store → embed,
with vector removal driven by the tombstone list.
