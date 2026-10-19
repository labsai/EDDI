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

## Static, CPU-sized in-flight limit — 2026-10-18, no change to the default

The adaptive limiter gained throughput in `steady` while it held in-flight turns at about 900–1,900.
That suggested two things: the heap-derived `auto` limit (2,602 at `-Xmx6g`) admits more turns than
20 cores serve well, and a static `eddi.conversation.max-in-flight-turns` might keep the gain without a
controller. This was measured on a build of `feat/turn-admission-hardening` @ `990123aef`. The later
`a6b2b5185` changes only a dashboard and a doc. The clients were the benchmark suite's from PR #1086, run with
`--retry-503 10`, on the same stack as above (`-Xmx6g`, 8 GB container, MongoDB 7.0.43): compose project
`eddi-loadtest-staticlimit`, ports 7771 / 18771 / 27771, `down -v` before every run. Configurations were
interleaved, and their order reversed between rounds. The limit was set through `LOADTEST_COMPOSE_EXTRA`
with a copy of `overrides/in-flight-100.yml` holding the new value.

The candidates were `auto`, 1500, 1000 and 600. 2000 was added from round 2 on, after 1500 abandoned
turns in the burst. 600 and 1000 stopped after two rounds, which agreed with each other. Rounds 5–7
repeated only the burst, for `auto` and 2000. No other k6 run overlapped any of the 36 runs (the
harness's `contended_by` is empty for all of them). From round 2 on, a host sampler showed 2–4 cores
going to processes outside Docker throughout (a file-sync client, other JVMs). That level held steady
and does not line up with the spread below.

```bash
export COMPOSE_PROJECT_NAME=eddi-loadtest-staticlimit LOADTEST_EDDI_PORT=7771 LOADTEST_WIREMOCK_PORT=18771 LOADTEST_MONGO_PORT=27771 LOADTEST_EDDI_MEMORY=8g LOADTEST_JAVA_OPTS="-Xmx6g"
export LOADTEST_COMPOSE_EXTRA=in-flight-2000.yml   # in-flight-100.yml with 2000; unset for auto
python scripts/loadtest/loadtest.py down && python scripts/loadtest/loadtest.py up
python scripts/loadtest/loadtest.py run --scenario steady --vus 2500 --turns 10 --delay-ms 3000 --retry-503 10
python scripts/loadtest/loadtest.py down && python scripts/loadtest/loadtest.py up
python scripts/loadtest/loadtest.py run --scenario burst --vus 5000 --ramp 30s --hold 60s --delay-ms 3000 --retry-503 10
```

Summary. "p95/p99 total" include refusals and backoff. Turns/s is the median, with the range in
brackets. Abandoned turns and 408s are summed over the runs.

| Scenario | Limit | Runs | Turns/s | p95 total | p99 total | Abandoned | 408 |
|---|---|---|---|---|---|---|---|
| steady, 2,500 conv. × 10 | `auto` (2,602) | 4 | 228 (180–255) | 19.6 s | 28.2 s | 0 | 322 |
| | 2000 | 3 | 228 (223–233) | 20.6 s | 29.5 s | 0 | 211 |
| | 1500 | 4 | 234 (137–246) | 21.9 s | 29.8 s | 2 | 312 |
| | 1000 | 2 | 223 (217–229) | 23.5 s | 35.0 s | 45 | 4 |
| | 600 | 2 | 156 (154–158) | 34.2 s | 46.3 s | 619 | 0 |
| burst to 5,000, 60 s hold | `auto` (2,602) | 7 | 275 (185–427) | 21.7 s | 29.8 s | 0 | 670 |
| | 2000 | 6 | 333 (281–355) | 24.7 s | 33.6 s | 9 | 256 |
| | 1500 | 4 | 288 (62–345) | 29.8 s | 42.2 s | 236 | 151 |
| | 1000 | 2 | 245 (233–257) | 36.9 s | 47.3 s | 1,156 | 1 |
| | 600 | 2 | 153 (153–153) | 44.1 s | 50.0 s | 3,773 | 0 |

Every run:

| Scenario | Limit | Round | OK turns | Turns/s | p95 total | p99 total | Abandoned | 408 | Other 5xx | Refused attempts |
|---|---|---|---|---|---|---|---|---|---|---|
| steady | `auto` | 1 | 24,992 | 254.5 | 15.4 s | 22.2 s | 0 | 8 | 0 | 0 |
| | | 2 | 24,856 | 179.6 | 23.0 s | 34.0 s | 0 | 141 | 3 | 0 |
| | | 3 | 24,887 | 228.6 | 19.4 s | 28.6 s | 0 | 109 | 4 | 1 |
| | | 4 | 24,934 | 227.8 | 19.7 s | 27.8 s | 0 | 64 | 2 | 1 |
| | 2000 | 2 | 24,943 | 228.3 | 20.7 s | 30.8 s | 0 | 53 | 4 | 3,993 |
| | | 3 | 24,923 | 232.9 | 20.6 s | 29.5 s | 0 | 73 | 4 | 2,963 |
| | | 4 | 24,907 | 222.7 | 19.3 s | 29.4 s | 0 | 85 | 8 | 2,681 |
| | 1500 | 1 | 24,994 | 245.2 | 17.8 s | 25.7 s | 1 | 5 | 0 | 10,109 |
| | | 2 | 24,711 | 137.0 | 29.2 s | 40.6 s | 0 | 245 | 44 | 4,346 |
| | | 3 | 24,943 | 222.9 | 21.9 s | 30.2 s | 1 | 50 | 6 | 10,817 |
| | | 4 | 24,986 | 245.5 | 21.9 s | 29.4 s | 0 | 12 | 2 | 11,137 |
| | 1000 | 1 | 24,977 | 229.0 | 22.8 s | 34.4 s | 23 | 0 | 0 | 20,770 |
| | | 2 | 24,973 | 217.4 | 24.2 s | 35.6 s | 22 | 4 | 1 | 21,791 |
| | 600 | 1 | 24,689 | 157.7 | 33.7 s | 46.4 s | 311 | 0 | 0 | 42,721 |
| | | 2 | 24,692 | 154.1 | 34.6 s | 46.3 s | 308 | 0 | 0 | 42,708 |
| burst | `auto` | 1 | 22,058 | 185.3 | 40.3 s | 43.0 s | 0 | 395 | 193 | 39 |
| | | 2 | 24,981 | 216.8 | 44.1 s | 52.3 s | 0 | 53 | 0 | 1,825 |
| | | 3 | 45,312 | 427.1 | 16.3 s | 23.5 s | 0 | 14 | 0 | 9,793 |
| | | 4 | 42,455 | 412.3 | 16.8 s | 25.4 s | 0 | 17 | 0 | 4,731 |
| | | 5 | 27,856 | 255.0 | 31.2 s | 44.1 s | 0 | 113 | 13 | 4 |
| | | 6 | 32,312 | 275.2 | 21.7 s | 27.0 s | 0 | 14 | 0 | 3,932 |
| | | 7 | 37,490 | 357.1 | 20.7 s | 29.8 s | 0 | 64 | 2 | 5,753 |
| | 2000 | 2 | 36,277 | 342.5 | 24.0 s | 33.0 s | 0 | 38 | 3 | 19,887 |
| | | 3 | 33,970 | 319.5 | 24.5 s | 34.3 s | 0 | 53 | 0 | 18,016 |
| | | 4 | 37,464 | 354.6 | 26.8 s | 35.2 s | 6 | 22 | 0 | 28,251 |
| | | 5 | 29,979 | 280.8 | 25.5 s | 34.2 s | 0 | 46 | 3 | 11,555 |
| | | 6 | 34,892 | 330.2 | 23.9 s | 32.4 s | 1 | 85 | 2 | 23,617 |
| | | 7 | 36,526 | 336.1 | 24.8 s | 33.0 s | 2 | 12 | 0 | 23,316 |
| | 1500 | 1 | 37,950 | 344.7 | 26.9 s | 39.0 s | 85 | 0 | 0 | 43,049 |
| | | 2 | 8,066 | 62.0 | 69.8 s | 78.2 s | 0 | 136 | 28 | 4,405 |
| | | 3 | 31,759 | 291.8 | 29.9 s | 42.4 s | 70 | 3 | 0 | 36,305 |
| | | 4 | 31,687 | 284.6 | 29.7 s | 42.1 s | 81 | 12 | 0 | 38,020 |
| | 1000 | 1 | 29,231 | 257.1 | 36.6 s | 46.7 s | 631 | 0 | 0 | 60,833 |
| | | 2 | 26,129 | 233.2 | 37.2 s | 47.9 s | 525 | 1 | 0 | 52,368 |
| | 600 | 1 | 18,806 | 153.4 | 44.4 s | 50.5 s | 1,992 | 0 | 0 | 71,415 |
| | | 2 | 18,716 | 152.7 | 43.8 s | 49.6 s | 1,781 | 0 | 0 | 69,691 |

In every limited run `eddi_processing_conversation_count` peaked at the limit, so each limit was in
force. Both 1500 runs in round 2 collapsed, to 137 and 62 turns/s. No cause was found, the host sampler
was not yet running, and they stay in the table. In the round-3 `auto` burst the audit queue filled
(100,000 entries) and dropped 20,214 of them, which flatters its 427 turns/s. No other run dropped
audit entries.

**Verdict: no static limit beats `auto`. The default stays.**

- **Steady: 1500 and above tie with `auto`; lower limits are worse.** With 2,500 conversations `auto`
  never binds (2,500 in flight at peak), so this compares no limit with a limit. 2000 and 1500 match
  `auto` within the noise: medians of 228–234 turns/s, p95 totals of about 20–22 s. But they turn away
  3,000–11,000 attempts that `auto` serves, and 1500 abandons the odd turn. 1000 and 600 abandon turns
  (45 and 619) without raising throughput, and 600 costs a third of it. No static limit reproduces the
  adaptive limiter's 12–15 % steady gain, so fewer turns in flight alone did not produce it.
- **Burst: 2000 has the higher median but still fails the test.** Paired by round (rounds 2–7), 2000
  minus `auto` was +126, −108, −58, +26, +55 and −21 turns/s: three wins each, and a median difference of
  +2.5. 2000's higher unpaired median comes from `auto`'s weak round 1. 2000 is steadier (281–355 against
  185–427), so its worst run is better. But its p95 and p99 totals are 3–4 s worse, and it abandoned turns
  in three of six runs (9 in all) against 0 in seven for `auto`. That fails the criterion the adaptive
  limiter failed: beat `auto` without raising abandoned turns. Limits of 1500 and below abandon 70–2,000
  turns per burst.
- **The premise does not hold.** On 20 cores, a CPU-sized limit below 2,000 does not serve these
  closed-loop clients better. A refused client backs off for 5 s and resends, so the work moves from
  EDDI's queue into client backoff. Throughput stays where the CPU puts it, about 230 turns/s in `steady`,
  while total latency and abandoned turns rise. On this host the heap-derived `auto` is a reasonable
  ceiling: it refuses little. Its tail comes from CPU saturation, which a lower limit turns into client
  waits but does not remove.

An operator who wants a predictable burst floor more than the best case can set
`EDDI_CONVERSATION_MAX_IN_FLIGHT_TURNS` to about 2,000 on a node like this one. At a 5,000-user burst it
costs a few abandoned turns. That is a deployment choice, not a better default. Raw results are the
`scripts/loadtest/results/sl-r*.json` files of the measuring checkout (not committed).
