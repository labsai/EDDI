## perf(runtime): overload hardening — capacity check before any read, per-conversation queue cap, bounded MongoDB pool wait (2026-10-17)

**Repo:** EDDI · **Branch:** `feat/turn-admission-hardening`

### What changed and why

A load test and JProfiler session on 2026-10-17 (1,000 to 5,000 concurrent conversations, 3 s mock
LLM) found that once turns run on virtual threads (#1045), the MongoDB connection pool is where a turn
spends its time under load. Indexed single-document reads averaged 2.2–3.3 s inside the driver, while
mongod's own profiler logged them at 15–20 ms.

- **Capacity check before any read.** `IConversationService.refuseIfAtCapacity` (a no-op default;
  `ConversationService` implements it against `eddi.conversation.max-in-flight-turns`). `RestAgentEngine`
  (`say`, `rerun`) and `RestAgentEngineStreaming` call it before the ownership check. Until now, a turn
  refused at the limit first read its descriptor and loaded its conversation. Admission in `admitTurn`
  stays the authoritative, atomic check.
- **`eddi.conversation.max-queued-turns-per-conversation` (default 20).** Turns of one conversation run
  one at a time, so a client that keeps sending without waiting for answers (a retry storm, a bot loop)
  only built a queue of turns, each holding a permit and a loaded conversation. One more than 20 waiting
  is now refused in `AbstractQueuedConversationCoordinator` with the existing 503 `capacity_exceeded`,
  counted as `eddi_turn_rejected_total{reason="conversation_queue"}`. The cap applies in both
  coordinators.
- **`eddi.mongodb.max-wait-time-ms` (default 10000).** The driver waited up to 120 s for a pooled
  connection, twice the turn timeout, so a saturated pool held turns long after their callers had given
  up. A `waitQueueTimeoutMS` in the connection string still wins.
- **`eddi.datastore.pool.wait` timer and `eddi.datastore.pool.wait.timeouts` counter** (MongoDB,
  `PoolWaitListener`). Micrometer's pool listener showed how many operations were waiting, never for how
  long. Both are on the Full Metrics dashboard's connection-pool panel. PostgreSQL already exports
  `agroal_blocking_time_*`.

### Measured

`scripts/loadtest`, 3 s mock LLM, 6 GB heap, everything on one Windows laptop (Docker Desktop), with
its own compose project and ports. "Legacy" is this build with `eddi.mongodb.max-wait-time-ms=0`, i.e.
the driver's 120 s.

| Scenario | Legacy (120 s pool wait) | This change (10 s) |
|---|---|---|
| 2,500 conversations × 10 turns | 69 turns/s · p50 26.9 s · p95 65.7 s · 668 × 408 | **191 turns/s · p50 7.5 s · p95 21.9 s** · 73 × 408, 7 × 5xx, 1 × 503 |
| Burst: ramp to 5,000 users in 30 s, hold 60 s | 75 turns/s · p50 48.2 s · p95 73.7 s | **240 turns/s · p50 13.3 s · p95 20.1 s** · 727 × 503 at the in-flight limit |

The bounded wait accounts for this difference: it is the only setting that differs between the two
columns, and each run started on a fresh database. With the driver's 120 s, the p95 (66 s) ran past
the 60 s turn timeout. Turns whose callers had already given up kept queueing for connections, and that
abandoned work crowded out live turns. Each figure comes from a single run on a laptop, so read the
ratio, not the absolute numbers. Details are in
[`scripts/loadtest/results/admission-2026-10-17.md`](../../scripts/loadtest/results/admission-2026-10-17.md).

### Design decisions

- **No adaptive limit, yet.** A limiter that lowered the in-flight limit while the mean pool wait
  exceeded a target was built, tested and measured, then left out of this change. Under the harness,
  which drives clients that ignore `Retry-After`, it cut goodput (2,500 conversations: 191 → 27 turns/s;
  5,000-user burst: 240 → 20 turns/s). In the burst, the pool-wait signal stayed around 470 ms while
  MongoDB served only about 500 operations/s. It is parked on a local branch, `wip/adaptive-turn-limit`.
  Judging it needs load-test clients that honour `Retry-After`; the benchmark-suite work adds them.
- **Queue cap default 20, not 5** (the throughput plan's WS4b proposal). Coordinator tests that queue
  20 turns on one conversation are legitimate FIFO tests, and a chat channel can deliver several messages
  from a fast typist. 20 is beyond any human pace and still stops a loop.

### What's next

- The Platform Operator's prompt does not explain `capacity_exceeded`, nor any of the turn limits. A
  follow-up should add it, together with an operator revision bump.
- WS4a deadline gaps (skipping turns whose deadline expired while queued) touch `ConversationStepRunner`,
  which the per-turn round-trip follow-up to #1055 is changing; take them after that lands.
