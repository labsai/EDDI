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
  `ProcessingTurn` token carries the permit. It is taken with one CAS against a permit counter,
  so concurrent admissions cannot overshoot, and given back exactly once by `finish()`. Only the
  turn's own exits call `finish()`: the turn task's `finally`, its discard hook, and the
  pre-submission rollback. The gauge's existing `release()` still fires as soon as the client
  has its answer, but persistence runs after that on the same thread, so the permit stays held
  until then. Releasing it with the gauge let the node run past its limit (1029 active at 1000
  in the load test below). The permit is admitted after idempotency admission (a duplicate key
  answered from the stored result takes no permit) and **before the tenant quota slot**, so a
  capacity refusal does not spend quota. `startConversation` admits its CONVERSATION_START turn
  the same way, because that turn runs inline and can call an LLM. A turn the watchdog abandons
  while its body keeps running (a swallowed interrupt) keeps its permit until that body really
  stops: the in-flight registration that already tracks such zombies returns it on `settle()`.
  A zombie that never ends therefore holds its permit, which `eddi_conversation_abandoned_running`
  shows. The refusal,
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
That build still released the permit with the client's answer. The overshoot past 1000 is the
persistence tail that review moved inside the permit, and the numbers were not re-measured
after that change.
MongoDB operations per successful turn are unchanged (≈ 22.7). A refused turn still costs its
conversation loads, because admission sits after them. That is WS3a's single-load work.

### `/v1` answers capacity refusals as 503

Review found that the OpenAI-compatible bridge turned any `RejectedExecutionException` into a 500:
the new in-flight limit, coordinator saturation and the shutdown drain alike. That applied to the sync
response, the streaming error event and conversation start. It now answers 503 `capacity_exceeded` with
`Retry-After: 5`, like every other surface. A quota-store outage gets 503
`quota_accounting_unavailable` (`OpenAiApiException.capacityExceeded`,
`OpenAiConversationBridge.rejectionAsApiException`, and a `Retry-After` header from
`OpenAiExceptionMapper`).

### `max-in-flight-turns=auto` (the default)

A fixed 1000 fitted only one container size. The Helm chart's 1 GiB limit gives a 512 MB heap at the
image's default 50 %, so a busy node would run out of memory long before 1000 turns. `auto`
([`InFlightTurnLimit`](../../src/main/java/ai/labs/eddi/engine/internal/InFlightTurnLimit.java)) derives the
limit from the maximum heap: (max heap − 256 MB) ÷ `eddi.conversation.in-flight-turn-memory-mb`
(new, default 2 MB per turn, twice the ~1 MB measured). That gives 128 at 512 MB, ~1400 at 3 GB and
~2900 at 6 GB.
- A number still sets the limit, and `0` still disables it.
- An invalid setting fails the boot (`@PostConstruct`), and the node logs the value it resolved.
- The value is exported as the gauge `eddi_conversation_max_in_flight_turns`, charted next to the
  in-flight count on the full-metrics dashboard. That panel's legend is now on.

### Sizing the limit

A later comparison run (the load-test harness, MongoDB and PostgreSQL, two rounds each) found that
with exactly as many clients as the limit (1000 against 1000), 5–12 % of turns were refused. A turn keeps
its place until it is persisted, which happens after its caller already has the answer, so a client's next
turn can arrive first. The configuration reference and `application.properties` now say to leave roughly
10–20 % headroom above the expected number of concurrently active conversations. They also give the
measured heap per in-flight turn (~1 MB for short conversations) and PostgreSQL's pool default (50).

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
