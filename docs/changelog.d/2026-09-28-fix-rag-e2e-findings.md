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
