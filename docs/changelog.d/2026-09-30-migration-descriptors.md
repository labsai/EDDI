## 🐛 fix(migration): conversation descriptors and steps reach the v6 shape on databases an earlier 6.x migrated; an unconvertible template is an ERROR once (2026-09-30)

**Repo:** EDDI (`fix/650-migration-descriptors`)

Findings from the second 5.x → 6.5 first-boot rehearsal and its docs review.

### 1. Per-agent conversation listings were empty after a 5.x upgrade

EDDI 5 stored a conversation descriptor's agent as `botResource` and `botName`. The rename
migration rewrote the URI inside `botResource` but never the field's name, and the 6.x
`ConversationDescriptor` reads `agentResource`. So `GET /conversationstore/conversations?agentId=…`
returned none of the conversations EDDI 5 created, with or without `agentVersion`, and the text
filter on the agent's name (`agentName` is one of the indexed search fields) missed them too.

Worse, on a database an earlier 6.x migrated: 6.x read such a descriptor without its agent and
every write of a descriptor is a whole replace, so each turn, and each conversation the idle sweep
ended, stored the descriptor with **neither** name.

- **`V6RenameMigration`** — a new server-side pass renames `botResource` → `agentResource` and
  `botName` → `agentName` in `descriptors` and `descriptors.history` (one `updateMany` with a
  pipeline; a document holding both names of a pair is left alone, counted and reported, as for
  conversations). A second pass gives an `agentResource` back to the conversation descriptors
  that lost both names, from the `agentId`/`agentVersion` of the conversation with the same id,
  in batches; a descriptor whose conversation is gone is counted and reported, not failed. Both
  run as step 7 of a first migration, and as a **catch-up** on a database whose rename migration
  an earlier 6.x recorded complete (the #908 pattern for triggers), until it succeeds once:
  it records `v6-rename-descriptor-fields-complete` in `migrationlog`.
- **`ConversationDescriptor`** — reads `botResource` and `botName` through private write-only
  setters, so an unmigrated descriptor reads with its agent and is written back with the v6 names.
- **`RestConversationStore`** — the listing falls back to the conversation's own agent when a
  descriptor names none, so a descriptor stripped after the catch-up ran (by a replica still on
  an earlier 6.x) is still listed.

### 2. A database 6.4 migrated kept the v5 step shape of every EDDI 5 conversation

The step rename (`conversationSteps[].packages` → `workflows`, #907/#909) runs only as part of
a first migration. On a database whose `v6-rename-migration-complete` an earlier 6.x recorded,
it never ran, and those conversations loaded only through `@JsonAlias("packages")` on the
snapshot — while a comment said nothing depended on that alias.

- **`V6RenameMigration`** — the same server-side pipeline now also runs as a catch-up on such a
  database (idempotent, counted, `_rev`/`_histRev` bumped as #909's passes do), recorded as
  `v6-rename-step-shape-complete`. Both catch-ups go through one `catchUp(key, …)` helper; a first
  migration records both keys with its completion.
- **`ConversationMemorySnapshot`** / `migrateConversationStepShape` Javadoc — the alias is a safety
  net that has to stay (a database awaiting its catch-up, a v5 step written after the pass), and
  now say so.

### 3. An unconvertible Thymeleaf template was logged at ERROR on every boot

A template `V6QuteMigration` refuses (one that builds template syntax) keeps the migration
incomplete, so it re-ran and re-reported the same document on every boot, for as long as the
legacy config existed.

- **`V6QuteMigration`** — the first boot that finds a refused document logs an ERROR with the
  collection, id (with the version, for a history row), field paths and the remedy. Later boots
  list the documents already reported in **one WARN**. The reported set is kept in `migrationlog`
  under `v6-qute-migration-unconvertible` (an `entries` list); a document fixed since is converted
  and dropped from it, a new refused one gets its own ERROR, and the record is removed when none
  is left. The end-of-run summary is an ERROR only for real failures (an unreadable collection, a
  failed write); refusals alone end with an INFO. A cursor failing part-way through a collection is
  now counted as a failure instead of escaping the pass.
- **`IMigrationLogStore`** — `readMigrationEntries` / `writeMigrationEntries`, implemented by the
  MongoDB store on a plain document. The defaults keep nothing, so a store without them reports as
  on a first boot every time (the PostgreSQL store; the Qute migration reads MongoDB anyway).
- **Docs** — [`configuration-reference.md`](../configuration-reference.md) (`eddi.migration.v6-qute.enabled`)
  and [`output-templating.md`](../output-templating.md).

### Design decisions

- **The Qute migration stays incomplete while any document is refused, reported or not.**
  Completion means no stored template is still Thymeleaf, and it is also what stops the scan: once
  complete, a refused document fixed later would never be converted, and a refused one restored
  later would never be reported. Re-checking only the recorded ones would be a second path through
  the same four config collections; the full scan is cheap, and it is what an incomplete migration
  already cost.
- **The descriptor passes bump no revision**, unlike the conversation passes of #909. A descriptor
  has no `_rev`; its `_version` is part of the conversation's URI (`?version=`) and must not move,
  and descriptor writes are unconditional replaces that a bump would not guard anyway. A replace
  racing the pass is safe from 6.5 on, because the model reads the v5 names.
- **Not `@JsonAlias`.** With an alias, a document holding both names reads as whichever key comes
  last; the setters let the v6 name win in either order.
- **No alias for `ConversationMemorySnapshot.botId`/`botVersion`.** Conversations are migrated
  server-side before anything loads them, and a snapshot save `$set`s fields rather than replacing
  the document: an alias would leave `botId` beside the new `agentId`, which the migration then
  reports as ambiguous forever.

### Tests

- `V6RenameMigrationConversationDescriptorsTest` (Testcontainers): first boot, current and history,
  listed through the real `RestConversationStore` filter by agent and by agent + version; the
  catch-up on a database an earlier 6.x migrated; runs once; idempotent; ambiguous left alone;
  the backfill; reading the v5 names and writing back the v6 ones; v6 wins in either key order; the
  listing's fallback to the conversation.
- `V6RenameMigrationFirstBootTest` (Testcontainers): the step-shape catch-up on a database an
  earlier 6.x migrated — renamed, revision bumped, loads with the same item counts, a second run
  changes nothing — and it does not run once recorded.
- `V6QuteMigrationReportingTest` (Testcontainers): ERROR per document on the first boot; one WARN and
  no ERROR on the second; a fixed document converted and dropped, completion once none is left; a
  new refused document gets its own ERROR; a history row named with its version.
- `LogCaptureSupport.captureRecordsOf` — captures the level and the formatted message.
- Every behavioural test above was mutation-checked: 18 mutants (each fix reverted in turn), all
  killed by the test written for it.

### Open

- A refused template that is fixed through the API leaves its old version in the `.history`
  collection, where it is still refused: the migration completes only once that row is corrected
  or removed by hand. That is deliberate — an agent pinned to the old version still reads it — and
  the ERROR says so.
