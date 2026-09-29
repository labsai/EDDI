## 🐛 fix(migration): a 5.x database now boots on 6.5 with its agents deployed, its conversations intact and no tokens in memory (2026-09-29)

**Repo:** EDDI (`fix/v6-first-boot-blockers`)

### Why

We rehearsed the upgrade against a restored production 5.5.1 database with both v6 migration flags on. Every deployed agent ended in ERROR, every old conversation loaded with empty steps, and the properties migration copied 159 learners' platform bearer tokens into `global` long-term memory. There were four separate causes.

### What changed

**1. A unique index from before 6.3 no longer stops the descriptor store from starting.** Databases created before 6.3 hold `descriptors.resource_1` with `unique: true`. 6.x asks for the same key non-unique, and MongoDB refuses with `IndexKeySpecsConflict` (86). The exception escaped the store's constructor, so no agent could deploy and `/descriptorstore` answered 500. [`MongoResourceStorage.ensureIndex`](../../src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorage.java) now reads the collection's indexes, following the same approach as the deployment store (#781):
- An index on the same key with another specification (a different `unique` flag, or partial, sparse, hidden, or with a collation other than the collection's default — none of which serves this store's queries) is dropped and rebuilt to the current specification. That includes one under another name: the server accepts a non-unique index beside it without any error, and the unique one would go on refusing writes.
- An equivalent index under another name (85) is kept.
- An index that holds the generated name on a *different* key is never dropped; that case is logged, the stale index on our key is still replaced, and ours is built as `<name>_eddi`.
- A conflict that can't be resolved is logged at ERROR, and the store starts anyway.

The index is rebuilt non-unique on purpose. Since 6.3, neither backend enforces uniqueness on `resource`: PostgreSQL's expression index isn't unique, and a MongoDB created by 6.3 never had it.

**2. The LLM workflow step type is renamed.** A v5 workflow names its LLM step `eddi://ai.labs.langchain`. The rename migration's URI rewrites end in `/` so that they match config URIs only, so the bare step type never matched, and every agent with an LLM step failed with `Extension 'ai.labs.langchain' not found`. The slash can't simply be dropped: `ai.labs.behavior` and `ai.labs.httpcalls` are still the registered step ids, even though their config URIs moved. [`V6RenameMigration.STEP_TYPE_REWRITES`](../../src/main/java/ai/labs/eddi/configs/migration/V6RenameMigration.java) is an exact-match table for step `type` fields in `workflows` and `workflows.history`. Its only entry is `langchain → llm`, the one v5 step type (of seven in the database) that 6.x doesn't register. `V6RenameMigrationStepTypeTest` builds the real extension registry by running every bootstrap module, and checks both sides of the table against it. [`LlmModule`](../../src/main/java/ai/labs/eddi/modules/llm/bootstrap/LlmModule.java) also registers `ai.labs.langchain` as an alias of the LLM task, as `ai.labs.behavior`/`ai.labs.rules` already are. The migration rewrites step types only on the boot that runs it: a database migrated by 6.0–6.4 recorded the migration complete with the old type still in place, and a v5 ZIP imports it verbatim. The test requires any v5 type that stays registered to be an alias of the same task.

**3. Old conversations keep their history.** EDDI 5 stored a step's runs as `conversationSteps[].packages`. The 6.x snapshot serialises them as `workflows` and ignored the unknown key, so a v5 conversation loaded 200/READY with no data in any step, and the next save wrote the empty steps back. There are two fixes:
- The migration renames the stored shape server-side: one `updateMany` with an aggregation pipeline, because `$rename` can't reach array elements. It covers `conversationSteps` and `redoCache`, and also renames `originPackageId` to `originWorkflowId` inside each result. It matches per array element — a step that holds `packages` and whose `workflows` is absent, null or empty — so a second run is a no-op. A step that holds both keys with a non-empty `workflows`, or neither key, is left unchanged and counted in a WARN.
- [`ConversationMemorySnapshot`](../../src/main/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshot.java) accepts both old keys on read (`@JsonAlias`), as a safety net for documents the migration hasn't reached.

**4. Credentials are not migrated into long-term memory.** [`PropertiesMigrationService`](../../src/main/java/ai/labs/eddi/configs/properties/mongo/PropertiesMigrationService.java) now leaves two kinds of key behind:
- the keys in the new `eddi.migration.properties.skip-keys` (default `userInfo`, the 5.x per-request identity: a token next to names and ids);
- any key whose value [`SecretScrubber.containsCredential`](../../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java) flags. That check uses the export redaction rules unchanged, and doesn't modify anything. Every field name at any depth, lists inside lists included, is also checked on its own, since the scrubber judges `apiKey: {value: "…"}` by the name `value`. BSON `ObjectId` values are identifiers by type and are left out of it; as extended JSON their hex string would trip the entropy rule.

Skipped keys are logged by name and count, never by value, and they aren't failures: the values stay in `properties_migrated_v6`. [`user-memory.md`](../user-memory.md) documents the cleanup query for a database that already ran the old migration.

### Decisions

- `resource_1` is rebuilt **non-unique** rather than kept unique; see 1.
- The scrubber's entropy rule is kept for the properties migration. On the rehearsal database it also skips five 17-character `createdProgram.courseId` values. That is accepted: they stay in the backup collection, and weakening the check for `*Id` fields would weaken it for session ids too.
- The step-type table lists only the types that no longer resolve. A type that is still registered is never rewritten, even when its config URI moved.

### Tests

`MongoResourceStorageIndexConflictTest`, `V6RenameMigrationStepTypeTest`, `V6RenameMigrationFirstBootTest` (Testcontainers MongoDB, read back through the production Jackson codec, including a save round trip), and new `PropertiesMigrationServiceTest` cases that use zero-entropy fake credentials. Each was mutation-checked: with the fix reverted, the tests fail, and with each half of the conversation fix removed on its own, the other half still keeps the load working.

### Next

PR B covers readiness with agents in ERROR plus ERROR retry, the nested Thymeleaf concat, and migrating triggers and user conversations. PR C covers the retention sweep on first boot, the startup probe, the documentation fixes, a 5.x → 6.x upgrade guide, and moving the conversation environment pass server-side.
