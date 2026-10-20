# Phase 1, part 2 — MongoDB round trips per turn — 2026-10-17

Branch `perf/turn-db-roundtrips`, stacked on #1055 (`perf/turn-mongo-roundtrips` @ `309f3a168`) with
`origin/main` @ `4b83ffb8b` merged in, so both columns include #1045 (virtual-thread turns).
**Before** is that merge (`f5ce3b915`, i.e. #1055 + main); **after** adds this branch's changes.

Same stack and settings for both: local build mounted into `labsai/eddi:6.5.0`, `-Xmx2g`, 3 GB container,
scenario `steady`, mock LLM delay 3000 ms (10 turns per conversation) and 0 ms (40 turns),
`-Deddi.conversation.max-in-flight-turns=1500`. The default `auto` sizes the limit from the heap,
782 at `-Xmx2g`, and refuses part of a 1,000-conversation run with 503s, which would hide the database effect.

```bash
export LOADTEST_JAVA_OPTS="-Xmx2g -Deddi.conversation.max-in-flight-turns=1500"
python scripts/loadtest/loadtest.py up --app <build>/target/quarkus-app
python scripts/loadtest/loadtest.py run --label <label> --scenario steady --vus 50|200|1000 --turns 10 --delay-ms 3000
python scripts/loadtest/loadtest.py run --label <label> --scenario steady --vus 50 --turns 40 --delay-ms 0
```

## MongoDB operations per OK turn

**Round trips** are commands per collection from `top`: queries + updates + insert commands + other
commands. That is what holds a pool connection. `opcounters` counts **inserted documents**, so audit
entries batched into one `insertMany` show there as ~3.2 inserts per turn; they are listed separately.

| Run | Round trips before | Round trips after | Queries before → after | Updates | Insert docs (`opcounters`) |
|---|---|---|---|---|---|
| 50 conv. / 3 s | 10.62 | **6.83** | 8.16 → 4.49 | 2.0 → 2.0 | 3.2 → 3.2 |
| 200 conv. / 3 s | 10.00 | **6.80** | 7.69 → 4.49 | 2.0 → 2.0 | 3.2 → 3.2 |
| 1,000 conv. / 3 s | 9.37 | **6.80** | 7.07 → 4.50 | 2.0 → 2.0 | 3.2 → 3.2 |
| 50 conv. / 0 ms, 40 turns | 8.59 | **6.28** | 6.51 → 4.20 | 2.0 → 2.0 | 3.05 → 3.05 |

Main before #1055 (same machine, same settings, earlier the same day): 19.6 round trips per turn at
1,000 conversations. #1055 and this branch together cut it to 6.8.

Per collection, 1,000 conversations:

| Collection | Before | After | What remains |
|---|---|---|---|
| `conversationmemories` | 4.14 q + 1.0 u | **2.09 q** + 1.0 u | the request-time load, one `{conversationState, _rev}` read when the turn starts, the append |
| `descriptors` | 1.10 q + 1.0 u | 1.10 q + 1.0 u | #1055: one ownership read per request, one `$set` timestamp |
| `usermemories` | 1.20 q | 1.20 q | `GdprComplianceService.isProcessingRestricted`, deliberately not changed (needs a product/security decision) |
| `audit_ledger` | 0.34–1.10 q + 0.10 cmd | **0.10 q** + 0.10 cmd | the chain seed, once per conversation (find + count) |
| `agents` + `workflows` | 0.14–0.31 q | **0.00** | — |
| inserts | 0.20 | 0.20 | one conversation and one descriptor per new conversation; audit `insertMany` once per flush |

Without the GDPR restriction read, a turn now costs **5.1–5.6 round trips**.

Mongo profiler attribution on a 20-conversation × 5-turn run (100 turns) confirms it per call site:
`conversationmemories` went from full load 1.24 + `{conversationState}` 2.0 + `{_rev}` 1.0 per turn
to full load 1.3 + `{_rev, conversationState}` 1.0. The audit ledger's per-flush `maxSequence` finds are
gone, leaving only the 2 seed reads per new conversation.

## Throughput and latency

| Run | Before turns/s · p50 · p95 | After turns/s · p50 · p95 | Peak pool wait queue before → after |
|---|---|---|---|
| 50 conv. / 3 s | 10.8 · 3540 · 9470 ms | 14.8 · 3215 · 3913 ms | 1 → 1 |
| 200 conv. / 3 s | 36.5 · 4146 · 6631 ms | 52.0 · 3348 · 4794 ms | 126 → 147 |
| 1,000 conv. / 3 s | 91.2 · 7642 · 17613 ms | 118.4 · 5509 · 13882 ms | 857 → 729 |
| 50 conv. / 0 ms | 105.1 · 368 · 1119 ms | 211.4 · 86 · 471 ms | 0 → 0 |

**Read these with care.** Other sessions were load-testing their own EDDI stacks on the same laptop
throughout: `eddi-loadtest-hotpath` and `eddi-loadtest-cluster` (a 2-node NATS cluster) during the
before runs, `eddi-loadtest-adaptive` and a `kdf-bench-mongo` during the after runs. Each run waited until
no other k6 container was active, but their EDDI and MongoDB containers stayed up. The before runs are
also slower than main's measured two hours earlier (124 turns/s at 1,000 conversations), with fewer
round trips per turn. The direction holds in every row: fewer round trips, higher throughput,
lower p50 and p95. The size of the latency gain is not a reliable number. Measure it again on an
idle machine (or the planned benchmark rig) before quoting it. All runs: 0 errors.

At 1,000 conversations the pool (100 connections) is still saturated in both runs: checked-out peaks at
100 and the wait queue stays in the hundreds. Fewer round trips shorten each turn's time in the
queue, but at this concurrency the pool, not the per-turn work, is still the ceiling. Sizing the pool
against `max-in-flight-turns` is WS3e.
