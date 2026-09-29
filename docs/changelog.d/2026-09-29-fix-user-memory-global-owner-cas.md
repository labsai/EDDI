## 🔒 fix(memory): claim a global memory key atomically, so a racing agent cannot overwrite it (2026-09-29)

**Repo:** EDDI (`fix/user-memory-global-owner-cas`)

### What changed and why

When `allowGlobalKeyOverwrite` is off, the `rememberFact` tool must not overwrite a
`global` memory owned by another agent. It checked ownership with a read and then
wrote with a plain upsert. Those were two steps. Two agents of the same user, both
allowed `global` visibility, could each read the key as free and each write it. The
store kept the first agent as owner but applied the second agent's value, so one
agent silently overwrote a memory the other owned (CWE-367). This was a CodeRabbit
finding on #860 and was deferred there.

- **`IUserMemoryStore.upsertIfOwnedBy(entry, agentId)`** writes a global entry only
  if its key is free or already owned by `agentId`, and returns whether it applied.
  The store makes that decision inside the write itself. An existing entry with no
  recorded owner (null or blank) counts as not owned.
- **PostgreSQL:** one `INSERT … ON CONFLICT (user_id, key) WHERE visibility =
  'global' DO UPDATE … WHERE usermemories.source_agent_id = EXCLUDED.source_agent_id
  RETURNING id`. A conflicting insert waits on the unique `idx_um_upsert_global`
  index and then evaluates the owner condition against the committed row. No
  returned row means the write was refused.
- **MongoDB:** the store now builds a **unique partial index on `(userId, key)` for
  global entries** (`idx_um_upsert_global`). Mongo had no such index before, so
  racing upserts could also create two global entries for one key.
  - `upsertIfOwnedBy` is an upsert filtered on the writer as owner. If another
    agent holds the key, the upsert attempts a second insert and the index rejects
    it with a duplicate-key error. That error is reported as a refusal.
  - A plain upsert that loses the same insert race retries once as an update.
  - `insertIfAbsent` that loses the race now returns `null` ("already there"), not a
    duplicate-key error.
  - If the index cannot be built, because a deployment already holds duplicate
    global keys, the store logs a WARN and falls back to a non-atomic check.
- **`UserMemoryTool.rememberFact`** uses the conditional write for global writes
  when `allowGlobalKeyOverwrite` is off. It returns the existing "owned by another
  agent" refusal when the write did not apply, and a refused write does not count
  against `maxWritesPerTurn`.
  - The early ownership read stays in place, only so that a doomed write does not
    trigger capacity eviction.
  - A global write with no agent id is now refused, since there is no owner to
    write as.
- **Unchanged:** REST, MCP, admin, property and `allowGlobalKeyOverwrite=true`
  writes keep the plain `upsert` semantics.

### Tests

- `UserMemoryToolTest`:
  - the conditional write is used for owned global writes and not for self writes
    or overwrite-allowed writes;
  - a lost race is refused;
  - a refused write leaves the turn budget intact;
  - a global write with no agent id is refused.
- `PostgresUserMemoryStoreUnitTest` and `configs/properties/mongo/MongoUserMemoryStoreTest`
  (mocked):
  - the conditional SQL and filter shape;
  - no returned row, or a duplicate key, is a refusal;
  - other write errors propagate;
  - the index options;
  - the no-index fallback;
  - the plain upsert's duplicate-key retry.
- `datastore/postgres/PostgresUserMemoryStoreTest` and `datastore/mongo/MongoUserMemoryStoreTest`
  (Testcontainers):
  - free, own, other-agent, ownerless and blank-owner keys;
  - 8 agents racing for a fresh key over 10 rounds, with exactly one winner whose
    value and ownership stick.
- The Mongo IT also checks that the index exists and that 8 concurrent plain
  upserts leave one entry.

**Files:** [`IUserMemoryStore.java`](../../src/main/java/ai/labs/eddi/configs/properties/IUserMemoryStore.java),
[`PostgresUserMemoryStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresUserMemoryStore.java),
[`MongoUserMemoryStore.java`](../../src/main/java/ai/labs/eddi/configs/properties/mongo/MongoUserMemoryStore.java),
[`UserMemoryTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UserMemoryTool.java)

```decision-log
| 2026-09-29 | Global-key ownership for the rememberFact tool is decided by an owner-conditional write in the store (ON CONFLICT … WHERE on PostgreSQL; an owner-filtered upsert against a new unique partial index on MongoDB) | Read-then-upsert let two agents both see a key as free and the later value win (CodeRabbit on #860, CWE-367) | Serialising writes per user in the tool (single-node only); a transaction around read and write (not available on standalone Mongo) |
```
