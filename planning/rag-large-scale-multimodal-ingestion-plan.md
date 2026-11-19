# Implementation Plan — Large-Scale and Multimodal Knowledge-Base Ingestion

**Status:** Proposed. Nothing in this plan is implemented.
**Created:** 2026-11-19
**Review history:**
- **Drafted** 2026-11-19.
- **Reviewed** the same day by an independent reviewer.
- **Revised** with the reviewer's corrections and the product owner's decisions (§0). A later
  product-owner update made `BOTH` the default for every media type. It supersedes both the
  reviewer's text-first recommendation and this plan's earlier "native only by default".

**Scope:** Backend (EDDI repo) and the Manager (`ui/manager`)
**Builds on:** the ingestion stack and Google Drive source of
[`rag-ingestion-source-types-plan.md`](rag-ingestion-source-types-plan.md) (Phase G1, branch
`feat/google-drive-ingestion`, tip `cf3c4cd4ad`). Paths are under `src/main/java/ai/labs/eddi/`
unless stated otherwise.

> **Three problems, one order.**
>
> - **Throttling.** A real ingestion of a 68-file Shared Drive on the Gemini free tier failed
>   49 of 68 documents on `429 RESOURCE_EXHAUSTED`.
> - **Media.** The pipeline cannot read an image or a video: that drive's 3 JPEGs and 2 MP4s
>   were skipped.
> - **Scale.** A drive of a million files is re-listed in full on every run.
>
> Multimodal ingestion multiplies both the request count and the cost per document. So the
> order is:
> 1. a minimal throttle fix, shippable in days;
> 2. folder scoping;
> 3. a durable work queue and incremental sync, which is what huge drives need;
> 4. budgets;
> 5. native media.

---

## Table of Contents

0. [Decisions](#0-decisions)
1. [Goal](#1-goal)
2. [Where the stack is today](#2-where-the-stack-is-today)
3. [Provider throttling and quotas](#3-provider-throttling-and-quotas)
4. [The ingestion work table](#4-the-ingestion-work-table)
5. [Very large drives](#5-very-large-drives)
6. [Folder scoping for the Drive source](#6-folder-scoping-for-the-drive-source)
7. [Multimodal ingestion](#7-multimodal-ingestion)
8. [Cost, budgets and preview](#8-cost-budgets-and-preview)
9. [Privacy, security and untrusted content](#9-privacy-security-and-untrusted-content)
10. [REST, Manager, operator, docs](#10-rest-manager-operator-docs)
11. [Phasing](#11-phasing)
12. [Testing strategy](#12-testing-strategy)
13. [Risks](#13-risks)
14. [What I would NOT do](#14-what-i-would-not-do)
15. [Open questions](#15-open-questions)
16. [Appendix: verified provider facts](#16-appendix-verified-provider-facts)

---

## 0. Decisions

Product-owner decisions, 2026-11-19. They are binding.

**D1 — Every media segment gets two vectors by default (`BOTH`).** This applies to image,
audio, video and PDF page alike. It is the owner's final decision. It supersedes the reviewer's
text-first recommendation and this plan's earlier "native only, with a video exception".

- **The two vectors:**
  - the **native media embedding**;
  - the **companion text embedded as a text vector**: a caption for an image, a transcript for
    audio and video, OCR or the text layer for a PDF page.
- **What links them:** both entries carry the same document metadata and citation metadata,
  and the same `mediaSegmentId`.
- **What the agent reads:** the stored segment text is the companion text under a citation
  header, so it can quote and cite either match.
- **Opt-in alternatives:**
  - `vectors: MEDIA`: native only;
  - `vectors: CAPTION`: text only.
- **A model without native support for a modality** gives the text vector only (the text
  path). If no companion model is configured either, the file is skipped and counted.
- **Video** needs no special case any more. Its audio track is ignored by the Gemini embedder,
  and at most 32 frames are sampled (§16). The transcript vector that `BOTH` adds is what makes
  spoken content searchable.

**D2 — Huge drives are a hard requirement:** millions of files, terabytes.

**D3 — Folder scoping ships early,** as planned (§6).

**Reviewer corrections adopted,** and where each one lands:

- **One durable per-document work table.** It replaces run checkpoints, retry sets and
  `listed=false` machinery (§4).
- **T1-min first** (§3.2).
- **The throttle is scoped per project, model and endpoint**, with a cooldown row and no AIMD
  (§3.3).
- **The Drive quota is counted in units** (§5.5).
- **Vector-store sizing, and refusing rather than warning** (§5.4–5.6).
- **The change feed runs concurrently with the backfill** (§5.2).
- **The Gemini Batch API is a later mode** (§8.4).
- **No Vertex AI RAG Engine** (§14).
- **Smaller additions:**
  - a temp-disk quota (§4.6);
  - GDPR erasure by data subject (§15);
  - dedupe deferred (§5.8);
  - multi-node fan-out (§4.7);
  - hardened media parsers (§7.3, §9).

---

## 1. Goal

- **Survive provider quotas.** When the provider says "slow down", a run waits, or the work
  item is postponed. Today the document fails.
- **Scale to large drives.** Ingest a Shared Drive of millions of files. After the first
  backfill, the work done is proportional to what changed. Work survives restarts and node
  loss, and is spread over the cluster.
- **Scope precisely.** Scope by folder path or id, exclude subtrees, and pick folders in the
  Manager. A scope change takes effect at once.
- **Make media searchable.** Index images, audio, video and PDF pages with a native multimodal
  vector plus a companion-text vector, starting with Gemini Embedding 2. Every match carries a
  quotable text and a citation such as "video X, 12:30–13:00".

### Non-goals

- Storing raw media in EDDI (§7.7).
- Images embedded in DOCX or PPTX files, or on crawled web pages (§15).
- Drive push notifications.
- A managed provider RAG product (§14).

---

## 2. Where the stack is today

| Piece | Today (evidence) | Gap |
|---|---|---|
| Pipeline | One run per source walks discovery inline: `Discovery.accept` → `Collector.process` → `upsert` → `onFile` → `acceptDocumentCounted` → `split` → `embed` (`modules/ingestion/IngestionPipeline.java:932, 1203–1242, 1347–1375, 1506–1584, 1637–1700`) | Sequential, single node, text only |
| Partitions | `listPartitions` exists, but the pipeline refuses anything other than one: "Several partitions need a cursor each and a coverage per partition, **which arrive with the work queue**" (`IngestionPipeline.java:804–810`, quote at `:806–807`) | The design anticipated §4 |
| Content | `SourceDocument.Content` is sealed: `HtmlPage`, `FileBytes(byte[], mimeType)`, `Text` (`connector/SourceDocument.java:89–138`) | Whole file on the heap |
| Extraction | `DocumentExtractors.resolveMimeType` refuses any detected type other than PDF, ZIP or octet-stream with "Files of type X hold no text to ingest" (`extract/DocumentExtractors.java:104–121`) | Media rejected at upload |
| Sniffing | `MimeValidator.detectMime` already recognises JPEG, PNG, GIF, BMP, WebP, TIFF, MP4, OGG, WAV and MP3 (`engine/attachments/MimeValidator.java:175–215`) | Reusable |
| Drive formats | `DriveFormat` has no media formats (`drive/DriveFormat.java:25–38`). Anything else is counted as "unsupported type" (`drive/GoogleDriveSourceConnector.java:588–592, 673–678`) | Why the JPEGs and MP4s were skipped |
| Drive throttling | `DriveApi.call` retries `THROTTLED`/`TRANSIENT` up to `MAX_RETRIES = 3`, honours `Retry-After`, caps backoff at 30 s and gives up past the deadline (`drive/DriveApi.java:56–57, 170–206, 229–248`). A persistent throttle stops discovery (`GoogleDriveSourceConnector.java:200–206`) | Per call; counts requests, not units |
| Embedding throttling | None. langchain4j `RetryUtils` defaults to 500 ms, ×1.5 backoff, 0.2 jitter (langchain4j-core 1.23.0, bytecode) and ignores `retryDelay`. HTTP 429 maps to `new RateLimitException(cause)`, where `cause = HttpException(int statusCode, String message)` (`ExceptionMapper.DefaultExceptionMapper`, bytecode). `buildGemini` passes `maxRetries` through (`modules/llm/impl/EmbeddingModelFactory.java:426–440`) | The 49/68 failure |
| Failure accounting | Any embedding exception → `recordDocumentFailure`: `failed++`, `inconclusive++` (`IngestionPipeline.java:1475–1490`) | A throttle reads as a failure |
| Cursors | In the SPI: `DiscoveryContext.cursor()`, `DiscoveryResult.nextCursor`, `SourceError.CursorInvalid`, `Coverage.INCREMENTAL`, `WorkItem` `DELETE` → `onDeleted` (`connector/SourceConnector.java:54–61`; `IngestionPipeline.java:458–462, 1309–1330`). But "Nothing persists cursors yet" (`IngestionPipeline.java:818–822`) | No storage |
| State schema | Postgres `rag_ingestion_documents`, primary key `(source_id, document_id)`: hash, ETag, last-modified, run id, `missed_runs`, `tombstoned`, fencing columns. `rag_ingestion_sources`: one row per source (`store_fingerprint`). `rag_ingestion_runs`: a unique partial index for one `RUNNING` run per source (`datastore/postgres/PostgresIngestionStateStore.java:54–103`). Mongo twin: `modules/ingestion/mongo/MongoIngestionStateStore.java:122–134` | Natural home of the work table and the cursor |
| Run liveness | The schedule fire **starts the run and returns** (`engine/runtime/internal/ScheduleFireExecutor.java:784`), so its lease does not cover the run. A run is stale only after `timeBudgetMinutes + 15 min` (`IngestionPipeline.java:104, 980–998`). One virtual thread per run (`RagSourceIngestionService.java:172`) | A crash blocks a source for its whole time budget |
| Scheduler | Cluster-wide CAS claim and lease, at-least-once (`engine/runtime/internal/SchedulePollerService.java:51–69`); `ragIngestion` fast path (`ScheduleFireExecutor.java:82–88, 298`). **One-shot schedules exist:** a null `nextFire` disables the schedule after its fire; `countPendingSchedulesByName` and `deleteUsedUpOneShotsByName` (`engine/schedule/IScheduleStore.java:145–164, 395`) | Reused for slice workers |
| Messaging | `eddi.messaging.type=in-memory` by default (`application.properties:152`); NATS is optional | Not a queue to rely on |
| Drive listing | Breadth-first `files.list` per folder, `pageSize=1000` (`drive/DriveApi.java:130–138`). `LIST_FIELDS` lacks `parents` (`:60–62`). `visited` and `seenFiles` grow with the drive (`GoogleDriveSourceConnector.java:162–163, 529`) | Full re-list; memory grows with the drive |
| Drive caps | `MAX_FOLDERS = 50`, `DEFAULT_MAX_FILES = 2_000`, `maxFiles ≤ 50_000`, `maxFileBytes ≤ 50 MB` (`configs/rag/model/IngestionSource.java:596–600, 685`). Every folder is re-verified each run (`GoogleDriveSourceConnector.java:146–152`) | Too small |
| Budget | `maxSegmentsPerRun` 20,000, report-only `costPerThousandSegments`, `timeBudgetMinutes` 10 (≤ 24 h) (`IngestionSource.java:819–843`; cost at `IngestionPipeline.java:1197–1200`). The old plan's §3.13 is not implemented | No dollars |
| Tenant spend | `TenantQuotaService.recordCost` has real callers (`TurnCostMeter.java:138`, `SystemSpendMeter.java:181`, `DreamService.java:1178`, `ConversationTitleService.java:249`). `SpendEvent.Kind` has no ingestion value (`engine/pricing/SpendEvent.java:52–64`) | Ingestion unmetered |
| Model change | Detected **only for in-memory stores**; `ingestionFingerprint` is `null` for external stores (`modules/llm/impl/EmbeddingStoreFactory.java:412–418, 429–431`) | pgvector silently mixes models |
| Dimensions | `buildGemini` defaults `outputDimensionality` to **3072** (`EmbeddingModelFactory.java:428`). The pgvector `dimension` defaults to **1536** (`EmbeddingStoreFactory.java:737`). HNSW is skipped above `MAX_HNSW_DIMENSIONS = 2000` (`modules/llm/impl/PgVectorSchema.java:102, 443–446`) | A default Gemini knowledge base on pgvector mismatches or goes unindexed |
| Embedding API | langchain4j 1.23.0 (`pom.xml:28`) has `EmbeddingModel.embed(EmbeddingRequest)` with `EmbeddingInput.from(Content...)`. `GoogleAiEmbeddingModel.doEmbed` maps only `TextContent` and base64 `ImageContent`, throwing `UnsupportedFeatureException` for anything else. `supportedContentTypes()` = `{TEXT, IMAGE}` for `embedding-2` names. `withTaskInstruction` prefixes only when **all** contents are `TextContent`, so images go unprefixed. Batches hold 100. `VertexAiEmbeddingModel` (1.23.0-beta33) is text-only. All from bytecode | Audio, video and PDF need an EDDI client |
| Chat models | The langchain4j Gemini chat mapper handles image, audio, video and PDF content (`PartsAndContentsMapper`, bytecode). `ChatModelRegistry.getOrCreate(type, params)` (`modules/llm/impl/ChatModelRegistry.java:311`) | Companion text is possible |
| Retrieval | `RagContextProvider` uses `EmbeddingStoreContentRetriever` with one `minScore` (`modules/llm/impl/RagContextProvider.java:168–180`). `formatRagContext` emits segment **text only** (`:273–300`). `/search` and `/evaluate` exist (`configs/rag/IRestRagIngestion.java:231–272`) | Citations must live in the text |
| Fuzzing | ClusterFuzzLite targets in `.clusterfuzzlite/` (e.g. `MatchingUtilities.java`, `SafeJsonExpression.java`) | Home for media-parser fuzzers |

---

## 3. Provider throttling and quotas

### 3.1 What happened, and the diagnosis

**What happened:**

- The quota hit was `EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier`, limit
  100. Google answered with `retryDelay: 26s`.
- langchain4j retried twice within about a second and threw `RateLimitException`.
- `recordDocumentFailure` failed the document, and the next document hit the same wall.

**A throttle belongs to the provider quota, not to the document.** Gemini quotas are scoped
per **project** and model, and cover requests per minute (RPM), tokens per minute (TPM) and
requests per day (RPD). Every call in that window fails: from this run, from other runs, and
from other knowledge bases on the same project.

### 3.2 T1-min: the fix that ships first (days)

No new tables and no queue.

1. **`ThrottledEmbeddingModel`** is a decorator in `modules/llm/impl`. `EmbeddingModelFactory.build`
   applies it for the **DOCUMENT** role only, outside `InputTypedEmbeddingModel`; the cache key
   already includes the role (`EmbeddingModelFactory.java:160`). The provider model is built
   with **`maxRetries(0)`**, and the decorator honours the configured `maxRetries` (default 5).
2. **`ProviderRetryHints.parse(Throwable)`** finds the delay, trying in order:
   - a `Retry-After` header, where an EDDI client sees one;
   - Google's `error.details[]` entry of `@type type.googleapis.com/google.rpc.RetryInfo`,
     field `retryDelay` (for example `"26s"`). It is read from `RateLimitException.getCause()`,
     an `HttpException` whose `getMessage()` should be the body; a test pins this (§15);
   - otherwise 30 s.

   It also reads `QuotaFailure.violations[].quotaId`:
   - `…PerDay…` means a daily exhaustion;
   - `…PerMinute…` means a per-minute exhaustion;
   - with no quota id, a delay over 5 minutes counts as daily.
3. **Wait within the run deadline.** A per-minute exhaustion whose delay fits before the run's
   deadline is slept out and retried, through an injectable `Sleeper` as in
   `DriveApi.java:76–82`. Anything else, including every daily exhaustion, throws
   `ProviderThrottledException(retryAfter, daily)`.
4. **The pipeline stops as THROTTLED.** `embed()` catches the exception before
   `recordDocumentFailure` and:
   - counts `throttled++`;
   - calls `recordUnreachable` for a known document, as `ownershipUnverified` does
     (`IngestionPipeline.java:1586–1600`);
   - sets `isCancelled()` so the run ends with the new `StopReason.THROTTLED` and coverage
     `PARTIAL`.

   **No document is counted as failed, and nothing is tombstoned**
   (`IngestionPipeline.java:463–470`). This extends what the Drive connector already does on a
   throttle that survives its retries (`GoogleDriveSourceConnector.java:200–206`).
5. **Resume.** The run record gets `resumeAfter`. The next run comes from either:
   - the source's next scheduled run; or
   - a **one-shot schedule** at `resumeAfter` (`IScheduleStore.java:145–164, 395`).

   Either one lists the source again. Today every Drive run is a full listing, so the deferred
   documents are found again as new or changed.
6. **Reporting:**
   - `IngestionRun`/`IngestionReport` gain `throttled` and `resumeAfter`, and `Outcome` gains
     `THROTTLED`. Both stores persist them; absent values read as zero or null.
   - Meters `eddi.ingestion.throttled` and `eddi.provider.throttle.wait` (tag `provider`).
   - The Manager shows "Paused by the provider's rate limit — resumes at 14:32", and the
     operator prompt explains it (§10).
7. **Query role.** Retrieval keeps langchain4j's short retry and **never waits on a throttle**.
   A throttled query fails that knowledge base's retrieval, which `RagContextProvider` already
   tolerates.

With T1-min, the 68-file run waits 26 s at the 100th call and completes. A daily quota stops
the run cleanly until the next day.

### 3.3 The shared throttle (phase Q)

- **`ThrottleKey` = `(provider, endpoint host, model, credential scope)`.** The scope is the
  project when it is known: Vertex `project`, or a configured `quotaProject`. Otherwise it is a
  truncated SHA-256 of the resolved key. A key never appears in logs, rows or metric tags.
- **A cooldown row,** `IProviderCooldownStore`: a Mongo collection plus Postgres
  `provider_cooldowns(throttle_key PK, until, daily, observed_at)`.
  - A 429 upserts `until = now + retryAfter`, keeping the later value.
  - Workers read the row before each call, cached for 5 s.
  - A daily exhaustion sets `until` to the next reset. Google's daily reset is at midnight
    Pacific time per its docs (unverified); the fallback is `retryAfter`.
- **Optional static ceilings** `requestsPerMinute` and `tokensPerMinute` in
  `embeddingParameters`. They feed a node-local token bucket and are off by default; a
  free-tier key sets `requestsPerMinute: 100`. Both go into `EmbeddingParameters.HONOURED`
  (`configs/rag/model/EmbeddingParameters.java:38–47`) and stay out of the embedding
  signature.
- **No AIMD and no node-count division.** The provider's `retryDelay` plus the shared cooldown
  is the feedback loop.
- `DriveApi` moves onto the same cooldown row, with a unit-weighted limiter (§5.5).

---

## 4. The ingestion work table

### 4.1 Why one table

A huge drive cannot be one run:

- it outlives deploys;
- a node can die mid-way;
- a run's lease ends when its fire returns (§2).

One durable per-document work table replaces run checkpoints, retry sets and folder `listed`
flags. The pipeline already reserved the slot: partitions "arrive with the work queue"
(`IngestionPipeline.java:806–807`).

### 4.2 Shape: extend the document-state store

Extend `rag_ingestion_documents` (Postgres) and its Mongo twin. They are already keyed
`(source_id, document_id)`, so one row carries both the document's ingested state and its
pending work:

| Column | Meaning |
|---|---|
| `work_action` | `UPSERT` \| `DELETE` \| null (nothing pending) |
| `work_state` | `PENDING` \| `CLAIMED` \| `SUBMITTED` (reserved, §8.4) \| `DONE` \| `POISON` |
| `work_version` | version token seen by discovery (Drive `md5Checksum` or `version`) |
| `work_hints` | small JSON: name, MIME type, parent id, size, media duration (≤ 2 KB) |
| `not_before` | earliest claim time; set by a throttle, budget stop, transient failure or daily quota |
| `attempts`, `last_error` | poison handling |
| `claimed_by`, `claim_until` | the claiming slice and its lease |
| `parent_id`, `in_scope` | Drive: the per-document scope decision (§5.3) |
| `tenant_id` | fairness; nullable until multi-tenancy |

**Indexes:**

- Postgres: a partial index `(not_before, source_id) WHERE work_state = 'PENDING'`, plus
  `(source_id, parent_id)`;
- Mongo: the equivalent compound and partial indexes.

**`rag_ingestion_sources` gains:**

- `cursor` (≤ 8 KB);
- `pending_backfill` (the frontier, §5.1);
- `scope_signature` and `resolved_scope`;
- `embedding_signature` (§7.6);
- `last_full_list_at`.

**No separate cursor store, and no NATS.** Messaging defaults to in-memory, and the work must
survive a cluster restart on either datastore.

### 4.3 Discovery only lists

Every kind of discovery (a full listing, a subtree listing, a change-feed page) writes rows
idempotently. It upserts `(source, document)` with `work_action`, `work_version`, `work_hints`,
`work_state=PENDING` and `not_before=now`, with two exceptions:

- a row already `DONE` at the same version and not tombstoned only has `last_run_id` touched;
- an equal pending action is left as it is.

Writes are batched per listing page, up to 1,000 rows in one statement or `bulkWrite`. **The
cursor advances once the page's rows are committed.** Discovery itself runs as a `DISCOVER`
step inside a slice (§4.4), so a crash mid-listing resumes from the stored frontier and cursor.

### 4.4 Slice workers under the schedule lease

**One schedule per source,** as today (`rag-ingestion:{kb}:{source}`). Each fire is now a
**slice**: 5–10 minutes of work (`settings.sliceMinutes`, default 8) done **inside the fire**.

- The fire returns only when its slice is done, so the poller's claim and lease cover the work
  (`SchedulePollerService.java:51–69`).
- `eddi.schedule.lease-timeout` must exceed the slice length plus grace. `init` already
  enforces that relation.
- **No heartbeat reaping is invented.** A dead node's lease expires and the schedule is
  reclaimed. Its rows return to `PENDING` once `claim_until` passes.

**A slice:**

1. runs a bounded `DISCOVER` step when one is due: the cron fired, or a frontier or cursor is
   pending;
2. claims up to N rows.
   - Postgres: `UPDATE … SET work_state='CLAIMED', claimed_by=?, claim_until=? WHERE
     (source_id, document_id) IN (SELECT … WHERE work_state='PENDING' AND not_before <= now()
     ORDER BY not_before LIMIT N FOR UPDATE SKIP LOCKED)`.
   - Mongo: a `findOneAndUpdate` loop.
3. processes each claimed row with today's `Collector` logic: fetch, extract or segment,
   embed, write, record;
4. marks the row `DONE` and clears the `work_*` columns.

**Continuation.** When due rows remain, the slice writes a **one-shot schedule**
`rag-ingestion:{kb}:{source}:cont` at the earliest `not_before`. It is de-duplicated with
`countPendingSchedulesByName` (`IScheduleStore.java:145–164`). A large backlog thus drains
continuously across the cluster without a custom scheduler.

**Parallel slices.** `settings.maxConcurrentSlices` (default 1, max 16) allows that many
continuation one-shots per source. Row claims never overlap. The single-`RUNNING`-run index
(`PostgresIngestionStateStore.java:100–103`) changes meaning: a **run** is now a logical sync,
one discovery generation plus its drain, and slices are recorded as its children.

**Fairness:**

- `eddi.rag.ingestion.max-workers` (default 4) caps concurrent slices across the cluster. The
  cap is a `rag_ingestion_slots` lease table, unless the poller's own cap is already
  cluster-wide (§15).
- Sources take turns, round-robin across sources and tenants. A source that just ran gets its
  continuation pushed back by `sliceMinutes × (active sources / max-workers)`. A
  million-file drive cannot starve a 20-file source.
- Within a source, rows are claimed by `not_before`.

**Poison files.** A row with `attempts ≥ settings.maxAttempts` (default 5) becomes `POISON`:
`failed++`, with `last_error` kept. It is re-armed only by a new version or a "retry failed"
call.

### 4.5 What becomes `not_before`

| Event | Effect |
|---|---|
| Per-minute throttle | `not_before = cooldown.until`; other sources on the key wait too, because the claim checks the cooldown |
| Daily quota | `until` = next reset |
| Budget stop (§8) | the next budget window; the slice ends |
| Transient fetch/embed error | exponential backoff, `attempts++` |
| Ownership lost | released unchanged |

A throttled or budget-stopped document is **never** failed and **never** causes a tombstone.

### 4.6 Progress, projection, disk

- **Progress** is counts by state per source: `PENDING`, `CLAIMED`, `DONE`, `POISON`, and
  "waiting" (`not_before > now`). Run records aggregate their slices.
- **Cost projection** (§8.3) sums `work_hints` over `PENDING` rows.
- **Streaming downloads with a disk quota:**
  - `Content.FileRef(Path, mimeType, size)` joins `FileBytes`.
  - `OutboundDriveTransport` streams into a per-slice temp directory, deleted in `finally`.
  - Before a download, the slice checks the file against `eddi.rag.ingestion.temp-disk-quota`
    (default 2 GB per node, summed over in-flight files) **and** against usable temp space
    minus a 1 GB floor.
  - A file that does not fit right now is released with a short `not_before`.
  - A file larger than the whole quota is skipped and counted (`skippedTooLarge`).

### 4.7 Multi-node fan-out still matters

The embedding quota is the bottleneck for embedding only. **Downloads, extraction, media cutting
and companion-text generation** are bound by CPU, I/O and model latency, and they scale with
nodes. Captioning a single video window takes seconds. So fan-out through
`maxConcurrentSlices` is part of phase Q, and the shared throttle (§3.3) keeps the provider
side honest whatever the node count.

---

## 5. Very large drives

### 5.1 Backfill as a frontier

The full listing is a breadth-first walk. Its frontier is stored in
`rag_ingestion_sources.pending_backfill`:

- the folder ids still to list;
- the current folder's `pageToken`.

Above 1,000 folders the frontier spills into the folder index (§5.3) as `listed=false`. That is
a property of the index, not a second queue. Each `DISCOVER` step lists a bounded number of
pages, enqueues their files (§4.3), updates the frontier, and yields.

### 5.2 Change feed concurrently with the backfill

1. **Before** the first listing, store `changes.getStartPageToken` (Shared Drive id,
   `supportsAllDrives=true`) as `cursor`.
2. **Every `DISCOVER` step drains `changes.list` from `cursor` first**, then continues the
   backfill frontier.
   - Parameters: `driveId`, `includeItemsFromAllDrives=true`, `supportsAllDrives=true`,
     `includeRemoved=true`, `pageSize=1000` (§16).
   - The cursor advances per page. `newStartPageToken` becomes the cursor at the end.

   Because the feed is drained **during** the backfill, the start token is never held for days.
   A change to a file that was already backfilled is not lost. The idempotent upsert makes a
   file seen by both the feed and the listing harmless.
3. **Coverage.**
   - Feed steps are `INCREMENTAL`, so they count no misses (`IngestionPipeline.java:458–462`).
   - Deletions become `DELETE` rows: `removed=true`, trashed, or now out of scope.
   - **Only a completed full listing** bumps miss counters and runs `reconcileDeletions`.
4. **Fresh frontier.** `fullRelistEveryDays` (default 30) starts a fresh frontier in the
   background. So does `SourceError.CursorInvalid`, together with a new start token.

### 5.3 Folder index and the scope decision

**The index.** `rag_ingestion_folders(source_id, folder_id, parent_id, name, in_scope, listed,
updated_at)`, with a Mongo twin, holds one row per folder: about 20 MB at 10⁵ folders.

- `LIST_FIELDS` gains `parents` (`drive/DriveApi.java:60–62`). The feed's `fields` include
  `file(parents, mimeType, trashed, …)`.
- **Each document row stores its `parent_id` and computed `in_scope`**, i.e. the ancestor
  decision, so a delete by scope is an indexed query.
- **Any folder change re-evaluates the subtree from the index.** A move, rename, trash, restore
  or new folder recomputes `in_scope` for that folder and its indexed descendants. Documents
  whose decision flips get `DELETE` rows (now out) or a subtree listing (now in). **Do not
  assume Drive emits entries for the descendants of a moved folder.**
- `visited` and `seenFiles` (`GoogleDriveSourceConnector.java:162–163, 529`) go away. Memory per
  slice is bounded by one page.

### 5.4 Storage rules for big sources: refuse, not warn

**Save-time refusals (400)** for a source with `maxFiles > 20,000` or a backfill:

- the `in-memory` store;
- a pgvector `vector` column above 2,000 dimensions (`PgVectorSchema.MAX_HNSW_DIMENSIONS`),
  unless `storeParameters.vectorType=halfvec` (HNSW up to 4,000);
- a mismatch between the effective output dimensionality (Gemini default 3072,
  `EmbeddingModelFactory.java:428`) and the pgvector `dimension` (default 1536,
  `EmbeddingStoreFactory.java:737`).

**Defaults:** new Gemini knowledge bases created by the Manager on pgvector default
`outputDimensionality` to **1536**, or 768 for very large sources. Gemini renormalises
truncated outputs (§16).

**`halfvec`** is an additive store parameter: column type plus `halfvec_cosine_ops` in
`PgVectorSchema.createIndexes`. Whether langchain4j's insert path accepts the column is open
(§15).

### 5.5 Drive quota in units, and egress

- **Units.** Drive's quota is **325,000 units/min/user**: a list costs **100**, a download or
  export **200** (reviewer-supplied, §16). `DriveApi` charges units to a limiter keyed by
  connection, under `eddi.rag.throttle.googleDrive.units-per-minute` (default 250,000). A 403
  `userRateLimitExceeded` or a 429 writes the shared cooldown row.
- **Egress.** Downloads are free up to 1 TB/day/project. `settings.maxBytesPerDay` (default
  200 GB) is checked before each download; past it, rows wait until the next day. A terabyte
  drive therefore backfills over days, and the preview says so.
- **Docs export limit:** keeps today's skip (`GoogleDriveSourceConnector.java:276–280`), now
  counted as `skippedExportLimit`.

### 5.6 Vector-store sizing (worked example)

Text only: 5,000,000 files × 10 chunks × 1,536 dims × 4 B ≈ **307 GB** of raw vectors, before
the index, text and metadata. HNSW roughly doubles it. At 768 dims, or with `halfvec` at 1,536,
it is ≈ 154 GB.

**Media under `BOTH` (D1) is two vectors per segment.** Examples:

- **Images.** 1M images = 1M segments = 2M vectors ≈ 12.3 GB at 1,536 dims.
- **Video.** 10,000 hours of video at 30 s windows with 5 s overlap = 1.44M segments = 2.88M
  vectors ≈ 17.7 GB, plus each window's transcript text, about 1 KB.

The preview (§8.3) reports both numbers.

**Guidance for `docs/rag.md`, "pgvector at scale":**

- choose dimensions before the first run;
- for backfills over 1M rows, set `storeParameters.deferIndex=true`. It skips HNSW creation
  until the frontier is empty, then builds the index once. Raise `maintenance_work_mem` to
  several GB for that build, which can take hours;
- upserts are batched (`EddiPgVectorEmbeddingStore.java:27–29`);
- watch the table size against the projection.

### 5.7 Caps raised, gated

These apply only to sources that pass §5.4:

- `maxFiles` up to 10,000,000 (the default stays 2,000);
- `MAX_FOLDERS` 500, and `excludeFolders` up to 500;
- `timeBudgetMinutes` becomes a per-slice limit.

### 5.8 Dedupe

Out of v1. Later, on evidence (§11).

---

## 6. Folder scoping for the Drive source

**Today:**

- `googleDrive.folderIds`, at most 50 (`IngestionSource.java:596, 615`), each checked against
  `sharedDriveId` every run (`GoogleDriveSourceConnector.java:146–152, 431`);
- `includeSubfolders` (`IngestionSource.java:618`);
- `excludeNamePatterns`, which matches **file** names only (`IngestionSource.java:626`).

### 6.1 Config

```json
"googleDrive": {
  "connection": "drive-sa",
  "sharedDriveId": "0AB…",
  "folders": [ { "path": "Sales/Offers" }, { "id": "1xYz…", "label": "Legal" } ],
  "excludeFolders": [ { "path": "Sales/Offers/Archive" }, { "id": "1aBc…" } ],
  "includeSubfolders": true,
  "excludeNamePatterns": ["~$*"]
}
```

- **Compatibility:** `folderIds` keeps working as `folders[{id}]`, for old configs and ZIPs.
- **Paths resolve to ids** from the drive root, one segment at a time, with
  `name = '<seg>' and mimeType = 'application/vnd.google-apps.folder' and '<parent>' in parents`.
  - Each segment is quote-escaped; `/` and control characters are refused.
  - Two same-named folders at one level are an error naming both ids, never a guess.
- **The resolved id is recorded** in `resolved_scope`. Later runs use the id, so a renamed
  folder keeps being ingested, and the run warns "'Sales/Offers' is now 'Sales/Quotes'". A run
  never writes the versioned knowledge-base config.
- **Exclusion wins over inclusion,** at any depth.

### 6.2 Folder picker endpoint

`GET /ragstore/rags/{id}/sources/{sourceId}/drive/folders?parentId=<id|root>&pageToken=…`
returns `{ folders: [{id, name}], nextPageToken }`.

- **Content:** folders only, one level at a time, `pageSize ≤ 200`. No files and no file
  contents.
- **Credentials:** it uses the saved source's connection, so the binding and allowlist checks
  apply. An unsaved source is saved first; an empty `folders` list allows that.
- **Authorisation:** **EDIT** on the knowledge base, through the same guard as `runSource`.
- **Validation:** `parentId` must match `GoogleDriveSource.DRIVE_ID`
  (`IngestionSource.java:594`), and its `driveId` must equal `sharedDriveId`.
- **Quota:** it goes through `DriveApi` and the unit limiter.

### 6.3 Scope changes

**Phase S-lite (on today's full listing).** Ships paths, `excludeFolders` and the picker.

- The listing records `parent_id` and resolves the ancestor chain as it walks.
- A changed `scope_signature` sets `missed_runs` to the threshold for documents outside the new
  roots. Their vectors therefore go at the end of the next completed listing, not after
  `tombstoneAfterMissedRuns` runs.

**Phase L (with the folder index):**

- **Added root:** a subtree frontier for that folder alone. The change feed continues.
- **Removed root or new exclusion:** a `DELETE` row for every document whose stored decision
  flips. The next slice deletes vectors first, then tombstones, as in `reconcileDeletions`
  (`IngestionPipeline.java:480–505`). This is a configuration change, not an outage, so
  `tombstoneAfterMissedRuns` does not apply.
- **`includeSubfolders` changed:** treated as remove plus add.

`requireFolder` then runs only for newly resolved roots, which is what lets `MAX_FOLDERS` rise
to 500.

---

## 7. Multimodal ingestion

### 7.1 Principle

A media segment is stored as a `TextSegment` whose **text** is the companion text under a
citation header. Under `BOTH` (D1 default) it is written as **two store entries** with the same
text, metadata and `mediaSegmentId`:

| Entry | `modality` metadata | Vector |
|---|---|---|
| Native | `image` \| `pdfPage` \| `audio` \| `video` | the native media embedding |
| Text | `transcript` (audio, video) \| `caption` (image) \| `ocr` (PDF page) | the companion text, embedded as text |

`EmbeddingStore<TextSegment>` stays the only store type, so every store, filter, deletion path
(by `documentId`/`sourceKey`) and the chunk viewer keep working. No raw media is stored.

### 7.2 Config

**Knowledge base** (one model serves the whole index):

```json
"multimodal": {
  "enabled": true,
  "modalities": ["image", "pdfPage", "audio", "video"],
  "vectors": "BOTH",
  "mediaMinScore": null,
  "companionText": {
    "provider": "gemini",
    "parameters": { "model": "<a chat model with image/audio/video input>", "apiKey": "${vault:tenant/gemini}" },
    "instructions": "Describe this for search. Transcribe speech verbatim.",
    "maxOutputTokens": 512
  }
}
```

**`vectors`** is either one value or a per-modality map, e.g. `{"default": "BOTH", "image":
"MEDIA"}`.

- `BOTH` is the default for every modality.
- `MEDIA` (native only) and `CAPTION` (text only) are opt-in.
- A modality the model cannot embed natively is `CAPTION`, whatever is configured.

**`companionText`** is required for `BOTH` and `CAPTION`. A media-enabled knowledge base
without it is refused at save, unless every enabled modality is explicitly `MEDIA`. In that
case the stored text is the citation header alone.

**Source** (opt-in, off by default):

```json
"settings": { "media": {
  "enabled": true,
  "modalities": ["image", "video"],
  "maxMediaBytes": 524288000,
  "maxDurationSeconds": 3600,
  "maxMediaSecondsPerDay": 36000,
  "windowSeconds": { "audio": 60, "video": 30 },
  "overlapSeconds": { "audio": 10, "video": 5 },
  "acknowledgeProviderProcessing": true
} }
```

**Validation (400):**

- the source's modalities must be a subset of the knowledge base's;
- windows must fit the provider limits table (§16);
- overlap must be under half the window;
- `acknowledgeProviderProcessing` must be true (§9);
- a modality the model does not support natively, with no companion model, is refused with a
  message naming both fixes.

**Provider limits are data** (`ProviderMediaLimits`). The defaults are the intersection of the
Gemini API and Vertex:

- images: PNG and JPEG;
- audio: MP3 and WAV, ≤ 180 s per window;
- video: MP4, windows ≤ 80 s (Vertex's limit with audio; the Gemini API allows 120 s).

### 7.3 Segmentation, and hardened parsers

New package `modules/ingestion/media/`. A `MediaSegmenter` turns a file into
`MediaSegment(source, mimeType, Locator)`, where `Locator` is `Whole`, `TimeRange(startMs,
endMs)` or `Page(n)`.

**Images.** One segment per image.

- The pixel count is checked from `ImageReader` metadata **before** decoding; the cap is 40 MP.
- Images are downscaled to ≤ 3072 px on the long edge as JPEG when the provider needs it.
- GIF, BMP, WebP and TIFF are converted to PNG when ImageIO reads them, and skipped otherwise.

**PDF pages.** PDFBox (`pom.xml:400`) splits the file into single-page PDFs, as Google
recommends (§16).

- The page's text layer is the companion text. A page with no text layer gets OCR from the
  companion model.
- This replaces "no text layer, EDDI has no OCR" (`extract/DocumentExtractors.java:162–168`) on
  media-enabled sources. Text-only PDF ingestion is unchanged when media is off.

**Audio and video: provider-side offsets preferred.** The `Part` schema lists
`videoMetadata {startOffset, endOffset}` and `fileData {fileUri}` (§16). Whether
`embedContent` honours them is **unverified**.

- **Phase M3 opens with a one-day spike:** upload the file once through the Files API, then
  embed each window by offset.
- If that works, EDDI needs **no cutting and no media parser for untrusted files**, beyond
  reading the duration.

**Fallback: in-JVM cutting with hardened parsers**, only if the spike fails.

- **WAV:** parse the RIFF header and slice PCM on frame boundaries.
- **MP3:** walk the MPEG frame headers; frames are self-contained, so slices are
  frame-aligned.
- **MP4:** `org.mp4parser:isoparser` (Apache-2.0, pure Java) cuts on sync samples. The locator
  records the actual cut times.
- **Hardening, for all three:**
  - memory is bounded to a fixed window buffer, streamed from the `FileRef`, never
    `readAllBytes`;
  - caps on box count, nesting depth, frame count and duration;
  - a wall-clock budget per file;
  - each parser is a pure function over `InputStream`;
  - each gets a fuzz target in `.clusterfuzzlite/` (`Mp3FrameWalkerFuzzer`, `WavHeaderFuzzer`,
    `Mp4CutFuzzer`), plus malformed-file and bomb tests (§12).

**Duration before download.** For video, it comes from Drive's
`videoMediaMetadata.durationMillis` (added to `LIST_FIELDS`) and is checked against
`maxDurationSeconds`. For audio, it comes from the header after download.

### 7.4 Embedding calls

The interface:

```java
interface MultimodalEmbeddingModel {
    Set<Modality> supportedModalities();
    List<Embedding> embedMedia(List<MediaInput> inputs, Deadline deadline);
}
```

It is obtained from `EmbeddingModelFactory.getOrCreateMultimodal(RagConfiguration)`.

**Images (M1): langchain4j.** Each image is embedded with `embed(EmbeddingRequest)` and
`EmbeddingInput.from(ImageContent.from(base64, mime))`, one input per image so each gets its
own vector. This is supported for `embedding-2` and sent without a task prefix (§2). It goes
through `ThrottledEmbeddingModel`.

**PDF pages, audio, video (M2–M4): `GeminiMultimodalEmbedder`**, which posts
`models/{model}:batchEmbedContents` through **`OutboundHttp`**:

- profile `DEFAULT`, `SsrfPolicy.STRICT`, the slice's deadline, and a `BodyMode` cap on the
  response;
- one `content` per segment, `parts: [{inline_data: {mime_type, data}}]` (or `file_data` plus
  `video_metadata` if the spike succeeds), plus `output_dimensionality`;
- batches bounded by count (≤ 100) and size (≤ 15 MB base64; the inline limit is unverified);
- the key in the `x-goog-api-key` header;
- the same `ThrottleKey` as the text path.

It is built in M2, which needs it for PDF pages.

**Text vectors** for companion text use the knowledge base's normal langchain4j text path
(`embedAll`, DOCUMENT role, throttled). Query vectors (QUERY role, `RagContextProvider.java:168`)
and document text vectors therefore share instruction handling. Text embedding is never
reimplemented.

**Guard parity.** Extract `EmbeddingModelFactory.build`'s checks (`:164–200`) into one
`resolve(RagConfiguration)`, used by both paths:

- global-variable resolution;
- `ConnectionParameterGuard.rejectConnectionReferences`;
- `requireSecretsAllowedAt`;
- `EmbeddingParameters.findProblem`;
- `SecretResolver.requireResolved`.

### 7.5 Companion text and citations

`CompanionTextGenerator` sends the segment (`ImageContent`, `PdfFileContent`, `AudioContent`,
`VideoContent`) plus `instructions` to the configured chat model through
`ChatModelRegistry.getOrCreate`.

- The media is data, never template input.
- The output is capped at `maxOutputTokens` and 4,000 characters.
- The call has no tools.

The stored text is a deterministic header followed by the generated text:

```
[Video: Onboarding 2026.mp4, 12:30–13:00] https://drive.google.com/file/d/…/view
<transcript / caption / OCR>
```

The header is in the **text** because `formatRagContext` emits text only
(`RagContextProvider.java:286`). Every retrieval path then cites without code changes.

New metadata keys (`IngestionPipeline.METADATA_*`) join the existing ones (`:1638–1650`):

- `modality`, `mimeType`;
- `mediaStartMs`, `mediaEndMs`, `page`;
- `mediaSegmentId` (`documentId#locator`);
- `companionModel`.

**Failure handling.**

- A throttled companion call sets the row's `not_before` (§4.5).
- A permanent companion failure under `BOTH` stores the native entry with header-only text,
  skips the text entry, counts `companionFailed`, and leaves the row `DONE`. Under `CAPTION` it
  counts as an attempt.

### 7.6 Retrieval, scores and the one-model rule

**Text queries hit native media vectors directly.** Google describes one unified space (§16),
and the query vector comes from the same model.

**Thresholds:**

- text, caption, transcript and OCR entries use `minScore`, which defaults to 0.6
  (`RagConfiguration.java:185`);
- native media entries use `mediaMinScore` (null = `minScore`). It exists because cross-modal
  similarity is not documented to share the text-to-text range.

**Dedupe**, implemented once in a shared `RetrievalMerger` and used by `RagContextProvider`, the
`/search` handler and `/evaluate`:

1. Search with threshold `min(minScore, mediaMinScore)` and `maxResults × 2`, the factor making
   room for pairs.
2. Drop each hit below its own entry's threshold.
3. Group the hits by `mediaSegmentId`; a hit without one is its own group. **Keep one hit per
   group, the one with the higher score.** Ties go to the text entry. The surviving hit's text
   is the segment text, identical on both entries, so the agent sees the segment **once**, with
   one citation.
4. Sort by score and cut to `maxResults`.

With multimodal off no hit has a `mediaSegmentId`, so this is a no-op; a golden test pins that.

**Calibration.** Operators tune `mediaMinScore` with `POST /ragstore/rags/{id}/evaluate`
(`IRestRagIngestion.java:258`). Under `BOTH`, a weak native score costs little, because the
text entry carries the match. Under `MEDIA`, the Manager warns while `mediaMinScore` is unset.

**One model per knowledge base.** Text and media vectors must share
`(provider, model, endpoint, dimensions)`, which is `EmbeddingStoreFactory.embeddingSignature`
(`:561–597`).

- **Phase B** stores `embedding_signature` per source.
- A mismatch with the **same dimension** re-enqueues every `DONE` row as `UPSERT`, i.e. a full
  re-embed, which is what in-memory stores do already (`IngestionPipeline.java:432`).
- A mismatch with a **different dimension** on pgvector is refused: "create a new table or
  knowledge base".

**Change key.** Media rows use `versionToken + "|" + mediaSignature`. The signature hashes the
windows, overlap, modalities, `vectors`, and the companion provider, model and instructions.
Text keeps today's key (`IngestionPipeline.java:1415–1424`).

### 7.7 Storage

- **All stores can hold it:** `in-memory`, `pgvector`, `mongodb-atlas`, `elasticsearch`,
  `qdrant` and `chroma` (`EmbeddingStoreFactory.java:691–695`). Only text, floats and string
  or long metadata are written.
- **Replacement** (`removeAll(Filter)`) is verified only for in-memory, pgvector and chroma
  (`docs/rag.md`).
- **No raw media is stored:** not in vectors, rows, `work_hints`, logs or metrics. Temp files
  are deleted in `finally`. The upload source keeps its stored file, as it does today
  (`IngestionSource.java:49–60`).
- **Budgets and sizing count two vectors per segment under `BOTH`.** They count against
  `maxSegmentsPerRun` and feed the store-growth projection (§5.6, §8.3).

### 7.8 Connectors

**Drive.**

- `DriveFormat` gains `IMAGE`, `AUDIO` and `VIDEO`, and PDF in media mode.
- These formats are enqueued only when the source's modalities include them. Otherwise they
  stay "unsupported" skips, which leave stored vectors untouched (`reportSkipped` only counts,
  `IngestionPipeline.java:947–953`).
- Switching a modality off is a scope change, so `DELETE` rows are written for its documents.

**Upload.**

- `resolveMimeType` accepts `MimeValidator`'s media types when the source allows them.
- Otherwise the upload is refused with "enable media on this source". It is not accepted and
  then failed through `onUnreadable`, which retires chunks (`:1437`).
- The 50 MB ceiling stays.

**Web.** Unchanged.

**Pipeline.** `onMedia(document, file)` runs these steps:

1. segment;
2. price the work (§8);
3. generate the companion text;
4. embed the native vector and the text vector;
5. run the tail shared with `embed()` (`:1665–1700`), with both entries of every segment
   written in one `addAll`: the ownership check, `addAll`, and `removeAll(runId ≠ current)`.

### 7.9 Other providers

**Vertex AI as an embedding endpoint** for `gemini-embedding-2`. This is a second
`MultimodalEmbeddingModel`, using GCP credentials.

- It comes late, behind a spike, because Vertex's request shape for this model was not stated
  (§16).
- langchain4j's `VertexAiEmbeddingModel` is text-only.
- Vertex's limits differ from the Gemini API's: 80 s of video with audio, and more image types.

**A second multimodal provider** (Voyage, Cohere embed-v4, Titan multimodal, a local CLIP).

- It needs one implementation plus a `ProviderMediaLimits` row; the pipeline is unchanged.
- An image-only model declares `{IMAGE}`, so its other modalities go through the text path.

---

## 8. Cost, budgets and preview

### 8.1 Prices

**Unit prices.** `ModelPriceEntry` (`engine/pricing/model/ModelPriceEntry.java:43`) gains
optional `unitPrices`, in USD per unit, e.g. `{"image": 0.00012, "audioSecond": 0.00016,
"videoFrame": 0.00079}`. These are Gemini Embedding 2's standard prices (§16).

- A PDF page is priced as 258 tokens at the image rate. That mapping is unverified.
- `ModelPriceCatalogValidator` checks every value is finite and ≥ 0.

**Text.** Text vectors use `inputPricePer1M` with a characters ÷ 4 estimate, because
langchain4j returns no usage for Gemini embeddings.

**Companion text.** Priced from the chat model's `TokenUsage` via `ModelPriceResolver.price`
(`modules/llm/pricing/ModelPriceResolver.java:49`).

**Cost per media segment under `BOTH`** = native unit cost + companion generation cost +
companion text-embedding cost. Media unit counts are known **before** the call: images,
seconds, and frames = min(32, seconds) per video window. Native costs are therefore exact.

**Worked example: one hour of video** in 30 s windows with 5 s overlap = 144 windows.

- **Native:** 144 × 30 frames × $0.00079 ≈ **$3.41**.
- **Transcript embedding:** about 144 × 300 tokens at $0.20/1M, under **$0.01**.
- **Companion generation:** model-priced, and typically the largest item.

The preview shows all three.

### 8.2 Budgets

`IngestionSettings` gains:

- `maxCostPerDayUsd`: a rolling 24 h, summed from slice records;
- `maxCostPerSliceUsd`;
- `maxBytesPerDay` (§5.5);
- `acknowledgeUnboundedCost`.

**Enforcement:**

- every embedding batch and every companion call is priced **before** it is sent;
- crossing a ceiling ends the slice and sets `not_before` on the rows left (§4.5);
- ingestion is recorded as **`SpendEvent.Kind.INGESTION`** (`detail = "rag:{kbId}:{sourceId}"`);
- `TenantQuotaService.recordCost` runs after each batch and `checkCostBudget` before the next,
  as `DreamService` does (`:1178`).

**Fallbacks and rules:**

- `costPerThousandSegments` stays the fallback when nothing is priced.
- A scheduled source with media requires a finite `maxCostPerDayUsd` or the acknowledgement.

### 8.3 Preview projects the cost

Preview lists without fetching (`GoogleDriveSourceConnector.java:259–266`). In phase Q it
becomes a dry `DISCOVER` that tallies in memory, bounded by `maxFiles`. Per modality it reports:

- counts, bytes and seconds (from Drive media metadata);
- segments, and **vectors (two per media segment under `BOTH`)**;
- dollars, split into native embedding, companion generation and text embedding;
- store growth: vectors × dimensions × 4 B;
- the duration implied by RPM, TPM, Drive units, `maxBytesPerDay` and `maxCostPerDayUsd`,
  e.g. "≈ 9 days at 200 GB/day".

A first sync projected above `eddi.rag.ingestion.confirm-above-usd` (default 10) requires
`?confirmCost=true`.

### 8.4 Gemini Batch API: later and optional

**Trade-off:** half price, but up to 24 h turnaround and 48 h expiry, small enqueued-token caps
(reviewer figures), and undocumented multimodal input.

**Shape, if built:** `settings.backfillMode: BATCH`, text first. Rows move
`CLAIMED → SUBMITTED` with the batch id, and a slice polls the batch and writes the results.
Built only on measured need.

---

## 9. Privacy, security and untrusted content

**Media leaves the deployment.**

- It goes to the embedding provider and the companion model. `acknowledgeProviderProcessing:
  true` is required, audited, and shown on the source.
- The Gemini **free tier trains on submitted content** (§16). That is stated beside the setup
  steps. `eddi.rag.multimodal.require-paid-tier-ack=true` adds a second acknowledgement.

**Transcripts of people are personal data.** `docs/gdpr-compliance.md:487` treats
knowledge-base content as editor-published, removed through source management; that stands.

- Media is off by default.
- `forgetDocument`, a file removed (seen through the change feed) and a source purge each remove
  both entries of every segment.
- The default instructions describe content; they do not name people.
- Erasure **by data subject** is open (§15), with a proposal.

**Untrusted content.** On-screen text and speech become companion text, attacker-writable like
a web page (old plan §3.6).

- Companion text gets the same retrieved-context framing as any other ingested text.
- It is never a template.
- The caption call has no tools.

**Outbound calls.**

- `OutboundHttp` with `SsrfPolicy.STRICT`, plus the vault host-allowlist check.
- Drive calls go through `DriveApi`.
- Credentials never appear in logs, rows or metric tags.

**Parsers.** Avoided where the provider can cut. Otherwise hardened and fuzzed (§7.3, §12).
Image pixel caps are checked before decoding.

---

## 10. REST, Manager, operator, docs

**REST** (`IRestRagIngestion`):

- the folder picker (§6.2);
- run and slice responses gain `throttled`, `resumeAfter`, counts by work state, and
  `costProjection`;
- `runSource` gains `?confirmCost=true`;
- new `POST …/sources/{sourceId}/retry-failed`, which re-arms `POISON` rows;
- `/search` and `/evaluate` return deduplicated hits with `modality` and the locator metadata.

**Manager** (`ui/manager/src/components/editors/ingestion-sources-panel.tsx`,
`ui/manager/src/lib/api/ingestion-sources.ts`):

- folder picker: a lazy tree with include and exclude;
- a media section per source, with the acknowledgement;
- a multimodal section on the knowledge base: `vectors` (BOTH, MEDIA, CAPTION),
  `mediaMinScore`, the companion model, and a dimension picker defaulting to 1536 on pgvector;
- progress by work state, and "paused until …";
- the cost projection.

Every string goes through `ui/manager/src/i18n/locales` and the three i18n gates.

**Platform Operator.** `ui/manager/src/lib/operator/system-prompt.ts` (the knowledge-base
section near line 667) learns:

- throttled versus failed;
- slices and the work queue;
- media opt-in and the `BOTH` default;
- dimension and store rules;
- folder scoping.

The picker joins `tool-scopes.ts` read-only, if at all. Bump `operator-revision.json`. Operator
PRs land serially.

**Docs:**

- `docs/rag.md`:
  - Drive: change feed, backfill, scoping, picker, units, egress;
  - a "Media" section with the §16 table;
  - the providers row: `requestsPerMinute`, `tokensPerMinute`, multimodal;
  - "pgvector at scale" (§5.6);
  - troubleshooting;
- `docs/gdpr-compliance.md`;
- a changelog fragment per PR;
- this plan's status line.

---

## 11. Phasing

Each phase ships alone and keeps `main` green on Mongo and Postgres.

| # | Phase | Content | Effort |
|---|---|---|---|
| 1 | **T1-min** | §3.2. Fixes the 49/68 run. **Throttling:** `ThrottledEmbeddingModel`, `maxRetries(0)`, parsing of `RetryInfo`/`Retry-After`/quota ids, waiting within the deadline. **Otherwise** the run stops as `THROTTLED`: coverage PARTIAL, no failures, no tombstones. **Resume** via a one-shot schedule. **Reporting:** counters, Manager text, an operator note. | S (days) |
| 2 | **S-lite** | §6.1–6.3 on the current full listing: `folders` by path or id, `excludeFolders`, picker endpoint and dialog, `parent_id` recorded, removal of out-of-scope documents at the next completed listing via `missed_runs` | M |
| 3 | **Q** | §4. **Work table:** work columns on both state stores, discovery that only lists. **Workers:** slice fires under the lease, continuation one-shots, `maxConcurrentSlices`, the global worker cap, round-robin, the attempt cap. **Downloads:** `FileRef` streaming with a disk quota. **Throttles:** the shared cooldown row (§3.3), the Drive unit limiter (§5.5). | L |
| 4 | **L** | §5: cursor and frontier columns, change feed concurrent with backfill, folder index, subtree re-evaluation, scope-change `DELETE` rows, `fullRelistEveryDays`, caps raised behind §5.4's refusals | L |
| 5 | **B** | §8: `unitPrices`, daily and per-slice budgets, `maxBytesPerDay`, `SpendEvent.Kind.INGESTION` and tenant metering, preview projection with two vectors per media segment, `confirmCost`; `embedding_signature` check for external stores; `halfvec` and deferred indexing; pgvector sizing docs | M |
| 6 | **M1** | Images: native + caption vectors (`BOTH`), langchain4j path, `RetrievalMerger` dedupe and `mediaMinScore`, Drive and upload image formats | M |
| 7 | **M2** | PDF pages: native + OCR or text-layer vectors; `GeminiMultimodalEmbedder` over `OutboundHttp` | M |
| 8 | **M3** | Audio: native + transcript vectors; offsets spike, then provider offsets or hardened WAV/MP3 cutting with fuzzers | M |
| 9 | **M4** | Video: native + transcript vectors; provider offsets or hardened MP4 cutting; Drive duration pre-check | M–L |
| 10 | **M-fallback** | Text path for models without native support (caption, transcribe or OCR → text vector only) | S–M |
| 11 | **Later, on evidence** | Batch API mode (§8.4), Vertex endpoint (§7.9), dedupe (§5.8), cross-document embedding batching | — |

T1-min is urgent and independent. S-lite is D3. Q and L make D2 real. B precedes media because
media, with two vectors and a companion call per segment, is where the money goes.

---

## 12. Testing strategy

Fakes at each HTTP boundary, no network, and every fix mutation-checked: revert the fix and see
the test fail.

**T1-min.**

- **Parsing:** `RetryInfo "26s"`, `Retry-After` as seconds and as a date, `QuotaFailure`
  quota ids for `…PerMinute…` and `…PerDay…`, and a garbage body.
- **Deadline:** a delay longer than the run deadline is refused, not slept.
- **`IngestionPipelineThrottleTest`:** a stub that answers 429 after 100 calls per simulated
  minute.
  - With a normal deadline: 0 failed, everything ingested.
  - With a short deadline: the run ends `THROTTLED` with coverage `PARTIAL`, 0 failed and 0
    tombstoned, and the next full run ingests the rest.

**Work-table contract** (`IngestionWorkQueueContract`, Mongo and Postgres Testcontainers,
mirroring `IngestionStateStoreContract`):

- enqueue is idempotent, and an unchanged row is not re-enqueued;
- concurrent claimers never share a row;
- an expired `claim_until` can be reclaimed;
- `not_before` is honoured;
- `POISON` after the attempt cap;
- counts by state;
- round-robin across two sources.

**Slice IT.**

- **Setup:** two worker threads with separate scheduler instances on one database, and 5,000
  fake files.
- **Kill:** one worker is killed mid-slice.
- **Assertion:** after its lease expires, every document is `DONE` exactly once in the store,
  counted by `documentId`.

**Fake Drive.**

- `getStartPageToken`.
- `changes.list`: adds, edits, removals, trash, folder moves across the scope boundary
  **without** descendant entries, and an invalid token.
- `parents` and `videoMediaMetadata`.
- The picker.
- Unit accounting.
- A change made during a backfill is applied exactly once.

**Scope.**

- Adding a folder lists only that folder.
- Removing a folder deletes its documents at the next slice.
- A renamed folder keeps being ingested, with a warning.
- An ambiguous path is an error naming both ids.
- Exclusion wins over inclusion.

**`MultimodalIngestionIT`.**

- **Stubs:** a stubbed `batchEmbedContents` (429 with the real error JSON, then fixed vectors),
  reached through `baseUrl` (`EmbeddingParameters.java:46`), and a stubbed companion chat model.
- **Store and input:** a pgvector Testcontainer. Upload a PNG, a 3-minute WAV and a scanned PDF.
- **Assertions:**
  - under `BOTH`, exactly two entries per segment with an equal `mediaSegmentId`, text and
    citation metadata;
  - under `MEDIA` and `CAPTION`, exactly one;
  - the request bodies: one content per segment, the MIME types, no task prefix on media;
  - no media bytes in the store.
- **Datastores:** both.

**Retrieval (`RetrievalMergerTest`).**

- **One segment, both entries matched:** one hit, with the higher score.
- **Ties:** the text entry wins.
- **Thresholds:** each entry is filtered by its own (`minScore` vs `mediaMinScore`).
- **Cut-off:** `maxResults` is applied after the dedupe.
- **Golden test:** with multimodal off, results equal today's.

**Parser safety and fuzzing.** Each of these fails bounded, and each parser gets a
ClusterFuzzLite target:

- a truncated WAV;
- corrupt MP3 frames;
- an MP4 with 10⁶ boxes or deep nesting;
- a 50k × 50k PNG header;
- a zero-length video.

**Live smoke (manual).**

- A sandbox Shared Drive with JPEGs, MP4s and a scanned PDF, on a **paid** key.
- A free-tier run, to watch T1-min wait instead of failing.

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| The `HttpException` message is not the full body, so no `retryDelay` | 30 s fallback plus the shared cooldown; the EDDI client sees headers (§3.2) |
| A batch counts as N requests against RPM | Measure in the T1 smoke run; RPM and TPM ceilings |
| `BOTH` doubles the vector count and adds a companion call per segment | Explicit in sizing (§5.6) and cost (§8.1); `MEDIA` or `CAPTION` per modality where that is too costly |
| Cross-modal scores sit far below text scores | `mediaMinScore`; under `BOTH` the text entry carries the match; `/evaluate` calibration |
| `embedContent` ignores `videoMetadata` offsets | The spike decides; hardened parsers as fallback (§7.3) |
| Slices inside a fire need longer leases | `init` already enforces lease > fire timeout + grace; `sliceMinutes` is config |
| Drive emits no descendant changes on a folder move | Subtree re-evaluation (§5.3); `fullRelistEveryDays` |
| A silent model change on external stores (exists today) | The `embedding_signature` check in B (§7.6) |
| The 3072-dimension default on pgvector | Refused for large sources; the Manager defaults to 1536 (§5.4) |
| The provider limits change (docs dated April 2026) | One limits table with `asOf`, mirrored in the docs |

---

## 14. What I would NOT do

- **Not integrate Vertex AI RAG Engine.** It is a GCP-only managed store, which contradicts
  EDDI being provider-agnostic and running on both datastores. Vertex as an embedding endpoint
  is kept (§7.9).
- **Not keep raw media** in EDDI. The source keeps it.
- **Not add ffmpeg or Apache Tika.** ffmpeg is a native binary in a digest-pinned image with
  its own CVE stream, and `MimeValidator` already sniffs every type used here.
- **Not use NATS as the work queue.** It is optional, and messaging defaults to in-memory.
- **Not invent heartbeat reaping or a custom scheduler.** Slices run inside schedule fires
  under the existing lease.
- **Not build AIMD or a distributed token bucket.** `retryDelay` plus one cooldown row is the
  feedback.
- **Not treat a throttle as a failure,** and not tombstone on a throttled, budget-stopped or
  incomplete sync.
- **Not let a run write the knowledge-base config.** Resolved folder ids live in state.
- **Not reimplement text embedding.** Query vectors would drift from document vectors.
- **Not aggregate media and caption into one multi-part vector.** That would make it
  impossible to tell which half matched. `BOTH` stores them as separate entries.
- **Not default media on,** or allow it without the acknowledgement.
- **Not ship dedupe in v1.**

---

## 15. Open questions

1. Does a `batchEmbedContents` call with N contents count as 1 or as N against RPM? How do TPM
   and RPD count media? Measure in T1.
2. Is the `HttpException` message the full error body, `RetryInfo` and `QuotaFailure`
   included? (The status code is available, per the bytecode.)
3. ~~Does the scheduler have a one-shot "run at"?~~ **Answered: yes**
   (`IScheduleStore.java:145–164, 395`).
4. Does `embedContent` honour `videoMetadata.startOffset/endOffset` and `fileData.fileUri` for
   Gemini Embedding 2? The answer decides whether EDDI parses media at all (§7.3).
5. What are the inline request byte limit and the per-image and per-PDF-page token cost?
6. Is the poller's concurrency cap per instance or cluster-wide? This decides whether
   `max-workers` needs a slot table (§4.4).
7. Does the langchain4j pgvector store write a `halfvec` column unchanged (§5.4)?
8. **Erasure by data subject for transcripts.**
   - **Proposal:** an opt-in `companionText.namedSpeakers`, off by default, is the only way
     names enter transcripts.
   - When it is on, the generator emits `speakers` metadata. An admin endpoint,
     `DELETE /ragstore/rags/{id}/subjects?name=…`, removes every entry naming the subject (both
     entries of each segment, by `mediaSegmentId`) and re-processes those windows with the
     speaker redacted.
   - Stores without list-metadata filters fall back to a scan.
   - **Decision needed:** may names appear at all?
9. Should images inside DOCX or PPTX files, and on crawled pages, become media segments?
10. Should long media get one companion call per file, with timestamps, instead of one per
    window?

---

## 16. Appendix: verified provider facts

Fetched 2026-11-19. `ai.google.dev` now 301-redirects to `aistudio.google.com/docs/…`, which is
what was read.

| Fact | Gemini API (aistudio.google.com/docs/embeddings, …/models/gemini-embedding-2) | Vertex AI (docs.cloud.google.com/…/embedding-2) |
|---|---|---|
| Model, status | `gemini-embedding-2`, "Stable", updated April 2026 | `gemini-embedding-2`; stage, regions and date not stated |
| Input limit | 8,192 tokens; excess silently truncated | 8,192 tokens |
| Output dims | 128–3072, default 3072; 768/1536/3072 recommended; truncated outputs auto-renormalised | up to 3,072 (MRL), `output_dimensionality` |
| Images | ≤ 6 per request; PNG, JPEG | ≤ 6; png, jpeg, webp, bmp, heic, heif, avif |
| Audio | ≤ 180 s; MP3, WAV | ≤ 180 s, 1 file; `audio/mp3`, `audio/wav` |
| Video | ≤ 120 s; MP4, MOV; H264/H265/AV1/VP9; ≤ 32 frames (1 fps up to 32 s, uniform beyond); **audio track not processed**; split longer videos into overlapping chunks | 80 s with audio, 120 s without; 1 video; `video/mpeg`, `video/mp4` |
| PDF | 1 file, ≤ 6 pages, 1 page recommended; 258 tokens per page | 1 file, ≤ 6 pages; no OCR by default |
| Request | `embedContent`, `batchEmbedContents`; `content.parts[]` with `inline_data {mime_type, data}`; parts in one content → one aggregated embedding, separate contents → separate embeddings; media inline or via the Files API | Not stated |
| Task type | `task_type` not accepted; text prefixes for text; avoid prefixes on multimodal input | — |
| Space | One unified space for text, image, video, audio and documents; incompatible with `gemini-embedding-001` | — |
| API reference (aistudio.google.com/api-reference/embeddings) | `Part` lists `inlineData`, `fileData {fileUri}`, `videoMetadata {startOffset, endOffset, fps}`; which of these `embedContent` honours is **not stated**; `asyncBatchEmbedContent` exists; batch and byte limits not stated | — |
| Price, standard | text $0.20/1M; image $0.45/1M ($0.00012/image); audio $6.50/1M ($0.00016/s); video $12.00/1M ($0.00079/frame); Batch API half price; free tier free and **used to improve Google's products**, paid tier not | Not shown |

**Drive `changes.list`** (developers.google.com/workspace/drive/api/reference/rest/v3/changes/list,
fetched 2026-11-19):

- parameters: `pageToken`, `driveId`, `includeItemsFromAllDrives`, `supportsAllDrives`,
  `includeRemoved`, `pageSize` (default 100, max 1000);
- the response carries `nextPageToken`, and `newStartPageToken` only once the list has been
  read to the end.

**From the reviewer, not re-fetched here:**

- Gemini quotas are per project, covering RPM, TPM and RPD.
- The Drive quota is 325,000 units/min/user; a list costs 100 units, a download or export 200.
- Drive egress is free up to 1 TB/day/project.
- Gemini Batch API: 24 h turnaround, 48 h expiry, enqueued-token caps.
- pgvector `halfvec` HNSW reaches 4,000 dimensions; `vector` HNSW stops at 2,000.

**Not verified:**

- `getStartPageToken`'s Shared Drive parameters, and the Change object's fields;
- whether Drive emits changes for descendants on a folder move;
- when the Gemini daily quota resets;
- Vertex's endpoint and request format;
- per-image and per-page token cost;
- the inline request byte limit;
- the `HttpException` body content;
- `halfvec` through the langchain4j pgvector store.
