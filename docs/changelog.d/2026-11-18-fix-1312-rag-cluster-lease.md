## 🐛 fix(rag): coordinate document replace and delete across instances sharing a vector store (2026-11-18)

**Repo:** EDDI (`fix/1312-rag-cluster-lease`) — issue [#1312](https://github.com/labsai/EDDI/issues/1312)

### What changed and why

`RagIngestionService` serialized a replacing ingestion (`replace=true`) and the document delete
([#1299](https://github.com/labsai/EDDI/pull/1299)) with one instance-local `ReentrantLock`. Two
instances sharing one vector store (pgvector, Chroma, …) could therefore replace the same document
at once: both store, then each cleanup removes the other's new chunks and the document is left with
none. A delete answered "deleted" on one instance while another was still embedding a replacement
that then wrote the text back, or landed between a replacement's store and its cleanup and took the
new chunks too. (Ingestion status was the third per-instance piece; it moved to the shared
`RAG_INGESTION` bucket in [#1359](https://github.com/labsai/EDDI/pull/1359) and is not touched
here.)

- **Cluster-wide document lease.** A replacement **and a delete** now run under
  `underDocumentLease(...)`, which takes the instance-local lock (the whole exclusion in in-memory
  mode, and what keeps one instance from asking the lease manager twice for one key) and then the
  document's lease from `IConversationLeaseManager` under a new `rag.` key prefix
  (`rag.h_<sha256 of config|kb|name>`, so the name never lands in a key in clear). A replacement
  holds it from before embedding until the previous version is removed; both release it in
  `finally`, and the lease manager's heartbeat renews it for however long embedding takes. A lease
  granted after the caller stopped waiting is released when it arrives.
- **Fencing.** A vector store cannot check a fencing token, so `EmbeddingStoreIngestor` was split
  into split → embed → *check lease* → `addAll` → *check lease* → `removeAll`. A lease is reported
  lost while there is still margin before another node can take it over (`ttl − 2×heartbeat`), so
  a write is only started on a lease still trusted. A replacement that lost its lease fails with
  `failed: This instance lost the lease …`; lost after storing, it also carries the existing
  both-versions-may-remain warning. A delete whose lease is already lost answers `BUSY`.
- **Delete keeps its bounded wait.** `delete(...)` waits `deleteLockTimeout` (30 s) for the lock
  and the lease together; `LeaseUnavailableException` — the document held elsewhere past the wait,
  NATS unreachable under `eddi.cluster.degraded.turns=reject`, a node shutting down or drained —
  answers `DeleteOutcome.BUSY` (`409`, nothing removed, retry). The `409` text now says "here or on
  another instance, or its cluster lease could not be taken".
- **Wait bounds for a replacement.** It waits up to 30 minutes (`replaceLeaseWait`) and then fails
  with a status naming the cause.

### Design decisions

- **The existing lease manager, not a new database primitive.** Multi-instance EDDI is NATS mode
  (`docs/clustering.md`: replicas need `eddi.messaging.type=nats`), and `IConversationLeaseManager`
  already offers arbitrary keys (`g.`, `leader.`) with expiry, heartbeat renewal, fast takeover,
  fencing revisions and drain/shutdown handling. A MongoDB/PostgreSQL lease would have been a second
  lease system for the same deployments (AGENTS.md §4.7, unification over duplication). In in-memory
  mode the `LocalLeaseManager` grants at once and the local lock does the work, exactly as before.
- **Appending (`replace=false`) takes no lease** — it removes nothing, so it cannot undo another
  operation's work. A delete therefore still cannot stop an append already in flight, as before.
- Degraded mode follows `eddi.cluster.degraded.turns`, like every other lease holder.
- The `RagIngestionService(models, stores, caches)` constructor stays for single-instance wiring
  and uses a `LocalLeaseManager`; the injected one takes the lease manager as a fourth argument.

### Tests

- `RagIngestionServiceClusterTest` — two service instances on a shared lease manager and store: a
  delete on B waits for A's replacement and then removes it; a short `deleteLockTimeout` gives
  `BUSY` without touching the store; a delete without a lease, or on a lost lease, answers `BUSY`
  and leaves the chunks; a delete releases its lease; replacements on two instances leave one
  version; a lost lease stores nothing, and lost before the cleanup skips it with a warning; the
  lease is released on failure; an unavailable lease fails the replacement; appending takes no
  lease; the key hashes the name; B reads A's warning.
- `RagDocumentLeaseIT` — the same against a real NATS server with two `KvLeaseManager`s: B's
  delete waits out A's replacement embedding for twice the lease TTL (heartbeat renewal), times
  out under a short wait, and an instance cut off from NATS loses its lease and does not write
  after the other instance took the document.

**Files:** [`RagIngestionService.java`](../../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`IConversationLeaseManager.java`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/IConversationLeaseManager.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`rag.md`](../rag.md), [`clustering.md`](../clustering.md)

```decision-log
| 2026-11-18 | RAG document replace and delete coordinate through `IConversationLeaseManager` (`rag.` prefix) on top of the instance-local lock | Instances sharing a vector store raced on replace/delete (#1312) | A new keyed lease on MongoDB/PostgreSQL — rejected: multi-instance EDDI is NATS mode and the NATS lease already has expiry, renewal, fencing and drain handling |
```
