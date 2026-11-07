## 🐛 fix(rag): coordinate document replacement across instances sharing a vector store (2026-11-07)

**Repo:** EDDI (`fix/1312-rag-cluster-lease`) — issue [#1312](https://github.com/labsai/EDDI/issues/1312)

### What changed and why

`RagIngestionService` serialized a replacing ingestion (`replace=true`) with an instance-local
`ReentrantLock`. Two instances sharing one vector store (pgvector, Chroma, …) could therefore
replace the same document at once: both store, then each cleanup removes the other's new chunks
and the document is left with none. The delete endpoint of PR #1299 takes the same lock, and so
inherited the same scope (a delete answered "deleted" while another instance was still embedding a
replacement that then wrote the text back; or a delete between a replacement's store and its
cleanup removed the new chunks too). Ingestion status and warnings were per-instance Caffeine, so
a status poll that reached another instance answered `404 unknown`.

- **Cluster-wide document lease.** A replacement now runs under `underDocumentLease(...)`, which
  takes the instance-local lock (the whole exclusion in in-memory mode, and what keeps one
  instance from asking the lease manager twice for one key) and then the document's lease from
  `IConversationLeaseManager` under a new `rag.` key prefix (`rag.h_<sha256 of config|kb|name>`,
  so the name never lands in a key in clear). It is held from before embedding until the previous
  version is removed, released in `finally`, and renewed by the lease manager's heartbeat for
  however long embedding takes. A lease granted after the caller stopped waiting is released when
  it arrives.
- **Fencing.** A vector store cannot check a fencing token, so the ingestor was split into
  split → embed → *check lease* → `addAll` → *check lease* → `removeAll`. A lease is reported lost
  while there is still margin before another node can take it over (`ttl − 2×heartbeat`), so a
  write is only started on a lease still trusted. A replacement that lost its lease fails with
  `failed: This instance lost the lease …`; lost after storing, it also carries the existing
  both-versions-may-remain warning.
- **Status and warnings are shared.** They moved from private Caffeine caches to
  `ICacheFactory` caches `rag-ingestion-status` / `rag-ingestion-warnings`, registered in
  `CacheFactory.SHARED_BUCKETS` as `RAG_STATUS` / `RAG_WARNINGS` (one-hour TTL, 10,000 entries
  locally). Any instance answers a poll; while NATS is down an instance falls back to its own
  copy.
- **Wait bounds.** A replacement waits up to 30 minutes for the lease (`replaceLeaseWait`) and then
  fails with a status naming the cause. `underDocumentLease(..., maxWait, ...)` throws
  `LeaseUnavailableException` (`TIMEOUT`, `DEGRADED`, `SHUTTING_DOWN`, `DRAINING`) without running
  the work, which is what a delete maps to its bounded-wait `BUSY` answer.

### Design decisions

- **The existing lease manager, not a new database primitive.** Multi-instance EDDI is NATS mode
  (`docs/clustering.md`: replicas need `eddi.messaging.type=nats`), and `IConversationLeaseManager`
  already offers arbitrary keys (`g.`, `leader.`) with expiry, heartbeat renewal, fast takeover,
  fencing revisions and drain/shutdown handling. A MongoDB/PostgreSQL lease would have been a second
  lease system for the same deployments (AGENTS.md §4.7, unification over duplication). In in-memory
  mode the `LocalLeaseManager` grants at once and the local lock does the work, exactly as before.
- **Statuses in NATS KV rather than the database**, through the same shared-cache mechanism nonces
  and A2A task mappings use: they are one-hour operational state, not records, and the buckets
  expire them server-side.
- **Appending (`replace=false`) takes no lease** — it removes nothing, so it cannot undo another
  operation's work.
- Degraded mode follows `eddi.cluster.degraded.turns`, like every other lease holder.

### For PR #1299 (document delete)

#1299 is still open and is not on `main`. When it lands, its `delete` should run its `removeAll`
inside `underDocumentLease(ragConfigId, kbId, documentName, deleteLockTimeout, lease -> ...)`
instead of `documentLock(...).tryLock(...)`, and map `LeaseUnavailableException` to
`DeleteOutcome.BUSY`. `RagDocumentLeaseIT` and `RagIngestionServiceClusterTest` already exercise a
delete-shaped `removeAll` under the lease from a second instance.

### Tests

- `RagIngestionServiceClusterTest` — two service instances on a shared lease manager, store and
  caches: a delete on B waits for A's replacement and then removes it; a short delete wait gives
  up with `TIMEOUT` without running; replacements on two instances leave one version; a lost lease
  stores nothing, and lost before the cleanup skips it with a warning; the lease is released on
  failure; an unavailable lease fails the replacement; appending takes no lease; the key hashes
  the name; B reads A's status and warning.
- `RagDocumentLeaseIT` — the same against a real NATS server with two `KvLeaseManager`s: B's
  delete waits out A's replacement embedding for twice the lease TTL (heartbeat renewal), times
  out under a short wait, and an instance cut off from NATS loses its lease and does not write
  after the other instance took the document.
- `CacheFactoryTest` binds the service's cache names to the shared buckets.

**Files:** [`RagIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`IConversationLeaseManager.java`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/IConversationLeaseManager.java),
[`CacheFactory.java`](../../src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java),
[`rag.md`](../rag.md), [`clustering.md`](../clustering.md)

```decision-log
| 2026-11-07 | RAG document replace/delete coordinate through `IConversationLeaseManager` (`rag.` prefix); ingestion statuses go through the shared `RAG_STATUS`/`RAG_WARNINGS` buckets | Instances sharing a vector store raced on replace/delete (#1312) | A new keyed lease on MongoDB/PostgreSQL — rejected: multi-instance EDDI is NATS mode and the NATS lease already has expiry, renewal, fencing and drain handling |
```
