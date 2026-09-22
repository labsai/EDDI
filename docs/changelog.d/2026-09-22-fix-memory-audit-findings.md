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
