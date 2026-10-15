## 🐛 fix(rag): in-memory knowledge bases no longer silently lose their data (2026-10-12)

**Repo:** EDDI (`fix/rag-inmemory-store-eviction`)

### What was wrong

`EmbeddingStoreFactory` kept every vector store in one Caffeine cache: 50 entries, a 30-minute idle
TTL, and an `invalidateAll()` on any vault-secret or global-variable change. For an external store
(pgvector, Qdrant, …) eviction drops a client and costs a reconnect. For `storeType: "in-memory"`
the cached object *is* the data, so a knowledge base with no query for half an hour, a 51st
knowledge base, or a credential rotation anywhere in the deployment emptied it, and retrieval
returned no context rather than an error. A scheduled ingestion run did not refill it either: the
pipeline skips a page whose content hash is unchanged, and the hashes outlived the vectors. Only
"Purge state + Run now" recovered.

### What changed

- **Data and clients are kept apart.** In-memory stores live in their own registry in
  `EmbeddingStoreFactory`, with no TTL, no size eviction and no reaction to secret or variable
  changes. The registry is keyed exactly as knowledge-base isolation (#945) addresses every other
  store: the RAG configuration's id, plus the name for a knowledge base still in the 6.5.0 name
  layout (`inMemoryKey`). Two knowledge bases with one name never share an in-memory store, and
  deleting one cannot empty another. External stores stay in the evicting client cache, unchanged.
- **An entry is dropped only when the knowledge base goes, or its vectors stop being valid:** the
  last version is deleted (`RestRagStore.deleteRag`, same "no current version left" rule as the
  schedules); an update moves it to another key (a rename in the name layout, a switch to
  `storeNamespace: "id"`), to another `storeType`, or changes its embedding signature
  (`RestRagStore.updateRag`); or a write arrives under a different embedding signature
  (`getOrCreateForIngestion`, the safety net for imports and other write paths). Retrieval and
  removals (`getOrCreate`) never replace a store; `/ingest`, pipeline runs and scheduled runs write
  through `getOrCreateForIngestion`.
- **Embedding signature** (`embeddingSignature`): store type, provider, the model as
  `EmbeddingParameters.modelName` resolves it (`model` and its alias `modelName` are one setting),
  Azure `deploymentName`, the endpoint (`baseUrl`, or `endpoint`) and the vector size
  (`dimensions`/`outputDimensionality`). The endpoint counts because an OpenAI-compatible server
  decides for itself what a model name means; keys, timeouts, retries and task types do not.
- **Store epoch.** Every in-memory store gets an epoch at creation. Ingestion records the store
  fingerprint (`store-epoch=<uuid>`) per source in a new `rag_ingestion_sources` collection/table
  (`IIngestionStateStore.storeFingerprint` / `recordStoreFingerprint`, removed by `purgeSource`).
  At the start of a run, `IngestionPipeline.reconcileStoreFingerprint` compares it with the store
  the run writes to; on a mismatch it calls `invalidateContent` and records the new fingerprint, so
  every document is re-embedded and no conditional request is sent. After a restart a scheduled
  run therefore refills the store by itself. A preview reports the re-embedding and writes nothing.
- **Deployment-wide cap.** `eddi.rag.in-memory.max-segments` (default 50,000; `0` disables)
  bounds the segments all in-memory stores hold together, enforced in
  `CappedInMemoryEmbeddingStore` under one lock. A write past it throws
  `InMemoryStoreFullException` with an actionable message; an ingestion run stops and reports
  `FAILED` with that message. Nothing is evicted.
- **Replaced and dropped stores are retired.** A run takes its store at the start, so a store
  replaced (embedding signature changed) or dropped (delete, move) mid-run used to keep receiving
  that run's writes: an orphan nothing reads, outside the cap, which counts only registered stores.
  The factory now retires such a store; a later write throws `InMemoryStoreRetiredException` under
  the cap's lock, and the run stops `FAILED` saying the store was replaced or removed. The next run
  re-embeds into the current store, because its epoch no longer matches. (Raised in review.)

### Design decisions

- **A new version with the same store key and embedding signature keeps the data.** Versions are
  not part of the registry key: nothing else a version can change (chunking, retrieval settings,
  sources) invalidates stored vectors. A rotated embedding API key is not a new model.
- **Only writes replace a store whose embedding configuration differs.** An agent still pinned to
  an older knowledge base version keeps reading what is there; replacing on a read would let two
  live versions wipe each other's store in turn and re-embed on every run.
- **The fingerprint is per source, in its own row,** rather than a new field on every document row:
  it needs no change to `recordIngested` or `DocumentState`, and invalidating via the existing
  `invalidateContent` also clears the stored validators, so a 304 cannot skip a page the new store
  lacks. It is an opaque, named string so that a follow-up can append an embedding-model
  fingerprint (provider + model + dimension) to the same value.
- **Legacy state** (no fingerprint recorded) is "unknown → re-embed" only when the store has a
  fingerprint, i.e. only for in-memory stores. External stores report none and behave exactly as
  before.
- **The cap fails instead of evicting**, because eviction is the bug being fixed.

### What's next

- A durable built-in store, so the default `storeType` keeps its data across restarts.
- An embedding-model fingerprint in the same value, so changing the model of an external store
  also re-embeds instead of mixing vectors.

**Files:** [`EmbeddingStoreFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java),
[`CappedInMemoryEmbeddingStore.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CappedInMemoryEmbeddingStore.java),
[`InMemoryStoreFullException.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/InMemoryStoreFullException.java),
[`IngestionPipeline.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`IIngestionStateStore.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java),
[`MongoIngestionStateStore.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/mongo/MongoIngestionStateStore.java),
[`PostgresIngestionStateStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresIngestionStateStore.java),
[`RestRagStore.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagStore.java),
[`RagIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`rag.md`](../rag.md#vector-stores), [`configuration-reference.md`](../configuration-reference.md#knowledge-bases-rag),
Platform Operator prompt (revision bump).

```decision-log
| 2026-10-12 | In-memory vector stores live in their own never-evicting registry keyed by knowledge base; an ingestion run re-embeds everything when the store's epoch differs from the one recorded with the source's state | In-memory KBs emptied after 30 idle minutes or any secret rotation, and unchanged content hashes stopped scheduled runs from refilling them | Keeping them in the client cache with a longer TTL; a per-document epoch column (wider schema change for the same effect); evicting the largest KB when a memory cap is hit |
```
