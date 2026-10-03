## 🔒 fix(rag): review of the knowledge base isolation — disguised reserved names, layout switch under a run, Atlas default collection (2026-10-03)

**Repo:** EDDI (`fix/rag-kb-isolation`)

### What changed and why

An independent review of the isolation change found six gaps; all are fixed on the branch.

- **The `eddi_kb` guard could be walked round.** It compared `startsWith("eddi_kb")` on the raw
  value, so `"eddi_kb_alpha"` (quoted, which PostgreSQL reads as exactly that table), `public.eddi_kb_alpha`
  or a `/**/`-prefixed name pointed a new knowledge base at another one's default store and passed. A
  reserved name is now any whose dot-separated part starts with the prefix (case-insensitive), and a
  pgvector `table` must be a plain, optionally schema-qualified identifier: the store puts the value
  into SQL unquoted, so anything else was either a way round the check or SQL an editor could run. A
  `${vars:…}` / `${vault:…}` reference is not judged. The same-location collision check now treats
  `public.t` and `t` as one table.
- **A run in flight across a switch to `storeNamespace: "id"`** kept writing into the old store and
  recorded its documents as ingested, so the next run found them "unchanged" and never filled the new
  store. `RagSourceIngestionService.cleanUpAfterRun` compared names; it now compares where the vectors
  live, the same test the save uses to clear the state.
- **An archive imported over an existing knowledge base** carried its own `storeNamespace` into the
  update — an `"id"` from another instance would silently move a 6.5.0 knowledge base to an empty
  store, and a `"name"` failed the import. The archive's value is now dropped for an update, and the
  fallback create (local knowledge base gone) goes through the same storage rules as every other create.
- **Atlas, default collection.** Atlas Vector Search refuses a filter on a field its index does not
  declare, and the index is the operator's, per collection — so every new Atlas knowledge base
  failed to retrieve until its index was edited, for a filter that protects nothing on a collection
  nobody else may name. Atlas is now filtered only when an explicit `collectionName` is set.
- **Vertex `endpoint`** is held to the cloud-metadata guard like every other endpoint parameter.

Tests: guard (disguised names, references, public-schema collision), `KnowledgeBaseStorage`
(spellings, identifier pattern, collision key, Atlas filter rule), source ingestion (layout switch
purged, id-layout rename keeps state), import (storage rules on create). Docs: `rag.md`.

### Design decisions

- **Refuse by shape, not by normalising.** Quoted pgvector identifiers could be unquoted and compared, but
  the store does not escape them either; refusing everything but a plain identifier removes the whole class.
- **Not changed, noted:** a pgvector `table` that resolves from a variable is not checked (variables are
  set by administrators); switching a knowledge base that has an explicit location to `"id"` leaves the
  old chunks in the shared table, untagged and no longer retrieved.

**Files:** [`KnowledgeBaseStorage.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/KnowledgeBaseStorage.java),
[`KnowledgeBaseStorageGuard.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/KnowledgeBaseStorageGuard.java),
[`RagSourceIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/RagSourceIngestionService.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`EmbeddingModelFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingModelFactory.java),
[`rag.md`](../rag.md).
