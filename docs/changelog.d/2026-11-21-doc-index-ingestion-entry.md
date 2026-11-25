## ✨ feat(rag): the document index — `documentIndex` flag, refusals in every direction, prepared-text ingestion (2026-11-21)

**Repo:** EDDI (`feat/doc-index-ingestion-entry`) — work package 6.1 of the PDF documents implementation plan; depends on WP-0.1 (`feat/doc-config-foundation`)

A RAG configuration can now be flagged as a **document index**: the store of users' and conversations' document chunks, never an agent's knowledge base. Nothing writes to one yet (WP-6.2 adds the `INDEX` stage and semantic search) and `documents.index.rag` stays unavailable; what ships is the flag, every refusal that keeps an index apart from agents' retrieval, and the ingestion entry point the stage will call.

### What changed and why

- **`RagConfiguration.documentIndex`** (`Boolean`, absent = `false`, not serialized when unset, so an ordinary knowledge base saves byte-identical).
- **`DocumentIndexEligibility`** (`engine/documents/index`) — the single rule every check calls. Eligible iff flagged, in the per-id layout, retrieval-filtered (so not an Atlas default collection), on a store type that filters and deletes by metadata (`in-memory`, `pgvector`, `elasticsearch`, `qdrant`, `chroma`, `mongodb-atlas` with an explicit collection), and without ingestion `sources`.
- **Refusals, in every direction** ([rag.md → Document index](../rag.md#document-index)):
  - RAG save (`KnowledgeBaseStorageGuard`, so REST create/update/duplicate and ZIP import/upgrade): an ineligible index is refused; `documentIndex` cannot be switched off and an index's storage cannot move; it cannot be turned on while the current version of any workflow uses the configuration as a RAG step (the workflows are named; refused, not passed, when they cannot be listed); an index shares its explicit location with no other knowledge base, in either direction.
  - Workflow save (`WorkflowStore.create`/`update`, which every path funnels through): a RAG step naming a document index is refused. An LLM task's `knowledgeBases`/`enableWorkflowRag` only ever reach the workflow's own RAG steps, so this covers them.
  - LLM save (`DocumentsConfigValidator`, in `LlmStore` through the existing call): `documents.index.rag` must name an eligible index. Reachable once WP-6.2 makes the key live; tested through the validator's package-private overload.
  - Startup: **`eddi.documents.index.default-rag`** (new, unset) is validated by `DocumentIndexDefaultCheck`; a missing or ineligible configuration fails startup with the reason.
  - Runtime: `RagContextProvider` skips a workflow RAG step whose configuration is a document index (one WARN per step URI, a `rag:trace` entry), before its store is opened.
- **`RagIngestionService.ingestPrepared(ragConfigId, cfg, List<PreparedSegment>, sourceKey, documentId)`** — synchronous; re-checks eligibility before embedding; tags every chunk `kbId = doclib:<ragConfigId>` (`KnowledgeBaseStorage.docLibKbId`), `sourceKey = doclib|<ownerKey>`, `documentId` = the root ref, set last so no segment metadata can override them (`PreparedSegment` also refuses those keys); stores the new chunks, then removes the document's older ones whatever their owner. **`deleteByDocumentId`** removes one document's chunks by `kbId` + `documentId` only, never refused for eligibility. No public method takes a caller-built filter or a caller-chosen `kbId`.

### Design decisions

- **`ingestPrepared` takes the RAG configuration id, not a `kbId`** (the plan's signature named a `kbId`): the store is keyed by the id, and a caller-chosen `kbId` is exactly the 6.5.0 defect `RagIngestionService.ingest`'s Javadoc records. The `doclib:` tag is derived inside.
- **Once an index, always an index; its storage never moves.** The plan allowed switching off "while no chunks exist"; there is no cheap, store-independent way to ask a vector store whether it holds any, so the stricter rule ships. Delete an unwanted index instead.
- **A document index may not have ingestion sources.** Source runs delete by `sourceKey` alone; disjoint prefixes already keep them off document chunks, but an index with no sources removes the question.
- **`documents.index.rag` / `default-rag` hold an id or an `eddi://ai.labs.rag/ragstore/rags/<id>` URI** (version optional: the current one).
- **`LlmStore` is not edited** (WP-0.1 owns it): the static validator reaches the RAG store through `DocumentIndexLookup.fromContainer()`, only when `index.rag` is set; without a container the save is refused, never passed.

### Docs

- [RAG → Document index](../rag.md#document-index): the flag, eligibility, every refusal, the chunk tags.
- [Configuration reference](../configuration-reference.md#documents): `eddi.documents.index.default-rag`.

### What's next

WP-6.2 (`INDEX` stage, semantic `searchDocument`, chunk erasure and re-keying) makes `index.rag` live and calls `ingestPrepared` / `deleteByDocumentId`. Platform Operator text for "why can't this RAG configuration be attached to a workflow" is folded into the next UI package, per the plan.
