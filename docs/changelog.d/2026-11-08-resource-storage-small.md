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

**Files:** [`MongoResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorage.java),
[`HistorizedResourceStore.java`](../../src/main/java/ai/labs/eddi/datastore/HistorizedResourceStore.java),
[`MongoResourceStorageTest.java`](../../src/test/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageTest.java),
[`MongoResourceStorageBranchTest.java`](../../src/test/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageBranchTest.java),
[`HistorizedResourceStoreTest.java`](../../src/test/java/ai/labs/eddi/datastore/HistorizedResourceStoreTest.java)
