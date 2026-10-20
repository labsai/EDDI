# The turn timeout and `/q/metrics` under a burst — 2026-10-19

The collapse investigation of the same day (`collapse-2026-10-19.md` on PR #1098) left three overload
behaviours open. In a 5,000-user burst against a node held to 2 CPUs:

1. the REST turn timeout (`systemRuntime.agentTimeoutInSeconds`, 60 s) did not hold: turns were answered
   `200` after a median of 113 s;
2. `/q/metrics` stopped answering for about 110 s while turns were still completing;
3. a queued turn whose caller had already been answered `408` still ran in full.

This note reproduces them, explains them and measures the fix on branch `fix/turn-timeout-under-load`.

## Setup

The load-test harness of PR #1086 (`loadtest.py`, not merged into this branch) running the published
`labsai/eddi:6.5.0` image with each build mounted. EDDI is held to 2 CPUs (`LOADTEST_EDDI_CPUS=2`),
`-Xmx6g`, an 8 GB container, `eddi.conversation.max-in-flight-turns=1500`, MongoDB 7.0.43 standalone,
a 3 s mock LLM. Scenario: `burst`, 5,000 users ramped over 30 s and held for 60 s, `--retry-503 10`.
`down -v` before every run.

Both builds carried a temporary diagnostic, not part of the change: for every turn request it recorded
when the request arrived (first route of the router), when `sayInternal` started, when the response was
resumed and when it was written, logged as 5-second aggregates.

**The host was contended throughout.** Several other sessions ran Maven builds and test suites on the
same 20-thread laptop all afternoon. Host CPU averaged 72–79 % during the measured runs, of which EDDI's
cap accounts for about 10 points. A gate that waited for an idle host did not open in two hours. The
baseline and the fix were therefore measured **interleaved** (baseline, fix, baseline, fix), so drifting
contention hits both arms alike. The effects below are large compared with the run-to-run spread.

## Why the timeout did not hold

`RestAgentEngine` arms the timeout with `AsyncResponse.setTimeout` inside `sayInternal`. Quarkus REST
schedules that timer on the request's event loop, and it fired on time: at most 105 ms late. It simply
started late, and the answer it was meant to bound was written late:

| Where a turn request waited (baseline) | average | worst |
|---|---|---|
| arrival → `sayInternal` runs (the 60 s not yet armed) | 16–26 s | 63–79 s |
| turn finished → response written | 12–17 s | 29–37 s |

Both waits are the same queue. `say` was a blocking endpoint, so it ran on the Quarkus worker pool
(200 threads at 2 CPUs), and resuming a blocking endpoint's `AsyncResponse` hands the rest of the
response, serialisation and write, back to that pool. Thread dumps taken every 10 s show all 200 worker
threads busy with **conversation starts**: the inline `CONVERSATION_START` pipeline and its MongoDB
reads and writes, parked on the connection pool. Every `say`, and every finished turn's answer, queued
behind them.

## Why `/q/metrics` stopped answering

Quarkus registers the Prometheus endpoint as a blocking route, so it runs on the same worker pool and
queued behind the same starts. The sampler (5 s client timeout, one scrape per second) went blind for
110–120 s in every baseline run.

## A dead end: starts on virtual threads

The first version of the fix moved conversation starts to virtual threads as well. The node collapsed
completely: 0 turns, 5,765 failed starts with `dial: i/o timeout`, and 24 "event loop blocked" warnings
(up to 60 s), against none in any baseline run. At 2 CPUs the JVM has 2 virtual-thread carriers.
Thousands of CPU-heavy starts admitted at once, cold class loading among them (it pins carriers), left the
event loops no CPU to accept connections. The worker pool is the start path's only concurrency limit,
so starts stay on it.

## The fix

- **The 60 s counts from arrival.** The router's first route stamps the request's arrival
  (`RequestArrivalStamp`). `sayInternal` arms the timeout for what is left of the 60 s, and a request that
  has already spent the whole budget waiting is answered `408` at once without queueing a turn
  (`eddi_turn_rejected_total{reason="caller_timed_out"}`). A turn deadline (`turnDeadlineMs`,
  `X-EDDI-Turn-Deadline-Ms`) now also counts from arrival.
- **Turns leave the worker pool.** `say`, `sayWithinContext` and `rerun` run on virtual threads, which
  also takes the response write off the pool. Starts stay where they are (see above).
- **A turn whose caller has gone is not run.** The turn carries when its caller gives up. A turn still
  queued at that moment is dropped when it reaches the front, before any read
  (`eddi.conversation.skip-turns-of-timed-out-callers`, default `true`,
  `eddi_turn_skipped_total{reason="caller_timed_out"}`). Its idempotency key is released, so a retry runs.
- **`/q/metrics` has threads of its own.** A route at order −10 (after authentication and authorization,
  before the `/q` sub-router) answers `GET`/`HEAD /q/metrics` on a two-thread executor
  (`eddi.metrics.dedicated-scrape-threads`, default `true`). The metrics permission applies as before.
- **A database pool timeout is a `503`, not a `500`.** With turns on virtual threads, more of them wait for
  a MongoDB connection at once, and a few waited past the driver's limit: 10 turns answered `500` in one
  run. A pool-wait timeout (MongoDB `MongoTimeoutException`, Agroal's acquisition timeout) on a turn or a
  start is now `503 capacity_exceeded` with `Retry-After: 5`. This build was measured before that change.

## Result

| | base2 | **fix3** | base3 | **fix4** |
|---|---|---|---|---|
| Host CPU (average) | 79 % | 72 % | 72 % | 73 % |
| `/q/metrics` scrapes timed out | 24 of 76 | **0 of 167** | 24 of 63 | **0 of 168** |
| Longest time without metrics | 120 s | **0 s** | 120 s | **0 s** |
| Turns answered `200` | 4,711 | 3,297 | 4,722 | 3,734 |
| `200` latency p50 / p99 / max | 55.6 / 80.5 / 94.4 s | **16.1 / 58.7 / 60.4 s** | 60.3 / 92.2 / 104.7 s | **17.7 / 58.8 / 60.2 s** |
| `200`s within the caller's 60 s (estimate) | ≈ 2,940 (21.9/s) | **≈ 3,290 (24.7/s)** | ≈ 2,350 (17.4/s) | **≈ 3,730 (27.9/s)** |
| `408` | 155 | 1,366 | 221 | 1,191 |
| Queued turns dropped, caller gone | — | 802 | — | 679 |
| `503` turns / refused starts | 1,902 / 0 | 1,683 / 1,206 | 1,126 / 0 | 817 / 421 |
| Event loop blocked warnings | 0 | 0 | 0 | 0 |

"Within 60 s" is interpolated from the latency percentiles k6 reports (median, p90, p95, p99, max), so it
is an estimate. For the fix every `200` arrived within 60 s.

- **Monitoring no longer goes blind.** Not one scrape timed out in either fix run, against two minutes
  without metrics in every baseline run.
- **The caller's timeout holds for answered turns.** In the baseline half to a third of the `200`s came
  after 60 s, when a real client has already given up, and the slowest took 105 s. In the fix the slowest
  `200` took 60.4 s.
- **More useful answers, fewer useless ones.** Fewer turns were answered `200` in all, but more of them
  inside the caller's budget: 24.7 and 27.9 per second against 21.9 and 17.4. The drop in raw `200`s is
  the late answers turning into `408`s.

## What is not fixed

- **A request that cannot get a thread within 60 s still gets its `408` late.** Dispatch onto a virtual
  thread also waits when two carriers serve thousands of turns: up to 109–123 s server-side, against
  63–79 s on the worker pool. Such a request is answered `408` the moment it runs, not at 60 s. Counted
  over every request, starts and `408`s included, the client-side p90 was 78–84 s in the fix runs against
  63–71 s in the baseline. The fix makes the slow answers honest; it does not make the node faster. The
  remedy is to shed load earlier, with an in-flight limit sized to the CPU rather than to the heap.
- **Starts still share the worker pool**, and still wait on it in a burst. A start of its own bounded
  concurrency would let the pool serve everything else.
- **Database operations per answered turn rose** (39 → 46–54) because dropped and timed-out turns did their
  reads before being dropped.
- **The harness runs mongod standalone**, where `w: majority` waits for a journal sync (see the collapse
  note).

Raw data (k6 summaries, diagnostics logs, thread dumps, metrics timelines) is in the measuring session's
scratch directory, not committed.
