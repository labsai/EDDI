## 🧪 test(rag): document replacement verified on a real Chroma server (2026-11-05)

**Repo:** EDDI (`test/chroma-replacement-it`)

### What changed and why

[`rag.md`](../rag.md#store-support-for-replacement) said replacing a document's chunks — the
`removeAll(Filter)` on `documentId` + `sourceKey` that a re-ingest, a tombstone and a deleted upload
all end in — was verified only on `in-memory` and `pgvector`, with Chroma merely "expected". Nothing
checked it, and a driver that cannot translate the compound filter leaves stale chunks retrievable
for good.

New [`ChromaReplacementIT`](../../src/test/java/ai/labs/eddi/modules/ingestion/ChromaReplacementIT.java)
starts `chromadb/chroma:1.1.0` (the image `docker-compose.chroma.yml` ships) and drives the real
`IngestionPipeline` through the real `EmbeddingStoreFactory`, so the store is the one `buildChroma`
makes, on its default `apiVersion` `V2` with its bounded JDK client. Only the embedding model is an
offline bag-of-words stand-in. Three cases, each asserting on what is actually in the collection:

- a changed page is re-ingested: the new text is there, the old text is gone, `replaceUnsupported` is
  false — this exercises the three-clause filter including `runId $ne`;
- a page removed from the site is tombstoned: its chunks are deleted, the surviving page's are not;
- a deleted upload is forgotten: `ForgetOutcome.REMOVED`, and the source's other document survives.

All pass locally against the container, so the docs now list Chroma as verified. `mongodb-atlas`,
`elasticsearch` and `qdrant` remain "expected".

### Notes

- Run locally from a sandboxed Windows shell, the JDK `HttpClient` could not open its selector
  ("Unable to establish loopback connection" — the NIO pipe's Unix-domain socket under a long temp
  path). `-DargLine=-Djdk.net.unixdomain.tmpdir=<short dir>` works around it; CI is unaffected.

**Files:** [`ChromaReplacementIT.java`](../../src/test/java/ai/labs/eddi/modules/ingestion/ChromaReplacementIT.java),
[`rag.md`](../rag.md)
