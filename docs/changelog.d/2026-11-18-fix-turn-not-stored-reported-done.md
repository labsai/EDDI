## 🐛 fix(engine): a turn is never reported done unless it was stored (2026-11-18)

**Repo:** EDDI (`fix/turn-not-stored-reported-done`)

### What changed and why

The November 2026 review (findings A1, A2, A5, A6, A9, A11, A12, A13; live L1, L6, L10, P2, P3, M1) showed that a
say turn was answered **before** it was written. When the write then failed — a MongoDB document over 16 MB, a
PostgreSQL data error, a cluster fence, a revision conflict, any unchecked driver exception — the caller had a `200`
with the answer and `READY`, the step did not exist, and an unchecked exception even bypassed the "forget this turn"
handling. Six turns in a row were lost this way in the live test.

- **Reply after the write.** `ConversationService#completeTurn` now defers the delivery of every say turn (it did so
  for pauses only) through [`DeferredDelivery`](../../src/main/java/ai/labs/eddi/engine/internal/DeferredDelivery.java);
  `ConversationStepRunner` delivers it once the write's outcome is known. Every exception of the write — checked or
  unchecked — forgets the turn for `Idempotency-Key` replays, marks the conversation `ERROR` (except after a conflict or
  a fence, whose winner owns the document), is counted as `eddi_turn_not_stored_total{reason}` and logged with the
  conversation, agent and reason. A streamed pipeline failure is still reported after the final snapshot.
- **The caller is told.** [`TurnNotStored`](../../src/main/java/ai/labs/eddi/engine/internal/TurnNotStored.java)
  classifies the failure (`conversation_too_large` for MongoDB codes 10334/17419, `BsonMaximumSizeExceededException`,
  PostgreSQL SQLSTATE class 54 and the size guard; `data_rejected` for SQLSTATE class 22; `conflict`; `fenced`;
  `store_failure`) into `error.code` `TURN_NOT_STORED` with a fixed message — no driver text. The REST say/rerun
  answers `507`/`422`/`409`/`503` (with `Retry-After` when retrying helps) and never `200`; a stream ends with an
  `error` event `{"code":"turn_not_stored","reason":…}` instead of `done`; every other surface gets the turn as `ERROR`
  with that error.
- **Size guard (A2).** `eddi.conversations.max-document-bytes` (default 14 MiB) is checked before every conversation
  write on both backends by [`ConversationDocumentSize`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationDocumentSize.java),
  an estimate that walks the snapshot (UTF-8 lengths, per-field overhead) instead of serialising it; over it, the write
  is refused with `ConversationTooLargeException` and reported as above. What is stored is unchanged.
- **Long-term memories after the write (A5).** `Conversation` no longer upserts changed `longTerm` properties inside the
  pipeline: it hands the write to the committer (`IConversationMemory#deferUntilCommitted`), which runs it right after a
  successful store (say and resume). A turn that is not stored writes no user memories; a failed upsert marks the
  conversation `ERROR`, so the next turn rewrites every long-term property.
- **Engine keys are not memories (A6).** `IUserMemoryStore.isEngineKey` (`conversation:*`) joins the GDPR prefix in
  `rejectReservedKey`, so both stores, REST, MCP, the `rememberFact` tool, the property setter's write-back, Dream and
  the legacy migration refuse or skip them; an entry stored earlier is ignored when memories are loaded.
- **Smaller state fixes.** Undo/redo on an `ENDED` conversation change nothing and answer `410` (A9);
  `endConversation` on an unknown id answers `404`, and the sync timeout's `408` carries a JSON body (A11); a turn on an
  agent whose deployment failed (deleted agent, missing config) answers `404` "Agent is not deployed or not ready"
  instead of `500` (A12); the "Error while processing user input" log line names the state the conversation really
  has (A13).

### Behaviour an upgrader notices

- Every say answer waits for its write (one store round trip of latency).
- A failed write is a non-`200` answer (REST) or an `error` stream event — clients that treated every `200` as stored
  were wrong before; now they are told.
- A conversation over 14 MiB refuses further turns (`507`); raise or disable the property if needed.
- `conversation:*` user memories can no longer be written; existing ones are ignored.

**Files:** `ConversationService`, `ConversationStepRunner`, `ConversationHitlService`, `DeferredDelivery`,
`TurnNotStored`, `RestAgentEngine`, `RestAgentEngineStreaming`, `Conversation`, `ConversationMemory`,
`IConversationMemory`, `ConversationDocumentSize`, `ConversationTooLargeException`, `IUserMemoryStore`,
`UserMemoryTool`, `RestUserMemoryStore`, `RestPropertiesStore`, `McpMemoryTools`, `DreamService`,
`PropertiesMigrationService`; docs [conversation-memory](../conversation-memory.md#when-a-turn-is-not-stored),
[configuration-reference](../configuration-reference.md), [metrics](../metrics.md),
[rest-api-reference](../rest-api-reference.md), [user-memory](../user-memory.md), `AGENTS.md`.

### Left open

- The HITL resume path still answers before its write (its long-term writes now wait for it).
- `/status` during a running turn (A10), the storage diet (A3/A4), NUL stripping on PostgreSQL and streaming
  finish-reason semantics are separate branches.
