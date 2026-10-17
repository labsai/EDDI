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
saturation. Kept on the local branch `wip/adaptive-turn-limit`, to be judged once the harness has clients that
honour `Retry-After`.
