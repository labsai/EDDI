## feat(ingestion): a durable per-document work table, drained in slices, with a shared provider cooldown and Drive's quota in units, phase Q (2026-11-23)

**Repo:** EDDI (`feat/ingestion-work-table`, on `feat/ingestion-folder-scoping` with `feat/ingestion-throttle` merged in)

### What changed and why

Phase Q (§4, §3.3, §5.5) of `planning/rag-large-scale-multimodal-ingestion-plan.md` (branch
`docs/rag-scale-multimodal-plan`). Until now a run listed a source and fetched and embedded each
document the moment it was listed, so a throttle part-way cut the listing short, and a crash or a
deploy lost everything the run had not finished.

- **The work table** (`IngestionWorkQueue`, extended by `IIngestionStateStore`). It lives on the
  document-state rows of both datastores: `rag_ingestion_documents` on PostgreSQL gains `work_state`,
  `work_version`, `work_token`, `work_payload`, `not_before`, `attempts`, `last_error`, `claimed_by` and
  `claim_until` (added with `ADD COLUMN IF NOT EXISTS`, all nullable or defaulted, plus a partial index
  over pending and claimed rows); the MongoDB twin gains the same fields and an index. Rows written
  before read as "no work". A document the source never held gets a row with no vectors
  (`tombstoned`, no content hash), which every reader already treats as "embed me".
- **Discovery only lists.** `IngestionPipeline` queues each new or changed document that is fetched on
  demand (Google Drive, uploads) instead of fetching it; queueing stamps the row with the listing run,
  so a queued document is never missed, and never touches its content hash or tombstone. A crawled
  page, which arrives with its content, and a preview are processed as before. Queueing is idempotent:
  equal work under the same version token keeps its `not_before` and attempts.
- **The drain.** After the listing and its reconciliation, the run claims due rows in batches
  (`eddi.rag.ingestion.work.claim-batch`, default 20) — one atomic statement per row, `FOR UPDATE SKIP
  LOCKED` on PostgreSQL and `findOneAndUpdate` on MongoDB, fenced on the run that owns the source — and
  processes each with the existing collector. Each claim is settled by an explicit outcome: done,
  deferred to a time (a throttle, never a failure), failed with a backoff (one minute doubling to an
  hour; poisoned after `eddi.rag.ingestion.work.max-attempts`, default 5), or released unchanged.
  Completion is conditional on the claimer and the claimed `work_version`, so newer work queued
  meanwhile is not lost. A document is attempted at most once per run.
- **Crash safety.** Claims carry a lease longer than the run's stale deadline; the reaper returns a
  dead run's claims to pending at once, and an expired lease can be claimed regardless. A document
  processed again after a crash is unchanged by its recorded version, so it is not embedded twice.
- **Continuations.** A run that leaves work arms a one-shot schedule,
  `rag-ingestion:{kb}:{source}:cont` (`RagIngestionContinuations`), for when the next row is due — and,
  before its first claim, one for after its claim lease, so a dead node is followed up. Its fire
  (`RagSourceIngestionService.processContinuationFire`, routed by `ScheduleFireExecutor`) drains under
  the knowledge base's current version with the new trigger `CONTINUATION`, lists nothing, and starts no
  run when nothing is queued. Disabling or removing a source and purging it withdraw it. A budget stop
  arms none: the budget stays a per-run limit.
- **T1's throttle onto `notBefore`.** A throttled document is deferred to `resumeAfter`, or
  `eddi.ingestion.throttle.cooldown` from now when the provider named no time; the run still stops
  `THROTTLED`. A complete listing now reconciles deletions even when its drain is throttled; a partial
  listing still reconciles nothing and withdraws no queued work, and a complete one withdraws the work
  of documents it no longer lists (`cancelUnlistedWork`), which keeps S's scope removal intact. The
  orphan sweep waits while any work is queued, deferred or failed.
- **The shared cooldown** (§3.3). `IProviderCooldownStore` (`provider_cooldowns` on PostgreSQL and
  MongoDB) holds one row per quota, keyed by `ProviderThrottleKey`: provider, endpoint host, model and
  credential scope (the project, or a truncated SHA-256 of the key). `ProviderCooldowns` caches reads for
  5 s. `ThrottledEmbeddingModel` records every throttle it meets and, before each call, waits out a
  cooldown that fits the deadline or stops without calling the provider; the drain checks it before
  claiming, so nothing is downloaded only to be deferred. Rows only move later.
- **Drive's quota in units** (§5.5). `DriveQuota` charges 100 units for a listing or metadata read and
  200 for a download or export against a token bucket per connection
  (`eddi.rag.throttle.googleDrive.units-per-minute`, default 250,000), waiting within the deadline. A
  Drive throttle writes the shared cooldown, so every node stops calling that connection; a download
  the drain meets it on is deferred, not failed.
- **REST and Manager.** `GET …/sources/{sourceId}/work` (VIEW) returns queued, deferred, in-flight,
  done and failed counts and `nextAttemptAt`; `POST …/sources/{sourceId}/retry-failed` (EDIT) re-arms
  poisoned rows and wakes the drain. The Manager's source card shows the work queue with **Retry
  failed**, and a `Continued` badge for continuation runs, in all 11 locales.
- **Operator** (revision 86): knows the work table, continuations and retry-failed. No endpoint was
  added to its allow-list.
- **Docs:** `docs/rag.md` — the work table, the shared cooldown, Drive units, the two endpoints.

### Design decisions

- **Slices run on the run's own worker, not inside the schedule fire.** The plan wants each fire to
  do its slice before returning so the poller's lease covers it. The fire timeout is 5 minutes and
  `processScheduledFire` documents why runs left the fire thread; a slice is still one run bounded by
  `timeBudgetMinutes`. What bounds the loss after a crash is the claim lease (the run's stale threshold)
  and the safety continuation armed before the first claim.
- **One run per source at a time** (`maxConcurrentSlices` stays 1): the single-RUNNING-run index and
  the fence stay as they are, and row claims are exclusive regardless.
- **The attempt cap and claim batch are deployment properties**, not per-source settings.

### Not done (later in Q or after)

`FileRef` streaming and the temp-disk quota; the global worker cap and round-robin fairness;
`maxConcurrentSlices` above 1; optional `requestsPerMinute`/`tokensPerMinute` ceilings; a slice IT
with two schedulers on one database.
