## 🐛 fix(turns): queued-turn guard on the one-read path, busy skips and inert abandoned outcomes (2026-11-06)

**Repo:** EDDI (`fix/turn-runner-guards`)

### What changed and why

Four holes in how a queued turn starts and how an abandoned one ends, all in `ConversationStepRunner`
plus one in the SSE stream.

- **A conversation deleted while its turn was queued (RT-1).** Both stores answer a missing document
  with no state and no revision. The queued-say guard skipped only `AWAITING_HUMAN`, `IN_PROGRESS` and
  `ENDED`, so a turn queued during a delete or a GDPR erasure ran on its request-time snapshot: model and
  tool calls, and `storePropertiesPermanently` wrote the erased user's long-term properties back after
  the erasure's memory sweep. Only the final persist failed. Such a turn is now skipped and reported as
  `ENDED`: REST answers `410`, a stream ends with `done`, and the clients already start a new conversation
  on `ENDED`.
- **A store failure at that guard (RT-2).** The state/revision read ran outside any `try`. A pool timeout
  there escaped the task, the coordinator dead-lettered the turn, and its caller never heard back (REST
  waited for its `408`, a stream got no terminal event). The same held for a runtime that refused the
  body (`RejectedExecutionException`). Both now answer **busy** (`409` + `Retry-After`, `done` with
  `IN_PROGRESS` on a stream; the input was not consumed), the contract the fence branch already used for a
  store failure. The three busy paths share one `skipAsBusy`.
- **The late outcome of a turn the watchdog abandoned (RT-5).** After a watchdog timeout the coordinator
  starts the next turn while a body that swallowed its interrupt keeps running. When it finally ended, its
  callbacks settled it as a cancelled turn, keyed only by conversation id: on one node that stamped
  `EXECUTION_INTERRUPTED` over the `READY` the next turn had committed (and made that turn's pause commit
  miss); in cluster mode its expired lease put a lease-lost record in the map the next turn drains, so the
  next turn was dead-lettered as lease-lost after it had answered `200`. Once the dispatcher has given up,
  the outcome now only refreshes the state cache. The same holds for a persist the completion callback
  started just before the watchdog gave up and that failed afterwards: a fence or revision-guard refusal is
  no longer recorded for the next turn to drain (which dead-lettered that turn), and a store failure no
  longer stamps `ERROR` over the next turn's state. The comments that said a node runs at most one turn of a
  conversation at a time were wrong and are corrected.
- **A stream whose turn hung past the watchdog (RT-2).** REST has its `408`; a stream had nothing and was
  kept open by the keep-alive for as long as the body hung. Once the turn has written its first event, a
  stream that then carries none of its events for `systemRuntime.agentTimeoutInSeconds` plus one
  keep-alive interval is ended with an `error` event, code `turn_timeout`. A live turn cannot reach that
  (the watchdog bounds it), a queued one has written nothing yet, and with the keep-alive off the backstop
  is off too.

`ConversationServiceQueuedTurnTest` now stubs `getStateAndRevision`, the path both production stores
take; before, every queued-turn test ran on the legacy two-read fallback. One test keeps the fallback
covered.

**Files:** [`ConversationStepRunner.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java),
[`RestAgentEngineStreaming.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngineStreaming.java),
[`configuration-reference.md`](../configuration-reference.md), [`hitl.md`](../hitl.md) (the resume stream's
`turn_timeout`), [`rest-api-reference.md`](../rest-api-reference.md) (the stream's `turn_timeout` code); tests in `ConversationServiceQueuedTurnTest`,
`ConversationStepRunnerLeaseTest`, `ConversationStepRunnerAbandonedTurnTest` (new) and
`RestAgentEngineStreamingKeepAliveTest`.

```decision-log
| 2026-11-06 | A queued turn of a vanished conversation is reported as ENDED through the skip contract | RT-1: it ran on its stale snapshot and wrote erased properties back | A new 404 channel on the skip contract, which carries only a snapshot |
| 2026-11-06 | The late outcome of a watchdog-abandoned turn is inert (cache refresh only) | RT-5: it settled the next turn's state and dead-lettered it in cluster mode | Keying the failure maps by turn token: larger, and still needs the abandoned turn to stop writing state |
| 2026-11-06 | Stream backstop: end a started turn's stream after agentTimeout + one keep-alive interval of silence, code turn_timeout | RT-2: a hung abandoned body held its stream open indefinitely | Notifying the stream from the watchdog: the skip contract would report a turn that may still produce output as finished |
```
