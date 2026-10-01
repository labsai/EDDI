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
- **A ceiling for every caller:** `index * limit` above 10,000 is refused with a 400 before any
  query. A page counts off every match before it, and an admin — or anyone when authorization is
  disabled — never reaches the owner-scan budget, so `index=2000000000` would otherwise have read the
  whole descriptor collection. (Raised in review.) A deep page also reads the rows it counts off in
  batches sized to that work, up to 1,000: each batch makes the database walk past the batches before
  it again, so fixed batches of 100 made a deep page quadratic. The owner budget is checked once per
  batch, never mid-batch: which rows of a batch are counted off — and so exempt from the budget — is
  known only after its summary read, and stopping mid-batch let the next batch start after rows nobody
  had examined (with batches larger than the budget, rows 500–599 of a deep page went missing).
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
  costs two small queries per descriptor page and loads no conversation in full. A row whose
  descriptor records another owner is still rejected from the descriptor alone, before any
  conversation read; a legacy descriptor that records no owner is decided from its conversation's
  summary.
- Page 0 reads descriptors in pages of `limit`; a later page, which first counts off the rows of the
  pages before it, reads them in batches of 100 to 1,000 sized to that work, so `index=50&limit=20`
  costs 4 queries (two batches of 1,000, each with its summary read) rather than 102.
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

## ⚡ perf(search): descriptor search is a literal substring test, not a `.*text.*` regex (2026-10-01)

**Repo:** EDDI (`fix/conversation-listing-pagination`)

Every descriptor listing's search box — conversations, agents, every config type — sent the escaped
term as the regex `.*<text>.*`, ORed over five fields (`userId`, `name`, `agentName`, `description`,
`resource`). An unanchored regex already matches anywhere, so the wrapping changed no result and only
added backtracking; PostgreSQL then ran it as `~`, which costs more per row than a literal test.

- New filter value `IResourceFilter.Contains(text)`: the field contains the text as a literal,
  case-sensitive substring; an absent field does not match. `DescriptorStore` builds the search from
  it (`StringUtilities.searchText` keeps the old quoted-term handling).
- **MongoDB** renders it as a bare escaped regex — no `.*` wrapping.
- **PostgreSQL** renders it as `LIKE '%…%' ESCAPE '\'` with `%`, `_` and `\` escaped. `LIKE` rather
  than `strpos()` (which measures the same) because a `pg_trgm` GIN index serves `LIKE` and never
  `strpos()`.

Measured on 300k descriptors (250k conversations) in throwaway `mongo:7` and `postgres:16-alpine`
containers, back to back on an otherwise idle machine, medians of 5 (MongoDB) and 7 (PostgreSQL)
runs:

| Query | MongoDB before → after | PostgreSQL before → after |
|---|---|---|
| selective search (hit) | 276 → 121 ms (2.3×) | 443 → 295 ms, cached plan 453 → 349 ms |
| search with no hit | 405 → 411 ms (no change) | 491 → 407 ms, cached plan 1,253 → 914 ms |

The PostgreSQL figures are for one prepared statement executed repeatedly, as the JDBC driver runs it:
the first five executions are planned for their value, later ones use PostgreSQL's cached generic
plan. A search that matches nothing still examines every descriptor on both databases, and on a
long-running PostgreSQL that cached plan makes it take about a second — before and after this change.

Results are unchanged: same rows, same case sensitivity, same quoted-term handling. Real-database
tests on both backends (`MongoContainsFilterTest`, `PostgresResourceStorageContainerTest`) assert the
same rows for literal metacharacters (`+`, `(`, `%`, `_`, `\`, `'`), case, an absent field and an
empty search.

PostgreSQL search is indexed too — see the next entry. MongoDB has no index that serves a substring
search; a text index would change matching to whole words.

### The "nothing matched, list everything" fallback keeps its meaning

A search that matches no conversation lists everything instead. That was decided from the first
unfiltered descriptor page; with the listing's filters now pushed into the query, an empty first page
no longer means the search matched nothing — "Billing" within agent A comes back empty when it matches
agent B's conversations. A one-row probe without the pushed-down filters now decides it, so that search
lists nothing (as before) instead of falling back to all of agent A.

### Verified against a running EDDI on both databases

The packaged jar, run against fresh `mongo:7` and `postgres:16-alpine` containers and driven only
through its REST API (two database edits aside: orphaning three conversations and marking four
`SEEN`). 67 conversations on two agents, some ended:

| Run | MongoDB | PostgreSQL |
|---|---|---|
| Paging at limits 3/7/10/20 for every filter (none, agent, agent+version, state, agent+state, view state), row content, orphans, search (by id, metacharacters, `%`/`_`, quoted, case, inside a filter), the orphan counter | 293/293 | 293/293 |
| With Keycloak: two `eddi-user`s and an admin, 50 conversations, one legacy descriptor without an owner — each user pages through exactly their own (the legacy one by its conversation's owner), the admin through all, searches never leak or fall back across owners | 121/121 | 121/121 |

The same unauthenticated checks against `labsai/eddi:latest` (6.4.0, before this fix) fail 98 times —
agent A's 45 conversations came back as 88 rows — so they do detect the bug.

Mutation-checked: nine more mutants in the search and fallback code (LIKE wildcards unescaped, ILIKE,
MongoDB text unescaped, back to a regex string, quotes kept, fallback always / never, a failed batch
dropping the page, an unreadable row counted as an orphan), all killed.

## ⚡ perf(postgres): index the descriptor search with pg_trgm, and plan it per execution (2026-10-01)

**Repo:** EDDI (`fix/conversation-listing-pagination`)

A search on PostgreSQL scanned every descriptor. On a long-running instance it was slower still: the
JDBC driver server-prepares a statement it sees repeatedly, PostgreSQL then reuses one generic plan,
and a search that matched nothing took about a second on 300k descriptors.

- **`PostgresSubstringSearchIndexes`**: GIN `gin_trgm_ops` indexes on the five searched fields,
  partial to the `descriptors` collection. `DescriptorStore` asks for them through the new optional
  `ISubstringSearchIndexing`, which only the PostgreSQL storage implements. The indexes are a fixed
  catalogue: every index name and `CREATE`/`DROP` statement is a compile-time constant, so no runtime
  string reaches the DDL (which takes no bind parameters); a field or collection outside the catalogue
  is not indexed. (CodeQL flagged the first version, which built the DDL from validated names.)
- **Built in the background**, 15 s after boot, with `CREATE INDEX CONCURRENTLY`: a large deployment
  neither waits at startup nor stops writing (300k descriptors took ~8 s for all five). One builder
  across replicas via an advisory lock. An index an interrupted build left INVALID — which
  `IF NOT EXISTS` would skip for ever while it still costs every write — is dropped and rebuilt.
- **Retries transient failures.** The first live boot deadlocked: the concurrent build ran into the
  other storages' schema setup and PostgreSQL aborted it, leaving an index INVALID. The build now waits
  for boot to settle and retries a deadlock, serialization failure, lock timeout or cancellation
  (after 30 s, 2 min, 10 min); a refused privilege is reported once and not retried.
- **Planned per execution, once the indexes are ready.** The search statement is sent with a prepare
  threshold of 0, so PostgreSQL plans it for its actual pattern and can use the indexes. Only that
  statement, and only when every index is valid: without the indexes, per-execution planning made a
  common term ~170× slower than the cached plan, which happens to stop early on the date index.
- **`eddi.datastore.postgres.substring-search-index`** (default `true`) turns it off. If
  `CREATE EXTENSION pg_trgm` is refused, a warning is logged and search runs unindexed, as before.

Measured live: the packaged jar on PostgreSQL 16 with 300k conversation descriptors and memories,
through `GET /conversationstore/conversations?filter=…`, 120 requests per term, median of the last 60
(after the connection pool's plan caching settled), identical rows with the feature off and on:

| Search | off (as before) | on |
|---|---|---|
| matches a few conversations | 393 ms | 39 ms |
| matches nothing | 375 ms | 23 ms |
| matches many (common term) | 14 ms | 18 ms |

Cost: each descriptor write maintains five GIN indexes — measured at the database, a descriptor
rewrite (one per conversation turn) went from ~60 µs to 130–200 µs — and ~50 MB per 300k descriptors.

Verified live on that boot: the build waited, repaired the INVALID index the deadlocked run had left,
built the rest and reported ready 29 s after start, with EDDI serving from 4 s. The 293-check listing
script passed on PostgreSQL with the feature on (indexes ready) and on MongoDB, where nothing changes.

Tests: `PostgresSubstringSearchIndexesTest` (real PostgreSQL: the index definitions, a planned search
uses the index, an INVALID index is rebuilt, another builder's lock is respected),
`PostgresSubstringSearchIndexesRetryTest` (a deadlock is retried to success, a privilege failure is
not, retries are bounded, the SQLState classification), and `PostgresResourceStorageTest` (planned per
execution only for a substring search with ready indexes; the build starts only when enabled).
Mutation-checked: eight mutants — never planned per execution, planned before ready, nothing
transient, no repair, lock ignored, privilege retried, a field outside the catalogue indexed, any
collection indexed — all killed.
