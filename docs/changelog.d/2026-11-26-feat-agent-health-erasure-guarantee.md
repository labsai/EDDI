## Agent Health adopts the GDPR erasure guarantee (2026-11-26)

**Repo:** EDDI · **Branch:** `feat/agent-health`

`main` gained the hard erasure guarantee for asynchronously written rows (#1450):
durable tombstones, guarded writes (`ErasureGuard.beforeWrite` / `afterWrite` with an
`Admission`), a drain barrier per async writer, and verification. This branch merged
`main` and replaced its best-effort `HealthErasureFence` with it, as the GDPR guide's
"For a new async writer" contract and `HealthShapedWriter` describe. This answers the
open review finding that a queued health row could land after an erasure or a
permanent conversation delete reported success.

- **`HealthErasureWriter`** (new, `engine/health`): the `AsyncErasureWriter` bean named
  `agentHealthTurns`. Its drain barrier is the health worker's queue; `removeRow`
  deletes one row by `<conversationId>|<executionId>`; `eraseConversation` and
  `countForConversation` delete and count a conversation's rows and markers.
- **`HealthTurnRecorder`**: queues every write through a `DrainBarrier` (the bounded
  executor keeps `AbortPolicy`), asks `beforeWrite` with the turn's capture time and
  writes neither the row nor its discard markers when refused, and calls `afterWrite`
  for the row and each marker after the write, also after a failed one. The
  fence and the per-write conversation re-read are gone.
- **`HealthTurnGdprParticipant`**: deletes the user's rows, then
  `verifyUserErased(agentHealthTurns, …)` over a count that includes markers.
- **`IHealthTurnStore`**: `deleteRow`, `countByUserId` and `countByConversationId`, in
  both stores. The MongoDB counts read the primary with majority read concern.
- **`ConversationPurgeService`**: a guarded purge erases the health rows through the
  guard. The best-effort `deleteHealthTurns` stays only for the unguarded agent-test
  purge, and the orphan sweep remains for that case.
- Docs: `agent-health.md` (Retention, GDPR, Metrics), `gdpr-compliance.md` item 18
  and the verify step, `metrics.md`, and the Agent Health dashboard panel text.
- The Agent Health dashboard row moved to panel ids 466–468, after `main`'s GDPR
  Erasure Guarantee row (463–465).
- Tests: `HealthTurnRecorderTest` runs a real `ErasureGuard` over in-memory tombstones.
  It covers erasure and conversation delete while queued and while writing, a failed
  write, a failed removal that keeps the erasure failing until a retry, unreadable
  tombstones, and the drain barrier. `HealthTurnStoreContract` covers the new store
  methods, and `ConversationPurgeServiceHealthTest` covers the guarded purge.

```decision-log
| 2026-11-26 | Agent Health turn rows are an `AsyncErasureWriter` (`agentHealthTurns`) of the GDPR erasure guarantee; `HealthErasureFence` and the per-write conversation re-read are removed | A node-local, 15-minute fence plus clean-up was best effort, and main now offers a guarantee every async writer adopts | Keeping the fence next to the guard; one guarded removal per conversation instead of per row |
```

## Agent Health: failed turns, terminal streaming timeouts and failed revisions (2026-11-26)

**Repo:** EDDI · **Branch:** `feat/agent-health`

Review follow-ups on PR #1386 (the CodeRabbit round on `1e0797c560`).

- **A failed turn whose snapshot was not stored marks nothing discarded.** A
  `TurnWait.FAILED` turn was always recorded as settled, so a failed say after an undo
  marked the undone executions discarded although the conversation still holds them.
  `ConversationService.reportHealthTurn` now takes the snapshot tracker's answer as is
  and decides there: a completed turn that was not stored stays unsettled, as before; a
  failed one is still upserted as failed, but discards only when its snapshot was
  stored (`HealthTurnRecorder.record` / `HealthTurnFactory.capture` gained a `discards`
  flag). It is not made insert-only: a failed resume or rerun keeps its step's
  execution id, and an insert-only row would have left the paused or earlier row
  standing and the failure invisible.
- **A stream that timed out with no text is recorded.** The streaming executor returns
  an empty result rather than throwing, so no catch recorded the final attempt.
  `LlmTask` now records one `TIMEOUT` entry after response validation — `fallback` when
  a fallback answered, else `none`, also when an `onStreamingTimeout: error` fails the
  task. In a cascade only the last step streams; when it times out with no earlier
  answer to serve, the entry carries that step's model and every step the cascade
  escalated past in the phase takes the same recovery (a cascade that ended in a
  failure recovered nothing). A timeout that serves an earlier step's answer keeps
  its `cascade` entry. A timeout
  after partial text is left as it was: the text is kept and no failure recorded.
- **A HITL revision whose model call fails is recorded** with `none`. Only the model
  call is covered: validation, guardrail, template and configuration failures are not
  model failures, and neither is a tool exchange that cannot be re-asked (now a
  dedicated `ReaskUnavailableException`).
- Docs (`agent-health.md`): the spend equality holds only when every turn is recorded
  and every discard tracked; `day` and `timestamp` are the time a turn was first
  captured, not when it started; the new failure-record rules.
- Tests: `ConversationStepRunnerHealthHookTest` (a failed turn before and after its
  store), `LlmTaskStreamingTimeoutHealthTest` (new), `LlmTaskHitlRevisionTest`. In
  `HealthTurnRecorderTest` the polling loop sleeps instead of spinning (the timeout's
  interrupt ends it), and every "nothing more happened" check waits for the drain
  barrier instead of 300 ms.

```decision-log
| 2026-11-26 | A failed turn whose snapshot was not stored is still upserted as failed but marks no execution discarded | The conversation keeps the executions its step lists, while a failed resume or rerun keeps its execution id, so insert-only would hide the failure behind the paused or earlier row | Recording it unsettled (insert-only), as for an unstored completed turn |
```
