## 🐛 fix(rag): ingestion keeps what it should, removes what it should, and survives a hostile file (2026-09-26)

**Repo:** EDDI (`fix/rag-ingestion`)

A review of RAG ingestion (findings R1–R10, U3–U6 and four new ones) found ways for a knowledge base
to lose documents that still exist, keep answering from documents that are gone, and be taken down
by one uploaded file. This fixes them. Every behaviour below is documented in
[`rag.md` → Ingestion Sources](../rag.md#ingestion-sources).

### Crawling

- **R1 — an unchanged parent no longer deletes its children.** A 304 has no body and so no links; the
  children of a revalidated page were never reached, the crawl still counted as complete, and after
  `tombstoneAfterMissedRuns` runs every page below it lost its vectors. Validators are now sent only
  for pages at `maxDepth`, whose links are not followed anyway. Pages above it are downloaded in full;
  the content hash still decides re-embedding, so this costs downloads, never embeddings.
  (`WebCrawler.processCandidate`)
- **Stale ETags are refreshed.** A page downloaded again and unchanged now stores the validators of
  that response (`IIngestionStateStore.recordSeen(…, etag, lastModified)`, implemented in the Mongo,
  PostgreSQL and in-memory stores). The first ingest's ETag used to be kept for ever, so a server that
  rotated it never answered 304 again.
- **R9 — canonical links are de-duplication, not identity.** A page is stored under the URL that served
  it. A page naming another in-scope, same-host page as canonical defers to it (that page is fetched
  and stored with its own content) and is stored itself only if the target produced nothing. A
  deferring or duplicate page's links are still followed — a paginated listing no longer loses its
  later entries. Sitemap discovery now follows `<sitemapindex>` files and falls back to `/sitemap.xml`
  when robots.txt names none or is not read (unless robots.txt disallows it). **Upgrade note:** a page
  that declared a different canonical was stored under the canonical's id before; it is now stored
  under its own URL, so such pages are re-embedded once and the old ids reconcile away over the next
  `tombstoneAfterMissedRuns` complete runs.

### Runs

- **R2 — scheduled crawls are no longer cancelled at the lease.** The fire ran the crawl on the
  scheduler's thread, which cancels a fire after its 5-minute lease while a crawl's default budget is
  10 minutes. `RagSourceIngestionService.processScheduledFire` now claims the run and starts it on its
  own worker (new `IngestionReport.Outcome.STARTED`), as a manual run does; the outcome is in the run
  history. `ScheduleFireExecutor` records a started run as a completed fire.
- **A run that loses its source stops.** The collector asks whether its run is still the active one —
  before every embedding, and every 5 s between pages. A run whose row was purged (source removed,
  knowledge base renamed, state purged) or that was reaped used to carry on writing chunks and state
  rows for a cleared source.
- **Removal of an upload source mid-run / rename mid-run.** After every run the worker re-reads the
  knowledge base (`cleanUpAfterRun`): if the source is gone or changed type, what it wrote after the
  removal is removed again; if the knowledge base was renamed, the source's state is purged again
  under the run claim. A store that cannot be read is never mistaken for a deleted knowledge base.
- **R10 — ingestion crons honour `eddi.schedule.min-interval-seconds`.** Refused with a 400 at save
  time (`requireAllowedIntervals`), and skipped with an error by `syncSchedules` for a writer that
  bypassed the check (ZIP import).

### Removing and purging

- **R3 — removing a web source removes its vectors.** `discardRemovedSources` skipped crawl sources on
  the theory their documents "come back on the next run"; a removed source has no next run. Any
  source removed or changed in type now has its chunks removed and its state forgotten; deleting the
  last version of a knowledge base does the same for every source.
- **R7 — purge is decided under the run claim.** `purge` takes the maintenance claim and returns
  `false` (→ 409) while a run holds the source, instead of a check-then-act in the REST layer. The
  rename path uses the new `forgetStateAfterRename`, which purges regardless (the run then stops).
- **RAG backend purge claim — refuted as worded, fixed at the root.** The API and Manager say a purge
  "does not by itself remove vectors" and the next run re-ingests, which was true for documents that
  still exist. It orphaned the rest: a document gone from the source before the purge was never found
  again, and with its state row gone nothing reconciled it. The first complete, failure-free run after
  a purge (a run that starts with no state) now removes every chunk of the source it did not write
  (`IngestionPipeline.sweepOrphans`). The documented contract — chunks keep answering until the next
  run — is unchanged.
- **R4 — a failed chunk removal keeps the file.** `forgetDocument` now returns `ForgetOutcome`
  (`REMOVED` / `UNSUPPORTED` / `FAILED`). On `FAILED` the file is kept and the delete answers `503`
  (`DeleteOutcome.REMOVAL_FAILED`); deleting it anyway stranded the chunks for good.
- **U6 — file deletes no longer pose as runs.** The maintenance claim is released with the new
  `IngestionRun.Status.MAINTENANCE`, which `listRuns` omits in all three stores. The row is kept, not
  deleted, because its generation must stay counted: deleting it let the next run reuse the
  generation and be fenced out of its own documents (pinned by a contract test).

### Uploaded files

- **R5 — PDF extraction is bounded.** `PdfStreamBudget` measures every compressed stream on the raw
  bytes before PDFBox opens the file, mirroring PDFBox's Flate decoder (it skips the two-byte header
  unchecked; double compression is measured at the level that expands most), and refuses one that
  expands past `ExtractionLimits.maxUncompressedBytes` (64 MB). PDFBox's scratch space is capped at the
  same figure; page programs are checked against a new `ExtractionLimits.maxDuration` (60 s) every 256
  operators; a page laying out far more glyphs than the character cap could keep is stopped.
- **R6 — an unreadable replacement retires the old text.** A stored file that cannot be read, or yields
  no text, is now a definitive failure for an upload source: the previous version's chunks are removed
  and its row tombstoned. It used to be recorded as "could not look", so the replaced document kept
  answering indefinitely. A file-store read failure is still treated as transient.
- **NEW — scanned PDFs are refused at upload, with the reason.** The upload now runs the extraction
  (stopped after 64 characters) and refuses a file with no text — "This PDF has no text layer… EDDI has
  no OCR". They used to be stored, listed as not indexed, and silently skipped by every run.
- **U3 — the extension no longer vouches for a binary format.** Text content under a `.pdf`, `.docx`,
  `.xlsx` or `.pptx` name is refused instead of being stored as that format and failing every run.
- **U4 — size is checked before bytes are read, and the 60 MB body limit is scoped to the upload.** The
  REST layer passes each part's declared size and the service refuses an oversized file without
  reading it. The new `RequestBodyLimitGuard` holds every other endpoint to
  `eddi.http.limits.default-max-body-size` (default `25M`, the pre-upload limit) on the declared
  `Content-Length`; only `POST /ragstore/rags/{id}/sources/{sourceId}/files` keeps
  `quarkus.http.limits.max-body-size`. Chunked requests without a length stay bounded by the global limit.
- **U5 — concurrent uploads cannot overshoot the limits.** Each file is measured against a fresh
  listing under a per-source (striped) lock on this instance; across instances a new file that finds
  the source over its limit once stored is deleted again. A cross-instance *replacement* can still
  overshoot by the replaced file's size — the replaced bytes are gone.

### Not in this branch

- **R8** (redirect bodies in `SafeHttpClient`) belongs to `fix/outbound-http-hardening`, which closes
  them (`discardingRedirectBodies`); not duplicated here.
- The Manager's purge dialog still says "Documents already stored are not removed by this", which
  remains true; no UI change is needed. The Manager does not yet show the `503` delete message or the
  `STARTED` fire outcome specially — both are backward compatible (a 5xx is already an error there).

### Residual risk, stated

- `PdfStreamBudget` covers Flate, the filter that can be a bomb in practice. An LZW-compressed stream
  is not measured; PDFBox decodes it whole. LZW's worst-case ratio is far lower, and the time budget
  still applies.
- A run in flight when its source is removed can write one more document between the purge and its
  next ownership check; `cleanUpAfterRun` removes it once the run ends.

**Files:** [`WebCrawler.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/WebCrawler.java),
[`IngestionPipeline.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`RagSourceIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/RagSourceIngestionService.java),
[`IIngestionStateStore.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java) and its Mongo/PostgreSQL stores,
[`IngestedFileService.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/files/IngestedFileService.java),
[`PdfTextExtractor.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/PdfTextExtractor.java),
[`PdfStreamBudget.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/PdfStreamBudget.java),
[`DocumentExtractors.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/DocumentExtractors.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`RestRagStore.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagStore.java),
[`RequestBodyLimitGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/RequestBodyLimitGuard.java),
[`ScheduleFireExecutor.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/ScheduleFireExecutor.java).
