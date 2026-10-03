## 🔒 fix(rag): every knowledge base gets a store of its own, and `openai` embeddings honour `baseUrl` (2026-10-02)

**Repo:** EDDI (`fix/rag-kb-isolation`)

### What changed and why

Two High findings of the 6.5.0 review, both reproduced live before the fix.

**Cross-knowledge-base poisoning.** `POST /ragstore/rags/{ownKb}/ingest?kbId=<other KB's name>`
checked EDIT on the knowledge base in the path and then wrote into the store the `kbId` named —
another knowledge base's; with `replace=true` it deleted that one's documents. Stores were keyed by
the knowledge base's *name* (cache key, default pgvector table / Atlas collection / ES index /
Qdrant and Chroma collection), names are not unique (`duplicateRag` copies them), and retrieval read
by name with no filter, so two knowledge bases with one name shared one store, and an explicit
`table`/`collectionName`/`indexName` could point at anyone's.

- The store is now addressed by the RAG configuration's **id**, everywhere: `EmbeddingStoreFactory.getOrCreate(ragConfigId, config)`
  replaces `getOrCreate(config, kbId)`, and ingestion, ingestion sources (`IngestionPipeline`) and
  retrieval (`RagContextProvider`, which takes the id from the workflow step's URI) all pass it.
- **`kbId` no longer selects anything.** It is accepted only when it names the path's knowledge base
  (its id, or its name — the old default); anything else is a `400` and nothing is ingested.
- **Two layouts, chosen by the new `storeNamespace` field** ([`KnowledgeBaseStorage`](../../src/main/java/ai/labs/eddi/configs/rag/model/KnowledgeBaseStorage.java)):
  `"id"` — default location `eddi_kbid_<id>`, chunks tagged `kbId=<id>`, retrieval filtered on it —
  for every knowledge base created, duplicated or imported from now on; absent/`"name"` — the 6.5.0
  layout, reproduced byte for byte per store type, so an upgraded knowledge base keeps reading the
  pgvector table it was written to. Retrieval is not filtered there, because 6.5.0 chunks carry no id.
- **Write-time rules** ([`KnowledgeBaseStorageGuard`](../../src/main/java/ai/labs/eddi/configs/rag/rest/KnowledgeBaseStorageGuard.java),
  used by `RestRagStore` create/update/duplicate and by the import's create path): a new KB gets
  `"id"` (`"name"` on create is a 400); an update without the field keeps the stored one; `"id"` →
  `"name"` is refused; renaming a 6.5.0 KB moves it to `"id"` (in 6.5.0 a rename already moved it to a
  new store — the one named after the new name, possibly someone else's); an explicit location may
  not start with `eddi_kb` and may only equal another KB's if the caller may EDIT that one too.
  Source ingestion state is now cleared when the *location* changes rather than on every rename, so
  renaming an `"id"` KB no longer forces a full re-crawl.
- Replace-by-document-name deletes only chunks tagged with the ingesting KB's own `kbId`, and the
  pipeline tags every crawled/uploaded chunk with it.

**`openai` embeddings ignored `baseUrl`.** `buildOpenAi` passed only `model` and `apiKey`, so a
knowledge base configured for a private OpenAI-compatible endpoint sent its documents to
api.openai.com. Every provider was audited against its langchain4j builder
([`EmbeddingParameters`](../../src/main/java/ai/labs/eddi/configs/rag/model/EmbeddingParameters.java)):
`openai` now honours `baseUrl`, `timeout` (ms), `organizationId`, `projectId`, `dimensions`,
`maxRetries`; `mistral`, `cohere` and `gemini` honour `baseUrl` and `timeout`; Azure `endpoint`,
Vertex `endpoint`, Bedrock `dimensions` and `maxRetries` where the builder has it. `modelName` is an
alias of `model` everywhere (a conflict is a 400). An endpoint-like parameter a provider cannot honour
is refused at save time and again at build time, never silently dropped; other unknown parameters are
logged. Vault references in embedding parameters resolve as before (verified live).

Docs: `rag.md` — new "Knowledge base isolation" section with the upgrade/migration path, per-provider
parameter table, `kbId` semantics, the Atlas `metadata.kbId` filter-field requirement, and the
non-upload body limit (about 27.7 MB with default attachment settings, not 25 MB). The Platform
Operator's knowledge-base section explains layouts, the empty-after-rename case and the endpoint rule
(revision bump). The Manager's RAG editor suggests `baseUrl` for `openai`.

### Design decisions

- **Mode field rather than a data migration.** Rewriting stored configs or moving vectors at startup
  would touch customer data stores EDDI does not own (external pgvector, Atlas, ES). An absent field
  means "as 6.5.0", which keeps every existing deployment working with no action; isolation for the
  old layout is one save away.
- **Filter at retrieval only in the `"id"` layout.** Filtering legacy KBs would hide every chunk 6.5.0
  wrote.
- **Collision check by store type + name, not host.** Raw connection strings cannot be compared
  reliably (`localhost` vs `127.0.0.1`, vault references); a false positive only asks for EDIT on both.

**Files:** [`KnowledgeBaseStorage.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/KnowledgeBaseStorage.java),
[`KnowledgeBaseStorageGuard.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/KnowledgeBaseStorageGuard.java),
[`EmbeddingParameters.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/EmbeddingParameters.java),
[`EmbeddingStoreFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java),
[`EmbeddingModelFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingModelFactory.java),
[`RagContextProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/RagContextProvider.java),
[`RagIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`RestRagStore.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagStore.java),
[`IngestionPipeline.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`rag.md`](../rag.md).

```decision-log
| 2026-10-02 | RAG stores are addressed by the config id; a `storeNamespace` field keeps 6.5.0 KBs on their name-derived store until renamed or switched | Cross-KB poisoning via `?kbId=` and same-name KBs sharing a store | A startup migration moving vectors (EDDI does not own external stores); filtering legacy KBs (would hide all 6.5.0 chunks) |
```
