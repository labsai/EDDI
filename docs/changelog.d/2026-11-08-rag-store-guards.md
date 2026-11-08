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

**A value written while NATS was down no longer reads back as the older shared value, and never overwrites a newer one.** `SharedKvCache.get()` only consulted the node-local copy when the bucket answered empty, so a key written `processing` with NATS up and `completed` during an outage kept reading `processing` after recovery, even on the writing node. A degraded write now remembers the bucket revision this node last saw for the key (or that the key was absent, or that it is unknown), and the next read of the key publishes the local value **by compare-and-set** against that revision (`update`, or `create` for an absent key). If another node wrote or deleted the key meanwhile, the bucket's state wins, the marker and the stale local copy are dropped and nothing is overwritten or resurrected; an unknown revision yields to a present bucket value and creates an absent one. A value the bucket refuses as too large (`ClusterValueRefusedException`, a subclass of `ClusterUnavailableException` thrown by `NatsSharedKv` for a payload or message size refusal) is tracked apart and never retried, so the read path costs no failed network write: it behaves as before, answering from the local copy only while the bucket holds nothing. Republishing starts a new bucket TTL (the KV API cannot preserve the original expiry), so a status written during an outage can stay readable for up to about twice its TTL after the last update; the writing node's own copy expires after one TTL, so nothing older is ever republished. Applies to all shared caches.

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
- **No retry timer after a NATS outage.** If NATS is unreachable while the terminal status is written, that write stays on the originating node, like every other non-fail-closed shared cache. It is published when that node next reads the key (compare-and-set, see above); no background retry is added here.
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
[`SharedKvCache.java`](../../src/main/java/ai/labs/eddi/engine/caching/SharedKvCache.java),
[`ClusterValueRefusedException.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterValueRefusedException.java),
[`rag.md`](../rag.md), [`clustering.md`](../clustering.md); tests in `KnowledgeBaseStorageGuardTest`,
`RagIngestionServiceTest`, `SharedKvCacheTest` and `CacheFactoryTest`.
