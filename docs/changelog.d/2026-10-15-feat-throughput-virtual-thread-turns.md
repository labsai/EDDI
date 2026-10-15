## perf(runtime): conversation turns on virtual threads, bounded by an in-flight limit (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/throughput-virtual-thread-turns`

### What changed and why

WS2 of the throughput plan. Each in-flight turn held one `ManagedExecutor` platform thread,
blocked on the pipeline's future for as long as the LLM took. The pool (~200 threads), which
blocking REST endpoints share, was therefore the node's turn ceiling. Past it, turns queued
without bound in front of the pool, and overload showed up as latency for everything rather
than as a rejection.

- **Turns run on virtual threads.** `IRuntime.ITurnExecution` is a marker sub-interface of the
  completion callback. Both coordinators (`AbstractQueuedConversationCoordinator.execute`, and
  `ClusterConversationCoordinator.runUnderLease`, which `submitNext` also goes through) now pass
  one, and [`BaseRuntime`](../../src/main/java/ai/labs/eddi/engine/runtime/BaseRuntime.java) runs
  those submissions on a dedicated virtual-thread executor (`eddi-turn-*`). The body is
  unchanged: thread bindings, the nested marker (so the pipeline still goes to the nested
  executor), the one-shot callback and the abandonment token. Deploys, retention sweeps and the
  parser endpoint stay on the pool. New gauge series:
  `eddi_runtime_executor_{active,queued}{pool="turn"}`.
- **Admission control: `eddi.conversation.max-in-flight-turns` (default `1000`).** The C11
  `ProcessingTurn` token is the permit. It is admitted with one CAS against the counter behind
  `eddi_processing_conversation_count`, so concurrent admissions cannot overshoot, and it is given
  back by the release that already fires exactly once on every exit path. It is admitted after
  idempotency admission (a duplicate key answered from the stored result takes no permit) and
  **before the tenant quota slot**, so a capacity refusal does not spend quota. The refusal,
  `TurnCapacityExceededException`, extends `RejectedExecutionException`, so every surface
  answers it with the existing 503 `capacity_exceeded` + `Retry-After`. It is counted in
  `eddi_turn_rejected_total{reason="in_flight_limit"}`.
- **`/v1/chat/completions` runs on a virtual thread** (`@RunOnVirtualThread` instead of
  `@Blocking`). Both the sync wait and the streaming body held a second worker thread per
  request next to the turn's own.
- **Monitoring.** `EddiExecutorSaturated` no longer describes turns. The new
  `EddiTurnCapacityReached` alert fires when turns are refused at the limit for 5 minutes; it
  has a runbook entry and a promtool case. The full-metrics dashboard's
  "Coordinator throughput" panel charts the rejection rate. `docs/metrics.md` and the
  configuration reference cover the new series and property.

### Measured (load-test harness of the WS0 PR, 3 s mock-LLM delay, 10 turns per conversation)

| Concurrent conversations | Before: turns/s · p50 · p95 | After: turns/s · p50 · p95 |
|---|---|---|
| 200 | 52–60 · 3.1–3.3 s · 3.5–4.4 s | 60 · 3.1 s · 3.6 s |
| 500 | 61 · 7.5 s · 9.8–10.1 s | 131 · 3.3 s · 4.2 s |
| 1000 | (not run: past the ceiling) | 269 · 3.2 s · 4.6 s |
| 1500 (limit 1000) | — | 182 · 3.6 s · 5.5 s; 8161 × 503, no timeout, no other 5xx |

Before, at 500 conversations, the managed pool was pinned at 199 active with up to 198 queued.
After, it stays at 0: turns peaked at 1029 active on `pool="turn"` with 326 live JVM threads.
MongoDB operations per successful turn are unchanged (≈ 22.7). A refused turn still costs its
conversation loads, because admission sits after them. That is WS3a's single-load work.

### Design decisions

- **A callback marker, not a new `IRuntime` method or a wrapped callable.** About 25 tests mock
  `IRuntime`, and a new method would return `null` there. The coordinator tests also assert
  callable identity (`eq(task)`) and `isNull()` bindings, which a wrapper would break. The
  coordinators already pass a callback; marking it changes nothing else.
- **`max-active-conversations` stays.** It caps how many conversations hold a turn queue and
  only ever refuses a *new* conversation; the new limit caps turns. Both are documented side by side.
- **Rejections log at DEBUG in `ConversationService`.** `RestAgentEngine` already logs one WARN
  per 503, and under overload a second line per refused turn only doubles the flood.

### Next

- `RestGroupConversation.discuss` and the other synchronous group endpoints still hold a worker
  for a whole discussion. Converting them is a JAX-RS-interface change; it is a separate piece.
- WS1 (configuration cache), then WS3a (one conversation load per turn, which also makes refused
  turns cheap).
