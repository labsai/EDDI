## 🐛 fix(conversations): filtered listings page exactly, and a listing page no longer loads whole conversations (2026-10-01)

**Repo:** EDDI (`fix/conversation-listing-pagination`)

### The bug

`GET /conversationstore/conversations` treated `index` as a page of *descriptors*, filtered the rows
in Java afterwards, and kept adding whole descriptor pages until it had `limit` rows. With any filter
— `agentId`, `agentVersion`, `conversationState`, `viewState`, or the owner filter every non-admin
gets — that went wrong two ways:

- **Overshoot.** The size was checked only after a whole descriptor page had been added, so a page
  could hold up to `2*limit - 1` rows. On a rehearsal of real 5.5.1 data, `agentId=…&limit=100`
  returned 189 rows and `limit=20` returned 39.
- **Overlap.** `index=1` started at descriptor page 1, which `index=0` had often already consumed. 90
  of the 90 rows on `index=1` were on `index=0` too; paging to an empty page saw 279 rows for 189
  conversations. Not a 6.5 regression: 6.4 showed 1,499 rows for 852 conversations.

### The fix

[`RestConversationStore.readConversationDescriptors`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java):

- **`index` is a page of results.** The listing reads descriptor pages from the start, counts off the
  `index*limit` rows that pass every filter, and stops at `limit` exactly — mid-page if need be.
- **The descriptor-field filters are pushed into the query**, for MongoDB and PostgreSQL alike, so the
  descriptors read are mostly results: the agent (`agentResource` names it, *or names no agent* — a
  descriptor an earlier 6.x rewrote without its agent gets it from the conversation), `viewState`
  (equality), and for a non-admin the owner (`userId` is the caller, *or no owner is recorded* — a
  pre-v5.1.6 conversation). The owner is not pushed when an agent is named, because a reviewer of that
  agent may list conversations they do not own. Each pushed group admits a superset of what the
  per-row check accepts; that check still runs on every row and stays the authority.
- **What stays in Java** is what only the conversation knows: its state (the descriptor's copy is not
  maintained), whether it still exists (orphans, still counted in
  `eddi.conversations.listing.orphaned_descriptors`), and the owner or agent of a legacy descriptor.
- **The owner-scan budget** (`MAX_OWNER_SCAN`, 500) counts the descriptors a page examines, minus the
  matches it counts off for earlier pages: those are the caller's own conversations, and charging them
  would put a caller's older conversations out of reach. The reviewer exception is unchanged.

### Performance

Every listed row used to be a full conversation-memory load — one round trip per row, deserializing
every step, property and output — to read six small fields; the agent's display name was another read
per row. Counting off earlier pages would have multiplied that.

- New `IConversationMemoryStore.loadListingSummaries(ids)`: one projected query per descriptor page.
  MongoDB: `$in` with a projection that counts the steps server-side (`$size`). PostgreSQL:
  `id = ANY(?)`, the state/agent columns plus three JSONB fields and `jsonb_array_length`. A page now
  costs two small queries per descriptor page and loads no conversation in full; a foreign row is
  still rejected from its descriptor alone, before any conversation read.
- Page 0 reads descriptors in pages of `limit`; a later page, which first counts off the rows of the
  pages before it, reads them in batches of 100, so `index=50&limit=20` costs about 22 queries rather
  than 102.
- An agent's display name is read once per agent version per listing, and only for returned rows.
- If a page's summary read fails, the page's ids are read one at a time, so a conversation the store
  cannot read costs only its own row (logged, sanitized, and not counted as an orphan) — as a failed
  per-row load did before.

### Store plumbing

- `DescriptorStore.readDescriptorsRestricted(…, List<QueryFilters>)` takes any number of AND-ed filter
  groups; the two-group overload delegates to it.
- `IConversationDescriptorStore.readDescriptors(…, restrictions)` exposes it for conversations.

### Tests

- `RestConversationStoreTest.FilteredPagination`: a filtered page stops at `limit`; consecutive
  filtered pages list every match once, in order; an orphan before the page does not shift it; a state
  filter pages through matching states; a page reads one summary batch per descriptor page and never
  loads a conversation in full; an agent name is read once; the agent/view-state groups and their
  patterns; the state is not pushed down.
- `RestConversationStoreOwnershipTest`: an owner's pages are exact and disjoint with foreign rows
  interleaved; page 5 of 650 own conversations is reachable (the budget is not spent on counted-off
  rows); the owner group is pushed for a non-admin, not for an agent listing, not for an admin.
- `MongoConversationMemoryStoreTest` / `PostgresConversationMemoryStoreTest` (real databases): a
  summary equals the summary of the full load, including step count and a narrow `ENDED` write;
  unknown and malformed ids have no entry.
- `V6RenameMigrationConversationDescriptorsTest` already ran the listing over a real MongoDB and
  covers the agent pushdown, including the "descriptor names no agent" fallback;
  `PostgresResourceStorageContainerTest` now checks the same agent group on a real PostgreSQL.
- An unreadable conversation costs only its row and is not counted as an orphan.
- Mutation-checked: 12 mutants (no stop at `limit`, no counting-off, budget charged for counted-off
  rows, nothing pushed down, owner pushed for agent listings, no agent-name cache, orphans kept, scan
  batch ignored, and four in the two `loadListingSummaries` implementations), all killed.

### Docs

The listing's paging contract is on `IRestConversationStore.readConversationDescriptors`;
[`upgrading-from-5x.md`](../upgrading-from-5x.md) says to page until empty and that earlier versions
over-counted.
