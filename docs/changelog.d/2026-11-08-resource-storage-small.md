## fix(datastore): Mongo id queries fetch only id and version; deletes no longer serialize per store (2026-11-08)

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
- **`HistorizedResourceStore.delete` and `deleteAllPermanently` are no longer
  `synchronized`.** There is one store instance per resource type, so on one
  node every delete of that type (for example the GDPR erasure loop over
  conversation descriptors) waited for the one in progress, which held the
  monitor across several majority-acknowledged round trips. The lock did not
  protect anything: `update` and `set` were never locked, and other nodes do
  not share the monitor. The storage's version-checked writes already decide
  concurrent writers. The class Javadoc now says so.

### Design decisions

- `readHistoryLatest` keeps reading the full document (no projection), since
  the history resource it returns is a data accessor.
- No new locking replaces the monitor. Two deletes of the same id are already
  settled by `storeHistoryAndRemove`'s version-conditioned delete on MongoDB and
  by one transaction on PostgreSQL.
- **PostgreSQL `storeHistoryAndRemove` now deletes first and writes the tombstone
  only if the delete removed the row.** The old order (tombstone, then delete)
  let a `deleteAllPermanently` that finished between a delete's read and its
  write be undone: the tombstone was upserted over the erased id, and the
  "no live row, a concurrent delete won" branch committed it, leaving the erased
  resource's data in `resources_history`. The monitor used to prevent that on
  one node; ordering the statements prevents it on every node, because the
  delete takes the row lock (an erasure waits for it and then also deletes the
  tombstone, or it has run first and the delete writes nothing). The same
  reordering means a refused delete (an update moved the resource on) never
  writes a tombstone to roll back.
- **MongoDB keeps tombstone-then-delete and has a known residual window.**
  Without a transaction the tombstone write and the check that the row still
  exists cannot be one step, and "row gone" cannot tell a concurrent delete from
  an erasure. The `MongoResourceStorage.storeHistoryAndRemove` Javadoc states
  the limit; a repeated erasure removes the orphan row.

**Files:** [`MongoResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorage.java),
[`PostgresResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorage.java),
[`HistorizedResourceStore.java`](../../src/main/java/ai/labs/eddi/datastore/HistorizedResourceStore.java),
[`PostgresResourceStorageTest.java`](../../src/test/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorageTest.java),
[`MongoResourceStorageTest.java`](../../src/test/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageTest.java),
[`MongoResourceStorageBranchTest.java`](../../src/test/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageBranchTest.java),
[`HistorizedResourceStoreTest.java`](../../src/test/java/ai/labs/eddi/datastore/HistorizedResourceStoreTest.java)
