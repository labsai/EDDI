## 🐛 fix(rag): storage collision check refuses on unreadable neighbours; cluster-shared ingestion status (2026-11-08)

**Repo:** EDDI (`fix/rag-store-guards`)

### What changed and why

**The explicit-location collision check now fails closed when another knowledge base cannot be read.**
`KnowledgeBaseStorageGuard` refuses an explicit `table` / `collectionName` / `indexName` that another
knowledge base already uses unless the caller may edit that one too. It already failed closed when the
scan ran past its page budget, but a neighbour whose document could not be read (a `ResourceStoreException`
or any runtime error while loading it) was logged at debug level and skipped. The neighbour that cannot be
read may be the very one that owns the location, so a transient read error let an editor point their
knowledge base at another knowledge base's table. Such a save now fails with the same `IllegalStateException`
the descriptor-listing failure already raised (a 500), naming the knowledge base that could not be read. A
neighbour that is gone (`ResourceNotFoundException`, deleted since it was listed) still owns no location and
is still skipped.

**Ingestion status is shared across a cluster.** `RagIngestionService` kept the status and warning of each
`POST /ragstore/rags/{id}/ingest` in two private Caffeine caches, so with `eddi.messaging.type=nats` a poll of
`GET …/ingestion/{ingestionId}/status` that a load balancer sent to another node answered 404 "no ingestion
with this id is known" for an ingestion that was running or done. Both now live in one cache from
`ICacheFactory`, `rag-ingestion-status` (one-hour TTL, sized 20,000: a status and at most one warning per
ingestion), which `CacheFactory.SHARED_BUCKETS` maps to the new `RAG_INGESTION` KV bucket. A single node
behaves exactly as before.

### Design decisions

- **One cache and one bucket, two key kinds.** The status is kept under `status:` plus the ingestion id
  and the warning under `warning:` plus the id, so neither can overwrite the other in the bucket, and an id
  polled as `warning:<id>` finds no status. Two caches in two buckets would add a JetStream stream for a
  handful of strings per ingestion.
- **A status is bound to its knowledge base.** The status endpoint checks access on the RAG configuration
  in its path, but an ingestion id is global, so an editor of knowledge base B holding an id of A could poll
  it through B and read A's document name or failure message (on any node, once the cache is shared). Both
  keys now carry the RAG configuration id (`status:<ragConfigId>:<id>`), so an id polled through another
  knowledge base is unknown (404). No extra NATS writes.
- **Not retried after a NATS outage.** If NATS is unreachable while the terminal status is written, that
  write stays on the originating node, like every other non-fail-closed shared cache (`SharedKvCache`
  falls back to the node-local copy); there is no retry mechanism and none is added here.
- **500, not 400, for an unreadable neighbour.** The request is not wrong; the server could not check it.
  It matches the listing-failure path right beside it, and a retry succeeds once the store answers.
- **Not in this PR:** serialising the check and the write (two editors saving the same new location at the
  same moment both pass). A per-JVM lock does not serialise a cluster, the import and upgrade paths write
  outside `RestRagStore`, and in the per-id layout, which every new knowledge base gets, chunks in a shared
  table are still filtered by knowledge-base id (a knowledge base still in the 6.5.0 name layout is not
  filtered, so for one of those the race is a real read). Injecting the
  guard into `RestRagStore` instead of constructing it is only a step toward that lock, so it waits for it.
  The replace locks in `RagIngestionService` stay per instance, as their Javadoc says.

**Files:** [`KnowledgeBaseStorageGuard.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/KnowledgeBaseStorageGuard.java),
[`RagIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`CacheFactory.java`](../../src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java),
[`rag.md`](../rag.md), [`clustering.md`](../clustering.md); tests in `KnowledgeBaseStorageGuardTest`,
`RagIngestionServiceTest` and `CacheFactoryTest`.
