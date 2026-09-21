## 🛠️ fix(migration): four first-boot defects found upgrading a real 5.5.1 database (2026-09-17)

**Repo:** EDDI (`fix/first-boot-migration-order`)

Found by rehearsing an upgrade of a customer deployment's **staging** EDDI 5.5.1 database (MongoDB Atlas, 3012
documents, 7 agents, 195 conversations) to 6.4.0 against a verified restore of the production-like
dump. Four defects fire on the first boot against a 5.x database; two of them destroy data. All four
are fixed here with tests, including three that drive a real MongoDB through Testcontainers.

### What changed

- **`TemplateSyntaxMigrator.migrateStringConcat` crashed on a `+` inside a string literal.** It split
  the concat expression with `split("\\s*\\+\\s*")`, which cuts literals apart: a literal `'+'`
  became two lone quote characters, a lone quote both starts and ends with a quote so it was taken for
  a quoted literal, and stripping its delimiters was `substring(1, 0)` —
  `StringIndexOutOfBoundsException: Range [1, 0) out of bounds for length 1`. A new
  `splitOnConcatOperator` splits only outside quotes, and `isStringLiteral` requires length ≥ 2 and
  matching delimiters. The real trigger on staging was a single `httpcalls` config holding a template
  whose three concatenated literals render as another template expression.
- **`V6QuteMigration.migrateCollection` had no per-document isolation**, so that one malformed template
  aborted the Thymeleaf→Qute conversion for *every* config in the database, logging only "will retry
  on next startup" — where it threw again. Each document now migrates in its own try/catch, failures
  are logged with collection and id, and the migration is **not** marked complete while any document
  failed, so it retries once the data is fixed. `migrateCollection` returns a
  `CollectionResult(migrated, failed)`.
- **`MongoDeploymentStorage`'s unique `(environment, agentId, agentVersion)` index destroyed deployment
  rows on a pre-rename database.** EDDI 5 wrote `botId`/`botVersion`; Mongo indexes the absent
  `agentId` as null, so an unrestricted unique index read all 113 staging rows as duplicates of one
  another, `createIndex` failed with E11000, and the recovery path `removeDuplicateDeploymentRows()`
  kept one row for the whole collection and deleted 112. The index is now partial on
  `agentId`/`agentVersion` existing, and the dedupe pipeline `$match`es only rows that carry the key.
- **The `@Scheduled(every = "10s", delayed = "10s")` `checkDeployments()` sweep ran before the rename
  migration and deleted deployments.** On a first boot against a 5.x database the agent configs are
  still in `bots`; `agents` does not exist until `V6RenameMigration` creates it, so
  `isAgentConfigMissing` returned true for every deployed agent and the sweep called
  `deleteDeploymentInfo` on each. Observed live: the deployment rows of both deployed agents deleted. The
  sweep now returns early while `V6RenameMigration.isPending()`.

### Design decisions

- **The sweep gate asks the migration, it does not track a flag.** The first cut set a
  `volatile boolean startupMigrationsAttempted` at the end of `autoDeployAgents()`. Two problems:
  nothing set it if anything above it threw (parking the sweep, and with it all deployment, forever),
  and it read "migrations attempted" as "collections renamed" — so a rename migration that *failed*
  released the sweep to delete the rows anyway. `V6RenameMigration.isPending()` is the actual
  precondition: `enabled && no completion entry in the migration log`, latched once complete so a
  ten-second schedule does not re-read the log forever, and fail-safe (an unreadable log counts as
  pending). Disabled is deliberately *not* pending — the property defaults to false, so "no completion
  entry" is the permanent state of every installation that never needed the migration, and reading that
  as pending would park the sweep on every normal EDDI 6 database. It also made the fix testable
  without rewriting the ~25 existing `checkDeployments()` tests, which call it directly on a freshly
  constructed object.
- **A conflicting index is dropped and rebuilt.** Mongo does not re-shape an existing index: adding
  `partialFilterExpression` to a key pattern that already carries the non-partial unique index is
  refused, not a no-op. Every installation already running 6.x would otherwise have kept the destructive
  index while logging something that reads like a warning about duplicate rows. On either conflict code
  the index actually sitting on the deployment key is looked up and dropped **by name**; if none does,
  the conflict is with someone else's index and is left alone. E11000 still goes to the
  dedupe-and-retry path, because there the *rows* are wrong and dropping the index would throw the
  constraint away instead of fixing them.
- **The partial filter uses `$exists`, not a null check**, so a row that legitimately carries a null
  `agentVersion` stays inside the uniqueness constraint. Only rows missing the field entirely — i.e.
  pre-rename rows — fall out of the index.
- **Not marking the Qute migration complete on a failure re-scans on every boot.** That is accepted:
  `TEMPLATE_COLLECTIONS` is four config collections plus their `.history` counterparts, the scan is
  cheap, and a migrated document contains no Thymeleaf syntax so nothing is rewritten twice. Shipping a
  half-migrated database silently is the worse trade. A collection that cannot be counted is a
  failure too, with one exception: `NamespaceNotFound` (26). Only some of these names exist on any given
  database and some driver versions answer `estimatedDocumentCount` on a missing namespace with that
  error rather than zero, so counting it would leave the migration permanently incomplete on a
  database with nothing to migrate; any other count failure means a collection nobody has read.
- **An empty part of a concat expression is now skipped rather than rendered as `{}`.** An empty
  operand only arises from a leading, trailing or doubled `+`, i.e. from an expression that was already
  malformed; `{}` is a broken Qute expression where nothing at all is a dropped empty operand.

### Review follow-up (PR #781)

Copilot found a real gap in the first version of the splitter: it left quote mode at the *first*
matching quote character, escaped or not, so a valid OGNL literal such as `'it\'s + here'` ended at
the escaped apostrophe and the `+` after it was read as an operator — cutting the literal in half
again, just for a rarer input. A backslash now escapes the next character while inside a literal.

Stripping the delimiters also reduces `\'`, `\"` and `\\` to the character they stood for, because
the conversion inlines the literal's text verbatim and Thymeleaf renders `'it\'s'` as `it's` — leaving
the backslash in would put it on the screen. The other OGNL escapes (`\t`, `\n`, …) are deliberately
left exactly as they are: a Windows path in a config is the likelier intent than a control character,
and guessing wrong there rewrites config content rather than merely failing to tidy it.

CodeRabbit then found that the `catch` around `estimatedDocumentCount()` was half-right in the other
direction: keeping the missing-collection case out of the failure count also swallowed authorization
errors, timeouts and server errors, so `runIfNeeded()` saw zero failures and recorded completion over
a collection it had never read — the same silent half-migration the per-document guard exists to
prevent. Only `NamespaceNotFound` (26) now counts as "nothing to migrate here"; anything else counts
as a failure and keeps the migration incomplete. A pre-existing test
(`runIfNeeded_collectionsNotExist`) asserted the old behaviour on a false premise — `getCollection`
does not contact the server, so it never fails merely because a collection is absent — and now
asserts the corrected contract under the name `runIfNeeded_collectionAccessFailureBlocksCompletion`.

A final independent review found five more things; all are fixed here.

- **A v5 database with two deployment rows that become one v6 key never finished migrating, and so
  never deployed an agent again.** `ENVIRONMENT_REWRITES` maps both `unrestricted` and `restricted` to
  `production`, and v5's own check-then-act upsert wrote same-environment duplicates. The unique index
  `MongoDeploymentStorage` builds at construction already exists when the migration runs, so the second
  row's write failed E11000 on every boot — the first row already rewritten, the second never could be
  — and with the sweep waiting on the migration, nothing deployed. Before this PR the dedupe deleted
  rows but boot completed; the PR had turned lossy-but-booting into never-deploying. A collision is now
  resolved with the store's own rule: one row per key, the newest `_id` kept, so every node picks the
  same survivor. `migrateEnvironments` also isolates documents: one that cannot be written is logged,
  the rest still go through, and the migration is left incomplete rather than aborted. Three
  Testcontainers tests cover both collision shapes and the no-collision control. The staging rehearsal
  could not have caught this: it held no such pair.
- **A pre-check on `migrateEnvironments` was removed.** An earlier commit on this branch added one to
  skip a clean collection, motivated by the staging measurement: the startup migrations took 24
  minutes, ~20 of them this pass on `conversationmemories` (195 documents averaging 410 KB; a read-only
  `mongodump` of the collection took 14 minutes on the same cluster). It could not help. Two of its
  three conditions were server-side counts, but the third — a legacy URI nested at arbitrary depth —
  has no filter form, and a sampled version was rejected because a miss is permanent once the migration
  records completion; an exhaustive one reads the whole collection, which is the entire cost. Clean
  collection: one read either way; dirty: two counts plus the same pass. It was net zero at best and
  the PR described it as a speedup. Removing the 20 minutes needs the rewrite moved server-side
  (`updateMany` with `$rename`/`$set`); that is follow-up work, not in this PR.
- **The index-conflict handling had the error codes wrong.** The review suggested handling 85 alone,
  and the real-server test showed why that is also wrong: an old non-partial index on the same key under
  the same auto-generated name comes back as `IndexKeySpecsConflict` (86) on current servers. Handling 85
  only passed every mocked test and left the destructive index in place. Now either code triggers a
  lookup of the index on the deployment key, dropped by name; a same-named index on another key is not
  touched.
- **`/q/health/ready` reported ready with nothing deployed.** With the rename migration pending the
  sweep is parked, yet `autoDeployAgents()` still set readiness. It now stays not-ready, with an ERROR
  saying why; the migration only runs at startup, so that lasts until a restart after the cause is fixed.
  The "sweep parked" warning is logged once instead of every ten seconds.
- **This entry contradicted itself** on whether an unreadable collection counts as a failure; corrected
  above to match the code.


### Files

- `src/main/java/ai/labs/eddi/configs/migration/TemplateSyntaxMigrator.java`
- `src/main/java/ai/labs/eddi/configs/migration/V6QuteMigration.java`
- `src/main/java/ai/labs/eddi/configs/migration/V6RenameMigration.java` — new `isPending()`
- `src/main/java/ai/labs/eddi/configs/deployment/mongo/MongoDeploymentStorage.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java`
- `src/test/java/ai/labs/eddi/configs/migration/TemplateSyntaxMigratorTest.java`,
  `V6QuteMigrationTest.java`, `V6RenameMigrationTest.java` — the concat fixture fails with the
  original `StringIndexOutOfBoundsException` against the pre-fix splitter
- `src/test/java/ai/labs/eddi/configs/deployment/mongo/MongoDeploymentStorageTest.java` (mocked) and
  `src/test/java/ai/labs/eddi/datastore/mongo/MongoDeploymentStorageTest.java` (Testcontainers —
  pre-rename rows survive construction, a non-partial index is rebuilt as partial, and the dedupe
  spares pre-rename rows on a half-migrated collection)
- `src/test/java/ai/labs/eddi/datastore/mongo/V6RenameMigrationDeploymentsTest.java` (Testcontainers —
  deployment rows that collapse onto one v6 key)
- `src/test/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagementTest.java`,
  `AgentDeploymentManagementBranchTest.java`

### Verification

201 tests green in the selection (`TemplateSyntaxMigratorTest`, `V6QuteMigrationTest`, both
`MongoDeploymentStorageTest`s, `V6RenameMigrationTest`, `V6RenameMigrationBranchTest`,
`AgentDeploymentManagementTest`, `AgentDeploymentManagementBranchTest`) plus the repo-wide guards
(`ImportStyleTest`, `DocumentationLinksTest`, `StrictBoundaryShippedConfigsTest`,
`RuleSetStoreShippedRulesetsTest`, `BuildQualityGatesTest`, `ChangelogRotationTest`). Mutation-checked:
reverting the literal-aware split, the partial filter, the dedupe `$match`, the index-conflict rebuild,
the sweep gate or the "do not mark complete when a document failed" behaviour each makes a test fail.

```decision-log
| 2026-09-18 | Keep the v5→v6 conversation rewrite a client-side pass; no skip-if-clean pre-check | Staging: 20 of 24 startup minutes in `migrateEnvironments` over 80 MB | A pre-check was built and removed: a nested legacy URI has no filter form, so proving a collection clean costs the same read. Server-side `updateMany` is the real fix, left as follow-up |
```
