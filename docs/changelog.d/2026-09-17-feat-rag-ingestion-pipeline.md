## 🔗 feat(rag): knowledge-base sources and the ingestion pipeline (2026-09-17)

**Repo:** EDDI (`feat/rag-ingestion-pipeline`)

### Why sources live on the knowledge base

`RagConfiguration` gains `sources[]` rather than ingestion sources becoming a 13th resource type. The
vector store is keyed by the knowledge base, so a source that could exist independently of one has to
name its target by string — and that is exactly how the draft in PR #529 came to key ingestion on the
**source's** name (`kbId = sourceConfig.name()`) while `RagContextProvider` keys retrieval on the
**knowledge base's**. Crawled content went into one pgvector table and every query read another. The run
reported success; the agent retrieved nothing. No test caught it because none performed a retrieval
after an ingest. Ownership removes the possibility rather than documenting it.

### The pipeline

`IngestionPipeline` runs crawl → convert → compare → embed per document, then reconciles deletions:

- **Re-ingesting replaces.** A document's chunks are removed by `documentId` metadata before its new
  ones are added. The draft called `EmbeddingStoreIngestor.ingest`, which only appends and never
  removed anything, so a page edited weekly left a year of stale versions retrievable beside the current
  one. Where a store's driver cannot delete by metadata, the run says so (`replaceUnsupported`) instead
  of quietly accumulating.
- **A document is recorded only after its vectors are stored.** The draft committed the content hash
  while *deciding* whether to ingest, with embedding afterwards inside a `catch` that only logged — so a
  single 429 marked a page done forever.
- **Only a crawl that covered the source may conclude anything is gone.** A run stopped by its page cap,
  time budget or segment budget sets `tombstoningSkipped` and deletes nothing.
- **Tombstoned documents lose their vectors.** In the draft, "stale detection" flipped a flag in a side
  table nothing consulted at retrieval time, so a deleted page kept answering questions forever.
- Segments carry `documentId`, `url`, `title`, `sourceName`, `runId` and `ingestedAt`, so an answer can
  cite its source. Counts are the segments actually written, not `markdown.length() / chunkSize`.
- `maxSegmentsPerRun` is the cost ceiling — exact without a pricing table; set
  `costPerThousandSegments` to have runs report dollars too. `PREVIEW` mode crawls and reports what
  would change without embedding or recording anything.

### Tests

**25 pipeline tests, 23 more for the in-memory state store.** The test double implements the same
`IngestionStateStoreContract` as MongoDB and PostgreSQL, so it cannot quietly behave differently from
production — the failure mode that let the draft's two stores drift apart.

Mutation-checked against all four headline defects: keying the store on the source, appending instead of
replacing, recording the hash before embedding, and tombstoning after a partial crawl each fail between
one and seven tests.

### Note on the branch

This branch is stacked: it contains the converter, state store and crawler commits because the pipeline
needs all three. Merge those three first, or review this as a stack.
