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

Since this branch was opened, #893 landed the same global index
(`idx_um_upsert_global`) and a duplicate-key retry for its owner-checked writes, but
left two gaps: it could not be built over data that already holds duplicates (the store
then fell back to a non-atomic ownership check), and nothing enforced the per-agent
identity. This change keeps #893's write path as it is and closes both.

- **`UserMemoryIdentityIndexes`** (new) runs at startup, before the store's own global
  index build. While either index is missing it merges duplicates, then installs both
  partial unique indexes: `(userId, key)` filtered to `visibility: global` — the same
  name and spec #893 builds, so the two never conflict — and
  `(userId, key, sourceAgentId)` (`idx_um_upsert_agent`) filtered to
  `visibility: {$in: [self, group]}`; a partial filter accepts `$in` but not `$ne`.
- **Merge.** Per duplicated identity the entry with the newest `updatedAt` survives
  (compared as parsed instants: `Instant.toString()` has a variable-length fraction, so
  string order is wrong) and takes the summed `accessCount`. Once both indexes exist the
  scan is skipped. A duplicate that another node inserts between the merge and the build
  triggers one more pass. Any failure is logged and startup continues, so #893's
  `globalKeyUnique` fallback still applies exactly as before. With the merge in place
  that fallback becomes the exception rather than the rule for upgraded deployments.
- **No new write-path code.** The duplicate-key retry and the `insertIfAbsent`
  "already present" answer are #893's; this branch originally carried its own and
  dropped it in favour of that one on merge.

### Tests

`datastore/mongo/MongoUserMemoryStoreTest` (Testcontainers, `mongo:6.0`) gains a
`Unique upsert identities` group: eight writers racing for one identity, 25 rounds each,
for global upserts, per-agent upserts (self and group mixed), `mergeProperties` and
`insertIfAbsent`; the startup merge; and the indexes refusing a duplicate written around
the store. With the startup call removed, six of the seven fail. The class now rebuilds the store after each
collection drop, so all its tests run under the new indexes. The two mock-based store
tests stub the index listing (`IdentityIndexStubs`).
