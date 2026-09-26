## 🐛 fix(engine): conversation turn state — queued turns, pauses, ENDED, redo, groups, NATS, migrations (2026-09-26)

**Repo:** EDDI (`fix/conversation-turn-state`)

Eleven findings from the 2026-09-25 review, all about a conversation turn ending
up in a state it should not have. Each has a regression test that fails without
the fix.

### What changed and why

- **H13a / E1 — a queued turn ran on stale memory.** `say()` loaded the memory at
  request time and queued the turn behind the same conversation's running turns.
  When it finally ran, its `lastStep` rules, LLM history and property writes were
  computed without the previous turn, and the append merge re-applied its stale
  conversation properties over the ones that turn wrote (last writer wins).
  `ConversationStepRunner` now reads the stored revision (a new projection read,
  `IConversationMemoryStore.getRevision`) before running a queued turn. If the
  document has moved on, it reloads the memory and rebuilds the turn over it. The
  rebuild goes through a `TurnBuilder` that `say()`/`sayStreaming()` hand to the
  runner in place of a finished callable, because the `Conversation` captures its
  longTerm baseline from the memory it is built over. A turn that cannot be rebuilt
  (the agent went away meanwhile) is reported as skipped, not run on the stale
  snapshot. A queued **rerun** is never rebuilt: it re-executes the step the caller
  saw, and rebuilt over a newer memory it would re-execute another turn's step (its
  tool calls and LLM spend included), so a superseded rerun is skipped. The REST
  `say` endpoint now answers a skip by the state it happened in: ENDED → 410,
  IN_PROGRESS → 409 "retry shortly", and an idle conversation (superseded rerun,
  failed rebuild) → 409 "the conversation changed while your message was queued —
  reload it before sending again" instead of the misleading "retry shortly".
  `IConversationMemoryStore.getRevision` is abstract, so a future store cannot
  silently disable the check. Residual: two turns of one conversation running concurrently on
  *different* pods still merge with last-writer-wins on the top-level fields,
  which is what the append path already documents.
- **E2 — a lost pause stayed in the state cache.** The say path caches the state a
  turn ended in from inside the pipeline, before the persist. When the pause commit
  was then refused (a revision or state CAS miss), the cache kept AWAITING_HUMAN for
  a conversation the store held as READY, and `/resume` failed. Every
  refused or discarded outcome now re-reads the persisted state into the cache.
  (H13a also removes the most common cause, a queued turn built on an old revision.)
- **State cache expiry.** The `conversationState` cache never expired, and several
  writers never touch it (other pods, the HITL timeout and crash-recovery paths).
  Entries now expire 30 s after they are written
  (`ConversationService.CONVERSATION_STATE_CACHE_TTL`).
- **E4 — a full-document write overwrote ENDED.** Ending a conversation is a narrow
  state write that does not move `_rev`, so a turn already running (a rerun, or a
  memory whose append baseline is unknown) still passed the revision guard, and its
  replace resurrected the conversation. It happens on one pod, because
  `endConversation` runs on the REST thread. Both stores' full replace now refuses
  a stored ENDED unless the write itself is ENDED. The runner reports that refusal as
  "the end stands" at INFO, and it does not count as a store conflict.
- **M-E1 — lazy redeploy before the ENDED check.** `say()` called `getAgent` (which
  deploys an undeployed agent, without the tenant gate or a deployment-store write)
  before it found out the conversation had ended. The ENDED check now comes first.
- **M-E2 — group-visible memories lost their groups.** `UserMemoryEntry.fromProperty`
  hard-coded `groupIds = []`, so a `visibility: group` property could never be
  recalled (recall matches on `groupIds`). A recalled group memory also lost its
  groups when it was written back. `Property` now carries the `groupIds` of the
  entry it was recalled from. On write the groups come from, in order: the
  property's own, then the baseline property it replaced, then the `groupId`
  context of the turn itself. An earlier step's `context:groupId` is deliberately
  NOT used: a step written before reserved context keys were enforced may carry a
  client-set value. So a resume, which has no context of its own, writes a
  brand-new group property without groups. Once PR 831 (reserved context keys)
  merges, this should switch to its verified group resolver. `MemoryCheckpoint`
  copies the field too. A replacement property adopts the groups of the property it
  replaced (and a written entry's groups are kept on the live property), so
  re-setting a group memory to the same value is not a write, and a later turn
  outside the group or resumed from a pause does not write it back with none.
  `groupIds` cannot be configured: `PropertyInstruction`
  ignores it in `property.json`. Recalled group ids do show up in serialized
  conversation properties over REST and MCP — they are the user's own memory's
  groups.
- **M-E3 — redo resurrected an abandoned branch.** A new turn never cleared the redo
  cache, so undo → say → redo spliced the withdrawn step back in. The public
  `ConversationMemory.startNextStep()` now clears it. The package-private overload
  that rebuilds a loaded conversation does not, so a stored redo cache survives
  the load.
- **Step-scoped properties now clear on every exit.** They were dropped only from
  the clean-exit post-tasks. After an ERROR, cancel or abandon they reached the
  persisted snapshot and the next turn's `{properties.x}`. They are now dropped in
  the turn's `finally` on every exit except a HITL pause. The resumed step still
  needs them there, and they are dropped when it ends.
- **M-E4 — NATS coordinator.** (1) A queued turn that `submitNext` could not
  schedule is now told through `IDiscardableTask.onDiscarded`, as the in-memory
  coordinator already did. Before, the REST caller waited for its 408 and an SSE
  stream hung. (2) `routeToDeadLetter` and the ordering publish also catch
  `RuntimeException`. An `IllegalStateException` from a closed connection escaped
  `submitNext` and wedged the conversation's queue. A failed marker publish now
  degrades to local execution. (3) The conversation stream, whose messages
  nothing consumes, is bounded by age, count and bytes, and discards the oldest
  first: `eddi.nats.stream-max-age` (1h), `eddi.nats.stream-max-messages`
  (100000) and `eddi.nats.stream-max-bytes` (256 MiB).
- **16 MB — persistent heartbeats grew without bound.** A `conversationStrategy:
  persistent` schedule appended a step on every fire to one document until MongoDB
  refused it, and from then on every fire was lost. The rollover is **opt-in**:
  with `eddi.schedule.persistent-conversation-max-steps` set (default `0`, off), a
  conversation that has reached that many steps AND is idle (READY, ERROR,
  EXECUTION_INTERRUPTED) is ended and replaced by a new one. The
  `conversation`-scoped properties are copied into the new conversation (what its
  own start turn set wins). `longTerm` properties need nothing, they live in user
  memory. The **LLM history does not carry over**. A conversation that is
  AWAITING_HUMAN or busy is never rolled over: that would end the pending approval
  (audited as a scheduler decision), abort a running resume or refuse a human's
  in-flight turn. When enabled, every persistent schedule already past the limit
  rolls over on its first idle fire. While the rollover is off, a persistent
  conversation past 1000 steps is reported once at WARN. Nothing is trimmed and the
  old conversation stays readable. A persistent conversation that has ENDED is
  replaced now as well; every fire into it used to be refused, forever. The fire
  reads the stored conversation once, raw, rather than converting every step into
  a response snapshot. Only a conversation the store reports missing (or an id it
  cannot parse) or one of another agent counts as gone: any other load failure now
  fails the fire, which is retried against the same conversation, instead of
  silently repointing the schedule at a fresh one. A rollover whose end call fails
  keeps the old conversation until a later idle fire, and properties are carried
  only when the old conversation belongs to the schedule's current user. See [scheduling.md](../scheduling.md#long-running-persistent-schedules).
- **E3 — migrations marked complete on empty collections.** The V6 Qute, channel
  connector and workspace access-index migrations read the collections the V6 rename
  migration populates. If the rename migration was still pending, they found
  nothing, recorded themselves complete, and never ran again. They are now parked
  until it completes, and they are not flagged. The first deployment sweep that sees
  the rename complete runs them, before it deploys anything or reports ready — so a
  migration-log read that failed transiently at boot no longer defers them to the
  next restart.
- **E6 — the deployment dedupe deleted the live row.** `replaceOne` rewrites the first
  matching row, which is the oldest. The dedupe kept the newest, so it deleted the row
  every deploy/undeploy had written to. `setDeploymentInfo` now stamps
  `lastModified`. The dedupe keeps the most recently stamped row, and among rows
  written before the stamp existed, the lowest `_id`, i.e. the one `replaceOne`
  was rewriting. That order only breaks ties: when the rows disagree on their status
  and there is no strictly newest stamp, the dedupe keeps the row the point read
  (`find(filter).first()`) returns — the status the store already reports — and if
  that cannot be read it keeps every row of the group rather than guess.

### Compatibility

- Stored data: `Property` gains an optional `groupIds` (absent on every existing
  property), and deployment rows gain `lastModified` (ignored by the reader). Both
  are additive. No migration is needed.
- REST/MCP shapes: unchanged, apart from the new nullable `groupIds` field on
  serialized properties. The REST `say` endpoint's answer to a skipped turn now
  depends on the state (410 for ENDED, a different 409 text for an idle
  conversation that changed under the queued message).
- `property.json`: a `groupIds` field on a property instruction is ignored.
- New config keys: `eddi.nats.stream-max-age`, `eddi.nats.stream-max-messages`,
  `eddi.nats.stream-max-bytes`, `eddi.schedule.persistent-conversation-max-steps`.
  They are documented in [configuration-reference.md](../configuration-reference.md).

**Files:** [`ConversationStepRunner.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java),
[`ConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
[`IConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/IConversationMemoryStore.java),
[`ConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryStore.java),
[`PostgresConversationMemoryStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java),
[`ConversationMemory.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemory.java),
[`Conversation.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java),
[`Property.java`](../../src/main/java/ai/labs/eddi/configs/properties/model/Property.java),
[`UserMemoryEntry.java`](../../src/main/java/ai/labs/eddi/configs/properties/model/UserMemoryEntry.java),
[`NatsConversationCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/NatsConversationCoordinator.java),
[`ScheduleFireExecutor.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/ScheduleFireExecutor.java),
[`AgentDeploymentManagement.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java),
[`MongoDeploymentStorage.java`](../../src/main/java/ai/labs/eddi/configs/deployment/mongo/MongoDeploymentStorage.java)

### Not done here

- Conversations that grow large *without* a schedule (a very long chat) still have
  no document-size guard. That needs the step-archival design the 2026-06 notes
  already call for, not a quick cap.
- Cross-pod concurrent turns of one conversation still merge top-level fields
  last-writer-wins (see H13a above).

```decision-log
| 2026-09-26 | A queued turn whose snapshot was superseded is rebuilt over a reloaded memory (revision probe first) | H13a/E1: queued turns ran on request-time memory | Always reloading (doubles every turn's load); refreshing the memory in place (the Conversation's longTerm baseline would stay stale) |
| 2026-09-26 | Persistent schedules may roll over (opt-in, idle only, conversation properties carried) at a step limit instead of trimming | 16 MB document limit | Trimming steps (destroys history, violates "full data is never deleted by optimization"); on by default (silently resets a stateful agent's history) |
```
