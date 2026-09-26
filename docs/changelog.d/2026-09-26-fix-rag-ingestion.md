## 🐛 fix(rag): ingestion keeps what it should, removes what it should, and bounds hostile uploads (2026-09-26)

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
  when robots.txt names none or is not read (unless robots.txt disallows it). **Upgrade notes:** a page
  that declared a different canonical was stored under the canonical's id before; it is now stored
  under its own URL, so such pages are re-embedded once and the old ids reconcile away over the next
  `tombstoneAfterMissedRuns` complete runs. Sitemap pages are queued at depth 0, so a source that
  stayed under `maxPages` by `maxDepth` alone can now reach the page limit — which disables deletion
  reconciliation for that run; the crawler logs a WARN naming the sitemap when that happens.
### Runs

- **R2 — scheduled crawls are no longer cancelled at the lease.** The fire ran the crawl on the
  scheduler's thread, which cancels a fire after its 5-minute lease while a crawl's default budget is
  10 minutes. `RagSourceIngestionService.processScheduledFire` now claims the run and starts it on its
  own worker (new `IngestionReport.Outcome.STARTED`), as a manual run does. When that run later
  fails, the worker writes a second, `FAILED` fire-log entry for the fire (it does not raise the
  schedule's `failCount`, so a failing crawl neither retries early nor dead-letters — documented in
  [`scheduling.md`](../scheduling.md#fire-logging)). On a graceful shutdown the runs this instance was
  carrying are closed as `CANCELLED` and their workers interrupted, so a rolling restart no longer
  blocks "Run now" for the ~25 minutes until they would have been reaped.
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
  bypassed the check (ZIP import). An existing too-frequent schedule keeps firing until its knowledge
  base is next saved — and that save is then refused until the cron is fixed.
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
  again, and with its state row gone nothing reconciled it. The first run after a purge (one that
  starts from no state) now records a marker row (`IngestionPipeline.SWEEP_MARKER`) that no crawl ever
  sees. It is missed by every complete run like a vanished page, and once missed
  `tombstoneAfterMissedRuns` times, a failure-free run removes every chunk of the source not written by
  a run since the purge (`sweepOrphans`, filter `runId NOT IN` the post-purge run history). A page only
  briefly absent after a purge thus gets the usual grace. A failure reading the state at run start now
  skips the marker, not the run. The documented contract — chunks keep answering until rebuilt — is
  unchanged.
- **R4 — a failed chunk removal keeps the file.** `forgetDocument` now returns `ForgetOutcome`
  (`REMOVED` / `UNSUPPORTED` / `FAILED`). On `FAILED` the file is kept and the delete answers `503`
  (`DeleteOutcome.REMOVAL_FAILED`); deleting it anyway stranded the chunks for good.
- **U6 — file deletes no longer pose as runs.** The maintenance claim is released with the new
  `IngestionRun.Status.MAINTENANCE`, which `listRuns` omits in all three stores. The row is kept, not
  deleted, because its generation must stay counted: deleting it let the next run reuse the
  generation and be fenced out of its own documents (pinned by a contract test). Both stores now read
  statuses through `Status.parse`, which maps one this build does not know to `FAILED`. **Rolling
  upgrade:** a node built before this branch still throws on `MAINTENANCE` in `listRuns` (500) until it
  is upgraded; ingestion is unreleased, so only snapshot deployments see it.
### Uploaded files

- **R5 — PDF extraction is bounded, by counting what PDFBox decodes.** The first version of this fix
  measured streams on the raw bytes only, and review showed four ways past it: spaces after the
  `stream` keyword, non-Flate filters (ASCIIHex/ASCII85/RunLength/LZW, alone or chained), one stream
  referenced many times from a page's `/Contents` (an OOM at `-Xmx1g` from a 61 KB file), and
  encrypted streams. PDFBox's `MemoryUsageSetting` cannot close these: `Filter.decode` writes into a
  buffer it sizes itself and never consults the stream cache (checked in the 3.0.8 bytecode). So
  `PdfDecodeBudget` wraps every filter in PDFBox's `FilterFactory`, once, in one that counts its
  output against a budget armed only on the extracting thread; the count covers every stream, chain
  stage, repeated reference and decrypted stream, and past `ExtractionLimits.maxDecodedBytes()`
  (2 × `maxUncompressedBytes`, 128 MB) the document is refused — also when PDFBox swallowed the
  failure, because the flag stays set. If the filters cannot be wrapped, PDFs are refused, not read
  unbounded. `PdfStreamBudget` stays as a cheap pre-filter: it now finds the stream start as PDFBox
  does, decodes the declared filter chain with PDFBox's own filters, and skips image streams and image
  codecs, which text extraction never decodes (so a large image no longer refuses a PDF). Every
  bypass above was re-run with the reviewer's probe at `-Xmx1g`, with the pre-filter on and off: all
  refused. The per-page deadline (60 s) and glyph cap stay; the upload-time probe now gets 10 s.
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
  `eddi.http.limits.default-max-body-size` (default `25M`), raised automatically to fit an attachment
  at `eddi.attachments.max-size-bytes` base64-encoded (4/3 + 1 MB, about 27.7 MB for the default
  20 MiB), so inline attachments and operators raising the attachment limit are not refused. A body
  with no `Content-Length` (chunked, or HTTP/2 data without the header) is refused with 411 on every
  endpoint but the upload — counting bytes as they arrive is not possible from a Vert.x filter,
  because the REST layer replaces the request's data handler; `eddi.http.limits.refuse-unsized-bodies`
  turns that off. Refusals send `Connection: close`. The upload exemption is matched below
  `quarkus.http.root-path`. A ZIP import is held to the same limit (as it was before 60M).
- **U5 — concurrent uploads cannot overshoot the limits.** Each file is measured against a fresh
  listing under a per-source (striped) lock on this instance; across instances a new file that finds
  the source over its limit once stored is deleted again — but only if the stored file still has the
  content hash written here, so another instance's same-named upload is never deleted. Two instances
  adding different files to a source with one slot left can both see it full and both refuse; the
  uploader retries. Identical bytes under one name, from two instances, into a full source, in the
  same instant, remain indistinguishable.
### Not in this branch

- **R8** (redirect bodies in `SafeHttpClient`) belongs to `fix/outbound-http-hardening`, which closes
  them (`discardingRedirectBodies`); not duplicated here.
- The Manager's purge dialog still says "Documents already stored are not removed by this", which
  remains true; no UI change is needed. The Manager does not yet show the `503` delete message or the
  `STARTED` fire outcome specially — both are backward compatible (a 5xx is already an error there).

### Residual risk, stated

- The PDF decode budget counts what PDFBox writes through its filters. Memory PDFBox takes outside
  them (object parsing, fonts it builds from decoded bytes, the page's glyph list — capped separately)
  is not counted by it; the deadline and glyph cap bound those.
- The budget relies on replacing entries in PDFBox's `FilterFactory` by reflection. A PDFBox upgrade
  that renames that field makes every PDF refused (loudly, with an ERROR at first use), never read
  unbounded; `DocumentExtractorsTest` fails in that case.
- A body without a `Content-Length` is refused rather than counted; a client that cannot send one
  needs `eddi.http.limits.refuse-unsized-bodies=false`, which leaves it to the global 60 MB.
- A run in flight when its source is removed can write one more document between the purge and its
  next ownership check; `cleanUpAfterRun` removes it once the run ends.

**Files:** [`WebCrawler.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/WebCrawler.java),
[`IngestionPipeline.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`RagSourceIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/RagSourceIngestionService.java),
[`IIngestionStateStore.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java) and its Mongo/PostgreSQL stores,
[`IngestedFileService.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/files/IngestedFileService.java),
[`PdfTextExtractor.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/PdfTextExtractor.java),
[`PdfStreamBudget.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/PdfStreamBudget.java),
[`PdfDecodeBudget.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/PdfDecodeBudget.java),
[`DocumentExtractors.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/extract/DocumentExtractors.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`RestRagStore.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagStore.java),
[`RequestBodyLimitGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/RequestBodyLimitGuard.java),
[`ScheduleFireExecutor.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/ScheduleFireExecutor.java).
