## 🐛 fix(rag): findings from a live end-to-end RAG run (2026-09-28)

**Repo:** EDDI (`fix/rag-e2e-findings`)

A live run of RAG and ingestion — a Claude agent answering from knowledge bases on the
`in-memory` and `chroma` stores, fed by direct ingestion and by an upload source holding
PDF, Word, Excel, PowerPoint, CSV, HTML and Markdown files — passed on both stores, and
confirmed on Chroma what [`docs/rag.md`](../rag.md) listed as unverified: re-ingesting
a changed file removes the chunks of its previous version. It also turned up the
defects below.

### Deleting an uploaded file answered 400 after it had succeeded

`DELETE /ragstore/rags/{id}/sources/{sourceId}/files/{fileId}?version=N` removed the
file and its vectors, then answered `400 "state should be: hexString has 24 characters"`,
so the Manager reported a failed delete for one that had happened.

`DocumentDescriptorFilter` runs after every successful DELETE and marks the deleted
resource's descriptor as deleted. It took the resource id from the last path segment,
and for this endpoint that is a 32-hex file id — valid enough for
`RestUtilities.extractResourceId`, not for a MongoDB `ObjectId`. The filter now does its
DELETE bookkeeping only for a path of the form `{store}/{collection}/{id}`, which every
configuration resource has; anything deeper is a sub-resource whose last segment is not
a configuration id.

- `DocumentDescriptorFilter.addressesResourceItself` — the path-shape check.
- `DocumentDescriptorFilterTest` — a sub-resource DELETE leaves descriptors untouched
  (fails with the check removed), plus the path shapes.

### Direct ingestion can now replace a document instead of duplicating it

`POST /ragstore/rags/{id}/ingest` only ever added: ingesting `pricing.md` a second time kept
both versions, and retrieval put the stale text in front of the model next to the current
one. The live run showed it on both stores — two identical chunks in the injected context.

`replace=true` (off by default, so existing callers are unchanged) now supersedes whatever
was ingested under the same `documentName` in the knowledge base.

- **Add first, remove afterwards** — the same order the ingestion sources use. Every chunk
  now carries the `ingestionId` that wrote it, and after the new chunks are stored the
  others under that name are removed with `ingestionId != current`. Chunks written before
  this change carry no `ingestionId`; the in-memory store and Chroma both match a missing
  key with `isNotEqualTo`, so those are superseded too (tested against the real in-memory
  store; checked by hand against Chroma 1.0).
- **Needs an explicit name** — `400` without one, because every unnamed document shares
  `unnamed` and replacing it would delete all of them.
- **Says so when it cannot** — on a store that cannot delete by metadata the ingestion
  completes and the status response carries a `warning`, rather than reporting a clean
  replace.
- **Manager** — the drop zone has a "replace a previously ingested document with the same
  file name" checkbox (off by default), and the status list shows the warning. Pasted
  text is always ingested under a fresh name, so it is not offered there.

Files: `RagIngestionService`, `IRestRagIngestion`, `RestRagIngestion`,
`ui/manager/src/components/editors/rag-editor.tsx`, the 11 Manager locales,
[`docs/rag.md`](../rag.md#document-ingestion). Tests: `RagIngestionServiceTest` (replace,
no replace, pre-change chunks, unsupported store — the first three fail with the removal
disabled), `RestRagIngestionTest`.
