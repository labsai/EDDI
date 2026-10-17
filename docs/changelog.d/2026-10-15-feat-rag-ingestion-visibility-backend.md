## ✨ feat(rag): live run progress, a knowledge-base inspector and schedule status for ingestion (2026-10-15)

**Repo:** EDDI (`feat/rag-ingestion-ux`)

### What changed and why

Ingestion ran blind: a run row was written as `RUNNING` with zeroes and only got its numbers when it
ended, nothing said what a knowledge base actually held, and a source's schedule was a cron string
with no word on whether it was armed, firing or failing. The backend now answers all three.

**Run visibility**

- `IIngestionStateStore.reportProgress(run)` writes a running run's counters onto its row, fenced on
  `status = RUNNING` in both stores, so a late write can neither reopen nor overwrite a finished or
  reaped run. `IngestionPipeline`'s collector calls it at most every two seconds (forced once at the
  start, so the trigger is on the row before the first document); a failed write is a stale number,
  never a failed run.
- `IngestionRun` gains `documentsSkipped`, `stopReason` and `trigger` (`MANUAL` / `SCHEDULED`), with a
  13-argument constructor kept for the existing callers. Postgres gains the columns through
  `ADD COLUMN IF NOT EXISTS`; Mongo needs nothing. The skipped count and stop reason were already in
  `IngestionReport` and reached only the preview response and the log.
- `RagSourceIngestionService.runAsync` takes the trigger; `processScheduledFire` passes `SCHEDULED`.
  A run cancelled at shutdown is now closed with the counters its last progress write recorded rather
  than zeroes.
- `POST …/sources/{sourceId}/run` returns the `runId` it reserved.

**Inspector** — `KnowledgeBaseInspector`, read-only:

- `GET …/sources/{sourceId}/documents` (VIEW) — the source's document states, most recently embedded
  first, filterable and paged, labelled by file name for uploads; the orphan-sweep marker row is hidden.
- `GET …/sources/{sourceId}/chunks?documentId=` (EDIT) — one document's chunks in document order.
- `POST /ragstore/rags/{id}/search` (EDIT) — a test retrieval with the knowledge base's own
  `maxResults`/`minScore` as defaults, optionally narrowed to one source.
- The vector stores have no listing, so chunks are reached by a similarity search filtered on the
  metadata ingestion writes, and always ANDed with `EmbeddingStoreFactory.retrievalFilter` — the
  inspector sees exactly what retrieval would, and a per-id knowledge base in a shared table sees only
  its own chunks. A store that cannot filter answers 501; a failing provider or store 502 with the cause.

**Schedules**

- `IScheduleStore.readScheduleByName` (Mongo + Postgres), and `GET …/sources/{sourceId}/schedule`
  (VIEW) returning next/last fire, fire status, fail count, disable reason and the last five fires.
- `GET /ragstore/rags/cron/preview` applies the save-time checks — parseable, matches a real date, not
  more frequent than `eddi.schedule.min-interval-seconds` — and returns the next fire times in UTC.

### Design decisions

- **Chunks and search need EDIT, the document list VIEW.** A published knowledge base grants VIEW to
  everyone; the chunk text is the knowledge base's content and every call costs an embedding.
- **Progress is throttled in the pipeline, not in the store.** One small update per source every two
  seconds; the interval is an instance field only so a test can make every document report.
- **Search goes through the retrieval isolation filter unconditionally.** An inspector that could see
  another knowledge base's chunks in a shared table would be a cross-tenant read.

### Tests

`IngestionStateStoreContract` (three new cases, run against Mongo, Postgres and the in-memory double),
`IngestionPipelineTest` (trigger + stop reason recorded; counters visible on the running row while it
embeds), `RagSourceIngestionServiceTest` (shutdown keeps progress), `KnowledgeBaseInspectorTest`
(real `InMemoryEmbeddingStore`: ordering, isolation, source narrowing, min score, file labels),
`RestRagIngestionInspectionTest` (access levels, clamping, 400/404/501/502), `RagSourceScheduleViewTest`,
and `readScheduleByName` in both schedule store container tests.

**Files:** [`IIngestionStateStore.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java),
[`IngestionPipeline.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`KnowledgeBaseInspector.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/KnowledgeBaseInspector.java),
[`RagSourceIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/RagSourceIngestionService.java),
[`IRestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/IRestRagIngestion.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`IScheduleStore.java`](../../src/main/java/ai/labs/eddi/engine/schedule/IScheduleStore.java), both state
and schedule stores, [`docs/rag.md`](../rag.md).

### What's next

The cost controls planned in [`rag-ingestion-source-types-plan.md`](../../planning/rag-ingestion-source-types-plan.md)
(§3.13) and run outcome/duration metrics. A cancel button for a running run would need a
cancellation signal the worker polls; `isCancelled` is the natural hook.
