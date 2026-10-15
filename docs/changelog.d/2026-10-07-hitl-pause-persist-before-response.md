## 🐛 fix(hitl): deliver a paused turn only after the pause is persisted (2026-10-07)

**Repo:** EDDI (`fix/hitl-pause-persist-before-response`)

### What changed and why

A turn that ended `AWAITING_HUMAN` told its client "paused" **before** the pause was written. The completion callback fires from inside the pipeline callable and answered the sync `say` response / the streaming `done` event; the paused memory was only stored afterwards by `ConversationStepRunner.runGuardedConversationStep`'s runtime `onComplete` (`populateHitlTimeoutBookmark`, `storeConversationMemoryIfState`, cancel re-check, pause counter, timeout schedule). A client that reacted to the answer by reading `GET /agents/{id}/approval-status` (which reads the **stored** snapshot) could see `state != AWAITING_HUMAN`, `pauseId: ""` and `pauseDetails: null` — the Manager rendered an approvable card with no details, and the chat widget could treat the pause as already resolved. The same ordering applied to a `resume` that re-pauses, and to every consumer of `say` (MCP chat tools, the OpenAI-compatible `/v1` API, group member turns, the quarkus-eddi SDK).

The paused outcome is now delivered from the persistence step:

- `ConversationService.completeTurn` (new) decides when a turn may be answered. A turn whose snapshot is `AWAITING_HUMAN` hands its completion to a one-shot `DeferredDelivery` (new) instead of calling the handler. Every other turn is answered immediately, exactly as before, so ordinary latency is unchanged.
- `ConversationStepRunner.runGuardedConversationStep` flushes the deferral from a `finally` after the commit outcome is known, with the state the store holds: `AWAITING_HUMAN` only when the pause was durably committed and not cancel-raced; otherwise the stored state (lost compare-and-set, cancel conversion to `EXECUTION_INTERRUPTED`, store failure, stale-pause discard). The state cache is updated from the same value, so a lost commit no longer caches a phantom pause. `onFailure` and the task's own `finally` flush a still-pending delivery, so the stream always terminates exactly once.
- `ConversationHitlService.resumeConversation` gets the same treatment for a resume that re-pauses (`resumeDelivery` / `committedResumeState`), flushed from `resumeFinished.onComplete`/`onFailure` and the guarded resume's `finally`.

### Design decisions

- **Narrow: pause turns only.** An ordinary turn's snapshot already matches what the store will hold, and the coordinator serializes the next turn behind this one (a stale-snapshot guard rebuilds a queued turn over the committed document), so holding every response until persistence would add a store round-trip to every turn for no consistency gain. Only a pause is a promise the client acts on immediately.
- The delivery runs inside the same coordinator turn on the same thread, after the commit, so turn ordering and the `processingTurn` release point are preserved; handlers that only complete a future (group member turns, MCP) cannot deadlock on it.
- `DeferredDelivery.deliver` never throws: it runs from `finally` blocks, where an escaping exception would be reported through the Future and flip a healthy conversation to `ERROR`.

### Files

`ConversationService`, `ConversationStepRunner`, `ConversationHitlService`, new `DeferredDelivery`, new `PausedTurnDeliveryOrderTest`, `docs/hitl.md` (ordering guarantee).

### Platform Operator

No change: no endpoint, config field or default changed; only the order in which an already-documented response is sent.
