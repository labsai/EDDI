# Turn admission hardening — 2026-10-17

Branch `feat/turn-admission-hardening` on `origin/main` @ `4b83ffb8b`. Local build mounted into
`labsai/eddi:6.5.0`, `-Xmx6g`, 8 GB container, MongoDB 7.0.43 single node, Docker Desktop on a
20-core Windows 11 laptop. Compose project `eddi-loadtest-admission` (ports 7670 / 18670 / 27670),
`down -v` between configurations, so each starts on a fresh database. Mock LLM delay 3000 ms.

```bash
export COMPOSE_PROJECT_NAME=eddi-loadtest-admission LOADTEST_EDDI_PORT=7670 LOADTEST_WIREMOCK_PORT=18670 LOADTEST_MONGO_PORT=27670 LOADTEST_EDDI_MEMORY=8g
LOADTEST_JAVA_OPTS="-Xmx6g -Deddi.mongodb.max-wait-time-ms=0" python scripts/loadtest/loadtest.py up   # legacy: driver default 120 s
python scripts/loadtest/loadtest.py run --scenario steady --vus 2500 --turns 10 --delay-ms 3000 --label legacy
python scripts/loadtest/loadtest.py run --scenario burst --vus 5000 --ramp 30s --hold 60s --delay-ms 3000 --label legacy-burst
# then the same with LOADTEST_JAVA_OPTS="-Xmx6g" (eddi.mongodb.max-wait-time-ms=10000, the new default)
```

| Scenario | Pool wait | OK turns | Turns/s | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|---|---|
| steady, 2,500 conv. × 10 | 120 s (legacy) | 24,332 | 69.1 | 26.9 s | 65.7 s | 86.0 s | 668 × 408 |
| steady, 2,500 conv. × 10 | **10 s** | 24,919 | **190.7** | **7.5 s** | **21.9 s** | 28.9 s | 73 × 408, 7 × 5xx, 1 × 503 |
| burst to 5,000, 60 s hold | 120 s (legacy) | 8,280 | 75.4 | 48.2 s | 73.7 s | 79.0 s | 1 app error |
| burst to 5,000, 60 s hold | **10 s** | 31,178 | **239.8** | **13.3 s** | **20.1 s** | 21.3 s | 727 × 503 (`in_flight_limit`) |

With 120 s, the p95 runs past the 60 s turn timeout: callers give up (408) while their turns keep
queueing for pooled connections and doing database work nobody will read. Bounding the wait makes
those turns fail fast and leaves the pool to live turns. During the 10 s burst run, 531 checkouts timed
out (`eddi_datastore_pool_wait_timeouts_total`).

The earlier same-day exploration (no bound, after several other runs on the same database) collapsed
to 39 turns/s in the same burst. Treat these as single runs on a laptop: the ratio is the finding, not
the absolute numbers.

## Adaptive limit — measured and not shipped

An adaptive limit, which lowered the in-flight limit while the mean MongoDB pool wait exceeded 100 ms,
was measured with the same configuration and the 10 s bound:

| Scenario | Off | On (cut 20 %/s) | On (cut 10 % once drained) |
|---|---|---|---|
| steady, 2,500 conv. | 190.7 turns/s, 1 × 503 | 24.0 turns/s, 22,605 × 503 | 26.6 turns/s, 22,679 × 503 |
| burst to 5,000 | 239.8 turns/s, 727 × 503 | 107.6 turns/s, 133,092 × 503 | 20.4 turns/s, 19,189 × 503 |

k6 here neither waits for `Retry-After` nor retries a refused turn: a refusal uses up an iteration
of the `steady` scenario, and VUs re-send immediately. Under that load the in-JVM pool-wait signal
stayed around 470 ms while MongoDB served about 500 operations/s, so it did not track database
saturation. It was judged again with clients that honour `Retry-After` (next section) and dropped.

## Adaptive limit with Retry-After-honouring clients — 2026-10-18, dropped

The limiter above (90 % cut once in flight has drained to the previous limit, +10 % growth, target
100 ms mean pool wait, minimum 32, 1 s window) was ported onto this branch, reading the
`eddi.datastore.pool.wait` timer, and switched by `EDDI_CONVERSATION_ADAPTIVE_LIMIT_ENABLED` on one
build. The clients came from the benchmark suite (PR #1086), run with `--retry-503 10`: a refused turn or
conversation start sleeps for `Retry-After` (5 s, ±20 % jitter) and resends, at most 10 attempts in all,
after which it counts as abandoned. "p95 total" includes refusals and backoff. Same stack as above
(`-Xmx6g`, 8 GB, the in-flight limit `auto` = 2,602), compose project `eddi-loadtest-adaptive`, `down -v`
before every run. Three rounds; round 1 overlapped another session's k6 run (⚠), rounds 2–3 were
uncontended.

```bash
python scripts/loadtest/loadtest.py run --scenario steady --vus 2500 --turns 10 --delay-ms 3000 --retry-503 10
python scripts/loadtest/loadtest.py run --scenario burst --vus 5000 --ramp 30s --hold 60s --delay-ms 3000 --retry-503 10
```

| Scenario | Round | Limiter | OK turns | Turns/s | p95 total | p99 total | Abandoned | 408 |
|---|---|---|---|---|---|---|---|---|
| steady, 2,500 conv. × 10 | 1 ⚠ | off | 24,580 | 127.9 | 35.3 s | 52.4 s | 0 | 389 |
| | 1 ⚠ | on | 23,351 | 92.9 | 45.1 s | 55.7 s | **1,464** | 0 |
| | 2 | off | 24,532 | 197.0 | 26.7 s | 41.9 s | 0 | 429 |
| | 2 | on | 24,979 | 220.1 | 23.9 s | 30.2 s | 0 | 20 |
| | 3 | off | 24,920 | 231.3 | 18.3 s | 26.5 s | 0 | 78 |
| | 3 | on | 24,997 | 264.9 | 18.9 s | 24.0 s | 0 | 3 |
| burst to 5,000, 60 s hold | 1 | off | 19,890 | 154.2 | 37.4 s | 45.8 s | 0 | 118 |
| | 1 | on | 17,935 | 162.0 | 48.5 s | 58.2 s | **603** | 0 |
| | 3 | off | 26,614 | 255.9 | 24.9 s | 31.7 s | 0 | 38 |
| | 3 | on | 26,940 | 248.7 | 34.3 s | 49.5 s | **102** | 0 |

**Verdict: not shipped.** On a quiet machine it gains 12–15 % goodput in `steady` and cuts the p99, with
the p95 a tie, and it removes most 408s. But it abandons turns in every burst run and in the contended
steady run. The ship criterion, beating "off" without raising abandoned turns, fails. Run-to-run spread
on this laptop is also large: "off" steady ranged from 128 to 231 turns/s.

**The signal does not measure the database.** A sampler took EDDI's pool-wait window mean, the driver's
wait queue and checked-out count, mongod `opcounters` and `globalLock` queue, and `docker stats` CPU
every second. Across the windows with traffic, pool wait correlates *negatively* with mongod
operations/s in nine of the ten runs (r = −0.12 to −0.36; +0.16 in the tenth, on 5 windows). In windows above
the 100 ms target, mongod served a median of 1.8–6.8k ops/s (its peak in the same runs was 18–30k), at 1.8–5.1 of 20
cores, with a global-lock queue of 0–8, and a median of 56–90 of the 100 pooled connections were checked out. Turns were waiting for the pool while
connections were free and the database was idle. The wait is time a runnable thread takes to be
scheduled onto a connection: CPU contention and pauses in the EDDI JVM, which used 5–13 cores. That is
why another stack's k6 run, which takes CPU from EDDI and not from MongoDB, drove the limit down to 92
and cost 1,464 turns. `mongodb_driver_pool_waitqueuesize` tracks the same thing (r = 0.03–0.80
against the wait), so it is no better. In these runs a signal that really measured MongoDB saturation
would never have fired, which is the same as "off".

What the steady gain does show is that on this host fewer turns in flight than the heap-derived
2,602 run faster: in the uncontended runs the adaptive limit
never went below 610 and stayed mostly between about 900 and 1,900. A static
`eddi.conversation.max-in-flight-turns` sized to CPU, which needs no signal, is the thing to measure
next. Turn latency minus the LLM time would be the right input for a CPU-aware limiter, but EDDI does
not know the LLM's share of a turn without timing every call, and with closed-loop clients a limiter
only moves the waiting from the queue into client backoff unless it raises throughput.

The ported code was not kept. The original is commit `63659ba18` (`wip/adaptive-turn-limit`), if anyone
needs it.
