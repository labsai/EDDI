## 🐛 fix(conversations): bound sparse-state conversation listings without truncating admin results (2026-11-07)

**Repo:** EDDI (`fix/931-sparse-state-listing-bound`) · Closes #931

### The problem

`GET /conversationstore/conversations?conversationState=…` could not push the state into its query. The state lives in the conversation memory, not the descriptor. So the listing paged through descriptors in batches and loaded a summary for each, then dropped the rows in another state. An admin (or any caller when authorization is disabled) had no bound at all. A sparse state therefore read the whole collection, and a deep page read it again for every row it skipped.

### What changed

- **The state is pushed into the query as a set of ids.** The listing reads the ids in the requested state off the memory store's state index. That is a new method, `IConversationMemoryStore.findConversationIdsByState(state, limit)`. Mongo uses a covered `{conversationState: 1, _id: 1}` index, created at startup, with a hint and an unhinted retry. PostgreSQL runs `SELECT id … WHERE conversation_state = ? LIMIT ?` on its existing state index. The listing then adds `QueryFilter.idIn(ids)` to the descriptor query: a new `IResourceFilter.IdIn` that Mongo maps to `_id $in` and PostgreSQL to `id = ANY(?::uuid[])`.
  - No conversation in the state: an empty page, without one descriptor read.
  - More than 10,000 ids (`MAX_STATE_ID_SET`, the storage layer's own result cap), or a failed id read: the state falls back to the per-row filter. A common state fills a page within a few batches anyway.
  - The per-row state check stays. The id set only narrows the query, so a conversation that left the state after its id was read is still excluded. One that entered it after the read is missed, which is ordinary point-in-time skew for a listing.
- **Admin listings now have an explicit bound instead of none.** A caller who sees every conversation may reject at most 10,000 descriptors (`MAX_REJECTED_SCAN`) per request. Rejected means unreadable, orphaned (descriptor without memory) or failing the per-row filters; skipped and returned rows do not count.
  - If the bound is reached with the page not full and more rows left, the request is refused with **422** and `{"error": "listing_scan_bound_exceeded", "message": …}`. The message gives the counts and how to narrow the request. The metric `eddi.conversations.listing.scan_bound_exceeded` counts these refusals.
  - It never answers with a silently short page.
  - Reaching the bound on the final, short batch is a complete answer, not a refusal.
- **Owner-scoped callers are unchanged.** They keep the existing 500-descriptor owner budget.
- **`agentVersion` is pushed into the agent pattern.** The pattern is `/<id>(\?(.*&)?version=N(&|$)|$)`, so other versions are not read and rejected one by one. A URI without a version is still left to the per-row check.

### How per-row exclusions interact with the bound

Orphans and unreadable conversations are not in the memory store, so they never appear in a state's id set. A state filter served by ids does not read them at all. They count against the bound only on the per-row paths: no state filter, or a state too common for the id set. A store with more than 10,000 orphans ahead of the requested page therefore gets a 422 that names the problem, where before it scanned without limit. The parity test `orphansAndTheScanBound` pins both sides.

### Measurements

`ConversationListingScanBenchmarkTest` produced these numbers. It is opt-in: `-Deddi.bench.listing=true`, and it writes `target/conversation-listing-benchmark.md`. It ran on a real MongoDB 7 and a real PostgreSQL (Testcontainers) with production serialization. Each figure is the median of 3 runs, limit 20. "Before" is the old unbounded per-row filter; "after" is this change.

| backend | conversations | ENDED | page | before: ms / descriptors read / queries | after: ms / descriptors read / queries |
|---|---|---|---|---|---|
| Mongo | 10,000 | 10 (0.1%) | first | 4,837 / 10,000 / 1,001 | 5 / 10 / 3 |
| Mongo | 100,000 | 100 (0.1%) | first | 14,299 / 20,000 / 2,000 | 51 / 20 / 2 |
| Mongo | 100,000 | 100 (0.1%) | offset 80 | 127,706 / 100,000 / 2,000 | 139 / 100 / 2 |
| Mongo | 100,000 | 1,000 (1%) | offset 980 | 16,626 / 100,000 / 200 | 173 / 1,000 / 2 |
| Mongo | 100,000 | 10,000 (10%) | first | 24 / 200 / 20 | 23 / 20 / 2 |
| Mongo | 100,000 | 10,000 (10%) | offset 9,980 | 18,440 / 100,000 / 200 | 1,695 / 10,000 / 20 |
| PostgreSQL | 10,000 | 10 (0.1%) | first | 16,935 / 10,000 / 1,001 | 7 / 10 / 3 |
| PostgreSQL | 100,000 | 100 (0.1%) | first | 36,980 / 20,000 / 2,000 | 5 / 20 / 2 |
| PostgreSQL | 100,000 | 100 (0.1%) | offset 80 | 311,527 / 100,000 / 2,000 | 7 / 100 / 2 |
| PostgreSQL | 100,000 | 1,000 (1%) | offset 980 | 40,610 / 100,000 / 200 | 171 / 1,000 / 2 |
| PostgreSQL | 100,000 | 10,000 (10%) | offset 9,980 | 44,661 / 100,000 / 200 | 2,520 / 10,000 / 20 |

"Queries" means descriptor queries plus summary queries. Each "after" listing adds one id query.

On Mongo, the server examined 50,251,999 keys and 50,250,000 documents for the 100,000 / 0.1% / offset-80 page before the change. After it, 700 keys and 300 documents.

The one row where "after" is not cheaper is a 10% state at 10,000 conversations, first page: 44 ms → 53 ms on Mongo, 30 → 55 ms on PostgreSQL. That is the cost of reading 1,000 ids to serve a page the per-row filter fills in 10 batches. It is the same order of magnitude, and it is bounded by the 10,000-id cap.

**Write cost** of each option (2,000 state writes on 10,000 conversations):

| backend | state write as shipped | without the new index | with a descriptor-state sync (rejected) |
|---|---|---|---|
| Mongo | 459 µs | 399 µs | 441 µs ¹ |
| PostgreSQL | 451 µs | — (no new index) | 676 µs (+50%) |

¹ This was measured without the new index, so compare it with 399 µs: the sync adds about 10%.

The covered index costs about 60 µs (15%) per state write on Mongo. A sync costs a second write on every state write, to a second collection.

### Why not keep the state on the descriptor (rejected)

The state is written by many paths, and none of them goes through the descriptor:

- every turn's snapshot: `storeConversationMemorySnapshot` and `…IfState`, which includes the `IN_PROGRESS` flips in `StepRunner`
- `setConversationState`
- the fenced compare-and-set, `compareAndSetState` and `…Fenced`: HITL pause and resume, crash recovery's `EXECUTION_INTERRUPTED`, `resetConversationState`
- the bulk `updateMany` in `endIdleConversations`
- undeploy (`AgentDeploymentManagement`)
- `ConversationService` end
- `PurgeService`
- GDPR erasure
- `V6RenameMigration`
- the plain `IResourceStore` create and update

Mirroring the state onto the descriptor would make every one of them a write to two collections, which cannot be atomic on either backend.

- A naive mirror goes stale on any crash or race between the two writes. A sequence-token protocol still leaves windows in which the two copies disagree.
- It would need a backfill of every existing descriptor, plus a migration for imported ZIPs.
- It would leave the per-row check in place anyway, because the copy could not be trusted.

The id set gets the same query narrowing from the one authoritative copy, with nothing to keep in sync and nothing to backfill.

### Tests

- `ConversationListingStateFilterParityTest` (new, Mongo and PostgreSQL) checks the id-set path against the per-row path:
  - sparse matches, admin and owner-scoped
  - no matches
  - state changes by `setConversationState` and `compareAndSetState`, with no sync needed
  - orphans and the 422
  - the bound on the last batch
  - a common state falling back to the per-row filter
  - `agentVersion` in the query
  - deep pages concatenating to the whole state
- `RestConversationStoreTest` covers:
  - the id restriction in the query
  - the empty set reading no descriptor
  - the fallback for too many ids or a failed read
  - the 422 body and its metric
  - a match within the bound
  - the owner budget kept
  - the version pattern
  - the race in which the id set says READY but the snapshot says ENDED
- Mutation-checked: each of these mutants fails the tests.
  - the id set not pushed
  - no 422
  - refusing on a short last batch
  - the admin path ignoring the bound
  - Mongo `IdIn` matching everything
  - PostgreSQL `IdIn` matching everything
  - the version left out of the pattern

### Not changed

- The Platform Operator holds `GET /conversationstore/conversations`. The 422 body names its own remedy (narrow by agent, version or filter, or ask for a shallower page), so neither the prompt nor the allow-list needs a change, and there is no revision bump.
- An owner-scoped listing whose 500-descriptor budget runs out still returns a short page without saying so. That is the non-admin half of the same problem and is left as a follow-up.

**Files:**
- [`RestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java)
- [`IResourceFilter.java`](../../src/main/java/ai/labs/eddi/datastore/IResourceFilter.java)
- [`MongoResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorage.java)
- [`PostgresResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorage.java)
- [`IConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/IConversationMemoryStore.java)
- [`ConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryStore.java)
- [`PostgresConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java)
- docs: [`rest-api-reference.md`](../rest-api-reference.md#bounded-listings), [`metrics.md`](../metrics.md)
- dashboard: the listing panel of [`eddi-full-metrics-dashboard.json`](../monitoring/eddi-full-metrics-dashboard.json) charts the new refusal counter; the Kubernetes copies are regenerated with `scripts/sync-monitoring-assets.py`

```decision-log
| 2026-11-07 | Conversation listings filter by state through the ids in that state (read off the memory store's state index), not a state copy on the descriptor; admin listings refuse with 422 after rejecting 10,000 rows instead of scanning without bound | #931: a sparse-state admin listing read the whole collection (127 s / 311 s for one deep page at 100,000 conversations) | Syncing the state onto the descriptor: ten-plus write paths, a non-atomic second write, a backfill, and the per-row check still needed |
```
