## fix(datastore): Mongo id queries fetch only id and version; deletes lock per id instead of per store (2026-11-08)

**Repo:** EDDI (`fix/resource-storage-small`)

### What changed and why

- **MongoDB id queries project `_id` and `_version` only.**
  `MongoResourceStorage.findResources` returns `(id, version)` pairs. The
  listings read the documents again by id (descriptor listings through
  `readMany`, the group conversation and shared artifact listings through
  `read`), and the GDPR erasure loops only need the ids. Without a projection,
  every match crossed the wire and was decoded for nothing, whole transcripts
  and artifact bodies included, and a listed one was decoded twice. `findResources`,
  `findResourceIdsContaining` and `getCurrentVersion` now project
  `{_version: 1}` (`_id` comes back unless excluded), and
  `findHistoryResourceIdsContaining` projects `{_id: 1}`, the composite id that
  carries the version. `getCurrentVersion` is behind
  `HistorizedResourceStore.getCurrentResourceId`, which has about 150 callers.
  Filtering and sorting still run on the server over the full documents. The
  PostgreSQL twin already selected only these columns.
- **`readHistoryLatest` makes one round trip instead of two.** It ran
  `countDocuments` and then the same filter as a sorted `limit(1)` find. The
  find returns null on its own when there is no history row, so the count is
  gone. The history document is still read in full, because callers of
  `IHistoryResource` can call `getData()`.
- **`HistorizedResourceStore` locks per resource id instead of per store.**
  `delete` and `deleteAllPermanently` were `synchronized` on the store, and
  there is one store instance per resource type, so on one node every delete of
  that type (for example the GDPR erasure loop over conversation descriptors)
  waited for the one in progress, which held the monitor across several
  majority-acknowledged round trips. The monitor was not useless, though: on
  MongoDB without a transaction it was the only thing on a node that kept a
  `delete` from reading a resource, losing the CPU while `deleteAllPermanently`
  erased it, and then upserting its tombstone (the erased data) into the emptied
  history after the erasure had reported success. It did not cover the same race
  through `update`, which archives the predecessor before its version-checked
  replace and was never locked. Now `update`, `delete` and
  `deleteAllPermanently` take one of 64 `ReentrantLock` stripes, chosen by
  `Math.floorMod(id.hashCode(), 64)`, for their whole read-then-write sequence.
  Same-id sequences on a node run one after the other, different ids contend
  only when they share a stripe, and each call holds one stripe and calls only
  the storage under it, so stripes are never nested. **What it does not
  guarantee:** the lock is per JVM, so a delete or update on one node and an
  erasure of the same id on another can still meet on MongoDB, exactly as they
  could under `synchronized`; `ModifiableHistorizedResourceStore.set` is not
  locked (its writes are version-checked replaces with no upsert, so they cannot
  write into an erased id); and stores that call `IResourceStorage.removeAllPermanently`
  directly (group conversations, shared artifacts, workspaces, agent test runs)
  bypass it. Two deletes of the same id, and update against delete, are decided
  by the storage's version checks either way.

### Design decisions

- `readHistoryLatest` keeps reading the full document (no projection), since
  the history resource it returns is a data accessor.
- A striped lock rather than no lock or a map of per-id locks: removing the
  monitor outright reopened the MongoDB erasure race on a single node, and a
  `ConcurrentHashMap` of locks grows with every id ever touched. 64 stripes are
  a fixed 64 locks per store.
- **PostgreSQL `storeHistoryAndRemove` now deletes first and writes the tombstone
  only if the delete removed the row.** The old order (tombstone, then delete)
  let a `deleteAllPermanently` that finished between a delete's read and its
  write be undone: the tombstone was upserted over the erased id, and the
  "no live row, a concurrent delete won" branch committed it, leaving the erased
  resource's data in `resources_history`. The monitor used to prevent that on
  one node, and the per-id lock still does; ordering the statements prevents it
  on every node, because the
  delete takes the row lock (an erasure waits for it and then also deletes the
  tombstone, or it has run first and the delete writes nothing). The same
  reordering means a refused delete (an update moved the resource on) never
  writes a tombstone to roll back.
- **MongoDB keeps tombstone-then-delete and has a known residual window.**
  Without a transaction the tombstone write and the check that the row still
  exists cannot be one step, and "row gone" cannot tell a concurrent delete from
  an erasure. The `MongoResourceStorage.storeHistoryAndRemove` Javadoc states
  the limit; on one node the per-id lock closes it, across nodes it stays open,
  and a repeated erasure removes the orphan row.

**Files:** [`MongoResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorage.java),
[`PostgresResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorage.java),
[`HistorizedResourceStore.java`](../../src/main/java/ai/labs/eddi/datastore/HistorizedResourceStore.java),
[`PostgresResourceStorageTest.java`](../../src/test/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorageTest.java),
[`MongoResourceStorageTest.java`](../../src/test/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageTest.java),
[`MongoResourceStorageBranchTest.java`](../../src/test/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageBranchTest.java),
[`HistorizedResourceStoreTest.java`](../../src/test/java/ai/labs/eddi/datastore/HistorizedResourceStoreTest.java)
