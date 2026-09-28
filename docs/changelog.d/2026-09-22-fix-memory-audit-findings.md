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
  An answer that only repeats some originals verbatim merged nothing, so it may drop an original
  only if that original duplicated a kept value; otherwise the group is skipped. Seen live on
  qwen2.5:3b, which returned three of four coffee preferences unchanged and lost "no sugar".
  [`DreamService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/DreamService.java)
- **`scope: "secret"` values of different users shared one vault slot.** The slot was
  `<agentId>.<propertyName>`, so after Bob entered his API key Alice's conversation resolved to
  Bob's (reproduced live: both references identical, the vault checksum switched to Bob's value).
  Every auto-vaulted write now gets its own slot, `<agentId>.u<sha256(userId)[:16]>.<nonce>.<name>`
  — nothing can collide, and the user id itself never enters the vault. Since slots are no longer
  overwritten they are deleted explicitly: on permanent conversation deletion (single and retention
  sweep) — every slot the conversation's undo history and redo cache still reference, since undo
  restores an overwritten secret's previous reference — and on GDPR erasure, which sweeps the
  default tenant plus every tenant any of those versions point into and reports
  `autoVaultedSecretsDeleted`. A vault failure never discards the references: the conversation is
  kept (deletion fails, the sweep retries), and GDPR erasure keeps the snapshots and reports the
  step failed. The slot shape is reserved — the secrets REST API and setup's `vaultKeyName` reject
  it — because the GDPR sweep matches slots by name. Legacy shared slots are never deleted by this —
  they may still back other users' conversations.
  `ConfigReferenceGuard` accepts the new slot only when it is exactly this conversation's agent,
  user (hash) and property under its tenant; the legacy `<agentId>.<name>` form stays accepted for
  conversations vaulted before the change.
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
- **`rememberFact`: corrections refused at the cap, budget spent on re-saves.** With
  `onCapReached: "reject"`, updating an existing fact was refused although an update adds no row
  (tool trace: `rememberFact(color, red)` → "capacity reached"). And models re-save facts they
  already stored — they do not see earlier tool calls — which spent `maxWritesPerTurn` on
  re-saves and refused every new fact, turn after turn (reproduced on Claude). The tool now
  resolves the write's target with the stores' upsert identity first: an unchanged re-save is
  reported as "already remembered" without writing or spending budget, an update never counts
  against the cap, and the limit message tells the model not to retry in the same turn.
  [`UserMemoryTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UserMemoryTool.java)
- **Dream and the rolling summary could not select an Ollama (or Bedrock/Azure/Vertex) model.**
  `SummarizationService` always wrote the model as `modelName`; the Ollama builder reads `model`
  (Bedrock, HuggingFace and Vertex read `modelId`, Azure `deploymentName`), so every Dream cycle on
  Ollama failed with `model is required`. The model now goes under the provider's key, and an
  unset `llmModel` no longer overwrites the inherited model with `null`.
  [`SummarizationService.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/SummarizationService.java)
- **The documented Dream schedule was rejected, and a user-less one accepted.** The schedule JSON
  in `docs/user-memory.md` has no `message` — a Dream fire dispatches to `DreamService`, not to a
  conversation — and failed with "message is required for CRON triggers". Conversely a Dream
  schedule without a `userId` was accepted and then failed on every fire until it dead-lettered.
  Dream schedules no longer need a message and are rejected up front without a real user.
  [`RestScheduleStore.java`](../../src/main/java/ai/labs/eddi/engine/schedule/rest/RestScheduleStore.java)
- **Dream could not detect the contradiction the docs describe.** "Agent A stored English,
  agent B stored German" was invisible by default, because the whole cycle was scoped to the firing
  agent's own entries. Detection is read-only, so it now considers every entry for a key the firing
  agent holds (a disagreement between two *other* agents is still not its to report) while pruning
  and summarization keep the ownership scope. Contradicting values are logged at DEBUG only —
  another agent's `self` memory does not belong in an INFO log.
  [`DreamService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/DreamService.java)
- **Strict write discipline: no same-turn fallback, a noisy digest, and a silent `keep_all`.**
  Reproduced live with a failing LLM task: every such turn returned an empty reply in `ERROR`, a
  rule on `task_failed_ai.labs.llm` could only answer on the *next* turn (and the one after failed
  again), the "concise" digest carried `dev.langchain4j.exception.ModelNotFoundException` and the
  provider's raw JSON body, and `keep_all` emitted no `task_failed_*` action at all although the
  documentation promises it whenever the discipline is on. New opt-in
  `strictWriteDiscipline.continueOnFailure` keeps running the remaining tasks after the failure is
  recorded, so an output keyed on `task_failed_<taskId>` — or an LLM task after a failed HTTP call —
  answers in the same turn; the default still stops the pipeline as before. The digest now strips
  exception class names and reduces a JSON error body to its `message`, and `keep_all` emits the
  action on top of the actions the task itself added.
  [`LifecycleManager.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManager.java)
- **Undo did not undo properties.** It popped the step and nothing else, so a slot filled by the
  undone turn stayed filled — in the conversation and in long-term memory (reproduced live: after
  undo, both a conversation-scoped and a `longTerm` property still held the undone value). Each
  completed turn now records its property changes on its step (`properties:changes`, uncommitted
  and non-public), which undo reverts and redo re-applies; `ConversationService` carries the
  `longTerm` part into the user-memory store, but only into the entry this turn wrote: same value,
  last written by this conversation, at the identity the turn persisted it under (the shared
  `global` row and the agent's own row can hold the same key, even the same value). A change made
  since by another conversation or agent is left alone, and so is anything ambiguous. The restored
  entry is persisted the way a turn persists it (`ConversationGroups.persistedVisibility`, with the
  deployed agent's `userMemoryConfig`), so undoing a visibility change undoes it in the store and a
  redone `group` property comes back as `group`. A new turn
  now also clears the redo stack: redo after *undo → new message* used to graft a step from the
  abandoned timeline. Turns completed through a HITL resume record no changes (undo leaves their
  properties as before).
  [`ConversationMemory.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemory.java),
  [`ConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java)
- **REST and MCP memory writes: a 500, no limits, unreadable entries, all-required MCP args.**
  `PUT /usermemorystore/memories` without `visibility` crashed with a NullPointerException (HTTP
  500); REST accepted a 1 MB value and any category; MCP accepted a 300-character key REST rejects;
  and both would store a `self`/`group` entry that no agent could ever read (no owning agent, no
  group). Both paths now share `UserMemoryWriteRules`: `visibility` required, `self`/`group` need a
  `sourceAgentId`, `group` needs `groupIds`, known categories only (`legacy`/`property` included),
  keys ≤ 255 and values ≤ 64 KiB of JSON; an absent category is stored as `fact`. MCP
  `upsert_user_memory` gained a `groupIds` argument, and the optional arguments of the memory tools
  are finally declared `required = false` — every one used to be advertised as required.
  [`UserMemoryWriteRules.java`](../../src/main/java/ai/labs/eddi/configs/properties/UserMemoryWriteRules.java),
  [`RestUserMemoryStore.java`](../../src/main/java/ai/labs/eddi/configs/properties/rest/RestUserMemoryStore.java),
  [`McpMemoryTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpMemoryTools.java)
- **Recall and summary turn numbers were one too high.** Both labelled a turn by its output index
  + 1, and index 0 is the conversation's opening step — so the user's first message was "Turn 2"
  (reproduced on Claude), and a model asked for "turn 3" got something other than what the user
  meant. Turn N is now step N: the opening step is turn 0, the user's first message turn 1; ranges
  are inclusive, reversed ranges are normalised, and the opening step no longer renders an empty
  user line. A turn number beyond `int` means the last summarized turn instead of throwing
  `NumberFormatException` out of the tool, and a range ending at `Integer.MAX_VALUE` no longer
  overflows into an empty result.
  [`ConversationRecallTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ConversationRecallTool.java),
  [`ConversationSummarizer.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationSummarizer.java)

### Documentation

`docs/user-memory.md` (what `enableMemoryTools` gates, `onCapReached`, re-save semantics, the upsert
identity, group memory, write validation, the Dream schedule's `message`/`userId`, consolidation
safety, cross-agent contradiction detection, the real `eddi.dream.*` metric names — the page listed
`dream.*` — and the migration's skip rules), `docs/memory-policy.md` (`continueOnFailure`, a
same-turn-fallback example, the digest's shape, `keep_all`'s action), `docs/conversation-memory.md`
(undo/redo and properties), `docs/properties.md` (default visibility and group scope),
`docs/secrets-vault.md` (per-write slots and their cleanup), `docs/langchain.md` (turn numbering) and
`AGENTS.md` §5.3, which claimed every matching rule in a group fires — by default only the first one
does (`executionStrategy: executeUntilFirstSuccess`).

### Known limitation, deliberately left

Memories written during the `CONVERSATION_START` turn carry no `sourceConversationId`: the
conversation id is minted by the first store, after that turn has run. Pre-assigning it would change
both conversation stores' "an id means update, and fail if the document is gone" guard, which exists
for concurrent-erasure races; the field is provenance only (nothing reads it), so the trade is not
worth making here.
