## 🗄️ fix(datastore): one id contract, no MongoDB on PostgreSQL, parser-safe orphan purge (2026-10-02)

**Repo:** EDDI (`fix/postgres-parity`)

### Why

Running the same 170-check live suite against a MongoDB and a PostgreSQL-only deployment (review of
2026-10-02, §2 #10/#11, §3.1, §4.7) left six checks that only PostgreSQL failed, and a handful of
places where the two backends answered the same request differently:

- **Unknown or malformed ids answered 500 on PostgreSQL** — `GET /rulestore/rulesets/xyz?version=1`,
  an id of dashes, a 24-hex ObjectId, `GET /agents/xyz`, `GET /agents/000000000000000000000000`,
  `POST /agents/xyz`, `GET /groups/{id}/conversations/------------------`. The id was bound into
  `?::uuid`, PostgreSQL raised SQLSTATE 22P02, and the store wrapped it in a `RuntimeException`.
  MongoDB answered 404 on some of them and **400** (or 500 on a POST) on others, because
  `new ObjectId(id)` throws `IllegalArgumentException` — including for a UUID, a perfectly good id on
  the other backend. The one place that tolerated a bad id on PostgreSQL did so by matching the
  English text "invalid input syntax for type uuid", which changes with `lc_messages`.
- **The retention sweep was held for ever on PostgreSQL.** `V6RenameMigration.holdsRetention()` asked
  MongoDB whether the EDDI 5 `bots` collection held documents, also when `eddi.datastore.type=postgres`.
  Its `MongoDatabase` is a lazy client proxy, so the call created a MongoClient against
  `mongodb://mongodb:27017`, waited 30 s, and the fail-safe catch answered "hold": no ended conversation
  was ever deleted, and the log claimed the database came from EDDI 5.
- **User-memory search** matched PostgreSQL's `value::text` — the value's JSON text — so `true` found
  every boolean, a number matched by its digits and the field names of an object value matched; MongoDB's
  `$regex` matched string values only. Nothing capped the result, and an LLM tool calls it.
- **Sorted listings put documents without the sort field first on PostgreSQL** (`DESC` is
  `NULLS FIRST` there) and last on MongoDB.
- **The orphan purge deleted dictionaries that parser documents reference.** It collected references
  from agent → workflow → workflow step only; `extensions.dictionaries[n].config.uri` of an
  `ai.labs.parser` document — the shape `/parserstore/parsers` documents — was never read, so such a
  dictionary was listed as an orphan and erased, leaving the parser naming a dictionary that no longer
  exists. None of the 43 safety tests modelled a parser document.

### What changed

- **One id contract, both backends: an id the backend cannot store is an unknown id.** Every lookup
  answers "not found", every write or delete of it is a no-op, and no query is sent, so REST answers 404
  exactly as for a well-formed id that does not exist.
  - `PostgresIds` (new): `isStorableId` (the canonical 8-4-4-4-12 UUID, either case) and
    `isInvalidTextRepresentation` (SQLSTATE `22P02`, replacing the English-message match in
    `PostgresResourceStorage.getCurrentVersion`).
  - `PostgresResourceStorage`: `read`, `readMany` (skips the bad id, keeps the rest), `readHistory`,
    `readHistoryLatest`, `getCurrentVersion`, `remove`, `removeAllPermanently`.
  - `PostgresConversationMemoryStore` and the MongoDB `ConversationMemoryStore`:
    `loadConversationMemorySnapshot`, `getConversationState`, `getRevision`, `compareAndSetState`,
    `setConversationState`, `setConversationEndReason`, `clearHitlBookmark`,
    `deleteConversationMemorySnapshot`.
  - `MongoResourceStorage`: `readMany`, `remove`, `removeAllPermanently` (the reads were already guarded).
  - `MongoUserMemoryStore`: `findEntryById`, `deleteEntry` (the PostgreSQL store keys entries by text,
    so it already answered "not found").
  - `RestUtilities.isValidId` no longer accepts dashes anywhere: an id segment is hex (18+ characters)
    or a canonical UUID. Eighteen dashes used to count as an id.
  - Group conversations, attachments, audit, schedules and user memory on PostgreSQL either go through
    `PostgresResourceStorage` or key by text; none binds an id into a `uuid` cast any more.
  - Found by the new parity test, fixed with it: `PostgresConversationMemoryStore.loadConversationMemorySnapshot`
    was the one read that did not create the table first, so on a fresh database a GET of an unknown
    conversation failed with "relation does not exist" (a 500). The schema check is now lock-free once
    the table exists. And `MongoResourceStorage.findResources` with no filter groups sent `$and: []`, which
    MongoDB rejects; it now means "everything", as on PostgreSQL.
- **No MongoDB on a PostgreSQL deployment.** `V6RenameMigration` and `V6QuteMigration` read
  `eddi.datastore.type` (exact match, as `DataStoreProducers` does) and on `postgres` are inert: not
  pending, never holding retention, never touching their `MongoDatabase`. EDDI 5 ran on MongoDB only.
  `PropertiesMigrationService` and `GridFsIndexInitializer` already had this guard; with these two, a
  PostgreSQL boot creates no MongoClient at all.
- **User-memory search** (`IUserMemoryStore.filterEntries`) has one meaning on both backends: every
  term in the key, in a string value, or in a string element of an array value — numbers, booleans and
  object values are not searched — newest first, at most `IUserMemoryStore.MAX_FILTER_RESULTS` (200).
  The `forgetFact` tool used this search to find the agent's own entry by exact key; it now reads the
  user's entries instead, so the cap cannot hide an older entry. Documented in
  [`user-memory.md`](../user-memory.md#searchmemory).
- **MCP `delete_user_memory`** answers an error for an id that names no entry, as the REST endpoint
  answers 404. It reported `"status": "deleted"` — for any unknown id on both backends, and, with the
  id contract above, for a malformed id on MongoDB that used to fail.
- **Sort order:** `PostgresResourceStorage.findResources` sorts `DESC NULLS LAST`. So the sorted page is
  still read off an index, the field indexes are now built in that order
  (`idx_resources_fdesc_<field>`, `(collection_name, (data ->> field) DESC NULLS LAST)`); the old
  ascending `idx_resources_field_<field>` is dropped once its replacement exists **and is valid**. On a
  database an earlier release built, the replacement is not built at boot: a plain `CREATE INDEX` holds a
  SHARE lock on the shared `resources` table — one descriptor per conversation, nine hinted descriptor
  fields — and would stop every write on every instance until all were built. `PostgresFieldIndexSwaps`
  builds them with `CREATE INDEX CONCURRENTLY` on a background thread once boot has settled (as the
  `pg_trgm` indexes are built), one advisory lock per index so two booting instances never build the
  same one, repairs an index an interrupted build left INVALID, and drops the old index
  `CONCURRENTLY` only after that. Until then the old index still serves equality filters; a refused
  build (no privilege) logs one warning and keeps it. A fresh database builds the new index at boot.
- **Orphan purge:** `RestOrphanAdmin` reads every parser document's current version and the parser
  version each workflow step pins, and counts their `extensions.dictionaries[n].config.uri` as
  references. The pre-delete re-check asks parser documents too, and fails closed. An unreadable parser
  document makes the scan incomplete, so the purge refuses (409). A parser that is itself an orphan
  keeps its dictionaries until it is gone; the next purge reaches them.
- **Dead code removed** (no reference in `src/main`): `datastore/mongo/DescriptorStore` (legacy, with
  the `eq(deleted, includeDeleted)` bug), `datastore/mongo/ResourceFilter`,
  `datastore/mongo/HistorizedResourceStore`, `datastore/mongo/ModifiableHistorizedResourceStore`,
  `datastore/mongo/AbstractMongoResourceStore`, `IResourceFilter.readResources` (the interface is now
  only the namespace of the filter types) and `StringUtilities.convertToSearchString`, with their tests.
  The live `datastore.HistorizedResourceStore` / `ModifiableHistorizedResourceStore` stay.
- **Docs:** [`configuration-reference.md`](../configuration-reference.md) — `eddi.datastore.type` says
  it is matched exactly, needs the `postgres` profile and the datasource settings, and that a PostgreSQL
  deployment opens no MongoDB connection; `eddi.migration.v6-rename.retention-confirmed` says a
  PostgreSQL deployment is never held.

### Tests

- `MalformedIdContractTest` (new): the same malformed and foreign ids through both resource storages
  and both conversation stores — "not found", and no statement or query sent. `MongoUserMemoryStoreTest`
  does the same for user-memory entry ids.
- `DatastoreParityTest` (new, Testcontainers MongoDB + PostgreSQL): the id contract, the sort position
  of a document without the sort field, the user-memory search semantics and its cap — each case run
  against both real databases.
- `V6MigrationsOnPostgresTest` (new) and `RestConversationStoreTest.runsOnPostgresWithoutMongo`: on
  PostgreSQL the sweep deletes as configured and the migration never touches an unreachable MongoDB.
- `RestOrphanAdminSafetyTest.ParserDocumentReferences` (7 new): parser-document references in the scan,
  at the current and at the pinned version, the re-check, and failing closed.
- `PostgresResourceStorageTest`, `RestUtilitiesTest`: the SQLSTATE match (a German message still
  counts, an English one with another SQLSTATE does not), the index order, that an upgraded database
  builds nothing blocking at boot, `isValidId`.
- `PostgresFieldIndexSwapsTest` (new) and `PostgresResourceStorageContainerTest.FieldIndexReplacement`
  (real PostgreSQL): the concurrent swap, its lock, its repair of an INVALID index, and that the old
  index survives a refused or invalid build.
- `UserMemoryToolScopingTest.forgetFactDoesNotDependOnTheCappedSearch`,
  `McpMemoryToolsBranchCoverageTest.unknownEntryIsNotReportedAsDeleted`.

```decision-log
| 2026-10-02 | Unknown, foreign and malformed ids are "not found" (404) on both datastores, checked before any statement is sent | PostgreSQL answered 500, MongoDB 400 on a GET and 500 on a POST, for the same request; archives and URLs from one backend carry the other's id format | 400 for a malformed id on both (MongoDB had it on some paths) — rejected: a UUID is not malformed, only foreign, and most callers cannot tell the backends apart |
| 2026-10-02 | PostgreSQL sorts `DESC NULLS LAST`, and its field indexes are rebuilt in that order (`idx_resources_fdesc_*`, replacing `idx_resources_field_*`) | MongoDB sorts a missing field last, PostgreSQL first | `NULLS LAST` on the old ascending index — rejected: the planner can no longer read a sorted page off it and sorts the whole collection per listing page |
| 2026-10-02 | On an upgraded database the `idx_resources_fdesc_*` replacements are built `CONCURRENTLY` in the background, under a per-index advisory lock, and the old index is dropped only once the new one is valid | A blocking `CREATE INDEX` at boot locks the shared `resources` table (a descriptor per conversation) against writes cluster-wide for the whole build | `CREATE INDEX CONCURRENTLY` at boot — rejected: it waits for every open transaction, so boot could stall, and two booting instances race on one name; keeping the ascending index and adding the new one beside it — rejected: two indexes per field cost every write |
| 2026-10-02 | The EDDI 5 migrations are inert when `eddi.datastore.type=postgres` | `holdsRetention()` dereferenced the lazy `MongoDatabase` proxy on PostgreSQL and held retention for ever | Injecting `Instance<MongoDatabase>` — rejected: the proxy is already lazy; the defect was calling it, and EDDI 5 never ran on PostgreSQL |
```
