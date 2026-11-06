## 🐛 fix(a2a): shard the per-principal task index; cache removal reports the cluster result (2026-11-06)

**Repo:** EDDI (`fix/a2a-index-shards`)

### Why

- **The A2A index outgrew a NATS message.** `CachedA2ATaskStore` kept one index value per
  principal: up to 10,000 task ids and 10,000 context ids, every one of them caller-chosen and
  up to 256 characters. At that size the value is about 5 MB, and NATS refuses anything over its
  payload limit (1 MB by default). The shared cache then kept the index on the writing node only,
  so a GDPR erasure or export that ran on another node no longer reached the records of that
  principal. Every save also rewrote the whole value with a compare-and-set, so a principal with
  many writers fought over one key.
- **Erasure on a node that did not write the record counted 0.** `SharedKvCache.remove` answered
  with the node-local mirror's value. A node that never wrote the key deleted it from the bucket
  but answered null, and the `a2aTasks` entry of the GDPR erasure result is the sum of those
  answers. The existing two-node test modelled both nodes over one plain map, so it could not
  see this.
- **`ICache` views could change entries that nobody else heard about.** A removal through
  `keySet()`, `values()` or `entrySet()` of `ClusterInvalidatingCache` changed the local copy
  without the eviction announcement every other mutation makes. Through `SharedKvCache` it
  skipped the bucket delete. `CacheImpl.put` and `remove` read first and wrote second, so a write
  that landed in between was replaced while the caller was told about the value before it. No
  caller relied on either yet.

### What changed

- **`CachedA2ATaskStore`:** the index is 16 values per principal. The key is
  `principalPrefix(principal) + "#" + shard`, and `floorMod(id.hashCode(), 16)` picks the shard
  for each task id and each context id. Each shard keeps at most 625 ids per list, oldest dropped
  first, which is about 330 KB at the 256-character id limit. A principal still holds at most
  10,000 of each. A save updates one shard. A sweep or export reads all 16 shards, plus the bare
  principal key that the previous release wrote. Nodes of that release write it during a rolling
  upgrade, and it lives for `eddi.a2a.task-ttl` after that. The Javadoc of `indexKeysOf` says to
  drop that key one release later. The value class is unchanged, so `SharedKvCache`'s value
  allow-list is unchanged.
- **`SharedKvCache.remove`:** reads the bucket's value before the delete and answers with it, or
  with the node-local copy when the bucket holds none (or is unreachable). That costs one extra
  bucket read per removal; removals are invalidations and erasures, not a hot path. `put`
  documents that it always answers null.
- **Read-only views:** `CacheImpl`, `ClusterInvalidatingCache` and `SharedKvCache` return
  `keySet()`, `values()` and `entrySet()` through `Collections.unmodifiableMap`, which also
  refuses `Entry.setValue`. `ICache`'s new class Javadoc states the contract. `CacheImpl.put` and
  `remove` delegate to Caffeine's atomic `asMap()` operations, so the casts and their
  `@SuppressWarnings` are gone.
- **Docs:** `clustering.md` describes the shards of `A2A_INDEX`, the erasure count, and the read
  of the previous release's index value.

### Design decisions

- **16 shards chosen by hash, not one value per id.** A key per id would make an export read
  thousands of keys and a sweep list keys the bucket cannot enumerate. With 16 shards, an export
  reads 17 keys and a shard stays about a third of the payload limit. Eviction is FIFO within a
  shard. Ids are spread by hash, so the first evictions start somewhat before 10,000 ids (around
  9,400 for evenly spread hashes); the documented bound is an upper bound and stays true.
- **The previous release's index value is still read, not reset.** In a rolling upgrade, old
  nodes keep writing the single value. Ignoring it would make an erasure on a new node miss
  their records until the TTL ran out.
- **Read-only views, not views that announce.** No caller changes a cache through a view, and
  announcing views would mean reimplementing `Set`, `Collection` and `Iterator` for both cluster
  wrappers. `CacheImpl` refuses too, so a one-node deployment fails exactly where a cluster
  would.

### Tests

`CachedA2ATaskStoreTest` (a 10,000-task principal with 256-character ids keeps every index value
under 512 KB; two nodes over `SharedKvCache` + `InMemorySharedKv` erase and count each other's
records; the previous release's index is reached; per-shard FIFO cap; total cap; concurrent
writers), `SharedKvCacheTest` (cross-node remove answer, degraded remove, read-only views),
`CacheImplTest` (put and remove report what they really replaced; read-only views),
`ClusterInvalidatingCacheTest` (read-only views, only the put announced).

**Files:** [`CachedA2ATaskStore.java`](../../src/main/java/ai/labs/eddi/engine/a2a/CachedA2ATaskStore.java),
[`SharedKvCache.java`](../../src/main/java/ai/labs/eddi/engine/caching/SharedKvCache.java),
[`CacheImpl.java`](../../src/main/java/ai/labs/eddi/engine/caching/CacheImpl.java),
[`ClusterInvalidatingCache.java`](../../src/main/java/ai/labs/eddi/engine/caching/ClusterInvalidatingCache.java),
[`ICache.java`](../../src/main/java/ai/labs/eddi/engine/caching/ICache.java),
[`clustering.md`](../clustering.md)
