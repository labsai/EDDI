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
