## ✨ feat(rag): delete a document ingested through `/ingest` (2026-11-05)

**Repo:** EDDI (`feat/rag-document-delete`)

### What changed and why

A client that pushes text through `POST /ragstore/rags/{id}/ingest` could supersede a document
(`replace=true`) but never remove one. `DELETE /ragstore/rags/{id}/documents?version=N&documentName=…`
now does, with the semantics of `replace=true`:

- **The same chunks.** `RagIngestionService.delete` removes by the metadata ingestion writes —
  `source = documentName` and this knowledge base's `kbId` — on this knowledge base's own store
  (`EmbeddingStoreFactory.getOrCreate`, not `getOrCreateForIngestion`, so a delete can never replace
  an in-memory store). Every version of the name goes, legacy chunks without an ingestion id
  included; another knowledge base's document of the same name in a shared store does not.
- **The same lock.** The per-document `ReplaceKey` lock is now taken through `documentLock`, by
  replacements and deletes alike, so a delete never lands between a replacement's store and its
  cleanup (where it would take the new chunks too and leave a "completed" replacement over an empty
  document). A delete waits up to 30 seconds and then answers `409`.
- **A name is required.** Missing, blank or `unnamed` is a `400`, as for `replace=true`.
- **EDIT**, checked first, like ingestion.
- **Outcomes.** `200` deleted (also for a name nothing was ingested under); `200` with a `warning`
  when the store cannot delete by metadata; `503` when the store fails, so the client retries.
- **Source-owned names are refused (`409`).** A source's chunks carry `sourceName`/`sourceKey` and no
  `source` key, so the delete filter cannot touch them — but answering "deleted" while they stay
  retrievable would mislead. `RagSourceIngestionService.sourceHoldingDocument` checks the knowledge
  base's sources: an upload source's file names and ids, and the non-tombstoned documents a crawling
  source's state records. A hit is refused with the source's id and the endpoint to use instead.

**Platform Operator:** the endpoint stays outside the allow-list, like every RAG write
(`planning/operator-write-scope-plan.md` §5). `grantsKnowledgeBaseAuthoring` now counts it, and the
read-only line says the operator cannot delete a document either; revision bumped.

No MCP tool ingests into a knowledge base (`McpAdminTools` reads `rag` but deliberately does not write
it), so there is no delete tool to add and no plugin skill to update.

### Judgment calls

- Ownership is checked by name against the sources' state, not by chunk metadata: listing chunks
  needs a similarity search (an embedding call) and still could not express "has a `sourceKey`".
  Consequence: a document pushed through `/ingest` under the same name as a source's file cannot be
  deleted here until that file is removed.
- An ingestion without `replace` takes no lock, so one in flight when a delete answers can still
  store its chunks afterwards. Documented rather than changed.

**Files:** [`RagIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`IRestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/IRestRagIngestion.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`RagSourceIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/RagSourceIngestionService.java),
[`rag.md`](../rag.md#deleting-a-document), the operator's
[`system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts),
[`tool-scopes.ts`](../../ui/manager/src/lib/operator/tool-scopes.ts) and
[`operator-revision.json`](../../ui/manager/src/lib/operator/operator-revision.json); tests in
`RagIngestionServiceTest`, `RagSourceIngestionServiceTest` and the new
`RestRagIngestionDeleteDocumentTest`.
