## 🐛 fix(memory): findings from a live end-to-end audit of the memory features (2026-09-22)

**Repo:** EDDI (`fix/memory-audit-findings`)

### Context

Every memory variation was exercised against a live instance of `main` (MongoDB, Ollama and
Claude): REST, MCP, property scopes, visibility, the LLM memory tools, Dream, the rolling summary,
windowing, strict write discipline, GDPR and the v5 migration. Each fix below reproduces a defect
observed there, and carries a regression test.

### What changed and why

- **Upsert hijack (MongoDB).** A `self`/`group` write was keyed on `(userId, key, sourceAgentId)`
  with no visibility term, so it matched the `global` entry the same agent had created and flipped
  the shared memory to private — every other agent lost it. Reproduced through REST and through
  the `rememberFact` tool ("keep a private note" under a key once shared). The filter now excludes
  global documents, which is the identity PostgreSQL already enforced with its partial unique
  index.
  [`MongoUserMemoryStore.java`](../../src/main/java/ai/labs/eddi/configs/properties/mongo/MongoUserMemoryStore.java)
- **Dream consolidation deleted every memory of a group.** Reproduced on Claude and Ollama: six
  unrelated facts in, zero out, fire reported `COMPLETED`. The model reused an original's key, the
  upsert overwrote that original in place, and "delete every original" then deleted the
  consolidation as well. Write targets are now resolved before anything is written: an original
  that receives a consolidated entry is kept, a key that would land on a memory outside the group
  (another category, another agent's global entry) skips the group untouched, duplicate keys in the
  model's answer are merged, and a failed insert restores the originals it overwrote. The answer
  is no longer truncated to `summarizeTargetEntries` — truncation dropped facts the model had
  preserved while the originals were deleted anyway; the target is now part of the prompt.
  [`DreamService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/DreamService.java)
- **`scope: "secret"` values of different users shared one vault slot.** The slot was
  `<agentId>.<propertyName>`, so after Bob entered his API key Alice's conversation resolved to
  Bob's (reproduced live: both references identical, the vault checksum switched to Bob's value).
  Every auto-vaulted write now gets its own slot, `<agentId>.u<sha256(userId)[:16]>.<nonce>.<name>`
  — nothing can collide, and the user id itself never enters the vault. Since slots are no longer
  overwritten they are deleted explicitly: on overwrite of the property, on permanent conversation
  deletion (single and retention sweep), and on GDPR erasure, which sweeps the default tenant plus
  every tenant the user's conversations point into and reports `autoVaultedSecretsDeleted`. Legacy
  shared slots are never deleted by this — they may still back other users' conversations.
  [`AutoVaultedSecrets.java`](../../src/main/java/ai/labs/eddi/secrets/AutoVaultedSecrets.java),
  [`PropertySetterTask.java`](../../src/main/java/ai/labs/eddi/modules/properties/impl/PropertySetterTask.java),
  [`RestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java),
  [`GdprComplianceService.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java)
- **A property's `visibility` was ignored.** `PropertySetterTask` and `PrePostUtils` built every
  property as `new Property(name, value, scope)`, so `"visibility": "self"` on a `longTerm`
  property was stored under the agent default — `global` for most agents, and agent B read agent
  A's "private" properties live. The instruction's visibility is now carried on every branch, and
  the inline `setOnActions` parser reads it too (an unknown value fails configuration instead of
  silently becoming global).
  [`PrePostUtils.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/PrePostUtils.java)
- **`userMemoryConfig` was dropped unless `enableMemoryTools` was on.** Its `defaultVisibility`
  and recall settings govern the `longTerm` property path of every agent, but the loader only
  kept the block for agents with memory tools — so `"defaultVisibility": "self"` on a rule-based
  agent was silently stored as `global`. The config now applies whenever it is declared; a new,
  separate `memoryToolsEnabled` switch (carried agent → properties handler → conversation memory)
  is what attaches the `UserMemoryTool`, so declaring a config never grants the cross-conversation
  write tool on its own.
  [`AgentStoreClientLibrary.java`](../../src/main/java/ai/labs/eddi/engine/runtime/client/agents/AgentStoreClientLibrary.java),
  [`ContextualToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ContextualToolsProvider.java)
- **`group`-visible `longTerm` properties were unreachable.** They were persisted with an empty
  `groupIds`, and recall matches group entries by group-id overlap — so nobody, the writing agent
  included, could read them. The persistence boundary now stamps the conversation's group (the
  resolver moved from the LLM module to `ConversationGroups` so both write paths share it); a
  `group` property in a conversation that belongs to no group is stored as `self`, the only scope
  that keeps it reachable without widening it.
  [`Conversation.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java),
  [`ConversationGroups.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationGroups.java),
  [`UserMemoryEntry.java`](../../src/main/java/ai/labs/eddi/configs/properties/model/UserMemoryEntry.java)
- **`autoRecallCategories` was never read — now documented as reserved.** Recall loads every
  visible category. It is deliberately *not* switched on: the store serializes the whole
  `userMemoryConfig` block, so the default `["preference", "fact"]` sits in every stored agent and
  is indistinguishable from an explicit setting — enforcing it would silently stop recalling
  `context`, `legacy` (migrated v5) and `property` entries for all of them.
- **The deprecated `maxSummarizationCalls` ceiling applied to every stored agent.** The getter
  wrote the default `10` into each saved agent, and reading it back called the setter, so after one
  save every config counted as having set the ceiling — the opposite of what
  `isMaxSummarizationCallsSet()` exists to tell apart. It is now serialized only when set. Agents
  already stored keep the `10` they were given; that bounds a dream cycle only past ten consolidated
  groups per user, which the per-category grouping rarely reaches.
  [`AgentConfiguration.java`](../../src/main/java/ai/labs/eddi/configs/agents/model/AgentConfiguration.java)
- **The v5 → v6 properties migration overwrote newer data, on every restart.** It upserted each
  legacy value over whatever the user already had — reproduced live: a v6 `NEW-v6-value` became
  `OLD-v5-value` — and a single legacy document without a `userId` counted as a failure, so the
  source was never retired and the clobbering repeated at each boot. A key the user already has in
  `usermemories` now keeps its value (which also keeps retries idempotent), and an unowned document
  is skipped instead of failed — it stays readable in `properties_migrated_v6`.
  [`PropertiesMigrationService.java`](../../src/main/java/ai/labs/eddi/configs/properties/mongo/PropertiesMigrationService.java)
