## ♻️ refactor(ingestion): provider-neutral source connector contract (F1) (2026-11-05)

**Repo:** EDDI (`refactor/ingestion-source-connector-contract`)

### What changed and why

This is the foundation of the ingestion source types plan (`planning/rag-ingestion-source-types-plan.md` §3.1, §3.2, §3.5).
`IngestionPipeline` was hard-wired to the web crawler and the uploaded-file store. It now dispatches
every run through a `SourceConnector` chosen by `IngestionSource.type`, so that provider-specific Java
lives only in connectors. **There is no behaviour change**: the existing ingestion tests pass without
modification.

- **The contract** (new package `modules/ingestion/connector`):
  - `SourceConnector`: `discover(DiscoveryContext) → DiscoveryResult` lists from a cursor and streams
    `WorkItem`s to `context.accept`. `fetch(WorkItem, SourceContext) → SourceDocument` reads one
    document. `listPartitions` and `verifyAccess` default to one implicit, always-accessible partition.
  - `WorkItem` carries `action` `UPSERT`/`UNCHANGED`/`DELETE`, a version token, hints, and an
    optional prefetched document.
  - `SourceDocument` has `Content` `HtmlPage`/`FileBytes`/`Text`, a version token, HTTP validators
    and metadata.
  - `SourceMetadataKeys` defines the standard vocabulary and reserves `acl.principals`, which is
    schema only.
  - `Coverage` is `FULL_LISTING`/`FULL_TRAVERSAL`/`INCREMENTAL`/`PARTIAL`. `StopReason` uses the same
    names as `WebCrawler.StopReason`.
  - `SourceError` is a sealed type: `Transient`, `Throttled`, `Auth`, `NotFound`, `Permanent`,
    `Rejected` and `CursorInvalid`, plus `isDefinitive()`. The checked `SourceException` carries it.
    `Permanent` is an answer about the document (it can never be read as it is). `Rejected` is a
    request the provider refused as made (malformed, an unknown field mask). It says nothing about the
    document and is not definitive, so a connector defect or an API change cannot tombstone a corpus.
  - `KnownVersion`, `Partition` and `SourceContext`.
  - `SourceConnectors` is the CDI registry by type, and fails startup when two beans claim one type.
- **Adapters:** `crawl/WebSourceConnector` wraps `WebCrawler` unchanged. Pages arrive prefetched, a
  304 becomes `UNCHANGED`, and crawl errors are classified so that `isDefinitive()` is exactly the
  crawler's `!contentUnknown`. `files/UploadSourceConnector` lists without reading, uses the hash of
  the bytes as the version token, and maps a deleted-meanwhile file to `NotFound` and a store failure
  to `Transient`. The `toCrawlRequest` and upload listing loop moved into the adapters verbatim.
- **Pipeline:** `Collector` no longer implements `CrawlSink`. It processes work items generically.
  Text extraction stays in the framework, selected by content kind, which keeps the
  page-skipped-when-blank and file-failed-when-blank semantics. The old 8-argument constructor
  remains and builds the two connectors. CDI uses a new constructor that takes `SourceConnectors`.
- **Traversal evidence is counted, not inferred.** The blank-site guard (`learnedSomething`) used to
  treat `failed - unreachable` as definitive answers. A source-level failure (no document id) counts
  as failed but was never unreachable, so a `FULL_TRAVERSAL` run that learned nothing about any
  document passed for one that saw the corpus gone, and its absence-based deletion ran. `Collector`
  now counts `definitive` where it is learned: a document-level `NotFound`/`Permanent`, or a file
  with no text. Embed and budget failures no longer stand in for evidence either. That is the safe
  direction, since the only effect is that reconciliation runs less often. The now write-only
  `unreachable` counter is gone.
- **Config models only, not wired:** `SourceFilters` (`formats`, `excludeNamePatterns`,
  `includePaths`, `maxFileBytes`) and `SourcePartitions` (`LISTED` | `ALL_ACCESSIBLE`, `ids`).
- **Docs:** a "Source connectors" section in [`rag.md`](../rag.md#source-connectors-for-contributors).

### Not in this slice

The following are not in F1:

- **Cursors are not stored.** The pipeline passes none and ignores a returned one, so every run
  lists in full.
- **No multi-partition runs.** More than one partition is refused with a run failure.
- **No work queue or workers, and no superseding of item versions.** These are F2 and F3.
- **`DELETE` items** are applied directly: vectors are removed first, then the item is tombstoned.
  No built-in connector emits them yet.

**Files:** [`IngestionPipeline.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`connector/`](../../src/main/java/ai/labs/eddi/modules/ingestion/connector/SourceConnector.java),
[`WebSourceConnector.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/WebSourceConnector.java),
[`UploadSourceConnector.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/files/UploadSourceConnector.java),
[`SourceFilters.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/SourceFilters.java),
[`SourcePartitions.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/SourcePartitions.java)

```decision-log
| 2026-11-05 | Discovery streams work items to `DiscoveryContext.accept`; F1 processes each one synchronously | The crawler's budget stop, ownership fence and conditional requests interleave with per-page work, so a returned list would buffer the whole site and change the order of side effects | Returning `List<WorkItem>` from `discover` |
| 2026-11-05 | `WorkItem.Action` gains `UNCHANGED` alongside UPSERT/DELETE | An HTTP 304 against the stored validators is a provider-confirmed "unchanged" with no content and no comparable version token | Encoding 304 as UPSERT with a fake version token |
| 2026-11-05 | `Coverage.FULL_TRAVERSAL` is separate from `FULL_LISTING` | A crawl needs a "learned something definitive" guard before absence counts as deletion, but an authoritative listing (uploads) must believe an empty result | One FULL_LISTING value plus a flag; applying the crawl guard to every source, which would never delete the last uploaded file |
| 2026-11-05 | Text extraction stays in the framework, selected by `Content` kind (HtmlPage / FileBytes / Text) | Keeps the limits and the blank-page-skipped and blank-file-failed semantics in one place for every source | Connectors extracting their own text |
| 2026-11-05 | `SourceError.isDefinitive()` (NotFound, Permanent) decides "gone" vs "could not look"; a failure with no document id is never evidence | It is a lossless mapping of the crawler's `contentUnknown`, and it generalises to provider errors | Exposing a raw contentUnknown boolean in the contract |
| 2026-11-05 | A request the provider refuses as made is `SourceError.Rejected`, not definitive; `Permanent` is kept for answers about the document | "Malformed request" was listed under `Permanent`, which counts as absence, so a connector bug would delete the documents it touched. The web connector's content-known 4xx stays `Permanent`: that is the server's answer for the page, as the crawler always treated it | One `Permanent` kind for both; making `Permanent` non-definitive, which would keep unexportable files' stale chunks for ever |
| 2026-11-05 | `SourceMetadataKeys.CITATION_URL` is `"url"`, the key chunks already carry | The vocabulary describes stored data rather than renaming it | A new `citationUrl` key on chunks |
```
