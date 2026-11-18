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
  successful store (say, start and resume). A turn that is not stored writes no user memories; a failed upsert is made up by the next
  turn (see the follow-up entry below).
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

## 🐛 fix(engine): review follow-up — start-turn memories, no "done" without a confirmed write (2026-11-18)

**Repo:** EDDI (`fix/turn-not-stored-reported-done`)

### What changed and why

An independent review of the branch found one regression and eight gaps.

- **Start-turn memories (M1).** Long-term properties set by the `CONVERSATION_START` turn were never written: the
  start path stores the conversation but did not run the deferred write. The deferred write now runs inside the
  runner's store methods (`ConversationStepRunner#commitDeferredWork`), so every path that stores a turn — say, start,
  resume — runs it. A write dropped with an unstored turn is counted
  (`eddi_turn_long_term_writes_total{outcome="discarded"}`).
- **Durable owed markers (S5).** The stored document keeps `pendingLongTermWrites` until the user-memory write has
  succeeded; then a second, conditional write clears them. A failed write no longer marks a stored turn `ERROR` (that
  made schedules, A2A and `/v1` retry a stored turn); it is counted (`outcome="failed"`) and the next turn makes it up.
- **Never "done" without a confirmed write (S1, S2).** The backstop delivery (watchdog gave up during the write, a
  failure after the answer, a dropped turn) now reports `outcome_unknown` or `fenced`, never the answer; a turn whose
  lease was lost reports `fenced`.
- **Dead letters (S3).** Lease-lost, fenced and conflict dead letters are marked `answeredRetry` (not replayable):
  their callers were told to send the turn again.
- **No answer on any surface (S4).** A not-stored snapshot carries no outputs, steps or properties
  (`TurnNotStored.withoutAnswer`), so MCP, Slack, the sub-agent tools and group members cannot present it as a reply;
  Slack posts the error text, MCP adds the `error`.
- **Size limit at admission (S6).** A conversation already over `eddi.conversations.max-document-bytes` is refused
  before the pipeline runs (507 `CONVERSATION_TOO_LARGE`; stream code `conversation_too_large`, added to the UI's
  pre-turn refusal codes). Undo/redo and the resume of a pending approval are no longer size-guarded.
  **PostgreSQL:** conversations there had no size limit before; one over 14 MiB now refuses new turns.
- **Size estimate calibrated (S7)** against the BSON the MongoDB store writes (field names, ISO timestamps): never
  below it, at most 35% above, in a test.
- **Wording (S8).** `store_failure` (database did not confirm) and `outcome_unknown` say the turn *may* not have been
  stored and are not retryable; a conflict with an end answers 410 `conversation_ended`.

### Left open

- The HITL resume path still answers before its own write (documented in conversation-memory.md).
- UI: a "not saved — retry" affordance for `turn_not_stored` (the error is shown; the partial answer stays on screen).

## 🐛 fix(engine): second review — no stale long-term writes, late writes fenced (2026-11-18)

**Repo:** EDDI (`fix/turn-not-stored-reported-done`)

### What changed and why

- **Start turn (F1).** The store's id is put on the memory of a new conversation, so the start turn's owed markers are
  cleared like any turn's (before, its first say turn re-wrote them and could overwrite a newer value).
- **Make-up writes yield (F4).** An owed key now carries the time this conversation changed it
  (`pendingLongTermWritesSince`, a new field of the stored conversation; older documents read it as empty). A write
  that makes up for an earlier turn reads the user memory store first and leaves a value that is already equal, or
  newer than that time, alone (`eddi_turn_long_term_writes_total{outcome="skipped_newer"}`). A key changed in the
  current turn keeps last-writer-wins.
- **Late writes are fenced (F2).** On a single node every turn now writes with the fencing token of the document it
  loaded; when the watchdog gives up on a turn whose write is still running, the document's fence is raised above
  that token (one write, on that path only), so the late write is refused instead of landing next to the caller's
  retry. The next turn on the node writes with the raised token.
- **Marker clean-up after the answer (F3).** The conditional clearing write runs after the caller has its answer; a
  miss is benign (no WARN, not counted as a conflict).
- **One claim per delivery (F5).** A not-stored delivery claims the turn's answer with one compare-and-set, and only
  the claimant counts it.
- **Channel-neutral texts (F9)** for Slack and tool results; the REST messages are unchanged.
- **Calibration (F13)** also covers non-ASCII text, numbers, nested values, properties and a redo cache.

### Behaviour an upgrader notices

- A say turn's answer waits for the conversation write and, when long-term properties changed, for the user-memory
  write.
- Single-node conversations get a `_fence` field (0 until a write is abandoned).

### Left open

- In a cluster the late write of an abandoned turn is fenced only when the conversation's next turn starts (that turn
  raises the fence); between the two it can still land.
- In NATS degraded mode (several nodes, no leases) a fence raised on one node is not known to another: that node's next
  turn of the conversation is refused as `fenced` once and succeeds after a reload.
- The size check at admission walks the stored snapshot once more per turn (an estimate walk, no serialisation): about 95 ms for a 16 MB document of 2,000 steps x 30 results on a desktop JVM, so roughly 190 ms per turn at that size with the write-time check; small conversations cost well under a millisecond.
- The scheduler and continuations still treat a refused over-limit conversation like any failure (retry); that is
  another branch.
