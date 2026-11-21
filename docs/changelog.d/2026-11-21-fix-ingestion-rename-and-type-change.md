## fix(ingestion): a type change's cleanup spares the next run; a rename reaches the citation (2026-11-21)

**Repo:** EDDI (`fix/ingestion-rename-and-type-change`, based on `feat/google-drive-ingestion`) —
two findings CodeRabbit raised on [#1495](https://github.com/labsai/EDDI/pull/1495) and that PR left out.

### What changed and why

- **The cleanup of a run whose source changed type no longer deletes the next run's chunks (Major).**
  Saving a source with a new type (a crawl switched to `googleDrive`, say) purges its state, which
  frees the run slot, so the new type's first run can start while the old run is still finishing. The
  old run's `cleanUpAfterRun` then called `forgetSource`, which removed every chunk under the shared
  source key and purged its state — the new run's chunks, state rows and `RUNNING` row included.
  `RagSourceIngestionService.discardWhatTheRunWrote` now takes the source's maintenance claim and reads
  the run history under it. Only when the old run's own row is still the newest — the save's removal
  failed — is everything under the key forgotten, as a retry. Otherwise only the chunks tagged with the
  old run's id go (`IngestionPipeline.forgetRun`: `sourceKey AND runId`); with no other run listed the
  state is purged, and with one listed (the new run holds the slot, or has finished) nothing is purged
  and the content hashes are invalidated, because the new run may have called one of the old run's
  documents "unchanged". An empty history is deliberately not read as "nobody else wrote": a purge
  erases run rows and keeps chunks. The claim is released in `finally`, so a failed vector delete no
  longer leaves the source blocked until it is reaped. `cleanUpAfterRun` takes the run id.
- **A renamed document gets its new citation without being embedded again (Minor).** A Drive file
  renamed without a byte change keeps its checksum, and a Google Doc whose `version` moved for a rename
  exports the same text, so the chunks kept the old `title` and `url`. `DocumentState` gains
  `citationHash` (`ContentHashes.citation(title, url)`, the title as capped on chunks), stored by a new
  `recordIngested` overload and a fenced `recordCitation` on all three state stores (MongoDB field,
  PostgreSQL `citation_hash` column added by `ALTER … ADD COLUMN IF NOT EXISTS`, the in-memory double).
  `WorkItem` gains an optional `Citation`, which the Drive and upload connectors fill from the listing.
  The collector checks it on both unchanged paths — the version-token skip before a fetch and the
  unchanged-hash branch after one (Google Docs, a crawled page with a new `<title>`) — and on a
  difference rewrites the chunks' `title` and `url` through the new `SegmentMetadataUpdates`, under
  `stillOwnsRun(true)`, never touching `runId`. The fingerprint is recorded only after the write; when
  the write fails, the response's new validators are not recorded either, so a Google Doc is exported
  again next run instead of being skipped by its now-current `version` with the old title.

### Design decisions

- **The fence is the chunk's `runId`, not a lease or a generation.** Every chunk already carries it,
  and the claim decides atomically whether the whole key may go. No new state-store method for bug 1.
- **A null fingerprint is adopted, not treated as a rename** — otherwise the first run after the
  upgrade would rewrite, or on most stores re-embed, every document.
- **Fallback where metadata cannot be updated in place:** `SegmentMetadataUpdates` is implemented by the
  two stores EDDI subclasses — `CappedInMemoryEmbeddingStore` (matching entries replaced under the same
  ids and vectors, under the cap's lock, cap not charged) and `EddiPgVectorEmbeddingStore` (one
  `UPDATE … SET metadata = metadata::jsonb || ?::jsonb`, valid on the `json` and `jsonb` layouts).
  Everywhere else that one document is fetched and embedded again, once.
- **No new report field or metric**, to keep the REST/OpenAPI surface and the dashboards unchanged.
- **Fits the open PRs:** #1489's re-embed service holds a maintenance claim per source and calls
  `purgeSource`, `claimForMaintenance` and `releaseClaim` unchanged; a cleanup meeting those claims takes
  the fenced branch. #1455 coordinates `RagIngestionService` (direct documents, `rag.` lease) and shares
  no code with source ingestion.

### Tests

`RagSourceTypeChangeCleanupTest` (real pipeline and state store, a filter-evaluating vector store):
cleanup interleaved with the new type's run, cleanup after it finished, no run since (and a run trying
to start mid-cleanup is refused), a failed delete releases the claim. `IngestionCitationRefreshTest`,
two Drive pipeline cases, three `IngestionStateStoreContract` cases (in-memory, MongoDB, PostgreSQL),
`CappedInMemoryMetadataUpdateTest`, and `PgVectorMetadataUpdateIT` on both pgvector layouts.

### Not done

- The save path itself (`discardRemovedSources`) still forgets the whole key. That is intended: at the
  save, the only run that can hold the key is one of the old type, which is exactly what it must stop.

- The rename branch of `cleanUpAfterRun` (`purge` after a knowledge-base rename) has the same shape: a
  run that started and finished in between has its state purged (not its chunks). It costs a
  re-embedding, not data, and is left as it was.
- A source's own rename still leaves `sourceName` on its chunks as it was.
