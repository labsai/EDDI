## 🐛 fix(memory): MongoDB `usermemories` enforces its upsert identities (2026-09-29)

**Repo:** EDDI (`fix/usermemories-unique-identity`) · closes #892

`MongoUserMemoryStore` keyed its upserts on two identities — one shared entry per
`(userId, key)` among `global` entries, one per `(userId, key, sourceAgentId)` among the
non-global ones — but no index enforced either. Two first writes racing for the same
identity both missed the filter and both inserted, and every later upsert then updated
whichever copy the server found first. That held for ordinary `upsert` and
`mergeProperties` writes and for `insertIfAbsent`, which the legacy
`PropertiesMigrationService` uses: #871 stopped it replacing newer values, but two nodes
migrating at once could still insert the same entry twice. PostgreSQL has enforced the
same identities since the store was written (`idx_um_upsert_global`,
`idx_um_upsert_agent`).

### What changed

- **`UserMemoryIdentityIndexes`** (new) installs two partial unique indexes with the
  PostgreSQL names: `(userId, key)` filtered to `visibility: global`, and
  `(userId, key, sourceAgentId)` filtered to `visibility: {$in: [self, group]}` — a
  partial filter accepts `$in` but not `$ne`. The store's constructor calls it.
- **Startup merge.** A deployment that already holds duplicates cannot build a unique
  index, so while either index is missing the pass groups each identity and keeps the
  entry with the newest `updatedAt` (compared as parsed instants: `Instant.toString()`
  has a variable-length fraction, so string order is wrong), summing `accessCount` across
  the group onto it. Once both indexes exist the scan is skipped. A duplicate that
  another node inserts between the merge and the build triggers one more pass. Any
  failure is logged and startup continues, without the guarantee, as before.
- **Race losers retry.** A duplicate-key error on `upsert`/`mergeProperties` is retried
  once, which turns the loser's insert into an update of the winner's entry. The server
  does not do this itself because neither identity filter is a plain equality on exactly
  the index keys. `insertIfAbsent` answers "already present" instead.

### Tests

`datastore/mongo/MongoUserMemoryStoreTest` (Testcontainers, `mongo:6.0`) gains a
`Unique upsert identities` group: eight writers racing for one identity, 25 rounds each,
for global upserts, per-agent upserts (self and group mixed), `mergeProperties` and
`insertIfAbsent`; the startup merge; and the indexes refusing a duplicate written around
the store. With the index call removed, six of the seven fail; with the retry removed,
the three upsert races fail on `E11000`. The class now rebuilds the store after each
collection drop, so all its tests run under the new indexes. The two mock-based store
tests stub the index listing (`IdentityIndexStubs`).
